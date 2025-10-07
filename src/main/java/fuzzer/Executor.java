package fuzzer;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import fuzzer.util.ClassExtractor;
import fuzzer.util.ExecutionResult;
import fuzzer.util.LoggingConfig;
import fuzzer.util.TestCase;
import fuzzer.util.TestCaseResult;

public class Executor implements Runnable{
    private final String debugJdkPath;
    private final String releaseJdkPath;
    private final BlockingQueue<TestCase> executionQueue;
    private final BlockingQueue<TestCaseResult> evaluationQueue;
    private final GlobalStats globalStats;
    private static final Logger LOGGER = LoggingConfig.getLogger(Executor.class);


    public Executor(String debugJdkPath, String releaseJdkPath, BlockingQueue<TestCase> executionQueue, BlockingQueue<TestCaseResult> evaluationQueue, GlobalStats globalStats) {
        this.executionQueue = executionQueue;
        this.evaluationQueue = evaluationQueue;
        this.debugJdkPath = debugJdkPath;
        this.releaseJdkPath = releaseJdkPath;
        this.globalStats = globalStats;
    }



    public void executeMutationTest(TestCase seed, TestCase mutated) {
        // compile both test cases
        LOGGER.info(String.format("Testing mutation of seed: %s", seed.getName()));
        if (!compile(seed.getPath())) {
            LOGGER.warning(String.format("Compilation failed for seed test case: %s", seed.getName()));
            globalStats.failedCompilations.increment();
            return;
        }

        if (!compile(mutated.getPath())) {
            globalStats.failedCompilations.increment();
            LOGGER.warning(String.format("Compilation failed for mutated test case: %s", mutated.getName()));
            return;
        }

        // get directory of the test case
        String classPath = Path.of(seed.getPath()).getParent().toString();

        ExecutionResult seedExecutionResult = null;
        ExecutionResult mutatedExecutionResult = null;
        ClassExtractor extractor = new ClassExtractor(true, 17);
        List<String> classNames = null;
        try {
            classNames = extractor.extractTypeNames(Path.of(seed.getPath()), true, true, true);
        } catch (IOException e) {
            LOGGER.warning(String.format("Failed to extract class names from seed %s: %s", seed.getName(), e.getMessage()));
            return;
        }
        String compileOnly = extractor.getCompileOnlyString(classNames);


        // run both in jit mode
        seedExecutionResult = runJITTest(seed.getName(), classPath, compileOnly);
        mutatedExecutionResult = runJITTest(mutated.getName(), classPath, compileOnly);


        // check for behavioral differences
        // first exit code
        if (seedExecutionResult.exitCode() != mutatedExecutionResult.exitCode()) {
            LOGGER.warning(String.format("Behavioral difference detected (exit code) between seed %s and mutated %s", seed.getName(), mutated.getName()));
            return;
        }

        TestCaseResult result = new TestCaseResult(mutated, seedExecutionResult, mutatedExecutionResult, true);

        evaluationQueue.add(result);
         
    }

    @Override
    public void run() {
        while (true) {
            try {
                TestCase testCase = executionQueue.take();

                boolean compilable = compile(testCase.getPath());
                if (!compilable) {
                    globalStats.failedCompilations.increment();
                    LOGGER.warning(String.format("Compilation failed for test case: %s", testCase.getName()));
                    continue;
                }

                ClassExtractor extractor = new ClassExtractor(true, 17);
                List<String> classNames = extractor.extractTypeNames(Path.of(testCase.getPath()), true, true, true);
                String compileOnly = ClassExtractor.getCompileOnlyString(classNames);

                String classPath = Path.of(testCase.getPath()).getParent().toString();

                ExecutionResult intExecutionResult = runInterpreterTest(testCase.getName(), classPath);

                ExecutionResult jitExecutionResult = runJITTest(testCase.getName(), classPath, compileOnly);

                TestCaseResult result = new TestCaseResult(testCase, intExecutionResult, jitExecutionResult, compilable);

                evaluationQueue.put(result);

            } catch (Exception e) {
                LOGGER.info("Exception in Executor: " + e.getMessage());
                break;
            }
        }
    }

