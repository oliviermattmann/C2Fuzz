package fuzzer.runtime;

import java.util.Objects;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.mutators.MutatorType;
import fuzzer.runtime.FuzzerConfig.CorpusPolicy;
import fuzzer.runtime.corpus.ChampionCorpusManager;
import fuzzer.runtime.corpus.CorpusDecision;
import fuzzer.runtime.corpus.CorpusManager;
import fuzzer.runtime.corpus.RandomCorpusManager;
import fuzzer.runtime.scheduling.MutatorScheduler;
import fuzzer.runtime.scheduling.MutatorScheduler.EvaluationFeedback;
import fuzzer.runtime.scheduling.MutatorScheduler.EvaluationOutcome;
import fuzzer.runtime.scoring.ScorePreview;
import fuzzer.runtime.scoring.Scorer;
import fuzzer.runtime.scoring.ScorerFactory;
import fuzzer.runtime.scoring.ScoringMode;
import fuzzer.runtime.monitoring.GlobalStats;
import fuzzer.runtime.monitoring.MutatorOptimizationRecorder;
import fuzzer.runtime.monitoring.SignalRecorder;
import fuzzer.model.ExecutionResult;
import fuzzer.io.FileManager;
import fuzzer.io.JVMOutputParser;
import fuzzer.logging.LoggingConfig;
import fuzzer.model.OptimizationVectors;
import fuzzer.model.TestCase;
import fuzzer.model.TestCaseResult;



public class Evaluator implements Runnable {

    private final GlobalStats globalStats;
    private final BlockingQueue<TestCase> mutationQueue;
    private final BlockingQueue<TestCaseResult> evaluationQueue;
    private final Scorer scorer;
    private final FileManager fileManager;
    private final SignalRecorder signalRecorder;
    private final MutatorOptimizationRecorder optimizationRecorder;
    private final MutatorScheduler scheduler;
    private final ScoringMode scoringMode;
    private final FuzzerConfig.Mode mode;
    private final CorpusManager corpusManager;

    private static final double RANDOM_CORPUS_ACCEPT_PROB = 0.5;
    private static final int CORPUS_CAPACITY = 10000;
    private static final String JVM_FATAL_ERROR_MARKER =
            "# A fatal error has been detected by the Java Runtime Environment:";
    private static final String JVM_ERROR_REPORT_MARKER =
            "# An error report file with more information is saved as:";
    private static final Logger LOGGER = LoggingConfig.getLogger(Evaluator.class);

    public Evaluator(FileManager fm,
                     BlockingQueue<TestCaseResult> evaluationQueue,
                     BlockingQueue<TestCase> mutationQueue,
                     GlobalStats globalStats,
                     ScoringMode scoringMode,
                     SignalRecorder signalRecorder,
                     MutatorOptimizationRecorder optimizationRecorder,
                     MutatorScheduler scheduler,
                     FuzzerConfig.Mode mode,
                     CorpusPolicy corpusPolicy,
                     long sessionSeed) {
        this.globalStats = Objects.requireNonNull(globalStats, "globalStats");
        this.evaluationQueue = Objects.requireNonNull(evaluationQueue, "evaluationQueue");
        this.mutationQueue = Objects.requireNonNull(mutationQueue, "mutationQueue");
        this.fileManager = Objects.requireNonNull(fm, "fileManager");
        this.scoringMode = Objects.requireNonNullElse(scoringMode, ScoringMode.PF_IDF);
        this.scorer = ScorerFactory.createScorer(this.globalStats, this.scoringMode);
        this.signalRecorder = signalRecorder;
        this.optimizationRecorder = optimizationRecorder;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.mode = Objects.requireNonNull(mode, "mode");
        CorpusPolicy effectivePolicy = Objects.requireNonNullElse(corpusPolicy, CorpusPolicy.CHAMPION);
        this.corpusManager = createCorpusManager(effectivePolicy, mutationQueue, this.scoringMode, scorer, sessionSeed);
        LOGGER.info(() -> String.format("Using %s corpus policy", effectivePolicy.displayName()));
        LOGGER.info(() -> String.format(
                "Evaluator configured with scoring mode %s",
                this.scoringMode.displayName()));
    }

