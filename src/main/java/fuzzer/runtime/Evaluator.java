package fuzzer.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.mutators.MutatorType;
import fuzzer.util.ExecutionResult;
import fuzzer.util.FileManager;
import fuzzer.util.JVMOutputParser;
import fuzzer.util.LoggingConfig;
import fuzzer.util.OptimizationVectors;
import fuzzer.util.TestCase;
import fuzzer.util.TestCaseResult;



public class Evaluator implements Runnable{
    private final GlobalStats globalStats;
    private final BlockingQueue<TestCase> mutationQueue;
    private final BlockingQueue<TestCaseResult> evaluationQueue;
    private final InterestingnessScorer scorer;
    private final FileManager fileManager;
    private final SignalRecorder signalRecorder;
    private final Map<IntArrayKey, ChampionEntry> champions = new HashMap<>();
    private final ScoringMode scoringMode;

    private static final double SCORE_EPS = 1e-9;
    private static final int CORPUS_CAPACITY = 100000;
    private static final double SCORE_REWARD_SCALE = 750.0;
    private static final double MUTATOR_ACCEPTED_BONUS = 0.45;
    private static final double MUTATOR_REPLACED_BONUS = 0.35;
    private static final double MUTATOR_REJECTED_PENALTY = -0.05;
    private static final double MUTATOR_DISCARDED_PENALTY = -0.1;
    private static final double MUTATOR_TIMEOUT_PENALTY = -0.35;
    private static final double MUTATOR_FAILURE_PENALTY = -0.25;
    private static final double MUTATOR_LOW_SCORE_PENALTY = -0.1;
    private static final double MUTATOR_BUG_REWARD = 1.2;
    private static final Logger LOGGER = LoggingConfig.getLogger(Evaluator.class);

    public Evaluator(FileManager fm,
                     BlockingQueue<TestCaseResult> evaluationQueue,
                     BlockingQueue<TestCase> mutationQueue,
                     GlobalStats globalStats,
                     ScoringMode scoringMode,
                     SignalRecorder signalRecorder) {
        this.globalStats = globalStats;
        this.evaluationQueue = evaluationQueue;
        this.mutationQueue = mutationQueue;
        this.fileManager = fm;
        //this.graphParser = new GraphParser();
        this.scorer = new InterestingnessScorer(globalStats, 5_000_000_000L/*s*/); // TODO pass real params currently set to 5s
        this.scoringMode = (scoringMode != null) ? scoringMode : ScoringMode.PF_IDF;
        this.signalRecorder = signalRecorder;
        LOGGER.info(() -> String.format(
                "Evaluator configured with scoring mode %s",
                this.scoringMode.displayName()));
    }

   
    @Override
    public void run() {
        LOGGER.info(() -> String.format(
                "Evaluator started. Scoring mode: %s",
                scoringMode.displayName()));

        while (true) {
            try {
                TestCaseResult tcr = evaluationQueue.take();
                processTestCaseResult(tcr);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Evaluator encountered an error", e);
            }
        }
    }

