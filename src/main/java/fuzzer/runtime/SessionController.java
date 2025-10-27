package fuzzer.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import fuzzer.Evaluator;
import fuzzer.Executor;
import fuzzer.GlobalStats;
import fuzzer.MutationWorker;
import fuzzer.mutators.MutatorType;
import fuzzer.util.FileManager;
import fuzzer.util.LoggingConfig;
import fuzzer.util.NameGenerator;
import fuzzer.util.OptimizationVector;
import fuzzer.util.TestCase;
import fuzzer.util.TestCaseResult;

public final class SessionController {

    private static final Logger LOGGER = LoggingConfig.getLogger(SessionController.class);
    private static final int EXECUTION_QUEUE_CAPACITY = 500;
    private final FuzzerConfig config;
    private final GlobalStats globalStats;
    private final NameGenerator nameGenerator = new NameGenerator();
    private final AtomicBoolean topCasesArchived = new AtomicBoolean(false);
    private final AtomicBoolean finalMetricsLogged = new AtomicBoolean(false);

    private final BlockingQueue<TestCase> executionQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<TestCase> mutationQueue = new PriorityBlockingQueue<>();
    private final BlockingQueue<TestCaseResult> evaluationQueue = new LinkedBlockingQueue<>();

    private FileManager fileManager;
    private Random random;
    private long sessionSeed;

    public SessionController(FuzzerConfig config) {
        this.config = config;
        this.globalStats = new GlobalStats(OptimizationVector.Features.values().length);
        initialiseGlobalStats();
    }

    public void run() {
        fileManager = new FileManager(config.seedsDir(), config.timestamp());
        initialiseRandom();
        switch (config.mode()) {
            case TEST_MUTATOR -> runMutatorTest();
            case FUZZ -> runFuzzingLoop();
        }
    }

    private void initialiseGlobalStats() {
        for (var feature : OptimizationVector.Features.values()) {
            String featureName = OptimizationVector.FeatureName(feature);
            globalStats.opMax.put(featureName, 0.0);
            globalStats.opFreq.put(featureName, new java.util.concurrent.atomic.LongAdder());
        }
    }

    private void initialiseRandom() {
        java.util.OptionalLong seedOpt = config.configuredRngSeed();
        if (seedOpt.isPresent()) {
            sessionSeed = seedOpt.getAsLong();
            random = new Random(sessionSeed);
            LOGGER.info(String.format("Using random seed: %d", sessionSeed));
        } else {
            random = new Random();
            sessionSeed = random.nextLong();
            LOGGER.info(String.format("Using random seed: %d", sessionSeed));
        }
    }