    public CorpusManager getCorpusManager() {
        return corpusManager;
    }

   
    @Override
    public void run() {
        LOGGER.info(() -> String.format(
                "Evaluator started. Scoring mode: %s",
                scoringMode.displayName()));

        if (mode != FuzzerConfig.Mode.FUZZ && mode != FuzzerConfig.Mode.FUZZ_ASSERTS) {
            LOGGER.warning(() -> String.format("Evaluator mode %s is not supported by this runner", mode));
            return;
        }
        while (true) {
            try {
                TestCaseResult tcr = evaluationQueue.take();
                processByMode(tcr);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Evaluator encountered an error", e);
            }
        }
    }

    private void processByMode(TestCaseResult tcr) throws InterruptedException {
        if (mode == FuzzerConfig.Mode.FUZZ) {
            processTestCaseResultDifferential(tcr);
        } else {
            processTestCaseResultAssert(tcr);
        }
    }

    private CorpusManager createCorpusManager(
            CorpusPolicy policy,
            BlockingQueue<TestCase> mutationQueue,
            ScoringMode scoringMode,
            Scorer scorer,
            long seed) {
        Random rng = new Random(seed ^ 0x632BE59BD9B4E019L);
        return switch (policy) {
            case RANDOM -> new RandomCorpusManager(CORPUS_CAPACITY, RANDOM_CORPUS_ACCEPT_PROB, rng, scorer, mutationQueue);
            case CHAMPION -> new ChampionCorpusManager(CORPUS_CAPACITY, mutationQueue, scoringMode, scorer, globalStats, rng);
        };
    }

    private void processTestCaseResultAssert(TestCaseResult tcr) throws InterruptedException {

        TestCase testCase = tcr.testCase();
        double parentScore = testCase.getParentScore();
        ExecutionResult result = tcr.jitExecutionResult();

        globalStats.recordTestEvaluated();

        if (handleJITTimeout(tcr)) {
            notifyScheduler(testCase, EvaluationOutcome.TIMEOUT, parentScore);
            return;
        }

        // Assert mode only checks fatal JVM assertion-style failures in process output.

        int exitCode = result.exitCode();
        String stdout = result.stdout();

        if (exitCode != 0) {
            if (isJvmAssertionFailure(stdout)) {
                incrementFoundBugs();
                fileManager.saveBugInducingTestCase(tcr, "JVM assertion failure detected");
            }
        }
        // Continue with normal scoring/corpus handling.
        evaluatePassingTestCase(tcr);
    }


    private void processTestCaseResultDifferential(TestCaseResult tcr) throws InterruptedException {
        TestCase testCase = tcr.testCase();
        double parentScore = testCase.getParentScore();

        globalStats.recordTestEvaluated();

        // if a testcase times out, we discard it
        if (handleTimeouts(tcr)) {
            notifyScheduler(testCase, EvaluationOutcome.TIMEOUT, parentScore);
            return;
        }

        // if exit codes differ, we have found a bug (unless the fuzzer broke, happens...)
        if (handleExitCodeMismatch(tcr)) {
            notifyScheduler(testCase, EvaluationOutcome.BUG, parentScore);
            return;
        }

        // above function guarantees that exit codes are the same
        // if the exit code is non-zero, we discard the test case
        if (handleNonZeroExit(tcr)) {
            notifyScheduler(testCase, EvaluationOutcome.FAILURE, parentScore);
            return;
        }

        // Finally check output equivalence; mismatches indicate a bug.
        if (handleStdoutMismatch(tcr)) {
            notifyScheduler(testCase, EvaluationOutcome.BUG, parentScore);
            return;
        }

        // No bug detected, we can now parse the optimizations and score the test case
        evaluatePassingTestCase(tcr);
    }