    private void processTestCaseResult(TestCaseResult tcr) throws InterruptedException {
        TestCase testCase = tcr.testCase();
        ExecutionResult intResult = tcr.intExecutionResult();
        ExecutionResult jitResult = tcr.jitExecutionResult();

        // if a testcase times out, we discard it
        if (handleTimeouts(tcr)) {
            return;
        }

        // if exit codes differ, we have found a bug (unless the fuzzer broke, happens...)
        if (handleExitCodeMismatch(tcr)) {
            return;
        }

        // above function guarantees that exit codes are the same
        // if the exit code is non-zero, we discard the test case
        if (handleNonZeroExit(tcr)) {
            return;
        }

        // finally we check check the output, if it differs, we have found a bug
        if (handleStdoutMismatch(tcr)) {
            return;
        }

        // No bug detected, we can now parse the optimizations and score the test case
        OptimizationVectors optVectors = JVMOutputParser.parseJVMOutput(jitResult.stderr());
        OptimizationVectors parentOptVectors = testCase.getParentOptVectors();

        testCase.setOptVectors(optVectors);
        testCase.setExecutionTimes(intResult.executionTime(), jitResult.executionTime());

        InterestingnessScorer.PFIDFResult pfidfPreview = null;
        double rawScore;
        if (scoringMode == ScoringMode.PF_IDF) {
            pfidfPreview = scorer.previewPFIDF(testCase, optVectors);
            rawScore = (pfidfPreview != null) ? Math.max(0.0, pfidfPreview.score()) : 0.0;
        } else {
            rawScore = scorer.score(testCase, optVectors, scoringMode);
        }

        double runtimeWeight = computeRuntimeWeight(testCase);
        double combinedScore = applyRuntimeWeight(rawScore, runtimeWeight);
        testCase.setScore(combinedScore);
        if (!Double.isFinite(combinedScore) || combinedScore <= 0.0) {
            if (scoringMode == ScoringMode.PF_IDF) {
                scorer.commitPFIDF(testCase, pfidfPreview);
            }
            applyMutatorReward(testCase, MUTATOR_LOW_SCORE_PENALTY);
            testCase.deactivateChampion();
            LOGGER.fine(String.format("Test case %s discarded: %s score %.6f (raw %.6f, runtime weight %.4f)",
                    testCase.getName(),
                    scoringMode.displayName(),
                    combinedScore,
                    rawScore,
                    runtimeWeight));
            return;
        }

        ChampionDecision decision = updateChampionCorpus(testCase);
        for (TestCase evictedChampion : decision.evictedChampions()) {
            if (evictedChampion != null) {
                evictedChampion.deactivateChampion();
                mutationQueue.remove(evictedChampion);
                fileManager.deleteTestCase(evictedChampion);
            }
        }

        double finalRawScore = rawScore;
        double finalScore = combinedScore;
        if (scoringMode == ScoringMode.PF_IDF) {
            finalRawScore = scorer.commitPFIDF(testCase, pfidfPreview);
            finalRawScore = Math.max(finalRawScore, 0.0);
            finalScore = applyRuntimeWeight(finalRawScore, runtimeWeight);
            testCase.setScore(finalScore);
        }

        double outcomeReward = 0.0;
        switch (decision.outcome()) {
            case ACCEPTED -> {
                if (scoringMode == ScoringMode.PF_IDF) {
                    synchronizeChampionScore(testCase);
                }
                testCase.activateChampion();
                mutationQueue.remove(testCase);
                mutationQueue.put(testCase);
                globalStats.recordBestVectorFeatures(testCase.getHashedOptVector());
                globalStats.recordChampionAccepted();
                recordSuccessfulTest(intResult.executionTime(), jitResult.executionTime(), finalScore, runtimeWeight);
                outcomeReward += MUTATOR_ACCEPTED_BONUS;
                LOGGER.info(String.format(
                        "Test case %s scheduled for mutation, %s score %.6f (raw %.6f, runtime weight %.4f)",
                        testCase.getName(),
                        scoringMode.displayName(),
                        finalScore,
                        finalRawScore,
                        runtimeWeight));
            }
            case REPLACED -> {
                TestCase previousChampion = decision.previousChampion();
                if (previousChampion != null) {
                    previousChampion.deactivateChampion();
                    mutationQueue.remove(previousChampion);
                }
                if (scoringMode == ScoringMode.PF_IDF) {
                    synchronizeChampionScore(testCase);
                }
                testCase.activateChampion();
                mutationQueue.remove(testCase);
                mutationQueue.put(testCase);
                globalStats.recordBestVectorFeatures(testCase.getHashedOptVector());
                globalStats.recordChampionReplaced();
                recordSuccessfulTest(intResult.executionTime(), jitResult.executionTime(), finalScore, runtimeWeight);
                outcomeReward += MUTATOR_REPLACED_BONUS;
                LOGGER.info(String.format(
                        "Test case %s replaced %s, %s score %.6f (raw %.6f, runtime weight %.4f)",
                        testCase.getName(),
                        previousChampion != null ? previousChampion.getName() : "<unknown>",
                        scoringMode.displayName(),
                        finalScore,
                        finalRawScore,
                        runtimeWeight));
            }
            case REJECTED -> {
                testCase.deactivateChampion();
                TestCase incumbent = decision.previousChampion();
                globalStats.recordChampionRejected();
                outcomeReward += MUTATOR_REJECTED_PENALTY;
                LOGGER.fine(String.format(
                        "Test case %s rejected: %s score %.6f (incumbent %.6f, raw %.6f, runtime weight %.4f)",
                        testCase.getName(),
                        scoringMode.displayName(),
                        finalScore,
                        incumbent != null ? incumbent.getScore() : 0.0,
                        finalRawScore,
                        runtimeWeight));
                fileManager.deleteTestCase(testCase);
                        
            }
            case DISCARDED -> {
                testCase.deactivateChampion();
                String reason = decision.reason();
                globalStats.recordChampionDiscarded();
                outcomeReward += MUTATOR_DISCARDED_PENALTY;
                LOGGER.fine(String.format(
                        "Test case %s discarded: %s (%s score %.6f, raw %.6f, runtime weight %.4f)",
                        testCase.getName(),
                        reason != null ? reason : "no reason",
                        scoringMode.displayName(),
                        finalScore,
                        finalRawScore,
                        runtimeWeight));

                fileManager.deleteTestCase(testCase);
            }
        }

        if (testCase.getMutation() != MutatorType.SEED) {
            double baseReward = computeScoreDeltaReward(testCase);
            double totalReward = baseReward + outcomeReward;
            applyMutatorReward(testCase, totalReward);
        }
    }

