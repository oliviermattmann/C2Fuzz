package fuzzer.runtime;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.io.ClassExtractor;
import fuzzer.model.ExecutionResult;
import fuzzer.io.FileManager;
import fuzzer.logging.LoggingConfig;
import fuzzer.model.TestCase;
import fuzzer.model.TestCaseResult;
import fuzzer.runtime.monitoring.GlobalStats;

public class Executor implements Runnable {
    private final String debugJdkPath;
    private final BlockingQueue<TestCase> executionQueue;
    private final BlockingQueue<TestCaseResult> evaluationQueue;
    private final GlobalStats globalStats;
    private final FileManager fileManager;
    private final FuzzerConfig.Mode mode;
    private final URI javacServerEndpoint;
    private static final Logger LOGGER = LoggingConfig.getLogger(Executor.class);
    private static final double MUTATOR_COMPILE_PENALTY = -0.6;

    public Executor(FileManager fm, String debugJdkPath, BlockingQueue<TestCase> executionQueue, BlockingQueue<TestCaseResult> evaluationQueue, GlobalStats globalStats, FuzzerConfig.Mode mode) {
        this.executionQueue = executionQueue;
        this.evaluationQueue = evaluationQueue;
        this.debugJdkPath = debugJdkPath;
        this.globalStats = globalStats;
        this.fileManager = fm;
        this.mode = mode;

        String host = Optional.ofNullable(System.getenv("JAVAC_HOST"))
                .filter(s -> !s.isBlank())
                .orElse("127.0.0.1");
        String port = Optional.ofNullable(System.getenv("JAVAC_PORT"))
                .filter(s -> !s.isBlank())
                .orElse("8090");
        this.javacServerEndpoint = URI.create(String.format("http://%s:%s/compile", host, port));
    }



    public MutationTestReport executeMutationTest(TestCase seed, TestCase mutated) throws InterruptedException {
        Path seedPath = fileManager.getTestCasePath(seed);
        Path mutatedPath = fileManager.getTestCasePath(mutated);

        boolean seedCompiled = compileWithServer(seedPath.toString());
        if (!seedCompiled) {
            LOGGER.warning(String.format("Seed %s failed to compile during mutator test.", seed.getName()));
        }

        boolean mutantCompiled = compileWithServer(mutatedPath.toString());
        if (!mutantCompiled) {
            globalStats.incrementFailedCompilations();
            LOGGER.warning(String.format("Mutated test %s failed to compile.", mutated.getName()));
        }

        ExecutionResult seedIntResult = null;
        ExecutionResult seedJitResult = null;
        ExecutionResult mutantIntResult = null;
        ExecutionResult mutantJitResult = null;

        String seedClasspath = seedPath.getParent().toString();
        String mutantClasspath = mutatedPath.getParent().toString();

        ClassExtractor extractor = new ClassExtractor(true, 17);
        List<String> seedTypes = List.of();
        List<String> mutantTypes = List.of();
        try {
            seedTypes = extractor.extractTypeNames(seedPath, true, true, true);
        } catch (IOException ioe) {
            LOGGER.warning(String.format("Failed to extract class names for seed %s: %s",
                    seed.getName(), ioe.getMessage()));
        }
        try {
            mutantTypes = extractor.extractTypeNames(mutatedPath, true, true, true);
        } catch (IOException ioe) {
            LOGGER.warning(String.format("Failed to extract class names for mutant %s: %s",
                    mutated.getName(), ioe.getMessage()));
        }

        String seedCompileOnly = ClassExtractor.getCompileOnlyString(seedTypes);
        String mutantCompileOnly = ClassExtractor.getCompileOnlyString(mutantTypes);

        if (seedCompiled) {
            seedIntResult = runInterpreterTest(seed.getName(), seedClasspath);
            seedJitResult = runJITTest(seed.getName(), seedClasspath, seedCompileOnly);
        }

        if (mutantCompiled) {
            mutantIntResult = runInterpreterTest(mutated.getName(), mutantClasspath);
            mutantJitResult = runJITTest(mutated.getName(), mutantClasspath, mutantCompileOnly);
        }

        return new MutationTestReport(
                seed,
                mutated,
                seedCompiled,
                mutantCompiled,
                seedIntResult,
                seedJitResult,
                mutantIntResult,
                mutantJitResult);
    }

