package fuzzer.runtime;

import java.util.Arrays;
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
import fuzzer.util.ExecutionResult;
import fuzzer.util.FileManager;
import fuzzer.util.JVMOutputParser;
import fuzzer.util.LoggingConfig;
import fuzzer.util.OptimizationVector;
import fuzzer.util.OptimizationVectors;
import fuzzer.util.TestCase;
import fuzzer.util.TestCaseResult;



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

    private static final double SCORE_EPS = 1e-9;
    private static final int CORPUS_CAPACITY = 10000;
    private static final double MUTATOR_BUG_REWARD = 1.2;
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
        this.globalStats = globalStats;
        this.evaluationQueue = evaluationQueue;
        this.mutationQueue = mutationQueue;
        this.fileManager = fm;
        this.scoringMode = (scoringMode != null) ? scoringMode : ScoringMode.PF_IDF;
        this.scorer = ScorerFactory.createScorer(globalStats, this.scoringMode);
        this.signalRecorder = signalRecorder;
        this.optimizationRecorder = optimizationRecorder;
        this.scheduler = scheduler;
        this.mode = mode;
        this.corpusManager = createCorpusManager(corpusPolicy, mutationQueue, this.scoringMode, scorer, sessionSeed);
        LOGGER.info(() -> String.format("Using %s corpus policy", corpusPolicy.displayName()));
        LOGGER.info(() -> String.format(
                "Evaluator configured with scoring mode %s",
                this.scoringMode.displayName()));
    }

   
    @Override
    public void run() {
        LOGGER.info(() -> String.format(
                "Evaluator started. Scoring mode: %s",
                scoringMode.displayName()));

        if (mode == FuzzerConfig.Mode.FUZZ) {
            runDifferential();
        } else if (mode == FuzzerConfig.Mode.FUZZ_ASSERTS) {
            runAssert();
        }
    }

    private void runDifferential() {
        while (true) {
            try {
                TestCaseResult tcr = evaluationQueue.take();
                processTestCaseResultDifferential(tcr);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Evaluator encountered an error", e);
            }
        }
    }

    private void runAssert() {
        while (true) {
            try {
                TestCaseResult tcr = evaluationQueue.take();
                processTestCaseResultAssert(tcr);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Evaluator encountered an error", e);
            }
        }
    }

    private CorpusManager createCorpusManager(
            CorpusPolicy policy,
            BlockingQueue<TestCase> mutationQueue,
            ScoringMode scoringMode,
            Scorer scorer,
            long seed) {
        CorpusPolicy effective = policy != null ? policy : CorpusPolicy.CHAMPION;
        Random rng = new Random(seed ^ 0x632BE59BD9B4E019L);
        return switch (effective) {
            case RANDOM -> new RandomCorpusManager(CORPUS_CAPACITY, RANDOM_CORPUS_ACCEPT_PROB, rng);
            case CHAMPION -> new ChampionCorpusManager(CORPUS_CAPACITY, mutationQueue, scoringMode, scorer, rng);
        };
    }

    private void processTestCaseResultAssert(TestCaseResult tcr) throws InterruptedException {

        TestCase testCase = tcr.testCase();
        double parentScore = testCase.getParentScore();
        ExecutionResult result = tcr.jitExecutionResult();

        if (globalStats != null) {
            globalStats.recordTestEvaluated();
        }

        if (handleJITTimeout(tcr)) {
            notifyScheduler(testCase, EvaluationOutcome.TIMEOUT, parentScore);
            return;
        }

        // no need to check for exit code mismatches or stdout mismatches in assert mode
        // also no need to check for stdout mismatches
        // the only thing we check for is the exit code and the stdout containing assertion failures

        int exitCode = result.exitCode();
        String stdout = result.stdout();

        if (exitCode != 0) {
            if (stdout.contains("# A fatal error has been detected by the Java Runtime Environment:")
                && stdout.contains("# An error report file with more information is saved as:")) {
                    // we have found an assertion failure yaayy
                    globalStats.incrementFoundBugs();
                    fileManager.saveBugInducingTestCase(tcr, "JVM assertion failure detected");
                }
        }
        // now do the scoring
        evaluatePassingTestCase(tcr);
    }


    private void processTestCaseResultDifferential(TestCaseResult tcr) throws InterruptedException {
        TestCase testCase = tcr.testCase();
        double parentScore = testCase.getParentScore();

        if (globalStats != null) {
            globalStats.recordTestEvaluated();
        }

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

        // finally we check check the output, if it differs, we have found a bug
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
        double parentScore = testCase.getParentScore();

        // dirty workaround for assert mode
        if (intResult == null) {
            intResult = jitResult;
        }

        OptimizationVectors optVectors = JVMOutputParser.parseJVMOutput(jitResult.stderr());
        testCase.setOptVectors(optVectors);
        recordOptimizationDelta(testCase);
        testCase.setExecutionTimes(intResult.executionTime(), jitResult.executionTime());

        ScorePreview scorePreview = scorer.previewScore(testCase, optVectors);
        double rawScore = (scorePreview != null) ? Math.max(0.0, scorePreview.score()) : 0.0;

        testCase.setScore(rawScore);
        if (!Double.isFinite(rawScore) || rawScore <= 0.0) {
            scorer.commitScore(testCase, scorePreview);
            testCase.deactivateChampion();
            // Even discarded/zero-score tests were executed; record them for metrics.
            recordSuccessfulTest(intResult.executionTime(), jitResult.executionTime(), 0.0);
            LOGGER.fine(String.format("Test case %s discarded: %s score %.6f (raw %.6f, runtime weight %.4f)",
                    testCase.getName(),
                    scoringMode.displayName(),
                    rawScore,
                    rawScore,
                    1.0));
            notifyScheduler(testCase, EvaluationOutcome.NO_IMPROVEMENT, rawScore);
            return;
        }

        CorpusDecision decision = corpusManager.evaluate(testCase, scorePreview);


        // when we evaluate the corpus might be full and champions get evicted
        for (TestCase evictedChampion : decision.evictedChampions()) {
            if (evictedChampion != null) {
                evictedChampion.deactivateChampion();
                mutationQueue.remove(evictedChampion);
                fileManager.deleteTestCase(evictedChampion);
            }
        }

        if (globalStats != null) {
            globalStats.updateCorpusSize(corpusManager.corpusSize());
        }

        double finalScore = rawScore;
        switch (decision.outcome()) {
            case ACCEPTED -> {
                double committedScore = scorer.commitScore(testCase, scorePreview);
                finalScore = committedScore;
                testCase.setScore(finalScore);
                testCase.activateChampion();
                mutationQueue.remove(testCase);
                mutationQueue.put(testCase);
                globalStats.recordChampionAccepted();
                recordSuccessfulTest(intResult.executionTime(), jitResult.executionTime(), finalScore);
                LOGGER.info(String.format(
                        "Test case %s scheduled for mutation, %s score %.6f",
                        testCase.getName(),
                        scoringMode.displayName(),
                        finalScore));
                corpusManager.synchronizeChampionScore(testCase);
            }
            case REPLACED -> {
                TestCase previousChampion = decision.previousChampion();
                if (previousChampion != null) {
                    previousChampion.deactivateChampion();
                    mutationQueue.remove(previousChampion);
                    fileManager.deleteTestCase(previousChampion);
                }
                double committedScore = scorer.commitScore(testCase, scorePreview);
                finalScore = committedScore;
                testCase.setScore(finalScore);
                testCase.activateChampion();
                mutationQueue.remove(testCase);
                mutationQueue.put(testCase);
                globalStats.recordChampionReplaced();
                recordSuccessfulTest(intResult.executionTime(), jitResult.executionTime(), finalScore);
                LOGGER.info(String.format(
                        "Test case %s replaced %s, %s score %.6f",
                        testCase.getName(),
                        previousChampion != null ? previousChampion.getName() : "<unknown>",
                        scoringMode.displayName(),
                        finalScore));
                corpusManager.synchronizeChampionScore(testCase);
            }
            case REJECTED -> {
                testCase.deactivateChampion();
                TestCase incumbent = decision.previousChampion();
                globalStats.recordChampionRejected();
                recordSuccessfulTest(intResult.executionTime(), jitResult.executionTime(), finalScore);
                LOGGER.fine(String.format(
                        "Test case %s rejected: %s score %.6f (incumbent %.6f)",
                        testCase.getName(),
                        scoringMode.displayName(),
                        finalScore,
                        incumbent != null ? incumbent.getScore() : 0.0));
                fileManager.deleteTestCase(testCase);
                        
            }
            case DISCARDED -> {
                testCase.deactivateChampion();
                String reason = decision.reason();
                globalStats.recordChampionDiscarded();
                recordSuccessfulTest(intResult.executionTime(), jitResult.executionTime(), finalScore);
                LOGGER.fine(String.format(
                        "Test case %s discarded: %s (%s score %.6f)",
                        testCase.getName(),
                        reason != null ? reason : "no reason",
                        scoringMode.displayName(),
                        finalScore));

                fileManager.deleteTestCase(testCase);
            }
        }


        // we need to be careful when notifying the scheduler about improvements
        // Use optimization deltas instead of score so Uniform still yields IMPROVED events.
        double finalScoreForScheduler = testCase.getScore();
        boolean improved = hasMergedCountIncrease(testCase.getParentOptVectors(), testCase.getOptVectors());
        EvaluationOutcome schedulerOutcome = improved
                ? EvaluationOutcome.IMPROVED
                : EvaluationOutcome.NO_IMPROVEMENT;
        notifyScheduler(testCase, schedulerOutcome, finalScoreForScheduler);

    }


    private void notifyScheduler(TestCase testCase, EvaluationOutcome outcome, double childScore) {
        if (testCase == null) {
            return;
        }
        MutatorType mutatorType = testCase.getMutation();
        if (mutatorType == null || mutatorType == MutatorType.SEED) {
            return;
        }
        double parentScore = Math.max(0.0, testCase.getParentScore());
        double child = Math.max(0.0, childScore);
        int[] parentCounts = extractMergedCounts(testCase.getParentOptVectors());
        int[] childCounts = extractMergedCounts(testCase.getOptVectors());
        EvaluationFeedback feedback = new EvaluationFeedback(mutatorType, parentScore, child, outcome, parentCounts, childCounts);
        if (scheduler != null) {
            scheduler.recordEvaluation(feedback);
        }
        if (globalStats != null) {
            globalStats.recordMutatorEvaluation(mutatorType, outcome);
        }
    }



    // used by processTestCaseResultDifferential
    private boolean handleTimeouts(TestCaseResult tcr) {
        TestCase testCase = tcr.testCase();
        boolean intTimeout = handleIntTimeout(tcr);
        boolean jitTimeout = handleJITTimeout(tcr);


        if (intTimeout || jitTimeout) {
            String reason;
            if (intTimeout && jitTimeout) {
                reason = "Interpreter and JIT timeout";
            } else if (intTimeout) {
                reason = "Interpreter timeout";
            } else {
                reason = "JIT timeout";
            }
            fileManager.saveFailingTestCase(tcr, reason);
            if (globalStats != null) {
                globalStats.recordMutatorTimeout(testCase.getMutation());
            }
            // applyMutatorReward(testCase, MUTATOR_TIMEOUT_PENALTY);
            return true;
        }
        return false;
    }

    private boolean handleJITTimeout(TestCaseResult tcr) {
        ExecutionResult jitResult = tcr.jitExecutionResult();
        if (jitResult.timedOut()) {
            globalStats.incrementJitTimeouts();
            return true;
        }
        return false;
    }

    private boolean handleIntTimeout(TestCaseResult tcr) {
        ExecutionResult intResult = tcr.intExecutionResult();
        if (intResult.timedOut()) {
            globalStats.incrementIntTimeouts();
            return true;
        }
        return false;
    }

    private boolean handleExitCodeMismatch(TestCaseResult tcr) {
        TestCase testCase = tcr.testCase();
        ExecutionResult intResult = tcr.intExecutionResult();
        ExecutionResult jitResult = tcr.jitExecutionResult();
        if (intResult.exitCode() != jitResult.exitCode()) {
            LOGGER.severe(String.format("Different exit codes for test case %s: int=%d, jit=%d",
                    testCase.getName(), intResult.exitCode(), jitResult.exitCode()));
            globalStats.incrementFoundBugs();
            fileManager.saveBugInducingTestCase(tcr, "Different exit codes");
            return true;
        }
        return false;
    }

    /*
     * Check wether the exit code is non-zero
     * We save the test case to see what went wrong and fix mutators
     * return true if it is non-zero (indicating a broken test case)
     * return false if it is zero (indicating a valid test case)
     */
    private boolean handleNonZeroExit(TestCaseResult tcr) {
        ExecutionResult intResult = tcr.intExecutionResult();
        if (intResult.exitCode() != 0) {
            fileManager.saveFailingTestCase(tcr, "Non-zero exit code");
            return true;
        }
        return false;
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
            globalStats.incrementFoundBugs();
            fileManager.saveBugInducingTestCase(tcr, "Different stdout (i.e. wrong results)");
            return true;
        }

        return false;
    }



    private void recordSuccessfulTest(long intExecTimeNanos, long jitExecTimeNanos, double score) {
        globalStats.recordExecTimesNanos(intExecTimeNanos, jitExecTimeNanos);
        globalStats.recordTest(score, 1.0);
        if (signalRecorder != null) {
            signalRecorder.maybeRecord(globalStats);
        }
    }

    private void recordOptimizationDelta(TestCase testCase) {
        if (optimizationRecorder == null || testCase == null) {
            return;
        }
        optimizationRecorder.record(testCase);
    }

    private static int[] extractMergedCounts(OptimizationVectors vectors) {
        if (vectors == null) {
            return null;
        }
        OptimizationVector merged = vectors.mergedCounts();
        if (merged == null || merged.counts == null) {
            return null;
        }
        return Arrays.copyOf(merged.counts, merged.counts.length);
    }

    private static boolean hasMergedCountIncrease(OptimizationVectors parentVectors, OptimizationVectors childVectors) {
        int[] childCounts = extractMergedCounts(childVectors);
        if (childCounts == null) {
            return false;
        }
        int[] parentCounts = extractMergedCounts(parentVectors);
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