    private double computeScoreDeltaReward(TestCase testCase) {
        if (testCase == null) {
            return 0.0;
        }
        double childScore = Math.max(0.0, testCase.getScore());
        double parentScore = Math.max(0.0, testCase.getParentScore());
        double delta = childScore - parentScore;
        return Math.tanh(delta / SCORE_REWARD_SCALE);
    }

    private void applyMutatorReward(TestCase testCase, double reward) {
        if (testCase == null || globalStats == null) {
            return;
        }
        MutatorType mutatorType = testCase.getMutation();
        if (mutatorType == null || mutatorType == MutatorType.SEED) {
            return;
        }
        // double normalized = Double.isFinite(reward) ? reward : 0.0;
        // globalStats.recordMutatorReward(mutatorType, normalized);
    }

    private double computeRuntimeWeight(TestCase testCase) {
        if (testCase == null) {
            return 1.0;
        }
        long interpreterRuntime = testCase.getInterpreterRuntimeNanos();
        long jitRuntime = testCase.getJitRuntimeNanos();
        long runtime = Math.max(interpreterRuntime, jitRuntime);
        if (runtime <= 0L) {
            return 1.0;
        }
        double weight = scorer.computeRuntimeScore(runtime);
        if (!Double.isFinite(weight)) {
            return 0.0;
        }
        return Math.max(0.0, weight);
    }

    private double applyRuntimeWeight(double rawScore, double runtimeWeight) {
        if (!Double.isFinite(rawScore) || !Double.isFinite(runtimeWeight)) {
            return 0.0;
        }
        if (rawScore <= 0.0 || runtimeWeight <= 0.0) {
            return 0.0;
        }
        return rawScore * runtimeWeight;
    }

