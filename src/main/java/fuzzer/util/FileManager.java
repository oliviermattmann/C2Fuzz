package fuzzer.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import fuzzer.mutators.MutatorType;

public class FileManager {
    String timeStamp;
    Path seedDirPath;
    Path sessionDirectoryPath;
    Path testCaseSubDirectoryPath;
    Path bugDirectoryPath;
    Path failedDirectoryPath;
    private static final Logger LOGGER = LoggingConfig.getLogger(FileManager.class);

    public FileManager(String seedDir, String timesstamp) {
        this.seedDirPath = Path.of(seedDir);
        this.timeStamp = timesstamp;
    }


    public ArrayList<TestCase> setupSeedPool(String prefix) {
        String seedDirString = seedDirPath.toString();
        File dir = new File(seedDirString);
        ArrayList<TestCase> seedTestCases = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".java"));
        if (files == null) {
            LOGGER.severe(String.format("Invalid seeds directory: %s", seedDirString));
            return seedTestCases;
        }

        sessionDirectoryPath = Path.of("fuzz_sessions/" + prefix + this.timeStamp);
        testCaseSubDirectoryPath = sessionDirectoryPath.resolve("testcases");
        bugDirectoryPath = sessionDirectoryPath.resolve("bugs");
        failedDirectoryPath = sessionDirectoryPath.resolve("failed");
        try {
            Files.createDirectory(sessionDirectoryPath);
            Files.createDirectory(testCaseSubDirectoryPath);
            Files.createDirectory(bugDirectoryPath);
            Files.createDirectory(failedDirectoryPath);
            // copy seeds to session dir
            for (File file : files) {
                String testCaseName = file.getName().replace(".java", "");
                Path testCaseDirectory = testCaseSubDirectoryPath.resolve(testCaseName);
                Files.createDirectory(testCaseDirectory);
                Path targetPath = testCaseDirectory.resolve(file.getName());
                Files.copy(file.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                TestCase testCase = new TestCase(testCaseName, null, MutatorType.SEED, 0.0, null);
                seedTestCases.add(testCase);
            }
            LOGGER.info(String.format("Seeds copied to: %s", this.sessionDirectoryPath.toAbsolutePath()));
        } catch (IOException e) {
            LOGGER.severe(String.format("Error creating seedpool directory or copying files: %s", e.getMessage()));
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
        createTestCaseDirectory(testCase);
        String fileName = testCase.getName() + ".java";
        Path testCaseDirectory = createTestCaseDirectory(testCase);
        Path testCasePath = testCaseDirectory.resolve(fileName);
        try {
            LOGGER.info("Writing new testcase: " + testCasePath.toString());
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

    public void deleteDirectory(Path dirPath) {
        try {
            if (Files.exists(dirPath)) {
                Files.walk(dirPath)
                    .sorted((a, b) -> b.compareTo(a)) // delete children before parents
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            LOGGER.severe(String.format("Error deleting file or directory %s: %s", path, e.getMessage()));
                        }
                    });
            }
        } catch (IOException e) {
            LOGGER.severe(String.format("Error deleting directory %s: %s", dirPath, e.getMessage()));
        }
    }

    public void moveDirectoryContents(Path sourceDirPath, Path targetDirPath) {
        try {
            Files.walk(sourceDirPath)
                .forEach(sourcePath -> {
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

    public void saveBugInducingTestCase(TestCaseResult testCaseResult, String reason) {
        TestCase testCase = testCaseResult.testCase();
        String testCaseName = testCase.getName();
        String infoFileName = testCaseName + "_info.txt";
        Path testCaseBugDirectoryPath = bugDirectoryPath.resolve(testCaseName);
        Path infoFilePath = testCaseBugDirectoryPath.resolve(infoFileName);

        // move test case directory to bugs/ directory
        createDirectory(testCaseBugDirectoryPath);
        Path sourceTestCaseDir = testCaseSubDirectoryPath.resolve(testCaseName);
        moveDirectoryContents(sourceTestCaseDir, testCaseBugDirectoryPath);

        // write information to a text file
        ExecutionResult intResult = testCaseResult.intExecutionResult();
        ExecutionResult jitResult = testCaseResult.jitExecutionResult();
        //LOGGER.info("Info file name: " + infoFileName);
        List<String> infoLines = new ArrayList<>();
        infoLines.add("Reason: " + reason);
        infoLines.add("Test case: " + testCase.getName());
        infoLines.add("Interpreter exit code: " + intResult.exitCode());
        infoLines.add("JIT exit code: " + jitResult.exitCode());
        infoLines.add("Interpreter stdout:\n" + intResult.stdout());
        infoLines.add("JIT stdout:\n" + jitResult.stdout());
        infoLines.add("Interpreter stderr:\n" + intResult.stderr());
        infoLines.add("JIT stderr:\n" + jitResult.stderr());

        writeInfoFile(infoFilePath, infoLines);
        
    }

    public void saveFailingTestCase(TestCaseResult testCaseResult) {
        TestCase testCase = testCaseResult.testCase();
        String testCaseName = testCase.getName();
        String infoFileName = testCaseName + "_info.txt";
        Path testCaseFailedDirectoryPath = failedDirectoryPath.resolve(testCaseName);
        Path infoFilePath = testCaseFailedDirectoryPath.resolve(infoFileName);


        // move test case directory to failed/ directory
        createDirectory(testCaseFailedDirectoryPath);
        Path sourceTestCaseDir = testCaseSubDirectoryPath.resolve(testCaseName);
        moveDirectoryContents(sourceTestCaseDir, testCaseFailedDirectoryPath);

        ExecutionResult intResult = testCaseResult.intExecutionResult();

        List<String> infoLines = new ArrayList<>();
        infoLines.add("Test case: " + testCase.getName());
        infoLines.add("Reason: Non-zero exit code");
        infoLines.add("Interpreter exit code: " + intResult.exitCode());
        infoLines.add("Last mutation: " + testCase.getMutation());
        infoLines.add("Interpreter stderr:\n" + intResult.stderr());

        writeInfoFile(infoFilePath, infoLines);

    }

}
