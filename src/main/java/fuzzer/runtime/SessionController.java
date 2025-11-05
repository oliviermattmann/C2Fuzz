package fuzzer.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import fuzzer.util.FileManager;
import fuzzer.util.JVMOutputParser;
import fuzzer.util.LoggingConfig;
import fuzzer.util.NameGenerator;
import fuzzer.util.OptimizationVector;
import fuzzer.util.TestCase;
import fuzzer.util.TestCaseResult;

public final class SessionController {

    private static final Logger LOGGER = LoggingConfig.getLogger(SessionController.class);
    private static final int EXECUTION_QUEUE_CAPACITY = 250;
    private final FuzzerConfig config;
    private final GlobalStats globalStats;
    private SignalRecorder signalRecorder;
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
        ensureBaseDirectories();
        fileManager = new FileManager(config.seedsDir(), config.timestamp());
        initialiseRandom();
        switch (config.mode()) {
            case TEST_MUTATOR -> {
                runMutatorTest();}
            case FUZZ -> {
                runFuzzingLoop();
            }
            case FUZZ_ASSERTS -> {
                runFuzzingLoop();
            }
        }
    }

    private void ensureBaseDirectories() {
        try {
            Files.createDirectories(Path.of("logs"));
        } catch (IOException e) {
            LOGGER.warning(String.format("Unable to create logs directory: %s", e.getMessage()));
        }
        try {
            Files.createDirectories(Path.of("fuzz_sessions"));
        } catch (IOException e) {
            LOGGER.warning(String.format("Unable to create fuzz_sessions directory: %s", e.getMessage()));
        }
    }

    private void initialiseGlobalStats() {
        for (var feature : OptimizationVector.Features.values()) {
            String featureName = OptimizationVector.FeatureName(feature);
            globalStats.getOpMaxMap().put(featureName, 0.0);
            globalStats.getOpFreqMap().put(featureName, new java.util.concurrent.atomic.LongAdder());
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
        String prefix = String.format("test_%s_", config.mutatorType().name());
        ArrayList<TestCase> seedTestCases = fileManager.setupSeedPool(prefix);
        if (seedTestCases.isEmpty()) {
            LOGGER.warning("No seeds available for mutator test mode.");
            return;
        }

        BlockingQueue<TestCase> dummyExecutionQueue = new LinkedBlockingQueue<>();
        BlockingQueue<TestCaseResult> dummyEvaluationQueue = new LinkedBlockingQueue<>();
        BlockingQueue<TestCase> dummyMutationQueue = new PriorityBlockingQueue<>();

        Executor executor = new Executor(
                fileManager,
                config.debugJdkPath(),
                dummyExecutionQueue,
                dummyEvaluationQueue,
                globalStats,
                this.config.mode());

        MutationWorker mutatorWorker = new MutationWorker(
                fileManager,
                nameGenerator,
                dummyMutationQueue,
                dummyExecutionQueue,
                random,
                config.printAst(),
                100,
                0.0,
                EXECUTION_QUEUE_CAPACITY,
                globalStats);

        int seedsPlanned = Math.min(config.testMutatorSeedSamples(), seedTestCases.size());
        int iterationsPerSeed = Math.max(1, config.testMutatorIterations());
        int plannedMutations = seedsPlanned * iterationsPerSeed;

        EnumMap<OptimizationVector.Features, Integer> aggregateDelta =
                new EnumMap<>(OptimizationVector.Features.class);
        for (OptimizationVector.Features feature : OptimizationVector.Features.values()) {
            aggregateDelta.put(feature, 0);
        }

        int attempts = 0;
        int generatedMutants = 0;
        int nullMutations = 0;
        int seedCompileFailures = 0;
        int mutantCompileFailures = 0;
        int seedExecutionFailures = 0;
        int mutantExecutionFailures = 0;
        int timeoutCount = 0;
        int exitMismatchCount = 0;
        int stdoutMismatchCount = 0;
        int optDeltaCount = 0;
        int unexpectedErrors = 0;

        LOGGER.info(String.format(
                "Testing mutator %s with %d seed(s) × %d iteration(s) (%d planned mutations).",
                config.mutatorType(),
                seedsPlanned,
                iterationsPerSeed,
                plannedMutations));

        for (int seedIndex = 0; seedIndex < seedsPlanned; seedIndex++) {
            TestCase seed = seedTestCases.get(seedIndex);
            for (int iteration = 0; iteration < iterationsPerSeed; iteration++) {
                attempts++;
                TestCase mutatedTestCase = mutatorWorker.mutateTestCaseWith(config.mutatorType(), seed);
                if (mutatedTestCase == null) {
                    nullMutations++;
                    LOGGER.fine(String.format(
                            "Mutator %s returned null for seed %s (iter %d/%d).",
                            config.mutatorType(),
                            seed.getName(),
                            iteration + 1,
                            iterationsPerSeed));
                    continue;
                }

                generatedMutants++;
                try {
                    Executor.MutationTestReport report = executor.executeMutationTest(seed, mutatedTestCase);

                    if (!report.seedCompiled()) {
                        seedCompileFailures++;
                        LOGGER.warning(String.format(
                                "Seed %s failed to compile; skipping comparison.",
                                seed.getName()));
                        continue;
                    }

                    if (!report.mutantCompiled()) {
                        mutantCompileFailures++;
                        LOGGER.warning(String.format(
                                "Mutant %s failed to compile; skipping execution.",
                                mutatedTestCase.getName()));
                        continue;
                    }

                    if (!report.seedExecuted()) {
                        seedExecutionFailures++;
                        LOGGER.warning(String.format(
                                "Seed %s did not produce execution results; skipping comparison.",
                                seed.getName()));
                        continue;
                    }

                    if (!report.mutantExecuted()) {
                        mutantExecutionFailures++;
                        LOGGER.warning(String.format(
                                "Mutant %s produced no execution results.",
                                mutatedTestCase.getName()));
                        continue;
                    }

                    boolean timedOut = report.mutantTimedOut();
                    boolean exitMismatch = report.exitCodeDiffers();
                    boolean stdoutMismatch = report.outputDiffers();

                    if (timedOut) {
                        timeoutCount++;
                    }
                    if (exitMismatch) {
                        exitMismatchCount++;
                    }
                    if (stdoutMismatch) {
                        stdoutMismatchCount++;
                    }

                    OptimizationVector seedVector = JVMOutputParser
                            .parseJVMOutput(report.seedJit().stderr())
                            .mergedCounts();
                    OptimizationVector mutantVector = JVMOutputParser
                            .parseJVMOutput(report.mutantJit().stderr())
                            .mergedCounts();

                    StringBuilder increases = new StringBuilder();
                    StringBuilder decreases = new StringBuilder();
                    for (OptimizationVector.Features feature : OptimizationVector.Features.values()) {
                        int seedValue = seedVector.getCount(feature);
                        int mutantValue = mutantVector.getCount(feature);
                        int delta = mutantValue - seedValue;
                        if (delta == 0) {
                            continue;
                        }
                        aggregateDelta.put(feature, aggregateDelta.get(feature) + delta);
                        if (delta > 0) {
                            if (increases.length() > 0) {
                                increases.append(", ");
                            }
                            increases.append('+').append(OptimizationVector.FeatureName(feature))
                                    .append(" (+").append(delta).append(')');
                        } else {
                            if (decreases.length() > 0) {
                                decreases.append(", ");
                            }
                            decreases.append('-').append(OptimizationVector.FeatureName(feature))
                                    .append(" (").append(delta).append(')');
                        }
                    }

                    StringBuilder deltaSummaryBuilder = new StringBuilder();
                    if (increases.length() > 0) {
                        deltaSummaryBuilder.append(increases);
                    }
                    if (decreases.length() > 0) {
                        if (deltaSummaryBuilder.length() > 0) {
                            deltaSummaryBuilder.append("; ");
                        }
                        deltaSummaryBuilder.append(decreases);
                    }
                    String optSummary = deltaSummaryBuilder.length() > 0
                            ? deltaSummaryBuilder.toString()
                            : "none";
                    if (deltaSummaryBuilder.length() > 0) {
                        optDeltaCount++;
                    }

                    LOGGER.info(String.format(
                            "Seed %s (iter %d/%d) -> %s | timeout=%b | exitDiff=%b | stdoutDiff=%b | optΔ: %s",
                            seed.getName(),
                            iteration + 1,
                            iterationsPerSeed,
                            mutatedTestCase.getName(),
                            timedOut,
                            exitMismatch,
                            stdoutMismatch,
                            optSummary));
                } catch (Exception e) {
                    unexpectedErrors++;
                    LOGGER.severe(String.format(
                            "Error executing mutated test case %s: %s",
                            mutatedTestCase.getName(),
                            e.getMessage()));
                }
            }
        }

        StringBuilder aggregateIncrease = new StringBuilder();
        StringBuilder aggregateDecrease = new StringBuilder();
        for (OptimizationVector.Features feature : OptimizationVector.Features.values()) {
            int totalDelta = aggregateDelta.get(feature);
            if (totalDelta > 0) {
                if (aggregateIncrease.length() > 0) {
                    aggregateIncrease.append(", ");
                }
                aggregateIncrease.append(OptimizationVector.FeatureName(feature))
                        .append(" (+").append(totalDelta).append(')');
            } else if (totalDelta < 0) {
                if (aggregateDecrease.length() > 0) {
                    aggregateDecrease.append(", ");
                }
                aggregateDecrease.append(OptimizationVector.FeatureName(feature))
                        .append(" (").append(totalDelta).append(')');
            }
        }
        StringBuilder aggregateSummaryBuilder = new StringBuilder();
        if (aggregateIncrease.length() > 0) {
            aggregateSummaryBuilder.append(aggregateIncrease);
        }
        if (aggregateDecrease.length() > 0) {
            if (aggregateSummaryBuilder.length() > 0) {
                aggregateSummaryBuilder.append("; ");
            }
            aggregateSummaryBuilder.append(aggregateDecrease);
        }
        String aggregateSummary = aggregateSummaryBuilder.length() > 0
                ? aggregateSummaryBuilder.toString()
                : "none";

        String summary = String.format(
                """
                Mutator %s summary:
                  seeds considered: %d
                  planned mutations: %d
                  actual attempts: %d
                  mutants generated: %d
                  null mutations: %d
                  seed compile failures: %d
                  mutant compile failures: %d
                  seed execution issues: %d
                  mutant execution issues: %d
                  timeouts: %d
                  exit mismatches: %d
                  stdout mismatches: %d
                  opt-delta mutations: %d
                  unexpected errors: %d
                  aggregate optΔ: %s
                """.stripTrailing(),
                config.mutatorType(),
                seedsPlanned,
                plannedMutations,
                attempts,
                generatedMutants,
                nullMutations,
                seedCompileFailures,
                mutantCompileFailures,
                seedExecutionFailures,
                mutantExecutionFailures,
                timeoutCount,
                exitMismatchCount,
                stdoutMismatchCount,
                optDeltaCount,
                unexpectedErrors,
                aggregateSummary);
        LOGGER.info(summary);
    }

    private void runFuzzingLoop() {
        ArrayList<TestCase> seedTestCases = fileManager.setupSeedPool("session_");
        signalRecorder = new SignalRecorder(
                fileManager.getSessionDirectoryPath().resolve("signals.csv"),
                1_000L);

        LOGGER.info(String.format("Starting %d executor thread(s)...", config.executorThreads()));
        ArrayList<Thread> executorWorkers = new ArrayList<>();
        for (int i = 0; i < config.executorThreads(); i++) {
            Executor executor = new Executor(
                    fileManager,
                    config.debugJdkPath(),
                    executionQueue,
                    evaluationQueue,
                    globalStats,
                    this.config.mode());
            Thread executorThread = new Thread(executor);
            executorWorkers.add(executorThread);
            executorThread.start();
        }

        LOGGER.info("Starting evaluator thread...");
        Evaluator evaluator = new Evaluator(
                fileManager,
                evaluationQueue,
                mutationQueue,
                globalStats,
                config.scoringMode(),
                signalRecorder,
                this.config.mode());
        Thread evaluatorThread = new Thread(evaluator);
        evaluatorThread.start();

        for (TestCase seed : seedTestCases) {
            executionQueue.add(seed);
        }

        LOGGER.info("Starting mutator thread...");
        int executionQueueBudget = Math.max(5 * config.executorThreads(), 1);
        double executionQueueFraction = 0.25;
        MutationWorker mutatorWorker = new MutationWorker(
                fileManager,
                nameGenerator,
                mutationQueue,
                executionQueue,
                random,
                config.printAst(),
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
        String summary = String.format(
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
        String championSummary = String.format(
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
                LOGGER.warning(String.format(
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

            String targetName = String.format("%02d_score%.4f_%s",
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
