package fuzzer.runtime;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import fuzzer.mutators.MutatorType;
import fuzzer.runtime.scheduling.BanditMutatorScheduler;
import fuzzer.runtime.scheduling.MopMutatorScheduler;
import fuzzer.runtime.scheduling.MutatorScheduler;
import fuzzer.runtime.scheduling.UniformRandomMutatorScheduler;
import fuzzer.util.FileManager;
import fuzzer.util.JVMOutputParser;
import fuzzer.util.LoggingConfig;
import fuzzer.util.NameGenerator;
import fuzzer.util.OptimizationVector;
import fuzzer.util.TestCase;
import fuzzer.util.TestCaseResult;

public final class SessionController {

    private static final Logger LOGGER = LoggingConfig.getLogger(SessionController.class);
    private static final int EXECUTION_QUEUE_CAPACITY = 750;
    private final FuzzerConfig config;
    private final GlobalStats globalStats;
    private final Set<String> seedBlacklist;
    private SignalRecorder signalRecorder;
    private MutatorOptimizationRecorder mutatorOptimizationRecorder;
    private final NameGenerator nameGenerator = new NameGenerator();
    private final AtomicBoolean topCasesArchived = new AtomicBoolean(false);
    private final AtomicBoolean finalMetricsLogged = new AtomicBoolean(false);
    private final AtomicBoolean mutationQueueDumped = new AtomicBoolean(false);
    private final AtomicBoolean shutdownInitiated = new AtomicBoolean(false);
    private final List<Thread> executorWorkers = new ArrayList<>();
    private final List<Thread> mutatorThreads = new ArrayList<>();
    private Thread evaluatorThread;

    private final BlockingQueue<TestCase> executionQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<TestCase> mutationQueue = new PriorityBlockingQueue<>();
    private final BlockingQueue<TestCaseResult> evaluationQueue = new LinkedBlockingQueue<>();

    private FileManager fileManager;
    private Random random;
    private long sessionSeed;

    public SessionController(FuzzerConfig config) {
        this.config = config;
        this.globalStats = new GlobalStats(OptimizationVector.Features.values().length);
        this.seedBlacklist = loadSeedBlacklist(config);
        initialiseGlobalStats();
    }

