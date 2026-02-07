package fuzzer.runtime;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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
import fuzzer.runtime.corpus.CorpusManager;
import fuzzer.runtime.monitoring.ConsoleDashboard;
import fuzzer.runtime.monitoring.GlobalStats;
import fuzzer.runtime.monitoring.MutatorOptimizationRecorder;
import fuzzer.runtime.monitoring.SignalRecorder;
import fuzzer.io.FileManager;
import fuzzer.logging.LoggingConfig;
import fuzzer.io.NameGenerator;
import fuzzer.model.OptimizationVector;
import fuzzer.model.TestCase;
import fuzzer.model.TestCaseResult;

public final class SessionController {

    private static final Logger LOGGER = LoggingConfig.getLogger(SessionController.class);
    private static final int EXECUTION_QUEUE_CAPACITY = 500;
    private final FuzzerConfig config;
    private final GlobalStats globalStats;
    private final Set<String> seedBlacklist;
    private final Instant sessionStart;
    private SignalRecorder signalRecorder;
    private MutatorOptimizationRecorder mutatorOptimizationRecorder;
    private final NameGenerator nameGenerator = new NameGenerator();
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
        // Ignore the sentinel OptEvent_Count feature.
        int featureSlots = Math.max(0, OptimizationVector.Features.values().length - 1);
        this.globalStats = new GlobalStats(featureSlots);
        this.seedBlacklist = loadSeedBlacklist(config);
        initialiseGlobalStats();
        this.sessionStart = Instant.now();
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
        fileManager = new FileManager(
                config.seedsDir(),
                config.timestamp(),
                globalStats,
                seedBlacklist,
                sessionStart);
        initialiseRandom();
        switch (config.mode()) {
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
        LOGGER.info(String.format("Corpus policy: %s", config.corpusPolicy().displayName()));
        LOGGER.info(String.format("Mutator policy: %s", config.mutatorPolicy().displayName()));
        LOGGER.info(String.format("Scoring mode: %s", config.scoringMode().displayName()));

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
        CorpusManager corpusManager = evaluator.getCorpusManager();

        for (TestCase seed : seedTestCases) {
            executionQueue.add(seed);
        }

        LOGGER.info(String.format("Starting %d mutator thread(s)...", config.mutatorThreads()));
        int executionQueueBudget = Math.max(5 * config.executorThreads(), 1);
        double executionQueueFraction = 0.25;
        int mutatorBatchSize = Math.max(1, config.mutatorBatchSize());
        int mutatorThreadCount = config.mutatorThreads();
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
                    mutatorBatchSize,
                    globalStats,
                    scheduler,
                    config.mutatorTimeoutMs(),
                    config.mutatorSlowLimit(),
                    corpusManager);
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

