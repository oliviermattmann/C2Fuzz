package fuzzer.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
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
    private static final Duration DASHBOARD_REFRESH_INTERVAL = Duration.ofMillis(1000);
    private static final String LOGS_DIR = "logs";
    private static final String SESSIONS_DIR = "fuzz_sessions";
    private final FuzzerConfig config;
    private final GlobalStats globalStats;
    private final Set<String> seedBlacklist;
    private final Instant sessionStart;
    private SignalRecorder signalRecorder;
    private MutatorOptimizationRecorder mutatorOptimizationRecorder;
    private final NameGenerator nameGenerator = new NameGenerator();
    private final AtomicBoolean shutdownInitiated = new AtomicBoolean(false);
    private final List<Thread> executorWorkers = new ArrayList<>();
    private final List<Thread> mutatorThreads = new ArrayList<>();
    private Thread evaluatorThread;

    private final BlockingQueue<TestCase> executionQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<TestCase> mutationQueue = new PriorityBlockingQueue<>();
    private final BlockingQueue<TestCaseResult> evaluationQueue = new LinkedBlockingQueue<>();

    private FileManager fileManager;
    private SessionReportWriter reportWriter;
    private Random random;
    private long sessionSeed;

    public SessionController(FuzzerConfig config) {
        this.config = config;
        // Ignore the sentinel OptEvent_Count feature.
        int featureSlots = Math.max(0, OptimizationVector.Features.values().length - 1);
        this.globalStats = new GlobalStats(featureSlots);
        this.seedBlacklist = loadSeedBlacklist(config);
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
                sessionStart,
                config.debugJdkPath());
        initialiseRandom();
        runFuzzingLoop();
    }

    private void ensureBaseDirectories() {
        ensureDirectory(LOGS_DIR);
        ensureDirectory(SESSIONS_DIR);
    }

    private void ensureDirectory(String dirName) {
        try {
            Files.createDirectories(Path.of(dirName));
        } catch (IOException e) {
            LOGGER.warning(String.format("Unable to create %s directory: %s", dirName, e.getMessage()));
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
        initialiseRecorders();
        reportWriter = new SessionReportWriter(globalStats, fileManager, sessionStart, mutatorOptimizationRecorder);
        MutatorScheduler scheduler = createScheduler();
        logRunConfiguration();
        startExecutors();
        Evaluator evaluator = startEvaluator(scheduler);
        CorpusManager corpusManager = evaluator.getCorpusManager();
        enqueueSeedTests(seedTestCases);
        startMutators(scheduler, corpusManager);
        runDashboardLoop();
    }

    private void initialiseRecorders() {
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
            return;
        }
        mutatorOptimizationRecorder = null;
        LOGGER.fine("Debug disabled; skipping mutator optimization recorder.");
    }

    private void logRunConfiguration() {
        LOGGER.info(String.format("Running in mode: %s", config.mode()));
        LOGGER.info(String.format("Corpus policy: %s", config.corpusPolicy().displayName()));
        LOGGER.info(String.format("Mutator policy: %s", config.mutatorPolicy().displayName()));
        LOGGER.info(String.format("Scoring mode: %s", config.scoringMode().displayName()));
    }

    private void startExecutors() {
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
            Thread executorThread = new Thread(executor, "executor-" + i);
            executorWorkers.add(executorThread);
            executorThread.start();
        }
    }

    private Evaluator startEvaluator(MutatorScheduler scheduler) {
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
        evaluatorThread = new Thread(evaluator, "evaluator");
        evaluatorThread.start();
        return evaluator;
    }

    private void enqueueSeedTests(ArrayList<TestCase> seedTestCases) {
        for (TestCase seed : seedTestCases) {
            executionQueue.add(seed);
        }
    }

    private void startMutators(MutatorScheduler scheduler, CorpusManager corpusManager) {
        LOGGER.info(String.format("Starting %d mutator thread(s)...", config.mutatorThreads()));
        mutatorThreads.clear();
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
            mutatorThread.setUncaughtExceptionHandler((thread, error) -> LOGGER.log(
                    Level.SEVERE,
                    String.format("Uncaught exception in %s", thread.getName()),
                    error));
            mutatorThreads.add(mutatorThread);
            mutatorThread.start();
        }
    }

    private void runDashboardLoop() {
        Runnable dashboardShutdown = () -> initiateShutdown("dashboard loop");
        ConsoleDashboard dashboard = new ConsoleDashboard(
                globalStats,
                mutationQueue,
                evaluationQueue,
                executionQueue,
                dashboardShutdown);
        Thread dashboardThread = Thread.currentThread();
        Thread shutdownHook = createShutdownHook(dashboardThread);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            dashboard.run(DASHBOARD_REFRESH_INTERVAL);
        } finally {
            removeShutdownHookIfPossible(shutdownHook);
            initiateShutdown("dashboard exit");
        }
    }

    private Thread createShutdownHook(Thread dashboardThread) {
        return new Thread(() -> {
            dashboardThread.interrupt();
            initiateShutdown("shutdown hook");
        }, "session-shutdown-hook");
    }

    private void removeShutdownHookIfPossible(Thread shutdownHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown already in progress.
        }
    }

    private void initiateShutdown(String trigger) {
        if (!shutdownInitiated.compareAndSet(false, true)) {
            LOGGER.fine(String.format("Shutdown already initiated (triggered by %s)", trigger));
            return;
        }

        LoggingConfig.safeInfo(LOGGER, String.format("Initiating shutdown (trigger: %s)", trigger));
        // Snapshot key artefacts immediately so we keep them even if shutdown is interrupted.
        if (reportWriter != null) {
            reportWriter.writeFinalMetrics();
            reportWriter.dumpMutationQueueSnapshotCsv(mutationQueue);
        }
        requestWorkerShutdown();
        awaitWorkerShutdown();
        if (!config.isDebug() && fileManager != null) {
            fileManager.cleanupSessionDirectory();
        }
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