    private void runMutatorTest() {
        String prefix = String.format(Locale.ROOT, "test_%s_", config.mutatorType().name());
        ArrayList<TestCase> seedTestCases = fileManager.setupSeedPool(prefix);

        BlockingQueue<TestCase> dummyExecutionQueue = new LinkedBlockingQueue<>();
        BlockingQueue<TestCaseResult> dummyEvaluationQueue = new LinkedBlockingQueue<>();
        BlockingQueue<TestCase> dummyMutationQueue = new PriorityBlockingQueue<>();

        Executor executor = new Executor(
                fileManager,
                config.debugJdkPath(),
                config.releaseJdkPath(),
                dummyExecutionQueue,
                dummyEvaluationQueue,
                globalStats);

        MutationWorker mutatorWorker = new MutationWorker(
                fileManager,
                nameGenerator,
                dummyMutationQueue,
                dummyExecutionQueue,
                random,
                config.printAst(),
                config.seedpoolDir().orElse(null),
                100,
                0.0,
                EXECUTION_QUEUE_CAPACITY,
                globalStats);

        int mutatedCount = 0;
        int mutantCompileFailures = 0;
        int executionDiffs = 0;
        int outputDiffs = 0;
        int timeouts = 0;
        int executionErrors = 0;

        for (TestCase seed : seedTestCases) {
            TestCase mutatedTestCase = mutatorWorker.mutateTestCaseWith(config.mutatorType(), seed);
            if (mutatedTestCase == null) {
                LOGGER.warning(String.format("Mutation resulted in null test case for seed: %s", seed.getName()));
                continue;
            }

            try {
                Executor.MutationTestReport report = executor.executeMutationTest(seed, mutatedTestCase);
                mutatedCount++;

                if (!report.seedCompiled()) {
                    LOGGER.warning(String.format("Seed %s failed to compile; skipping comparison.", seed.getName()));
                    executionErrors++;
                    continue;
                }

                if (!report.mutantCompiled()) {
                    mutantCompileFailures++;
                    continue;
                }

                if (!report.mutantExecuted()) {
                    LOGGER.warning(String.format("Mutation %s produced no execution results.", mutatedTestCase.getName()));
                    executionErrors++;
                    continue;
                }

                boolean exitMismatch = report.exitCodeDiffers();
                boolean stdoutMismatch = report.outputDiffers();
                boolean timedOut = report.mutantTimedOut();

                if (exitMismatch) {
                    executionDiffs++;
                }
                if (stdoutMismatch) {
                    outputDiffs++;
                }
                if (timedOut) {
                    timeouts++;
                }

                LOGGER.info(String.format(Locale.ROOT,
                        "Mutation %s -> %s | compile ok | timeout=%b | exitDiff=%b | stdoutDiff=%b",
                        seed.getName(),
                        mutatedTestCase.getName(),
                        timedOut,
                        exitMismatch,
                        stdoutMismatch));

            } catch (Exception e) {
                LOGGER.severe(String.format("Error executing mutated test case: %s", e.getMessage()));
                executionErrors++;
            }
        }

        LOGGER.info(String.format(Locale.ROOT,
                """
                Mutator %s summary:
                  seeds processed: %d
                  mutants generated: %d
                  compile failures: %d
                  execution errors: %d
                  timeouts: %d
                  exit mismatches: %d
                  stdout mismatches: %d
                """.stripTrailing(),
                config.mutatorType().name(),
                seedTestCases.size(),
                mutatedCount,
                mutantCompileFailures,
                executionErrors,
                timeouts,
                executionDiffs,
                outputDiffs));
    }