    public record MutationTestReport(
            TestCase seed,
            TestCase mutant,
            boolean seedCompiled,
            boolean mutantCompiled,
            ExecutionResult seedInterpreter,
            ExecutionResult seedJit,
            ExecutionResult mutantInterpreter,
            ExecutionResult mutantJit) {

        public boolean mutantTimedOut() {
            return (mutantInterpreter != null && mutantInterpreter.timedOut())
                    || (mutantJit != null && mutantJit.timedOut());
        }

        public boolean mutantExecuted() {
            return mutantInterpreter != null && mutantJit != null;
        }

        public boolean seedExecuted() {
            return seedInterpreter != null && seedJit != null;
        }

        public boolean exitCodeDiffers() {
            if (seedInterpreter == null || mutantInterpreter == null
                    || seedJit == null || mutantJit == null) {
                return false;
            }
            return seedInterpreter.exitCode() != mutantInterpreter.exitCode()
                    || seedJit.exitCode() != mutantJit.exitCode();
        }

        public boolean outputDiffers() {
            if (seedInterpreter == null || mutantInterpreter == null
                    || seedJit == null || mutantJit == null) {
                return false;
            }
            return !seedInterpreter.stdout().equals(mutantInterpreter.stdout())
                    || !seedJit.stdout().equals(mutantJit.stdout());
        }
    }

    @Override
    public void run() {
        try {
            while (true) {
                try {
                    TestCase testCase = executionQueue.take();
                    if (globalStats != null) {
                        globalStats.recordTestDispatched();
                    }
                    Path testCasePath = fileManager.getTestCasePath(testCase);
                    String testCasePathString = testCasePath.toString();
                    String classPathString = testCasePath.getParent().toString();

                    long compilationStart = System.nanoTime();
                    boolean compilable = compileWithServer(testCasePathString);
                    long compilationDurationNanos = System.nanoTime() - compilationStart;
                    if (!compilable) {
                        globalStats.incrementFailedCompilations();
                        globalStats.recordMutatorCompileFailure(testCase.getMutation());
                        LOGGER.fine(String.format("Compilation failed for test case: %s", testCase.getName()));
                        LOGGER.fine(String.format("applied mutation: %s", testCase.getMutation()));
                        continue;
                    }
                    globalStats.recordCompilationTimeNanos(compilationDurationNanos);
                    ClassExtractor extractor = new ClassExtractor(true, 17);
                    List<String> classNames = extractor.extractTypeNames(testCasePath, true, true, true);
                    String compileOnly = ClassExtractor.getCompileOnlyString(classNames);

                    ExecutionResult intExecutionResult = null;

                    // In FUZZ mode, run both interpreter and JIT tests
                    // in FUZZ_ASSERTS only run JIT tests
                    if (this.mode == FuzzerConfig.Mode.FUZZ) {
                        intExecutionResult = runInterpreterTest(testCase.getName(), classPathString);
                    }
                    ExecutionResult jitExecutionResult = runJITTest(testCase.getName(), classPathString, compileOnly);

                    if (LOGGER.isLoggable(Level.INFO)) {
                        String intMs = (intExecutionResult == null)
                                ? "n/a"
                                : String.format("%.3f%s",
                                    intExecutionResult.executionTime() / 1_000_000.0,
                                    intExecutionResult.timedOut() ? " timeout" : "");
                        String jitMs = (jitExecutionResult == null)
                                ? "n/a"
                                : String.format("%.3f%s",
                                    jitExecutionResult.executionTime() / 1_000_000.0,
                                    jitExecutionResult.timedOut() ? " timeout" : "");
                        LOGGER.info(String.format(
                                "Executed %s (mutator=%s) int=%s ms jit=%s ms",
                                testCase.getName(),
                                testCase.getMutation(),
                                intMs,
                                jitMs));
                    }

                    TestCaseResult result = new TestCaseResult(
                            testCase,
                            intExecutionResult,
                            jitExecutionResult,
                            compilable);

                    evaluationQueue.put(result);

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Executor encountered an error", e);
                    break;
                }
            }
        }
    }

    private void recordMutatorReward(TestCase testCase, double reward) {
        // Mutator weighting disabled for now; keep stub so logic can be re-enabled quickly.
        // if (testCase == null || globalStats == null) {
        //     return;
        // }
        // MutatorType mutatorType = testCase.getMutation();
        // if (mutatorType == null || mutatorType == MutatorType.SEED) {
        //     return;
        // }
        // double normalized = Double.isFinite(reward) ? reward : 0.0;
        // globalStats.recordMutatorReward(mutatorType, normalized);
    }

    