    private boolean compile(String sourceFilePath) {
        List<String> compileCommand = new ArrayList<>();
        compileCommand.add(releaseJdkPath + "/javac");
        compileCommand.add(sourceFilePath);
        try {
            Process process = new ProcessBuilder(compileCommand).start();
            int compileExitCode = process.waitFor();
            if (compileExitCode != 0) {
                LOGGER.info("Compilation failed with exit code " + compileExitCode);
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                String errorOutput = reader.lines().collect(Collectors.joining("\n"));
                LOGGER.info("Compilation error output:\n" + errorOutput);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private ExecutionResult runInterpreterTest(String sourceFilePath, String classPath) {
        try {
            return runTestCase(sourceFilePath, "-Xint", "-cp", classPath);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return null;
        }
    }

    private ExecutionResult runJITTest(String sourceFilePath, String classPath, String compileOnly){
        try {
            return runTestCase(sourceFilePath,    "-XX:+DisplayVMOutputToStderr", "-XX:-DisplayVMOutputToStdout",
             "-XX:-LogVMOutput", "-XX:-TieredCompilation"//, "-XX:TieredStopAtLevel=4"
           ,"-XX:+UnlockDiagnosticVMOptions", "-XX:+TraceLoopOpts"
           ,"-XX:+TraceLoopUnswitching", "-XX:+PrintCEE","-XX:+PrintInlining","-XX:+TraceDeoptimization","-XX:+PrintEscapeAnalysis",
           "-XX:+PrintEliminateLocks","-XX:+PrintOptoStatistics",
           "-XX:+PrintEliminateAllocations","-XX:+PrintBlockElimination","-XX:+PrintPhiFunctions",
           "-XX:+PrintCanonicalization","-XX:+PrintNullCheckElimination","-XX:+TraceRangeCheckElimination",
           "-XX:+PrintOptimizePtrCompare", "-XX:+TraceIterativeGVN"
           , compileOnly
           ,"-cp", classPath
           
           );
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return null;
        } 
    }

    private ExecutionResult runTestCase(String sourceFilePath, String... flags) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        sourceFilePath = sourceFilePath.replace(".java", "");
        command.add(debugJdkPath + "/java");
        command.addAll(Arrays.asList(flags));
        command.add(sourceFilePath);
    
        LOGGER.info("Executing command: " + String.join(" ", command));
        long startTime = System.nanoTime();
    
        Process process = new ProcessBuilder(command).start();
    
        ExecutorService outputThreads= Executors.newFixedThreadPool(2);
    
        Future<String> stdoutFuture = outputThreads.submit(() -> readStream(process.getInputStream()));
        Future<String> stderrFuture = outputThreads.submit(() -> readStream(process.getErrorStream()));
    
        // Wait for process to finish with timeout
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
    
        if (!finished) {
            LOGGER.warning("Process did not finish in time, killing...");
            process.destroyForcibly();
        }
    
        // Close streams explicitly to unblock readers
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(process.getOutputStream());
    
        // Collect results from futures with small timeout
        String stdout = safeGet(stdoutFuture, 5, TimeUnit.SECONDS);
        String stderr = safeGet(stderrFuture, 5, TimeUnit.SECONDS);
    
        outputThreads.shutdownNow();
    
        long endTime = System.nanoTime();
        long executionTime = endTime - startTime;
    
        int exitCode;
        try {
            exitCode = process.exitValue();
        } catch (IllegalThreadStateException e) {
            // Still running? Treat as failure
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

    private static void closeQuietly(Closeable c) {
    try {
        if (c != null) c.close();
    } catch (IOException ignored) {}
}

}
