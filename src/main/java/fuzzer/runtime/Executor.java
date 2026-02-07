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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.io.ClassExtractor;
import fuzzer.io.FileManager;
import fuzzer.logging.LoggingConfig;
import fuzzer.model.ExecutionResult;
import fuzzer.model.TestCase;
import fuzzer.model.TestCaseResult;
import fuzzer.runtime.monitoring.GlobalStats;

public class Executor implements Runnable {
    private static final Logger LOGGER = LoggingConfig.getLogger(Executor.class);
    private static final Duration JAVAC_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration JAVAC_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final long EXECUTION_TIMEOUT_SECONDS = 15L;
    private static final long PROCESS_SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final String debugJdkPath;
    private final BlockingQueue<TestCase> executionQueue;
    private final BlockingQueue<TestCaseResult> evaluationQueue;
    private final GlobalStats globalStats;
    private final FileManager fileManager;
    private final FuzzerConfig.Mode mode;
    private final URI javacServerEndpoint;
    private final ClassExtractor classExtractor = new ClassExtractor(true, 17);

    public Executor(FileManager fm, String debugJdkPath, BlockingQueue<TestCase> executionQueue, BlockingQueue<TestCaseResult> evaluationQueue, GlobalStats globalStats, FuzzerConfig.Mode mode) {
        this.executionQueue = Objects.requireNonNull(executionQueue, "executionQueue");
        this.evaluationQueue = Objects.requireNonNull(evaluationQueue, "evaluationQueue");
        this.debugJdkPath = Objects.requireNonNull(debugJdkPath, "debugJdkPath");
        this.globalStats = Objects.requireNonNull(globalStats, "globalStats");
        this.fileManager = Objects.requireNonNull(fm, "fileManager");
        this.mode = Objects.requireNonNull(mode, "mode");

        String host = Optional.ofNullable(System.getenv("JAVAC_HOST"))
                .filter(s -> !s.isBlank())
                .orElse("127.0.0.1");
        String port = Optional.ofNullable(System.getenv("JAVAC_PORT"))
                .filter(s -> !s.isBlank())
                .orElse("8090");
        this.javacServerEndpoint = URI.create(String.format("http://%s:%s/compile", host, port));
    }



    @Override
    public void run() {
        while (true) {
            try {
                TestCase testCase = executionQueue.take();
                globalStats.recordTestDispatched();
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
                List<String> classNames = classExtractor.extractTypeNames(testCasePath, true, true, true);
                String compileOnly = ClassExtractor.getCompileOnlyString(classNames);

                ExecutionResult intExecutionResult = null;

                // In FUZZ mode, run both interpreter and JIT tests
                // in FUZZ_ASSERTS only run JIT tests
                if (this.mode == FuzzerConfig.Mode.FUZZ) {
                    intExecutionResult = runInterpreterTest(testCase.getName(), classPathString);
                }
                ExecutionResult jitExecutionResult = runJITTest(testCase.getName(), classPathString, compileOnly);
                boolean missingInterpreterResult = (this.mode == FuzzerConfig.Mode.FUZZ && intExecutionResult == null);
                boolean missingJitResult = (jitExecutionResult == null);
                if (missingInterpreterResult || missingJitResult) {
                    LOGGER.warning(String.format(
                            "Execution failed for %s (missing %s result); dropping testcase",
                            testCase.getName(),
                            missingJitResult ? "JIT" : "interpreter"));
                    fileManager.deleteTestCase(testCase);
                    continue;
                }

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
            }
        }
    }


    

    private final HttpClient javacHttpClient = HttpClient.newBuilder()
        .connectTimeout(JAVAC_CONNECT_TIMEOUT)
        .build();

    private boolean compileWithServer(String sourceFilePath) {
        Path sourcePath = Paths.get(sourceFilePath).toAbsolutePath().normalize();
        String payload = String.format(
                "{\"sourcePath\":\"%s\"}", escapeJson(sourcePath.toString()));

        HttpRequest request = HttpRequest.newBuilder(javacServerEndpoint)
                .timeout(JAVAC_REQUEST_TIMEOUT)
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
        sourceFilePath = stripJavaSuffix(sourceFilePath);
        command.add(Path.of(debugJdkPath, "java").toString());
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
            finished = process.waitFor(EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                try {
                    process.waitFor(PROCESS_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
                    process.waitFor(PROCESS_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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

    private static String stripJavaSuffix(String source) {
        if (source == null) {
            return null;
        }
        return source.endsWith(".java")
                ? source.substring(0, source.length() - ".java".length())
                : source;
    }

    private static String readFileSilently(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            return "<error reading stream: " + ioe.getMessage() + ">";
        }
    }

}
