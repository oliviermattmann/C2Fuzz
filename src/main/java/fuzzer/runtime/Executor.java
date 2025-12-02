package fuzzer.runtime;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import fuzzer.util.ClassExtractor;
import fuzzer.util.ExecutionResult;
import fuzzer.util.FileManager;
import fuzzer.util.LoggingConfig;
import fuzzer.util.TestCase;
import fuzzer.util.TestCaseResult;

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
    private static final Duration STREAM_DRAIN_TIMEOUT = Duration.ofSeconds(60);
    private static final ExecutorService TEST_RUN_POOL = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()));

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
            CompletableFuture<ExecutionResult> seedIntFuture =
                    submitTestRun(() -> runInterpreterTest(seed.getName(), seedClasspath));
            CompletableFuture<ExecutionResult> seedJitFuture =
                    submitTestRun(() -> runJITTest(seed.getName(), seedClasspath, seedCompileOnly));
            seedIntResult = awaitTestResult(seedIntFuture);
            seedJitResult = awaitTestResult(seedJitFuture);
        }

        if (mutantCompiled) {
            CompletableFuture<ExecutionResult> mutantIntFuture =
                    submitTestRun(() -> runInterpreterTest(mutated.getName(), mutantClasspath));
            CompletableFuture<ExecutionResult> mutantJitFuture =
                    submitTestRun(() -> runJITTest(mutated.getName(), mutantClasspath, mutantCompileOnly));
            mutantIntResult = awaitTestResult(mutantIntFuture);
            mutantJitResult = awaitTestResult(mutantJitFuture);
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
                //boolean compilable = compile(testCasePathString);
                boolean compilable = compileWithServer(testCasePathString);
                long compilationDurationNanos = System.nanoTime() - compilationStart;
                if (!compilable) {
                    globalStats.incrementFailedCompilations();
                    globalStats.recordMutatorCompileFailure(testCase.getMutation());
                    LOGGER.warning(String.format("Compilation failed for test case: %s", testCase.getName()));
                    LOGGER.warning(String.format("applied mutation: %s", testCase.getMutation()));
                    // recordMutatorReward(testCase, MUTATOR_COMPILE_PENALTY);
                    continue;
                }
                long compilationMillis = TimeUnit.NANOSECONDS.toMillis(compilationDurationNanos);
                globalStats.recordCompilationTimeNanos(compilationDurationNanos);
                LOGGER.info(String.format(
                        "Compilation for %s took %d ms (Executor dispatch)",
                        testCase.getName(), compilationMillis));
                ClassExtractor extractor = new ClassExtractor(true, 17);
                List<String> classNames = extractor.extractTypeNames(testCasePath, true, true, true);
                String compileOnly = ClassExtractor.getCompileOnlyString(classNames);

                // String classPath = Path.of(testCase.getPath()).getParent().toString();
                ExecutionResult intExecutionResult = null;
                CompletableFuture<ExecutionResult> intFuture = null;
                if (this.mode == FuzzerConfig.Mode.FUZZ) {
                    intFuture = submitTestRun(() -> runInterpreterTest(testCase.getName(), classPathString));
                }
                CompletableFuture<ExecutionResult> jitFuture =
                        submitTestRun(() -> runJITTest(testCase.getName(), classPathString, compileOnly));

                if (intFuture != null) {
                    intExecutionResult = awaitTestResult(intFuture);
                }
                ExecutionResult jitExecutionResult = awaitTestResult(jitFuture);

                TestCaseResult result = new TestCaseResult(testCase, intExecutionResult, jitExecutionResult, compilable);

                evaluationQueue.put(result);

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOGGER.info("Executor interrupted; shutting down.");
                break;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Executor encountered an error", e);
                break;
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

        LOGGER.fine("Running test case with command: " + String.join(" ", command));
    
        //LOGGER.info("Executing command: " + String.join(" ", command));
        long startTime = System.nanoTime();
    
        Process process = new ProcessBuilder(command).start();

        ExecutorService outputThreads = Executors.newFixedThreadPool(2);

        Future<String> stdoutFuture = outputThreads.submit(() -> readStream(process.getInputStream()));
        Future<String> stderrFuture = outputThreads.submit(() -> readStream(process.getErrorStream()));

        boolean finished = false;
        try {
            finished = process.waitFor(15, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            process.destroyForcibly();
            closeProcessStreams(process);
            outputThreads.shutdownNow();
            Thread.currentThread().interrupt();
            throw ie;
        }

        if (!finished) {
            process.destroyForcibly();
        }
        // Read outputs before closing the process streams to avoid races that surface as
        // "<error reading stream: Stream closed>" false positives.
        String stdout = safeGet(stdoutFuture, STREAM_DRAIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        String stderr = safeGet(stderrFuture, STREAM_DRAIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        closeProcessStreams(process);
        outputThreads.shutdownNow();

        long endTime = System.nanoTime();
        long executionTime = endTime - startTime;

        int exitCode;
        try {
            exitCode = process.exitValue();
        } catch (IllegalThreadStateException e) {
            exitCode = -1;
        }

        return new ExecutionResult(exitCode, stdout, stderr, executionTime, !finished);
    }

    private static String readStream(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            return br.lines().collect(Collectors.joining("\n"));
        }
    }

    private static String safeGet(Future<String> f, long timeout, TimeUnit unit) {
        try {
            return f.get(timeout, unit);
        } catch (Exception e) {
            return "<error reading stream: " + e.getMessage() + ">";
        }
    }

    private static void closeProcessStreams(Process process) {
        if (process == null) {
            return;
        }
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(process.getOutputStream());
    }

    private static void closeQuietly(Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (IOException ignored) {
        }
    }

    private CompletableFuture<ExecutionResult> submitTestRun(InterruptibleSupplier<ExecutionResult> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new CompletionException(ie);
            }
        }, TEST_RUN_POOL);
    }

    private ExecutionResult awaitTestResult(CompletableFuture<ExecutionResult> future) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof CompletionException ce && ce.getCause() != null) {
                cause = ce.getCause();
            }
            if (cause instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            LOGGER.log(Level.WARNING, "Test execution failed", cause);
            return null;
        }
    }

    @FunctionalInterface
    private interface InterruptibleSupplier<T> {
        T get() throws InterruptedException;
    }

}
