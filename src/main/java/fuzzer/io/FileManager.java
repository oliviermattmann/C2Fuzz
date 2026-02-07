package fuzzer.io;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Stream;

import fuzzer.logging.LoggingConfig;
import fuzzer.mutators.MutatorType;
import fuzzer.model.BugSignature;
import fuzzer.model.ExecutionResult;
import fuzzer.model.TestCase;
import fuzzer.model.TestCaseResult;
import fuzzer.reporting.BugBucketizer;
import fuzzer.runtime.monitoring.GlobalStats;

public class FileManager {
    private final String timeStamp;
    private final Path seedDirPath;
    private Path sessionDirectoryPath;
    private Path testCaseSubDirectoryPath;
    private Path bugDirectoryPath;
    private Path failedDirectoryPath;
    private static final Logger LOGGER = LoggingConfig.getLogger(FileManager.class);
    private final GlobalStats globalStats;
    private final BugBucketizer bugBucketizer = new BugBucketizer();
    private final Map<String, Integer> bucketCounts = new ConcurrentHashMap<>();
    private final Set<String> seedBlacklist;
    private final Instant fuzzStartTime;

    public FileManager(String seedDir, String timestamp, GlobalStats globalStats, Set<String> seedBlacklist, Instant fuzzStartTime) {
        this.seedDirPath = Path.of(seedDir);
        this.timeStamp = timestamp;
        this.globalStats = globalStats;
        this.seedBlacklist = (seedBlacklist != null) ? Set.copyOf(seedBlacklist) : Set.of();
        this.fuzzStartTime = fuzzStartTime != null ? fuzzStartTime : Instant.now();
    }