    private void evaluatePassingTestCase(TestCaseResult tcr) throws InterruptedException {

        TestCase testCase = tcr.testCase();
        ExecutionResult intResult = tcr.intExecutionResult();
        ExecutionResult jitResult = tcr.jitExecutionResult();

        // Assert mode has no interpreter run, so use JIT execution result for shared bookkeeping.
        if (intResult == null) {
            intResult = jitResult;
        }

        OptimizationVectors optVectors = JVMOutputParser.parseJVMOutput(jitResult.stderr());
        testCase.setOptVectors(optVectors);
        recordOptimizationDelta(tcr);
        testCase.setExecutionTimes(intResult.executionTime(), jitResult.executionTime());

        ScorePreview scorePreview = scorer.previewScore(testCase, optVectors);
        double rawScore = (scorePreview != null) ? Math.max(0.0, scorePreview.score()) : 0.0;

        testCase.setScore(rawScore);
        if (!Double.isFinite(rawScore) || rawScore <= 0.0) {
            handleInvalidRawScore(testCase, intResult, jitResult, rawScore);
            notifyScheduler(testCase, EvaluationOutcome.NO_IMPROVEMENT, rawScore);
            return;
        }

        CorpusDecision decision = corpusManager.evaluate(testCase, scorePreview);

        handleEvictedChampions(decision);
        globalStats.updateCorpusSize(corpusManager.corpusSize());
        applyCorpusDecision(testCase, scorePreview, decision, rawScore, intResult, jitResult);


        // we need to be careful when notifying the scheduler about improvements
        // Use optimization deltas instead of score so Uniform still yields IMPROVED events.
        double finalScoreForScheduler = testCase.getScore();
        boolean improved = hasMergedCountIncrease(testCase.getParentOptVectors(), testCase.getOptVectors());
        EvaluationOutcome schedulerOutcome = improved
                ? EvaluationOutcome.IMPROVED
                : EvaluationOutcome.NO_IMPROVEMENT;
        notifyScheduler(testCase, schedulerOutcome, finalScoreForScheduler);

    }

    private void handleInvalidRawScore(TestCase testCase,
                                       ExecutionResult intResult,
                                       ExecutionResult jitResult,
                                       double rawScore) {
        testCase.deactivateChampion();
        // Even discarded/zero-score tests were executed; record them for metrics.
        recordSuccessfulTest(intResult.executionTime(),
                jitResult.executionTime(),
                0.0,
                1.0);
        LOGGER.fine(String.format("Test case %s discarded: %s score %.6f",
                testCase.getName(),
                scoringMode.displayName(),
                rawScore));
    }

    private void handleEvictedChampions(CorpusDecision decision) {
        for (TestCase evictedChampion : decision.evictedChampions()) {
            evictedChampion.deactivateChampion();
            mutationQueue.remove(evictedChampion);
            fileManager.deleteTestCase(evictedChampion);
        }
    }

    private void applyCorpusDecision(TestCase testCase,
                                     ScorePreview scorePreview,
                                     CorpusDecision decision,
                                     double rawScore,
                                     ExecutionResult intResult,
                                     ExecutionResult jitResult) throws InterruptedException {
        switch (decision.outcome()) {
            case ACCEPTED -> handleAccepted(testCase, scorePreview, intResult, jitResult);
            case REPLACED -> handleReplaced(testCase, scorePreview, decision.previousChampion(), intResult, jitResult);
            case REJECTED -> handleRejected(testCase, decision.previousChampion(), intResult, jitResult, rawScore);
            case DISCARDED -> handleDiscarded(testCase, decision.reason(), intResult, jitResult, rawScore);
        }
    }

    private void handleAccepted(TestCase testCase,
                                ScorePreview scorePreview,
                                ExecutionResult intResult,
                                ExecutionResult jitResult) throws InterruptedException {
        double committedScore = scorer.commitScore(testCase, scorePreview);
        testCase.setScore(committedScore);
        testCase.activateChampion();
        mutationQueue.remove(testCase);
        mutationQueue.put(testCase);
        globalStats.recordChampionAccepted();
        recordSuccessfulTest(intResult.executionTime(),
                jitResult.executionTime(),
                committedScore,
                1.0);
        LOGGER.fine(String.format(
                "Test case %s scheduled for mutation, %s score %.6f",
                testCase.getName(),
                scoringMode.displayName(),
                committedScore));
        corpusManager.synchronizeChampionScore(testCase);
    }

