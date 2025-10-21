package fuzzer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.mutators.MutatorType;
import fuzzer.util.ClassExtractor;
import fuzzer.util.FileManager;
import fuzzer.util.LoggingConfig;
import fuzzer.util.NameGenerator;
import fuzzer.util.OptimizationVector;
import fuzzer.util.TestCase;
import fuzzer.util.TestCaseResult;

public class Fuzzer {

    Mode mode;
    // two modes: test a specific mutator, or run the fuzzer
    MutatorType mutatorType; // only used in test mutator mode

    private static final Logger LOGGER = LoggingConfig.getLogger(Fuzzer.class);
    private static final String DEFAULT_DEBUG_JDK_PATH = "/home/oli/Documents/education/eth/msc-thesis/code/jdk/build/linux-x86_64-server-fastdebug/jdk/bin";
    private static final String DEFAULT_RELEASE_JDK_PATH = "/home/oli/Documents/education/eth/msc-thesis/code/jdk/build/linux-x86_64-server-release/jdk/bin";
    private static final String ENV_DEBUG_JDK_PATH = "C2FUZZ_DEBUG_JDK";
    private static final String ENV_RELEASE_JDK_PATH = "C2FUZZ_RELEASE_JDK";
    private static String timestamp;
    private static final GlobalStats globalStats = new GlobalStats(OptimizationVector.Features.values().length);
    Random random;
    BlockingQueue<TestCase> executionQueue;
    BlockingQueue<TestCase> mutationQueue;
    BlockingQueue<TestCaseResult> evaluationQueue;
    private final AtomicBoolean topCasesArchived = new AtomicBoolean(false);
    private final AtomicBoolean finalMetricsLogged = new AtomicBoolean(false);
    private ScoringMode scoringMode = ScoringMode.PF_IDF;
    private boolean scoringModeExplicit = false;
    

    boolean printAst = false;
    String seedsDir = null;
    String seedpoolDir = null;
    String debugJdkPath = null;
    String releaseJdkPath = null;
    int executorThreads = 4;

    FileManager fileManager;
    NameGenerator nameGenerator;
    Long rngSeed = null;

    

    public void run(String[] args) {
        nameGenerator = new NameGenerator();
        parseArgs(args);
        resolveJdkPaths();
        fileManager = new FileManager(seedsDir, timestamp);

        // initialize global stats
        for (var f : OptimizationVector.Features.values()) {
            String featureName = OptimizationVector.FeatureName(f);
            globalStats.opMax.put(featureName, 0.0);
            globalStats.opFreq.put(featureName, new java.util.concurrent.atomic.LongAdder());
        }

        // create queues
        executionQueue = new LinkedBlockingQueue<>();
        mutationQueue = new PriorityBlockingQueue<>();
        evaluationQueue = new LinkedBlockingQueue<>();


        if (rngSeed != null) {
            LOGGER.info(String.format("Using random seed: %d", rngSeed));
            this.random = new Random(rngSeed);
            
        } else {
            
            this.random = new Random();
            rngSeed = this.random.nextLong();
            LOGGER.info(String.format("Using random seed: %d", rngSeed));
        }

        if (printAst) {
            LOGGER.info("AST printing enabled.");
        }

        // run in the selected mode
        switch (mode) {
            case TEST_MUTATOR -> {
                LOGGER.info("Running in TEST_MUTATOR mode.");
                testMutator();
            }
            case FUZZ -> {
                LOGGER.info("Running in FUZZ mode.");
                runFuzzingLoop();
            }
            case TEST_FEATURE -> {
                LOGGER.info("Running in TEST_FEATURE mode.");
                testFeature();
            }
            default -> throw new IllegalStateException("Unexpected mode: " + mode);
        }

    }