    private final HttpClient javacHttpClient = HttpClient.newBuilder() 
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private boolean compileWithServer(String sourceFilePath) {
        Path sourcePath = Paths.get(sourceFilePath).toAbsolutePath().normalize();
        String payload = String.format(
                "{\"sourcePath\":\"%s\"}", escapeJson(sourcePath.toString()));

        HttpRequest request = HttpRequest.newBuilder(javacServerEndpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = javacHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return true;
            }
            LOGGER.warning(() -> "Compilation failed (HTTP " + response.statusCode() + "):\n" + response.body());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "I/O error talking to javac server", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Interrupted while waiting for javac server response", e);
        }
        return false;
    }

    private static String escapeJson(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\':
                    result.append("\\\\");
                    break;
                case '"':
                    result.append("\\\"");
                    break;
                case '\b':
                    result.append("\\b");
                    break;
                case '\f':
                    result.append("\\f");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        result.append(String.format("\\u%04x", (int) ch));
                    } else {
                        result.append(ch);
                    }
            }
        }
        return result.toString();
    }


    private ExecutionResult runInterpreterTest(String sourceFilePath, String classPath)
            throws InterruptedException {
        try {
            return runTestCase(sourceFilePath, "-Xint", "-cp", classPath);
        } catch (IOException ioe) {
            LOGGER.log(Level.WARNING, "Interpreter execution failed", ioe);
            return null;
        }
    }

    private ExecutionResult runJITTest(String sourceFilePath, String classPath, String compileOnly)
            throws InterruptedException {
        try {
            Path cp = Paths.get(classPath);
            String errorFile = cp.resolve("hs_err_pid%p.log").toString();
            String replayFile = cp.resolve("replay_pid%p.log").toString();
            return runTestCase(
                    sourceFilePath,
                    "-Xbatch",
                    "-XX:+DisplayVMOutputToStderr",
                    "-XX:-DisplayVMOutputToStdout",
                    "-XX:-UsePerfData",
                    "-XX:-LogVMOutput",
                    "-XX:-TieredCompilation",
                    "-XX:+UnlockDiagnosticVMOptions",
                    "-XX:+TraceC2Optimizations",
                    "-XX:ErrorFile=" + errorFile,
                    "-XX:ReplayDataFile=" + replayFile,
                    compileOnly,
                    "-cp",
                    classPath);
        } catch (IOException ioe) {
            LOGGER.log(Level.WARNING, "JIT execution failed", ioe);
            return null;
        }
    }


    private ExecutionResult runTestCase(String sourceFilePath, String... flags) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        sourceFilePath = sourceFilePath.replace(".java", "");
        command.add(debugJdkPath + "/java");
        command.addAll(Arrays.asList(flags));
        command.add(sourceFilePath);

        LOGGER.fine(String.format("Executing test case %s with command: %s", sourceFilePath, String.join(" ", command)));

        long startTime = System.nanoTime();
    
        Path stdoutFile = Files.createTempFile("c2fuzz_stdout_", ".log");
        Path stderrFile = Files.createTempFile("c2fuzz_stderr_", ".log");

        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .redirectOutput(stdoutFile.toFile())
                .redirectError(stderrFile.toFile());
        Process process = null;
        boolean finished = false;
        try {
            process = processBuilder.start();
            finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }

            long endTime = System.nanoTime();
            long executionTime = endTime - startTime;

            int exitCode = process.isAlive() ? -1 : process.exitValue();
            String stdout = readFileSilently(stdoutFile);
            String stderr = readFileSilently(stderrFile);

            return new ExecutionResult(exitCode, stdout, stderr, executionTime, !finished);
        } catch (InterruptedException ie) {
            if (process != null) {
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            Thread.currentThread().interrupt();
            throw ie;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            try {
                Files.deleteIfExists(stdoutFile);
                Files.deleteIfExists(stderrFile);
            } catch (IOException ioe) {
                LOGGER.fine("Failed to delete temp output files: " + ioe.getMessage());
            }
        }

    }

    private static String readFileSilently(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            return "<error reading stream: " + ioe.getMessage() + ">";
        }
    }

}