    public ArrayList<TestCase> setupSeedPool(String prefix) {
        String seedDirString = seedDirPath.toString();
        File dir = new File(seedDirString);
        if (!dir.isDirectory()) {
            throw new IllegalStateException(String.format("Invalid seeds directory: %s", seedDirString));
        }

        ArrayList<TestCase> seedTestCases = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".java"));
        if (files == null) {
            throw new IllegalStateException(String.format("Unable to list seeds in directory: %s", seedDirString));
        }

        sessionDirectoryPath = Path.of("fuzz_sessions/" + prefix + this.timeStamp);
        testCaseSubDirectoryPath = sessionDirectoryPath.resolve("testcases");
        bugDirectoryPath = sessionDirectoryPath.resolve("bugs");
        failedDirectoryPath = sessionDirectoryPath.resolve("failed");
        try {
            Files.createDirectories(sessionDirectoryPath);
            Files.createDirectories(testCaseSubDirectoryPath);
            Files.createDirectories(bugDirectoryPath);
            Files.createDirectories(failedDirectoryPath);
            // copy seeds to session dir
            for (File file : files) {
                String testCaseName = file.getName().replace(".java", "");
                if (seedBlacklist.contains(testCaseName)) {
                    LOGGER.info(String.format("Skipping blacklisted seed: %s", file.getName()));
                    continue;
                }
                Path testCaseDirectory = testCaseSubDirectoryPath.resolve(testCaseName);
                Files.createDirectories(testCaseDirectory);
                Path targetPath = testCaseDirectory.resolve(file.getName());
                Files.copy(file.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                TestCase testCase = new TestCase(testCaseName, null, MutatorType.SEED, 0.0, null, testCaseName, 0, 0);
                seedTestCases.add(testCase);
            }
            if (!seedBlacklist.isEmpty()) {
                LOGGER.info(String.format("Applied seed blacklist: %d entries, %d seeds skipped", seedBlacklist.size(), files.length - seedTestCases.size()));
            }
            LOGGER.info(String.format("Seeds copied to: %s", this.sessionDirectoryPath.toAbsolutePath()));
            LoggingConfig.redirectToSessionDirectory(this.sessionDirectoryPath);
        } catch (IOException e) {
            LOGGER.severe(String.format("Error creating session directory or copying files: %s", e.getMessage()));
            return seedTestCases;
        }
        return seedTestCases;
    }


    public Path getSessionDirectoryPath() {
        return sessionDirectoryPath;
    }

    public Path getTestCasePath(TestCase testCase) {
        return this.testCaseSubDirectoryPath.resolve(testCase.getName()).resolve(testCase.getName() + ".java");
    }

    public void deleteTestCase(TestCase testCase) {
        Path testCaseDirectory = this.testCaseSubDirectoryPath.resolve(testCase.getName());
        deleteDirectory(testCaseDirectory);
    }

    public void writeNewTestCase(TestCase testCase, String sourceCode) {
        String fileName = testCase.getName() + ".java";
        Path testCaseDirectory = createTestCaseDirectory(testCase);
        Path testCasePath = testCaseDirectory.resolve(fileName);
        try {
            Files.write(testCasePath, sourceCode.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.severe("Error writing new testcase: " + e.getMessage());
        }
    }


    public Path createTestCaseDirectory(TestCase testCase) {
        Path testCaseDirectory = this.testCaseSubDirectoryPath.resolve(testCase.getName());
        createDirectory(testCaseDirectory);
        return testCaseDirectory;
    }

    public void createDirectory(Path dirPath) {
        try {
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
                //LOGGER.info(String.format("Created directory: %s", dirPath.toAbsolutePath()));
            }
        } catch (IOException e) {
            LOGGER.severe(String.format("Error creating directory %s: %s", dirPath, e.getMessage()));
        }
    }

    public void cleanupSessionDirectory() {
        deleteDirectory(sessionDirectoryPath.resolve("testcases"));
        deleteDirectory(sessionDirectoryPath.resolve("failed"));
    }

    

    public void deleteDirectory(Path dirPath) {
        try {
            if (Files.exists(dirPath)) {
                try (Stream<Path> paths = Files.walk(dirPath)) {
                    paths.sorted((a, b) -> b.compareTo(a)) // delete children before parents
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    LOGGER.severe(String.format("Error deleting file or directory %s: %s", path, e.getMessage()));
                                }
                            });
                }
            }
        } catch (IOException e) {
            LOGGER.severe(String.format("Error deleting directory %s: %s", dirPath, e.getMessage()));
        }
    }

    public void moveDirectoryContents(Path sourceDirPath, Path targetDirPath) {
        try {
            try (Stream<Path> paths = Files.walk(sourceDirPath)) {
                paths.forEach(sourcePath -> {
                    try {
                        Path relativePath = sourceDirPath.relativize(sourcePath);
                        Path targetPath = targetDirPath.resolve(relativePath);
                        if (Files.isDirectory(sourcePath)) {
                            Files.createDirectories(targetPath);
                        } else {
                            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        LOGGER.severe(String.format("Error moving file from %s to %s: %s", sourcePath, targetDirPath, e.getMessage()));
                    }
                });
            }
            // after moving all contents, delete the source directory
            deleteDirectory(sourceDirPath);
        } catch (IOException e) {
            LOGGER.severe(String.format("Error moving contents from %s to %s: %s", sourceDirPath, targetDirPath, e.getMessage()));
        }
    }

    public void writeInfoFile(Path filePath, List<String> content) {
        try {
            //LOGGER.info("Writing info file for bug-inducing test case: " + filePath.toString());
            java.nio.file.Files.write(filePath, content);
        } catch (IOException e) {
            LOGGER.info("Failed to write info file for bug-inducing test case: " + e.getMessage());
        }
    }

    public synchronized void saveBugInducingTestCase(TestCaseResult testCaseResult, String reason) {
        TestCase testCase = testCaseResult.testCase();
        String testCaseName = testCase.getName();
        String infoFileName = testCaseName + "_info.txt";
        Path sourceTestCaseDir = testCaseSubDirectoryPath.resolve(testCaseName);
        Path sourceTestCasePath = sourceTestCaseDir.resolve(testCaseName + ".java");

        BugSignature signature = bugBucketizer.bucketize(testCaseResult, sourceTestCasePath, reason);
        Path bucketRootPath = bugDirectoryPath.resolve(signature.bucketId());
        Path testCaseBucketDirectoryPath = bucketRootPath.resolve(testCaseName);
        Path infoFilePath = testCaseBucketDirectoryPath.resolve(infoFileName);

        // move test case directory to bugs/<bucket>/<case>/ directory
        createDirectory(bucketRootPath);
        createDirectory(testCaseBucketDirectoryPath);
        moveDirectoryContents(sourceTestCaseDir, testCaseBucketDirectoryPath);

        writeInfoFile(infoFilePath, buildBugInfoLines(testCaseResult, reason, signature.bucketId()));
        writeBucketSummary(bucketRootPath, signature);
        recordBucketCase(bucketRootPath, signature, testCaseName);
        copyHsErrIfPresent(signature, bucketRootPath);
        recordBucketCount(signature.bucketId());
    }

    private List<String> buildBugInfoLines(TestCaseResult testCaseResult, String reason, String bucketId) {
        TestCase testCase = testCaseResult.testCase();
        ExecutionResult intResult = testCaseResult.intExecutionResult();
        ExecutionResult jitResult = testCaseResult.jitExecutionResult();
        List<String> infoLines = new ArrayList<>();
        infoLines.add("Reason: " + reason);
        infoLines.add("Bucket: " + bucketId);
        infoLines.add("Test case: " + testCase.getName());
        infoLines.add("Mutation depth: " + testCase.getMutationDepth());
        Duration elapsed = Duration.between(fuzzStartTime, Instant.now());
        double elapsedSeconds = elapsed.toMillis() / 1000.0;
        infoLines.add(String.format("Fuzzer runtime until bug (s): %.3f", elapsedSeconds));
        infoLines.add("Interpreter exit code: " + (intResult != null ? intResult.exitCode() : -1));
        infoLines.add("JIT exit code: " + (jitResult != null ? jitResult.exitCode() : -1));
        infoLines.add("Interpreter stdout:\n" + (intResult != null ? intResult.stdout() : ""));
        infoLines.add("JIT stdout:\n" + (jitResult != null ? jitResult.stdout() : ""));
        infoLines.add("Interpreter stderr:\n" + (intResult != null ? intResult.stderr() : ""));
        infoLines.add("JIT stderr:\n" + (jitResult != null ? jitResult.stderr() : ""));
        infoLines.add("Last mutation: " + testCase.getMutation());
        infoLines.add("Initial Seed: " + testCase.getSeedName());
        return infoLines;
    }

    private void writeBucketSummary(Path bucketRootPath, BugSignature signature) {
        Path metaPath = bucketRootPath.resolve("bucket_meta.txt");
        if (Files.exists(metaPath)) {
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.addAll(signature.toSummaryLines());
        lines.add("");
        lines.add("canonical_signature:");
        lines.add(signature.canonical());
        writeInfoFile(metaPath, lines);
    }

    private void recordBucketCase(Path bucketRootPath, BugSignature signature, String testCaseName) {
        Path casesPath = bucketRootPath.resolve("cases.txt");
        String entry = testCaseName + System.lineSeparator();
        try {
            Files.writeString(
                    casesPath,
                    entry,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warning(String.format("Unable to append case to %s: %s", casesPath, e.getMessage()));
        }
    }

    private void copyHsErrIfPresent(BugSignature signature, Path bucketRootPath) {
        String hsErr = signature.hsErrPath();
        if (hsErr == null || hsErr.isBlank()) {
            return;
        }
        try {
            Path source = Path.of(hsErr);
            if (!Files.exists(source)) {
                return;
            }
            Path target = bucketRootPath.resolve(source.getFileName());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warning(String.format("Unable to copy hs_err file for bucket %s: %s", signature.bucketId(), e.getMessage()));
        }
    }

    private void recordBucketCount(String bucketId) {
        if (bucketId == null || bucketId.isBlank()) {
            return;
        }
        bucketCounts.merge(bucketId, 1, Integer::sum);
        if (globalStats != null) {
            globalStats.recordBugBucket(bucketId);
        }
        writeBucketIndex();
    }

    private void writeBucketIndex() {
        if (bugDirectoryPath == null) {
            return;
        }
        Path indexPath = bugDirectoryPath.resolve("bugs_index.json");
        try {
            List<Map.Entry<String, Integer>> entries = bucketCounts.entrySet()
                    .stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList();
            List<String> lines = new ArrayList<>();
            lines.add("{");
            lines.add("  \"buckets\": [");
            for (int i = 0; i < entries.size(); i++) {
                Map.Entry<String, Integer> entry = entries.get(i);
                String comma = (i == entries.size() - 1) ? "" : ",";
                lines.add(String.format("    {\"bucketId\":\"%s\",\"count\":%d}%s",
                        entry.getKey(),
                        entry.getValue(),
                        comma));
            }
            lines.add("  ]");
            lines.add("}");
            Files.write(
                    indexPath,
                    lines,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOGGER.warning(String.format("Unable to write bucket index: %s", e.getMessage()));
        }
    }

}