    private void handleReplaced(TestCase testCase,
                                ScorePreview scorePreview,
                                TestCase previousChampion,
                                ExecutionResult intResult,
                                ExecutionResult jitResult) throws InterruptedException {
        TestCase replacedChampion = Objects.requireNonNull(previousChampion, "replaced champion");
        replacedChampion.deactivateChampion();
        mutationQueue.remove(replacedChampion);
        fileManager.deleteTestCase(replacedChampion);
        double committedScore = scorer.commitScore(testCase, scorePreview);
        testCase.setScore(committedScore);
        testCase.activateChampion();
        mutationQueue.remove(testCase);
        mutationQueue.put(testCase);
        globalStats.recordChampionReplaced();
        recordSuccessfulTest(intResult.executionTime(),
                jitResult.executionTime(),
                committedScore,
                1.0);
        LOGGER.info(String.format(
                "Test case %s replaced %s, %s score %.6f",
                testCase.getName(),
                replacedChampion.getName(),
                scoringMode.displayName(),
                committedScore));
        corpusManager.synchronizeChampionScore(testCase);
    }

    private void handleRejected(TestCase testCase,
                                TestCase incumbent,
                                ExecutionResult intResult,
                                ExecutionResult jitResult,
                                double rawScore) {
        testCase.deactivateChampion();
        globalStats.recordChampionRejected();
        testCase.setScore(rawScore);
        recordSuccessfulTest(intResult.executionTime(),
                jitResult.executionTime(),
                rawScore,
                1.0);
        LOGGER.fine(String.format(
                "Test case %s rejected: %s score %.6f (incumbent %.6f)",
                testCase.getName(),
                scoringMode.displayName(),
                rawScore,
                incumbent != null ? incumbent.getScore() : 0.0));
        fileManager.deleteTestCase(testCase);
    }

    private void handleDiscarded(TestCase testCase,
                                 String reason,
                                 ExecutionResult intResult,
                                 ExecutionResult jitResult,
                                 double rawScore) {
        testCase.deactivateChampion();
        globalStats.recordChampionDiscarded();
        testCase.setScore(rawScore);
        recordSuccessfulTest(intResult.executionTime(),
                jitResult.executionTime(),
                rawScore,
                1.0);
        LOGGER.fine(String.format(
                "Test case %s discarded: %s (%s score %.6f)",
                testCase.getName(),
                reason != null ? reason : "no reason",
                scoringMode.displayName(),
                rawScore));

        fileManager.deleteTestCase(testCase);
    }

    private void notifyScheduler(TestCase testCase, EvaluationOutcome outcome, double childScore) {
        TestCase candidate = Objects.requireNonNull(testCase, "testCase");
        MutatorType mutatorType = Objects.requireNonNull(candidate.getMutation(), "testCase mutation");
        if (mutatorType == MutatorType.SEED) {
            return;
        }
        double parentScore = Math.max(0.0, candidate.getParentScore());
        double child = Math.max(0.0, childScore);
        OptimizationVectors parentVectors = candidate.getParentOptVectors();
        OptimizationVectors childVectors = candidate.getOptVectors();
        int[] parentCounts = (parentVectors != null && parentVectors.mergedCounts() != null)
                ? parentVectors.mergedCounts().counts
                : null;
        int[] childCounts = (childVectors != null && childVectors.mergedCounts() != null)
                ? childVectors.mergedCounts().counts
                : null;
        EvaluationFeedback feedback = new EvaluationFeedback(mutatorType, parentScore, child, outcome, parentCounts, childCounts);
        scheduler.recordEvaluation(feedback);
        globalStats.recordMutatorEvaluation(mutatorType, outcome);
    }



    // used by processTestCaseResultDifferential
    private boolean handleTimeouts(TestCaseResult tcr) {
        TestCase testCase = tcr.testCase();
        boolean intTimeout = handleIntTimeout(tcr);
        boolean jitTimeout = handleJITTimeout(tcr);


        if (intTimeout || jitTimeout) {
            recordRuntimeForTimeout(tcr);
            globalStats.recordMutatorTimeout(testCase.getMutation());
            return true;
        }
        return false;
    }

    private void recordRuntimeForTimeout(TestCaseResult tcr) {
        ExecutionResult intResult = tcr.intExecutionResult();
        ExecutionResult jitResult = tcr.jitExecutionResult();
        long intTime = (intResult != null) ? intResult.executionTime()
                : (jitResult != null ? jitResult.executionTime() : 0L);
        long jitTime = (jitResult != null) ? jitResult.executionTime() : intTime;
        globalStats.recordExecTimesNanos(intTime, jitTime);
    }