    private boolean handleTimeouts(TestCaseResult tcr) {
        TestCase testCase = tcr.testCase();
        ExecutionResult intResult = tcr.intExecutionResult();
        ExecutionResult jitResult = tcr.jitExecutionResult();
        boolean intTimeout = intResult.timedOut();
        boolean jitTimeout = jitResult.timedOut();

        if (intTimeout) {
            globalStats.incrementIntTimeouts();
            LOGGER.severe(String.format("Interpreter Timeout for test case %s: int timed out=%b, jit timed out=%b",
                    testCase.getName(), true, jitTimeout));
        }

        if (!intTimeout && jitTimeout) {
            LOGGER.severe(String.format("JIT Timeout for test case %s: int timed out=%b, jit timed out=%b",
                    testCase.getName(), false, true));
        }

        if (jitTimeout) {
            globalStats.incrementJitTimeouts();
        }

        if (intTimeout || jitTimeout) {
            if (globalStats != null) {
                globalStats.recordMutatorTimeout(testCase.getMutation());
            }
            applyMutatorReward(testCase, MUTATOR_TIMEOUT_PENALTY);
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
            applyMutatorReward(testCase, MUTATOR_BUG_REWARD);
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
        TestCase testCase = tcr.testCase();
        ExecutionResult intResult = tcr.intExecutionResult();
        if (intResult.exitCode() != 0) {
            fileManager.saveFailingTestCase(tcr);
            applyMutatorReward(testCase, MUTATOR_FAILURE_PENALTY);
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
            applyMutatorReward(testCase, MUTATOR_BUG_REWARD);
            return true;
        }

        return false;
    }



    private ChampionDecision updateChampionCorpus(TestCase testCase) {
        int[] hashedCounts = testCase.getHashedOptVector();
        if (hashedCounts == null) {
            return ChampionDecision.discarded("Missing optimization vector");
        }
        boolean hasActivity = false;
        for (int value : hashedCounts) {
            if (value > 0) {
                hasActivity = true;
                break;
            }
        }
        if (!hasActivity) {
            return ChampionDecision.discarded("No active optimizations observed");
        }

        IntArrayKey key = new IntArrayKey(hashedCounts);
        double score = testCase.getScore();
        ChampionEntry existing = champions.get(key);
        if (existing == null) {
            ChampionEntry entry = new ChampionEntry(key, hashedCounts, testCase, score);
            champions.put(key, entry);
            refreshChampionScore(entry);
            ArrayList<TestCase> evicted = enforceChampionCapacity();
            if (evicted.remove(testCase)) {
                return ChampionDecision.discarded(String.format(
                        "Corpus capacity reached; %s score below retention threshold",
                        scoringMode.displayName()));
            }
            return ChampionDecision.accepted(List.copyOf(evicted));
        }

        double incumbentScore = existing.score;
        if (scoringMode == ScoringMode.PF_IDF) {
            incumbentScore = refreshChampionScore(existing);
        }

        if (score > incumbentScore + SCORE_EPS) {
            TestCase previous = existing.testCase;
            existing.update(testCase, hashedCounts, score);
            refreshChampionScore(existing);
            ArrayList<TestCase> evicted = enforceChampionCapacity();
            return ChampionDecision.replaced(previous, List.copyOf(evicted));
        }

        return ChampionDecision.rejected(existing.testCase, String.format(
                "Incumbent has higher or equal %s score",
                scoringMode.displayName()));
    }

    private ArrayList<TestCase> enforceChampionCapacity() {
        ArrayList<TestCase> evicted = new ArrayList<>();
        if (CORPUS_CAPACITY <= 0 || champions.size() <= CORPUS_CAPACITY) {
            return evicted;
        }

        ArrayList<ChampionEntry> entries = new ArrayList<>(champions.values());
        entries.sort(Comparator.comparingDouble(entry -> entry.score));

        int index = 0;
        while (champions.size() > CORPUS_CAPACITY && index < entries.size()) {
            ChampionEntry candidate = entries.get(index++);
            if (champions.remove(candidate.key) != null) {
                evicted.add(candidate.testCase);
            }
        }
        return evicted;
    }

    private void synchronizeChampionScore(TestCase champion) {
        if (champion == null) {
            return;
        }
        int[] hashed = champion.getHashedOptVector();
        if (hashed == null) {
            return;
        }
        ChampionEntry entry = champions.get(new IntArrayKey(hashed));
        if (entry != null && entry.testCase == champion) {
            entry.score = champion.getScore();
        }
    }

    private double refreshChampionScore(ChampionEntry entry) {
        if (entry == null) {
            return 0.0;
        }
        if (scoringMode != ScoringMode.PF_IDF) {
            return entry.score;
        }
        TestCase champion = entry.testCase;
        if (champion == null) {
            return entry.score;
        }
        OptimizationVectors vectors = champion.getOptVectors();
        if (vectors == null) {
            return entry.score;
        }
        double rescored = scorer.debugInteractionScore_PF_IDF(vectors);
        double normalized = Double.isFinite(rescored) ? Math.max(rescored, 0.0) : 0.0;
        double runtimeWeight = computeRuntimeWeight(champion);
        double combined = applyRuntimeWeight(normalized, runtimeWeight);
        if (Math.abs(combined - entry.score) > SCORE_EPS) {
            entry.score = combined;
            boolean wasQueued = false;
            if (champion.isActiveChampion()) {
                wasQueued = mutationQueue.remove(champion);
            }
            champion.setScore(combined);
            if (champion.isActiveChampion() && wasQueued) {
                try {
                    mutationQueue.put(champion);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    LOGGER.log(Level.WARNING, "Interrupted while requeuing champion", ie);
                }
            }
        }
        return entry.score;
    }

    private void recordSuccessfulTest(long intExecTimeNanos, long jitExecTimeNanos, double score, double runtimeWeight) {
        globalStats.recordExecTimesNanos(intExecTimeNanos, jitExecTimeNanos);
        globalStats.recordTest(score, runtimeWeight);
        if (signalRecorder != null) {
            signalRecorder.maybeRecord(globalStats);
        }
    }

    private static final class ChampionEntry {
        final IntArrayKey key;
        TestCase testCase;
        double score;
        int[] counts;

        ChampionEntry(IntArrayKey key, int[] counts, TestCase testCase, double score) {
            this.key = key;
            this.counts = Arrays.copyOf(counts, counts.length);
            this.testCase = testCase;
            this.score = score;
        }

        void update(TestCase newChampion, int[] newCounts, double newScore) {
            this.testCase = newChampion;
            this.score = newScore;
            this.counts = Arrays.copyOf(newCounts, newCounts.length);
        }
    }

    private static final class IntArrayKey {
        private final int[] data;
        private final int hash;

        IntArrayKey(int[] counts) {
            this.data = Arrays.copyOf(counts, counts.length);
            this.hash = Arrays.hashCode(this.data);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof IntArrayKey)) {
                return false;
            }
            IntArrayKey other = (IntArrayKey) obj;
            return Arrays.equals(this.data, other.data);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private enum ChampionOutcome {
        ACCEPTED,
        REPLACED,
        REJECTED,
        DISCARDED
    }

    private static final class ChampionDecision {
        private final ChampionOutcome outcome;
        private final TestCase previousChampion;
        private final List<TestCase> evictedChampions;
        private final String reason;

        private ChampionDecision(ChampionOutcome outcome, TestCase previousChampion, List<TestCase> evictedChampions, String reason) {
            this.outcome = outcome;
            this.previousChampion = previousChampion;
            this.evictedChampions = evictedChampions;
            this.reason = reason;
        }

        static ChampionDecision accepted(List<TestCase> evicted) {
            return new ChampionDecision(ChampionOutcome.ACCEPTED, null, evicted, null);
        }

        static ChampionDecision replaced(TestCase previousChampion, List<TestCase> evicted) {
            return new ChampionDecision(ChampionOutcome.REPLACED, previousChampion, evicted, null);
        }

        static ChampionDecision rejected(TestCase incumbent, String reason) {
            return new ChampionDecision(ChampionOutcome.REJECTED, incumbent, List.of(), reason);
        }

        static ChampionDecision discarded(String reason) {
            return new ChampionDecision(ChampionOutcome.DISCARDED, null, List.of(), reason);
        }

        ChampionOutcome outcome() {
            return outcome;
        }

        TestCase previousChampion() {
            return previousChampion;
        }

        List<TestCase> evictedChampions() {
            return evictedChampions;
        }

        String reason() {
            return reason;
        }
    }

}