    private void runFuzzingLoop() {
        ArrayList<TestCase> seedTestCases = fileManager.setupSeedPool("session_");

        LOGGER.info(String.format("Starting %d executor thread(s)...", config.executorThreads()));
        ArrayList<Thread> executorWorkers = new ArrayList<>();
        for (int i = 0; i < config.executorThreads(); i++) {
            Executor executor = new Executor(
                    fileManager,
                    config.debugJdkPath(),
                    config.releaseJdkPath(),
                    executionQueue,
                    evaluationQueue,
                    globalStats);
            Thread executorThread = new Thread(executor);
            executorWorkers.add(executorThread);
            executorThread.start();
        }

        LOGGER.info("Starting evaluator thread...");
        Evaluator evaluator = new Evaluator(fileManager, evaluationQueue, mutationQueue, globalStats, config.scoringMode());
        Thread evaluatorThread = new Thread(evaluator);
        evaluatorThread.start();

        for (TestCase seed : seedTestCases) {
            executionQueue.add(seed);
        }

        LOGGER.info("Starting mutator thread...");
        int executionQueueBudget = Math.max(5 * config.executorThreads(), 1);
        double executionQueueFraction = 0.5;
        MutationWorker mutatorWorker = new MutationWorker(
                fileManager,
                nameGenerator,
                mutationQueue,
                executionQueue,
                random,
                config.printAst(),
                config.seedpoolDir().orElse(null),
                executionQueueBudget,
                executionQueueFraction,
                EXECUTION_QUEUE_CAPACITY,
                globalStats);
        Thread mutatorThread = new Thread(mutatorWorker);
        mutatorThread.start();

        Runnable dashboardShutdown = this::logFinalMetrics;
        Runnable snapshotOnExit = () -> saveTopTestCasesSnapshot(30);
        ConsoleDashboard dash = new ConsoleDashboard(
                globalStats,
                mutationQueue,
                evaluationQueue,
                executionQueue,
                dashboardShutdown);
        Thread dashboardThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            dashboardThread.interrupt();
            snapshotOnExit.run();
        }));
        dash.run(Duration.ofMillis(1000));
    }

    private void logFinalMetrics() {
        if (!finalMetricsLogged.compareAndSet(false, true)) {
            return;
        }
        GlobalStats.FinalMetrics metrics = globalStats.snapshotFinalMetrics();
        double featurePct = metrics.featureCoverageRatio() * 100.0;
        double pairPct = metrics.pairCoverageRatio() * 100.0;
        String summary = String.format(Locale.ROOT,
                """
                Final run metrics:
                  total tests executed: %,d
                  scored tests: %,d
                  found bugs: %,d
                  failed compilations: %,d
                  unique features seen: %d / %d (%.1f%%)
                  unique optimization pairs observed: %d / %d (%.1f%%)
                  average score: %.4f
                  maximum score: %.4f
                """,
                metrics.totalTests,
                metrics.scoredTests,
                metrics.foundBugs,
                metrics.failedCompilations,
                metrics.uniqueFeatures,
                metrics.totalFeatures,
                featurePct,
                metrics.uniquePairs,
                metrics.totalPairs,
                pairPct,
                metrics.avgScore,
                metrics.maxScore).stripTrailing();
        LOGGER.info(summary);

        long championAccepted = globalStats.getChampionAccepted();
        long championReplaced = globalStats.getChampionReplaced();
        long championRejected = globalStats.getChampionRejected();
        long championDiscarded = globalStats.getChampionDiscarded();
        long championTotal = championAccepted + championReplaced + championRejected + championDiscarded;
        String championSummary = String.format(Locale.ROOT,
                "Champion decisions: total %,d | accepted %,d | replaced %,d | rejected %,d | discarded %,d",
                championTotal,
                championAccepted,
                championReplaced,
                championRejected,
                championDiscarded);

        Path sessionDir = (fileManager != null) ? fileManager.getSessionDirectoryPath() : null;
        if (sessionDir != null) {
            Path summaryFile = sessionDir.resolve("final_metrics.txt");
            String fileContent = summary + System.lineSeparator() + championSummary + System.lineSeparator();
            try {
                Files.writeString(summaryFile, fileContent, StandardCharsets.UTF_8);
            } catch (IOException ioe) {
                LOGGER.warning(String.format(Locale.ROOT,
                        "Failed to write final metrics file %s: %s",
                        summaryFile,
                        ioe.getMessage()));
            }
        } else {
            LOGGER.warning("Session directory unavailable; skipping final metrics file.");
        }
    }

    private void saveTopTestCasesSnapshot(int limit) {
        if (mutationQueue == null) {
            LOGGER.warning("Mutation queue not initialised; nothing to snapshot.");
            return;
        }

        List<TestCase> snapshot = new ArrayList<>(mutationQueue);

        if (snapshot.isEmpty()) {
            LOGGER.info("No scored test cases available to archive on shutdown.");
            return;
        }

        if (!topCasesArchived.compareAndSet(false, true)) {
            LOGGER.fine("Top test cases already archived; skipping snapshot.");
            return;
        }

        snapshot.sort(Comparator.comparingDouble(TestCase::getScore).reversed());

        int count = Math.min(limit, snapshot.size());
        Path baseDir = config.seedpoolDir()
                .map(Path::of)
                .orElseGet(() -> Path.of("fuzz_sessions", "best_cases_" + config.timestamp()));
        Path targetDir = baseDir.resolve(String.format("top_%d_on_exit", count));

        try {
            Files.createDirectories(targetDir);
        } catch (IOException ioe) {
            LOGGER.severe("Failed to create directory for top test cases: " + ioe.getMessage());
            return;
        }

        for (int i = 0; i < count; i++) {
            TestCase tc = snapshot.get(i);
            Path sourcePath = fileManager.getTestCasePath(tc);
            if (!Files.exists(sourcePath)) {
                LOGGER.fine("Skipping missing test case file: " + sourcePath);
                continue;
            }

            String targetName = String.format(Locale.ROOT, "%02d_score%.4f_%s",
                    i + 1,
                    tc.getScore(),
                    sourcePath.getFileName().toString());
            Path targetPath = targetDir.resolve(targetName);

            try {
                Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ioe) {
                LOGGER.warning(String.format("Failed to copy %s to archive: %s", sourcePath, ioe.getMessage()));
            }
        }

        LoggingConfig.safeInfo(LOGGER, String.format("Archived %d top test cases to %s", count, targetDir));
    }

}