    private void testMutator() {
        ArrayList<TestCase> seedTestCases = fileManager.setupSeedPool(String.format("test_%s_", mutatorType.name()));

        // to test the mutator we first compile and benchmark all seeds
        // then we apply the mutator to each seed once and benchmark the result
        // we can then see whether our mutation is compilable and if it evokes new behaviour compared to the seed

        Executor executor = new Executor(fileManager, debugJdkPath, releaseJdkPath, executionQueue, evaluationQueue, globalStats);
        MutationWorker mutatorWorker = new MutationWorker(fileManager, nameGenerator, mutationQueue, executionQueue, random, printAst, seedpoolDir, 100);

        int mutatedCount = 0;
        int mutantCompileFailures = 0;
        int executionDiffs = 0;
        int outputDiffs = 0;
        int timeouts = 0;
        int executionErrors = 0;

        for (TestCase seed : seedTestCases) {
            TestCase mutatedTestCase = mutatorWorker.mutateTestCaseWith(this.mutatorType, seed);
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
                mutatorType.name(),
                seedTestCases.size(),
                mutatedCount,
                mutantCompileFailures,
                executionErrors,
                timeouts,
                executionDiffs,
                outputDiffs));
    }

    private void testFeature() {
        ClassExtractor extractor = new ClassExtractor(false, 17);
        File dir = new File(seedsDir);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".java"));
        if (files == null) {
            LOGGER.severe(String.format("Invalid seeds directory: %s", seedsDir));
            return;
        }
        for (File file : files) {
            Path filePath = file.toPath();
            try {
                List<String> classNames = extractor.extractTypeNames(filePath, true, true, true);
                String compileOnly = extractor.getCompileOnlyString(classNames);
                System.out.println(compileOnly);
            } catch (Exception e) {
                LOGGER.severe(String.format("Error extracting classes from file %s: %s", file.getName(), e.getMessage()));
            }
        }


    }

    private void runFuzzingLoop() {
        ArrayList<TestCase> seedTestCases = fileManager.setupSeedPool("session_");

        LOGGER.info(String.format("Starting %d executor thread(s)...", executorThreads));
        ArrayList<Thread> executorWorkers = new ArrayList<>();
        for (int i = 0; i < this.executorThreads; i++) {
            Executor executor = new Executor(fileManager, debugJdkPath, releaseJdkPath, executionQueue, evaluationQueue, globalStats);
            Thread executorThread = new Thread(executor);
            executorWorkers.add(executorThread);
            executorThread.start();
        }

        LOGGER.info("Starting evaluator thread...");
        Evaluator evaluator = new Evaluator(fileManager, evaluationQueue, mutationQueue, globalStats, scoringMode);
        Thread evaluatorThread = new Thread(evaluator);
        evaluatorThread.start();

        // add all seed test cases to the queue
        executionQueue.addAll(seedTestCases);

        // create new mutator worker
        LOGGER.info("Starting mutator thread...");
        MutationWorker mutatorWorker = new MutationWorker(fileManager, nameGenerator, mutationQueue, executionQueue, random, printAst, seedpoolDir, 1000);
        Thread mutatorThread = new Thread(mutatorWorker);
        mutatorThread.start();

        // while (true) { 
        //     try {
        //         Thread.sleep(10000);
        //     } catch (InterruptedException e) {
        //         LOGGER.info("Main thread interrupted, shutting down...");
        //         break;
        //     }
        //     LOGGER.info(String.format("Main thread heartbeat: executed %,d tests, found %d bugs, mutation queue size: %d, execution queue size: %d, evaluation queue size: %d",
        //         globalStats.totalTestsExecuted.sum(),
        //         globalStats.foundBugs.sum(),
        //         mutationQueue.size(),
        //         executionQueue.size(),
        //         evaluationQueue.size()));
            
        // }
        Runnable dashboardShutdown = this::logFinalMetrics;
        Runnable snapshotOnExit = () -> saveTopTestCasesSnapshot(30);
        ConsoleDashboard dash = new ConsoleDashboard(globalStats, mutationQueue, evaluationQueue, executionQueue, dashboardShutdown);
        Thread dashboardThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            dashboardThread.interrupt();
            snapshotOnExit.run();
        })); // archive top tests on shutdown
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
        Path baseDir = seedpoolDir != null
            ? Path.of(seedpoolDir)
            : Path.of("fuzz_sessions", "best_cases_" + timestamp);
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

        LOGGER.info(String.format("Archived %d top test cases to %s", count, targetDir));
    }
   
    private void parseArgs(String[] args) {
        List<String> argList = Arrays.asList(args);
        if (argList.contains("--print-ast")) {
            LOGGER.info("AST printing enabled via command line argument.");
            printAst = true;
        }
        int idx = argList.indexOf("--debug-jdk");
        if (idx != -1 && idx + 1 < argList.size()) {
            debugJdkPath = argList.get(idx + 1);
        }
        idx = argList.indexOf("--release-jdk");
        if (idx != -1 && idx + 1 < argList.size()) {
            releaseJdkPath = argList.get(idx + 1);
        }
        idx = argList.indexOf("--scoring");
        if (idx != -1) {
            if (idx + 1 >= argList.size()) {
                LOGGER.warning("Flag --scoring provided without a mode; retaining default scoring mode.");
            } else {
                String requestedMode = argList.get(idx + 1);
                ScoringMode parsed = ScoringMode.parseOrNull(requestedMode);
                if (parsed != null) {
                    scoringMode = parsed;
                    scoringModeExplicit = true;
                    LOGGER.info(() -> String.format(Locale.ROOT,
                            "Scoring mode set via CLI: %s",
                            scoringMode.displayName()));
                } else {
                    LOGGER.warning(() -> String.format(Locale.ROOT,
                            "Unknown scoring mode '%s' specified via --scoring; retaining default %s",
                            requestedMode,
                            scoringMode.displayName()));
                }
            }
        }

        if (!scoringModeExplicit) {
            String raw = System.getProperty("c2fuzz.scoring");
            if (raw == null || raw.isBlank()) {
                raw = System.getenv("C2FUZZ_SCORING");
            }
            ScoringMode parsed = ScoringMode.parseOrNull(raw);
            if (parsed != null) {
                scoringMode = parsed;
                LOGGER.info(String.format(Locale.ROOT,
                        "Scoring mode resolved from property/environment: %s",
                        scoringMode.displayName()));
            } else if (raw != null && !raw.isBlank()) {
                LOGGER.warning(String.format(Locale.ROOT,
                        "Unknown scoring mode '%s' from property/environment; using default %s",
                        raw,
                        scoringMode.displayName()));
            }
        }

        LOGGER.info(String.format(Locale.ROOT,
                "Using scoring mode: %s",
                scoringMode.displayName()));
        idx = argList.indexOf("--jdk");
        if (idx != -1 && idx + 1 < argList.size()) {
            String unifiedJdk = argList.get(idx + 1);
            debugJdkPath = unifiedJdk;
            releaseJdkPath = unifiedJdk;
        }
        idx = argList.indexOf("--executors");
        if (idx != -1 && idx + 1 < argList.size()) {
            String threadArg = argList.get(idx + 1);
            try {
                int parsedThreads = Integer.parseInt(threadArg);
                if (parsedThreads <= 0) {
                    LOGGER.warning(String.format("Ignoring non-positive executor count %d. Keeping %d threads.", parsedThreads, executorThreads));
                } else {
                    executorThreads = parsedThreads;
                }
            } catch (NumberFormatException e) {
                LOGGER.warning(String.format("Invalid executor count '%s'. Keeping %d threads.", threadArg, executorThreads));
            }
        }
        idx = argList.indexOf("--test-mutator");
        int idx_feature = argList.indexOf("--test-feature");
        if (idx != -1 && idx + 1 < argList.size()) {
            // see which mutator to test
            String mutatorName = argList.get(idx + 1);
    
            switch (mutatorName) {
                case "INLINE_EVOKE" -> mutatorType = MutatorType.INLINE_EVOKE;
                case "LOOP_UNROLLING_EVOKE" -> mutatorType = MutatorType.LOOP_UNROLLING_EVOKE;
                case "REFLECTION_CALL" -> mutatorType = MutatorType.REFLECTION_CALL;
                case "REDUNDANT_STORE_ELIMINATION_EVOKE" -> mutatorType = MutatorType.REDUNDANT_STORE_ELIMINATION_EVOKE;
                case "AUTOBOX_ELIMINATION_EVOKE" -> mutatorType = MutatorType.AUTOBOX_ELIMINATION_EVOKE;
                case "ESCAPE_ANALYSIS_EVOKE" -> mutatorType = MutatorType.ESCAPE_ANALYSIS_EVOKE;
                case "LOOP_PEELING_EVOKE" -> mutatorType = MutatorType.LOOP_PEELING_EVOKE;
                case "LOOP_UNSWITCHING_EVOKE" -> mutatorType = MutatorType.LOOP_UNSWITCHING_EVOKE;
                case "DEOPTIMIZATION_EVOKE" -> mutatorType = MutatorType.DEOPTIMIZATION_EVOKE;
                case "ALGEBRAIC_SIMPLIFICATION_EVOKE" -> mutatorType = MutatorType.ALGEBRAIC_SIMPLIFICATION_EVOKE;
                case "DEAD_CODE_ELIMINATION_EVOKE" -> mutatorType = MutatorType.DEAD_CODE_ELIMINATION_EVOKE;
                case "LOCK_ELIMINATION_EVOKE" -> mutatorType = MutatorType.LOCK_ELIMINATION_EVOKE;
                case "LOCK_COARSENING_EVOKE" -> mutatorType = MutatorType.LOCK_COARSENING_EVOKE;
                default -> {
                    LOGGER.warning(String.format("Unknown mutator type specified: %s. Defaulting to INLINE_EVOKE.", mutatorName));
                    mutatorType = MutatorType.INLINE_EVOKE;
                }
            }

            mode = Mode.TEST_MUTATOR;

        } else if (idx_feature != -1) {

            LOGGER.info("TEST_FEATURE");
            mode = Mode.TEST_FEATURE;
        }
        
        
        else {
            mode = Mode.FUZZ;
        }

        // get the seed directory (required)
        idx = argList.indexOf("--seeds");
        if (idx != -1 && idx + 1 < argList.size()) {
            seedsDir = argList.get(idx + 1);
            LOGGER.info(String.format("Using seeds directory: %s", seedsDir));
        } else {
            LOGGER.warning("No seeds directory specified. Use --seeds <directory> to provide one.");
            throw new IllegalArgumentException("Seeds directory is required.");
        }
        idx = argList.indexOf("--rng");
        if (idx != -1 && idx + 1 < argList.size()) {
            try {
                rngSeed = Long.valueOf(argList.get(idx + 1));
                LOGGER.info(String.format("Using RNG seed: %d", rngSeed));
            } catch (NumberFormatException e) {
                LOGGER.warning(String.format("Invalid RNG seed provided: %d" + argList.get(idx + 1)));
            }
        }
    }

    private void resolveJdkPaths() {
        if (debugJdkPath == null) {
            debugJdkPath = System.getenv(ENV_DEBUG_JDK_PATH);
        }
        if (releaseJdkPath == null) {
            releaseJdkPath = System.getenv(ENV_RELEASE_JDK_PATH);
        }

        if (debugJdkPath == null && releaseJdkPath != null) {
            debugJdkPath = releaseJdkPath;
        }
        if (releaseJdkPath == null && debugJdkPath != null) {
            releaseJdkPath = debugJdkPath;
        }

        if (debugJdkPath == null) {
            debugJdkPath = DEFAULT_DEBUG_JDK_PATH;
            LOGGER.info(String.format("Using default debug JDK path: %s", debugJdkPath));
        } else {
            LOGGER.info(String.format("Using debug JDK path: %s", debugJdkPath));
        }

        if (releaseJdkPath == null) {
            releaseJdkPath = DEFAULT_RELEASE_JDK_PATH;
            LOGGER.info(String.format("Using default release JDK path: %s", releaseJdkPath));
        } else {
            LOGGER.info(String.format("Using release JDK path: %s", releaseJdkPath));
        }

        validateJdkBinary(debugJdkPath, "java");
        validateJdkBinary(releaseJdkPath, "javac");
    }

    private void validateJdkBinary(String basePath, String binaryName) {
        if (basePath == null) {
            LOGGER.warning(String.format("No base path provided for binary '%s'.", binaryName));
            return;
        }
        Path binaryPath = Path.of(basePath, binaryName);
        if (!Files.isExecutable(binaryPath)) {
            LOGGER.warning(String.format("JDK binary not found or is not executable: %s", binaryPath));
        }
    }

    public static void main(String[] args) {
        timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()); 
        try {
            LoggingConfig.setup(timestamp, Level.INFO);
        } catch (IOException e) {
            System.err.println("Failed to set up logging configuration.");
            return;
        }
        new Fuzzer().run(args);
    }

    private enum Mode {
        TEST_FEATURE,
        TEST_MUTATOR,
        FUZZ
    }


    // -------------------- dashboard --------------------
    static final class ConsoleDashboard {
        private final GlobalStats gs;
        private boolean firstDraw = true;
        private int lastLines = 0;
        private final Instant start = Instant.now();

        private final BlockingQueue<TestCase> mutationQueue;
        private final BlockingQueue<TestCaseResult> evaluationQueue;
        private final BlockingQueue<TestCase> executionQueue;
        private final Runnable onShutdown;
        private boolean shutdownInvoked = false;

        private static final int TOP_ROWS = 6;           // fixed rows to show for ops
        private static final boolean SHOW_OTHERS = true;

        ConsoleDashboard(GlobalStats gs, BlockingQueue<TestCase> mutationQueue, BlockingQueue<TestCaseResult> evaluationQueue, BlockingQueue<TestCase> executionQueue, Runnable onShutdown) { 
            this.gs = gs; 
            this.mutationQueue = mutationQueue;
            this.evaluationQueue = evaluationQueue;
            this.executionQueue = executionQueue;
            this.onShutdown = onShutdown;
        }

        void run(Duration interval) {
            // If not a TTY, fall back to plain prints.
            boolean fancy = System.console() != null && supportsAnsi();
            if (fancy) hideCursor();
            try {
                while (true) {
                    List<String> lines = renderLines();
                    if (fancy) redrawInPlace(lines);
                    else lines.forEach(System.out::println);
                    Thread.sleep(interval.toMillis());
                    if (!fancy) System.out.println(); // visual separation in non-ANSI environments
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                invokeShutdownOnce();
            } finally {
                invokeShutdownOnce();
                if (fancy) {
                    restoreCursor();
                    // move to next line after dashboard block
                    System.out.print("\u001B[" + Math.max(0, lastLines - 1) + "E\r");
                }
                System.out.flush();
            }
        }

        private void invokeShutdownOnce() {
            if (!shutdownInvoked) {
                shutdownInvoked = true;
                if (onShutdown != null) {
                    onShutdown.run();
                }
            }
        }

        private List<String> renderLines() {
            List<String> out = new ArrayList<>();
            long total = gs.totalTestsExecuted.sum();
            long failedComps = gs.failedCompilations.sum();

            double avgThroughput = total / Math.max(1.0, Duration.between(start, Instant.now()).toMinutes());

            long foundBugs = gs.foundBugs.sum();
            int mutQueueSize = mutationQueue.size();
            int execQueueSize = executionQueue.size();
            int evalQueueSize = evaluationQueue.size();
            double avgIntExecMillis = gs.getAvgIntExecTimeMillis();
            double avgJitExecMillis = gs.getAvgJitExecTimeMillis();
            double avgCombinedExecMillis = gs.getAvgExecTimeMillis();
            double avgCompilationMillis = gs.getAvgCompilationTimeMillis();

            // Snapshot op frequencies and tops
            Map<String, Long> freq = new HashMap<>();
            gs.opFreq.forEach((k, v) -> freq.put(k, v.sum()));

            List<Map.Entry<String, Long>> top = new ArrayList<>(freq.entrySet());
            top.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

            Duration up = Duration.between(start, Instant.now());

            out.add(String.format("FUZZER DASHBOARD  |  up %s", human(up)));
            out.add("────────────────────────────────────────────────────────────");
            out.add(String.format("Total tests: %,d", total));
            out.add(String.format("Total failed compilations: %,d", failedComps));
            out.add(String.format("Total Interpreter Timeouts: %,d", gs.intTimeouts.longValue()));
            out.add(String.format("Total Jit Timeouts: %,d", gs.jitTimeouts.longValue()));
            out.add(String.format("Avg throughput: %.1f tests/min", avgThroughput));
            out.add(String.format("Avg score: %.4f   |   Max score: %.4f", 
                gs.getAvgScore(), gs.getMaxScore()));
            out.add(String.format("Avg exec time (int): %.1f ms   |   Avg exec time (jit): %.1f ms   |   Combined avg: %.1f ms",
                avgIntExecMillis, avgJitExecMillis, avgCombinedExecMillis));
            out.add(String.format("Avg compilation time: %.1f ms", avgCompilationMillis));
            long championAccepted = gs.getChampionAccepted();
            long championReplaced = gs.getChampionReplaced();
            long championRejected = gs.getChampionRejected();
            long championDiscarded = gs.getChampionDiscarded();
            long championTotal = championAccepted + championReplaced + championRejected + championDiscarded;
            out.add(String.format(Locale.ROOT,
                    "Champion decisions: total %,d | accepted %,d | replaced %,d | rejected %,d | discarded %,d",
                    championTotal,
                    championAccepted,
                    championReplaced,
                    championRejected,
                    championDiscarded));
            out.add(String.format("Found bugs: %d", foundBugs));
            out.add(String.format("Execution queue size: %d   |   Mutation queue size: %d   |   Evaluation queue size: %d", 
                execQueueSize, mutQueueSize, evalQueueSize));


            // out.add(String.format("Archive: %d / %d", archiveSize, archiveCap));
            // out.add(String.format("Max optScore: %.4f   |   Max novelty: %.4f", optMax, novMax));
            out.add("");
            out.add("Top op frequencies:");
            if (top.isEmpty()) {
                out.add("  (no data yet)");
            } else {
                for (var e : top) {
                    double max = Optional.ofNullable(gs.opMax.get(e.getKey())).orElse(0.0);
                    out.add(String.format("  %-12s %,10d   max: %.3f", e.getKey(), e.getValue(), max));
                }
            }
            out.add("");
            out.add("Press Ctrl+C to quit");
            return out;
        }

        private void redrawInPlace(List<String> lines) {
            if (firstDraw) {
                // Clear screen and move to home, then draw fresh.
                System.out.print("\u001B[2J\u001B[H"); // clear + home
                lines.forEach(l -> System.out.println(l));
                firstDraw = false;
                lastLines = lines.size();
            } else {
                // Move cursor up to the start of the previous block,
                // then rewrite each line and clear to end of line.
                System.out.print("\u001B[" + lastLines + "F"); // move cursor up N lines, to column 1
                for (String l : lines) {
                    System.out.print("\u001B[2K"); // clear entire line
                    System.out.println(l);
                }
                // If new render has fewer lines than before, clear the extras.
                int extra = lastLines - lines.size();
                for (int i = 0; i < extra; i++) {
                    System.out.print("\u001B[2K"); // clear line
                    System.out.println();
                }
                lastLines = lines.size();
            }
            System.out.flush();
        }

        private static String human(Duration d) {
            long s = d.getSeconds();
            long h = s / 3600;
            long m = (s % 3600) / 60;
            long sec = s % 60;
            if (h > 0) return String.format("%dh %02dm %02ds", h, m, sec);
            if (m > 0) return String.format("%dm %02ds", m, sec);
            return String.format("%ds", sec);
        }

        private static boolean supportsAnsi() {
            // Most modern terminals (macOS/Linux) and Windows 10+ (with Virtual Terminal) support ANSI.
            // We optimistically enable; if you want, gate with an env flag or OS check.
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                // Java doesn't expose conhost VT mode; many environments work (Windows Terminal, IntelliJ, Git Bash).
                // Return true by default; if your users see escape codes, set this to false or detect.
                return true;
            }
            return true;
        }

        private static void hideCursor()  { System.out.print("\u001B[?25l"); }
        void restoreCursor()              { System.out.print("\u001B[?25h"); }
    }
}