        LoggingConfig.safeInfo(LOGGER, String.format("Initiating shutdown (trigger: %s)", trigger));
        // Snapshot key artefacts immediately so we keep them even if shutdown is interrupted.
        logFinalMetrics();
        dumpMutationQueueSnapshotCsv();
        requestWorkerShutdown();
        awaitWorkerShutdown();
        if (!config.isDebug()) {
            fileManager.cleanupSessionDirectory();
        }
        logFinalMetrics();
    }

    private void logFinalMetrics() {
        if (!finalMetricsLogged.compareAndSet(false, true)) {
            return;
        }
        long[] pairCountsSnapshot = globalStats.snapshotPairCounts();
        List<String> missingPairs = computeMissingPairs(pairCountsSnapshot);
        long totalPairsSnapshot = pairCountsSnapshot.length;
        long uniquePairsSnapshot = totalPairsSnapshot - missingPairs.size();
        GlobalStats.FinalMetrics baseMetrics = globalStats.snapshotFinalMetrics();
        GlobalStats.FinalMetrics metrics = new GlobalStats.FinalMetrics(
                baseMetrics.totalDispatched,
                baseMetrics.totalTests,
                baseMetrics.scoredTests,
                baseMetrics.failedCompilations,
                baseMetrics.foundBugs,
                baseMetrics.uniqueFeatures,
                baseMetrics.totalFeatures,
                uniquePairsSnapshot,
                totalPairsSnapshot,
                baseMetrics.avgScore,
                baseMetrics.maxScore,
                baseMetrics.corpusSize,
                baseMetrics.corpusAccepted,
                baseMetrics.corpusReplaced,
                baseMetrics.corpusRejected,
                baseMetrics.corpusDiscarded);
        Duration elapsed = Duration.between(sessionStart, Instant.now());
        String elapsedFormatted = formatElapsedDuration(elapsed);
        double elapsedSeconds = Math.max(0.0, elapsed.toMillis() / 1000.0);
        double featurePct = metrics.featureCoverageRatio() * 100.0;
        double pairPct = (totalPairsSnapshot == 0) ? 0.0 : (uniquePairsSnapshot * 100.0) / totalPairsSnapshot;
        long testsEvaluated = globalStats.getTotalTestsEvaluated();
        double dispatchThroughput = (elapsedSeconds > 0.0) ? metrics.totalDispatched / elapsedSeconds : 0.0;
        double evaluatedThroughput = (elapsedSeconds > 0.0) ? testsEvaluated / elapsedSeconds : 0.0;
        long intTimeouts = globalStats.getIntTimeouts();
        long jitTimeouts = globalStats.getJitTimeouts();
        double avgIntRuntimeMs = globalStats.getAvgIntExecTimeMillis();
        double avgJitRuntimeMs = globalStats.getAvgJitExecTimeMillis();
        double avgExecRuntimeMs = globalStats.getAvgExecTimeMillis();
        double avgCompilationMs = globalStats.getAvgCompilationTimeMillis();
        String summary = String.format(
                """
                Final run metrics:
                  tests dispatched: %,d
                  tests evaluated: %,d
                  scored tests: %,d
                  found bugs: %,d
                  failed compilations: %,d
                  interpreter timeouts: %,d
                  JIT timeouts: %,d
                  unique features seen: %d / %d (%.1f%%)
                  unique optimization pairs observed: %d / %d (%.1f%%)
                  average score: %.4f
                  maximum score: %.4f
                  average interpreter runtime: %.3f ms
                  average JIT runtime: %.3f ms
                  average exec runtime: %.3f ms
                  average compilation time: %.3f ms
                  throughput (dispatch/s): %.2f
                  throughput (evaluated/s): %.2f
                  total runtime: %s (%.3f s)
                """,
                metrics.totalDispatched,
                testsEvaluated,
                metrics.scoredTests,
                metrics.foundBugs,
                metrics.failedCompilations,
                intTimeouts,
                jitTimeouts,
                metrics.uniqueFeatures,
                metrics.totalFeatures,
                featurePct,
                uniquePairsSnapshot,
                totalPairsSnapshot,
                pairPct,
                metrics.avgScore,
                metrics.maxScore,
                avgIntRuntimeMs,
                avgJitRuntimeMs,
                avgExecRuntimeMs,
                avgCompilationMs,
                dispatchThroughput,
                evaluatedThroughput,
                elapsedFormatted,
                elapsedSeconds).stripTrailing();
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

        StringBuilder mutatorSummary = new StringBuilder("Mutators:\n");
        GlobalStats.MutatorStats[] mutatorStats = globalStats.snapshotMutatorStats();
        if (mutatorStats != null) {
            for (GlobalStats.MutatorStats ms : mutatorStats) {
                if (ms == null || ms.mutatorType == null || ms.mutatorType == MutatorType.SEED) {
                    continue;
                }
                long attempts = ms.mutationAttemptTotal();
                mutatorSummary.append(String.format(
                        "  %s: attempts %,d | success %,d | skip %,d | fail %,d | compFail %,d | bugs %,d%n",
                        ms.mutatorType.name(),
                        attempts,
                        ms.mutationSuccessCount,
                        ms.mutationSkipCount,
                        ms.mutationFailureCount,
                        ms.compileFailureCount,
                        ms.evaluationBugCount));
            }
        }

        Path sessionDir = (fileManager != null) ? fileManager.getSessionDirectoryPath() : null;
        if (sessionDir != null) {
            Path summaryFile = sessionDir.resolve("final_metrics.txt");
            String fileContent = summary + System.lineSeparator()
                    + championSummary + System.lineSeparator()
                    + mutatorSummary;
            try {
                Files.writeString(summaryFile, fileContent, StandardCharsets.UTF_8);
            } catch (IOException ioe) {
                LOGGER.warning(String.format(
                        "Failed to write final metrics file %s: %s",
                        summaryFile,
                        ioe.getMessage()));
            }
            writeMissingPairs(sessionDir, missingPairs);
        } else {
            LOGGER.warning("Session directory unavailable; skipping final metrics file.");
        }
    }

    private static String formatElapsedDuration(Duration elapsed) {
        if (elapsed == null) {
            return "0:00:00";
        }
        long totalSeconds = Math.max(0L, elapsed.getSeconds());
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%d:%02d:%02d", hours, minutes, seconds);
    }

    /**
     * Build a list of optimization pairs that were never observed.
     */
    private List<String> computeMissingPairs(long[] pairCountsSnapshot) {
        int features = globalStats.getFeatureSlots();
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < features; i++) {
            for (int j = i + 1; j < features; j++) {
                int idx = globalStats.pairIndex(i, j);
                long count = (idx >= 0 && idx < pairCountsSnapshot.length) ? pairCountsSnapshot[idx] : 0L;
                if (count == 0L) {
                    String left = OptimizationVector.FeatureName(OptimizationVector.FeatureFromIndex(i));
                    String right = OptimizationVector.FeatureName(OptimizationVector.FeatureFromIndex(j));
                    missing.add(String.format("%02d,%02d\t%s | %s", i, j, left, right));
                }
            }
        }
        return missing;
    }

    /**
     * Write the list of missing optimization pairs.
     */
    private void writeMissingPairs(Path sessionDir, List<String> missingPairs) {
        Path target = sessionDir.resolve("missing_pairs.txt");
        try {
            if (missingPairs.isEmpty()) {
                Files.writeString(target, "All pairs observed.\n", StandardCharsets.UTF_8);
            } else {
                Files.write(target, missingPairs, StandardCharsets.UTF_8);
            }
        } catch (IOException ioe) {
            LOGGER.warning(String.format("Failed to write missing pairs file %s: %s", target, ioe.getMessage()));
        }
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