    private Set<String> loadSeedBlacklist(FuzzerConfig cfg) {
        if (cfg == null) {
            return Collections.emptySet();
        }
        var optPath = cfg.blacklistPath();
        if (optPath.isEmpty()) {
            return Collections.emptySet();
        }
        Path path = Path.of(optPath.get());
        if (!Files.exists(path)) {
            LOGGER.warning(String.format("Blacklist file does not exist: %s", path));
            return Collections.emptySet();
        }
        try {
            Set<String> entries = new HashSet<>();
            for (String raw : Files.readAllLines(path)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.endsWith(".java")) {
                    line = line.substring(0, line.length() - 5);
                }
                entries.add(line);
            }
            LOGGER.info(String.format("Loaded %d blacklisted seed(s) from %s", entries.size(), path));
            return Collections.unmodifiableSet(entries);
        } catch (IOException ioe) {
            LOGGER.warning(String.format("Failed to read blacklist file %s: %s", path, ioe.getMessage()));
            return Collections.emptySet();
        }
    }

    public void run() {
        ensureBaseDirectories();
        fileManager = new FileManager(config.seedsDir(), config.timestamp(), globalStats, seedBlacklist);
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
                globalStats,
                null);

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
                    Executor.MutationTestReport report;
                    try {
                        report = executor.executeMutationTest(seed, mutatedTestCase);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        LOGGER.info("Mutator test interrupted; stopping early.");
                        return;
                    }

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
        Duration signalInterval = Duration.ofSeconds(Math.max(1L, config.signalIntervalSeconds()));
        signalRecorder = new SignalRecorder(
                fileManager.getSessionDirectoryPath().resolve("signals.csv"),
                signalInterval);
        if (config.isDebug()) {
            Duration mutatorInterval = Duration.ofSeconds(Math.max(1L, config.mutatorStatsIntervalSeconds()));
            mutatorOptimizationRecorder = new MutatorOptimizationRecorder(
                    fileManager.getSessionDirectoryPath().resolve("mutator_optimization_stats.csv"),
                    mutatorInterval,
                    globalStats);
        } else {
            mutatorOptimizationRecorder = null;
            LOGGER.fine("Debug disabled; skipping mutator optimization recorder.");
        }

        MutatorScheduler scheduler = createScheduler();

        LOGGER.info(String.format("Running in mode: %s", config.mode()));

        LOGGER.info(String.format("Starting %d executor thread(s)...", config.executorThreads()));
        executorWorkers.clear();
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
                mutatorOptimizationRecorder,
                scheduler,
                this.config.mode(),
                config.corpusPolicy(),
                sessionSeed);
        evaluatorThread = new Thread(evaluator);
        evaluatorThread.start();

        for (TestCase seed : seedTestCases) {
            executionQueue.add(seed);
        }

        LOGGER.info("Starting mutator threads...");
        int executionQueueBudget = Math.max(5 * config.executorThreads(), 1);
        double executionQueueFraction = 0.25;
        int mutatorThreadCount = 2;
        for (int i = 0; i < mutatorThreadCount; i++) {
            Random workerRandom = new Random(random.nextLong());
            MutationWorker mutatorWorker = new MutationWorker(
                    fileManager,
                    nameGenerator,
                    mutationQueue,
                    executionQueue,
                    workerRandom,
                    config.printAst(),
                    executionQueueBudget,
                    executionQueueFraction,
                    EXECUTION_QUEUE_CAPACITY,
                    globalStats,
                    scheduler);
            Thread mutatorThread = new Thread(mutatorWorker, "mutator-" + i);
            mutatorThreads.add(mutatorThread);
            mutatorThread.start();
        }

        Runnable dashboardShutdown = () -> initiateShutdown("dashboard loop");
        ConsoleDashboard dash = new ConsoleDashboard(
                globalStats,
                mutationQueue,
                evaluationQueue,
                executionQueue,
                dashboardShutdown);
        Thread dashboardThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            dashboardThread.interrupt();
            initiateShutdown("shutdown hook");
        }));
        dash.run(Duration.ofMillis(1000));
    }

    private void initiateShutdown(String trigger) {
        if (!shutdownInitiated.compareAndSet(false, true)) {
            LOGGER.fine(String.format("Shutdown already initiated (triggered by %s)", trigger));
            return;
        }

        LOGGER.info(String.format("Initiating shutdown (trigger: %s)", trigger));
        requestWorkerShutdown();
        awaitWorkerShutdown();
        if (config.isDebug()) {
            LOGGER.info("Debug mode active");
            dumpMutationQueueSnapshotCsv();
            logFinalMetrics();
        } else {
            saveTopTestCasesSnapshot(50);
            logFinalMetrics();
            fileManager.cleanupSessionDirectory();
        }

        logFinalMetrics();
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
        if (mutatorOptimizationRecorder != null) {
            mutatorOptimizationRecorder.flush();
        }

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

    private void dumpMutationQueueSnapshotCsv() {
        if (mutationQueue == null) {
            return;
        }
        if (!mutationQueueDumped.compareAndSet(false, true)) {
            return;
        }

        List<TestCase> snapshot = new ArrayList<>(mutationQueue);
        if (snapshot.isEmpty()) {
            LOGGER.info("Mutation queue empty; skipping CSV dump.");
            return;
        }

        snapshot.sort(Comparator.comparingDouble(TestCase::getScore).reversed());

        Path sessionDir = (fileManager != null) ? fileManager.getSessionDirectoryPath() : null;
        Path outputPath = (sessionDir != null)
                ? sessionDir.resolve("mutation_queue_snapshot.csv")
                : Path.of("mutation_queue_snapshot.csv");

        try (BufferedWriter writer = Files.newBufferedWriter(
                outputPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write(String.join(",",
                    "rank",
                    "name",
                    "seedName",
                    "parentName",
                    "parentScore",
                    "score",
                    "priority",
                    "mutator",
                    "timesSelected",
                    "mutationDepth",
                    "mutationCount",
                    "activeChampion",
                    "interpreterRuntimeNanos",
                    "jitRuntimeNanos",
                    "hashedOptVector"));
            writer.write(System.lineSeparator());

            for (int i = 0; i < snapshot.size(); i++) {
                TestCase tc = snapshot.get(i);
                String hashedVector = "";
                int[] hashedOptVector = tc.getHashedOptVector();
                if (hashedOptVector != null) {
                    hashedVector = Arrays.toString(hashedOptVector);
                }
                String line = String.join(",",
                        csvValue(i + 1),
                        csvValue(tc.getName()),
                        csvValue(tc.getSeedName()),
                        csvValue(tc.getParentName()),
                        csvValue(tc.getParentScore()),
                        csvValue(tc.getScore()),
                        csvValue(tc.getPriority()),
                        csvValue(tc.getMutation() != null ? tc.getMutation().name() : ""),
                        csvValue(tc.getTimesSelected()),
                        csvValue(tc.getMutationDepth()),
                        csvValue(tc.getMutationCount()),
                        csvValue(tc.isActiveChampion()),
                        csvValue(tc.getInterpreterRuntimeNanos()),
                        csvValue(tc.getJitRuntimeNanos()),
                        csvValue(hashedVector));
                writer.write(line);
                writer.write(System.lineSeparator());
            }
            LOGGER.info(String.format(
                    "Dumped %d mutation-queue entries to %s",
                    snapshot.size(),
                    outputPath));
        } catch (IOException ioe) {
            LOGGER.warning(String.format(
                    "Failed to write mutation queue snapshot %s: %s",
                    outputPath,
                    ioe.getMessage()));
        }
    }

    private static String csvValue(Object value) {
        if (value == null) {
            return "";
        }
        String str = String.valueOf(value);
        boolean needsQuotes = str.contains(",") || str.contains("\"") || str.contains("\n") || str.contains("\r");
        if (needsQuotes) {
            str = "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
    }

    private void requestWorkerShutdown() {
        executorWorkers.forEach(thread -> {
            if (thread != null) {
                thread.interrupt();
            }
        });
        if (evaluatorThread != null) {
            evaluatorThread.interrupt();
        }
        mutatorThreads.forEach(thread -> {
            if (thread != null) {
                thread.interrupt();
            }
        });
    }

    private void awaitWorkerShutdown() {
        executorWorkers.forEach(thread -> joinThread(thread, "executor"));
        joinThread(evaluatorThread, "evaluator");
        mutatorThreads.forEach(thread -> joinThread(thread, "mutator"));
    }

    private void joinThread(Thread thread, String label) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
            if (thread.isAlive()) {
                LOGGER.warning(String.format("%s thread still running during shutdown: %s", label, thread.getName()));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private MutatorScheduler createScheduler() {
        List<MutatorType> mutators = Arrays.asList(MutatorType.mutationCandidates());
        MutatorScheduler scheduler;
        long schedulerSeed = sessionSeed ^ 0x9E3779B97F4A7C15L;
        LOGGER.info(String.format("Scheduler seed: %d", schedulerSeed));
        Random schedulerRandom = new Random(schedulerSeed);
        switch (config.mutatorPolicy()) {
            case BANDIT -> scheduler = new BanditMutatorScheduler(List.copyOf(mutators), schedulerRandom);
            case MOP -> scheduler = new MopMutatorScheduler(List.copyOf(mutators), schedulerRandom);
            case UNIFORM -> scheduler = new UniformRandomMutatorScheduler(List.copyOf(mutators), schedulerRandom);
            default -> scheduler = new UniformRandomMutatorScheduler(List.copyOf(mutators), schedulerRandom);
        }
        LOGGER.info(String.format("Mutator scheduler policy: %s", config.mutatorPolicy().displayName()));
        return scheduler;
    }
}