    private boolean handleJITTimeout(TestCaseResult tcr) {
        ExecutionResult jitResult = tcr.jitExecutionResult();
        boolean timedOut = jitResult.timedOut();
        if (timedOut) {
            globalStats.incrementJitTimeouts();
        }
        return timedOut;
    }

    private boolean handleIntTimeout(TestCaseResult tcr) {
        ExecutionResult intResult = tcr.intExecutionResult();
        boolean timedOut = intResult.timedOut();
        if (timedOut) {
            globalStats.incrementIntTimeouts();
        }
        return timedOut;
    }

    private boolean handleExitCodeMismatch(TestCaseResult tcr) {
        TestCase testCase = tcr.testCase();
        ExecutionResult intResult = tcr.intExecutionResult();
        ExecutionResult jitResult = tcr.jitExecutionResult();
        if (intResult.exitCode() != jitResult.exitCode()) {
            LOGGER.severe(String.format("Different exit codes for test case %s: int=%d, jit=%d",
                    testCase.getName(), intResult.exitCode(), jitResult.exitCode()));
            incrementFoundBugs();
            fileManager.saveBugInducingTestCase(tcr, "Different exit codes");
            return true;
        }
        return false;
    }

    /*
     * Check whether the exit code is non-zero.
     * Return true if it is non-zero (indicating a broken test case),
     * return false if it is zero (indicating a valid test case).
     */
    private boolean handleNonZeroExit(TestCaseResult tcr) {
        ExecutionResult intResult = tcr.intExecutionResult();
        return intResult.exitCode() != 0;
    }

    /*
     * Check if the stdout of both runs is identical
     * return true if they differ (indicating a bug)
     * return false if they are the same
     */
    private boolean handleStdoutMismatch(TestCaseResult tcr) {
        TestCase testCase = tcr.testCase();
        ExecutionResult intResult = tcr.intExecutionResult();
        ExecutionResult jitResult = tcr.jitExecutionResult();
        String intOutput = intResult.stdout();
        String jitOutput = jitResult.stdout();

        if (!intOutput.equals(jitOutput)) {
            LOGGER.severe(String.format("Different stdout for test case %s", testCase.getName()));
            incrementFoundBugs();
            fileManager.saveBugInducingTestCase(tcr, "Different stdout (i.e. wrong results)");
            return true;
        }

        return false;
    }

    private static boolean isJvmAssertionFailure(String stdout) {
        return stdout.contains(JVM_FATAL_ERROR_MARKER)
                && stdout.contains(JVM_ERROR_REPORT_MARKER);
    }

    private void recordSuccessfulTest(long intExecTimeNanos,
                                      long jitExecTimeNanos,
                                      double score,
                                      double runtimeWeight) {
        globalStats.recordExecTimesNanos(intExecTimeNanos, jitExecTimeNanos);
        globalStats.recordTest(score, runtimeWeight);
        if (signalRecorder != null) {
            signalRecorder.maybeRecord(globalStats);
        }
    }

    private void incrementFoundBugs() {
        globalStats.incrementFoundBugs();
    }

    private void recordOptimizationDelta(TestCaseResult tcr) {
        if (optimizationRecorder == null) {
            return;
        }
        optimizationRecorder.record(tcr.testCase());
    }

    private static boolean hasMergedCountIncrease(OptimizationVectors parentVectors, OptimizationVectors childVectors) {
        int[] childCounts = childVectors.mergedCounts().counts;
        int[] parentCounts = (parentVectors != null && parentVectors.mergedCounts() != null)
                ? parentVectors.mergedCounts().counts
                : null;
        if (parentCounts == null) {
            for (int count : childCounts) {
                if (count > 0) {
                    return true;
                }
            }
            return false;
        }
        int len = Math.min(parentCounts.length, childCounts.length);
        for (int i = 0; i < len; i++) {
            if (childCounts[i] > parentCounts[i]) {
                return true;
            }
        }
        for (int i = len; i < childCounts.length; i++) {
            if (childCounts[i] > 0) {
                return true;
            }
        }
        return false;
    }

}
