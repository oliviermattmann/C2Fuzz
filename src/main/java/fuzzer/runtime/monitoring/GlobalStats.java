package fuzzer.runtime.monitoring;

import java.util.Arrays;

import fuzzer.mutators.MutatorType;
import fuzzer.runtime.scheduling.MutatorScheduler.EvaluationOutcome;
import fuzzer.runtime.scheduling.MutatorScheduler.MutationAttemptStatus;

public final class GlobalStats {
    private final SessionCounters sessionCounters = new SessionCounters();
    private final CoverageMetrics coverageMetrics;
    private final RuntimeMetrics runtimeMetrics = new RuntimeMetrics();
    private final CorpusMetrics corpusMetrics = new CorpusMetrics();
    private final MutatorMetrics mutatorMetrics;

    public GlobalStats(int numOptimizations) {
        this.coverageMetrics = new CoverageMetrics(numOptimizations);
        this.mutatorMetrics = new MutatorMetrics(MutatorType.values());
    }

    /** Record that a test was pulled from the execution queue. */
    public void recordTestDispatched() {
        sessionCounters.recordTestDispatched();
    }

    /** Record that a test reached the evaluator. */
    public void recordTestEvaluated() {
        sessionCounters.recordTestEvaluated();
    }

    /** Call this from worker threads when a test finishes. */
    public void recordTest(double score, double runtimeWeight) {
        runtimeMetrics.recordTest(score, runtimeWeight);
    }

    public void incrementJitTimeouts() {
        sessionCounters.incrementJitTimeouts();
    }

    public void incrementIntTimeouts() {
        sessionCounters.incrementIntTimeouts();
    }

    public void incrementFailedCompilations() {
        sessionCounters.incrementFailedCompilations();
    }

    public void incrementFoundBugs() {
        sessionCounters.incrementFoundBugs();
    }

    public boolean recordBugBucket(String bucketId) {
        return sessionCounters.recordBugBucket(bucketId);
    }

    public long getUniqueBugBuckets() {
        return sessionCounters.getUniqueBugBuckets();
    }

    public void recordExecTimesNanos(long intNanos, long jitNanos) {
        runtimeMetrics.recordExecTimesNanos(intNanos, jitNanos);
    }

    public void recordCompilationTimeNanos(long nanos) {
        runtimeMetrics.recordCompilationTimeNanos(nanos);
    }

    public long getTotalTestsDispatched() {
        return sessionCounters.getTotalTestsDispatched();
    }

    public long getTotalTestsEvaluated() {
        return sessionCounters.getTotalTestsEvaluated();
    }

    public long getFailedCompilations() {
        return sessionCounters.getFailedCompilations();
    }

    public long getFoundBugs() {
        return sessionCounters.getFoundBugs();
    }

    public long getJitTimeouts() {
        return sessionCounters.getJitTimeouts();
    }

    public long getIntTimeouts() {
        return sessionCounters.getIntTimeouts();
    }

    public double getAvgIntExecTimeNanos() {
        return runtimeMetrics.getAvgIntExecTimeNanos();
    }

    public double getAvgJitExecTimeNanos() {
        return runtimeMetrics.getAvgJitExecTimeNanos();
    }

    public double getAvgIntExecTimeMillis() {
        return getAvgIntExecTimeNanos() / 1_000_000.0;
    }

    public double getAvgJitExecTimeMillis() {
        return getAvgJitExecTimeNanos() / 1_000_000.0;
    }

    public double getAvgExecTimeNanos() {
        return runtimeMetrics.getAvgExecTimeNanos();
    }

    public double getAvgExecTimeMillis() {
        return getAvgExecTimeNanos() / 1_000_000.0;
    }

    public double getAvgCompilationTimeNanos() {
        return runtimeMetrics.getAvgCompilationTimeNanos();
    }

    public double getAvgCompilationTimeMillis() {
        return getAvgCompilationTimeNanos() / 1_000_000.0;
    }

    /** Average score over all recorded scores. */
    public double getAvgScore() {
        return runtimeMetrics.getAvgScore();
    }

    /** Maximum score observed so far. */
    public double getMaxScore() {
        return runtimeMetrics.getMaxScore();
    }

    public double getAvgRuntimeWeight() {
        return runtimeMetrics.getAvgRuntimeWeight();
    }

    public double getMaxRuntimeWeight() {
        return runtimeMetrics.getMaxRuntimeWeight();
    }

    public double getMinRuntimeWeight() {
        return runtimeMetrics.getMinRuntimeWeight();
    }

    public int pairIdx(int i, int j) {
        return coverageMetrics.pairIdx(i, j);
    }

    /** Worker: record one run when you already have the present indices. Increments N. */
    public void addRunFromPresent(int[] present, int m) {
        coverageMetrics.addRunFromPresent(present, m);
    }

    /** Read n_ij = #evaluations where both i and j were present. */
    public long getPairCount(int i, int j) {
        return coverageMetrics.getPairCount(i, j);
    }

    /** Number of feature slots being tracked (excludes sentinel). */
    public int getFeatureSlots() {
        return coverageMetrics.getFeatureSlots();
    }

    /** Snapshot the pair counts array for consistent reporting. */
    public long[] snapshotPairCounts() {
        return coverageMetrics.snapshotPairCounts();
    }

    public long getFeatureCount(int idx) {
        return coverageMetrics.getFeatureCount(idx);
    }

    public void recordChampionAccepted() {
        corpusMetrics.recordChampionAccepted();
    }

    public void recordChampionReplaced() {
        corpusMetrics.recordChampionReplaced();
    }

    public void recordChampionRejected() {
        corpusMetrics.recordChampionRejected();
    }

    public void recordChampionDiscarded() {
        corpusMetrics.recordChampionDiscarded();
    }

    public long getChampionAccepted() {
        return corpusMetrics.getChampionAccepted();
    }

    public long getChampionReplaced() {
        return corpusMetrics.getChampionReplaced();
    }

    public long getChampionRejected() {
        return corpusMetrics.getChampionRejected();
    }

    public long getChampionDiscarded() {
        return corpusMetrics.getChampionDiscarded();
    }

    public void updateCorpusSize(long size) {
        corpusMetrics.updateCorpusSize(size);
    }

    public long getCorpusSize() {
        return corpusMetrics.getCorpusSize();
    }

    public void recordMutationSelection(int timesSelected) {
        mutatorMetrics.recordMutationSelection(timesSelected);
    }

    public long[] snapshotMutationSelectionHistogram() {
        return mutatorMetrics.snapshotMutationSelectionHistogram();
    }

    public void recordMutatorMutationAttempt(MutatorType mutatorType, MutationAttemptStatus status) {
        mutatorMetrics.recordMutatorMutationAttempt(mutatorType, status);
    }

    public void recordMutatorEvaluation(MutatorType mutatorType, EvaluationOutcome outcome) {
        mutatorMetrics.recordMutatorEvaluation(mutatorType, outcome);
    }

    public void recordMutatorTimeout(MutatorType mutatorType) {
        mutatorMetrics.recordMutatorTimeout(mutatorType);
    }

    public void recordMutatorCompileFailure(MutatorType mutatorType) {
        mutatorMetrics.recordMutatorCompileFailure(mutatorType);
    }

    public MutatorStats[] snapshotMutatorStats() {
        return mutatorMetrics.snapshotMutatorStats(MutatorType.values());
    }

    public FinalMetrics snapshotFinalMetrics() {
        long[] pairCountsSnapshot = coverageMetrics.snapshotPairCounts();
        long scored = runtimeMetrics.getScoreCount();
        long uniqueFeatures = coverageMetrics.countUniqueFeatures();
        long uniquePairs = Arrays.stream(pairCountsSnapshot).filter(v -> v > 0L).count();
        return new FinalMetrics(
                sessionCounters.getTotalTestsDispatched(),
                scored,
                scored,
                sessionCounters.getFailedCompilations(),
                sessionCounters.getFoundBugs(),
                uniqueFeatures,
                coverageMetrics.getFeatureSlots(),
                uniquePairs,
                pairCountsSnapshot.length,
                runtimeMetrics.getAvgScore(),
                runtimeMetrics.getMaxScore(),
                corpusMetrics.getCorpusSize(),
                corpusMetrics.getChampionAccepted(),
                corpusMetrics.getChampionReplaced(),
                corpusMetrics.getChampionRejected(),
                corpusMetrics.getChampionDiscarded());
    }

    /** Read N = #evaluations recorded via addRun*. */
    public long getRunCount() {
        return coverageMetrics.getRunCount();
    }

    public static final class MutatorStats {
        public final MutatorType mutatorType;
        public final long timeoutCount;
        public final long compileFailureCount;
        public final long mutationSuccessCount;
        public final long mutationSkipCount;
        public final long mutationFailureCount;
        public final long evaluationImprovedCount;
        public final long evaluationNoChangeCount;
        public final long evaluationBugCount;
        public final long evaluationTimeoutCount;
        public final long evaluationFailureCount;

        MutatorStats(
                MutatorType mutatorType,
                long timeoutCount,
                long compileFailureCount,
                long mutationSuccessCount,
                long mutationSkipCount,
                long mutationFailureCount,
                long evaluationImprovedCount,
                long evaluationNoChangeCount,
                long evaluationBugCount,
                long evaluationTimeoutCount,
                long evaluationFailureCount) {
            this.mutatorType = mutatorType;
            this.timeoutCount = timeoutCount;
            this.compileFailureCount = compileFailureCount;
            this.mutationSuccessCount = mutationSuccessCount;
            this.mutationSkipCount = mutationSkipCount;
            this.mutationFailureCount = mutationFailureCount;
            this.evaluationImprovedCount = evaluationImprovedCount;
            this.evaluationNoChangeCount = evaluationNoChangeCount;
            this.evaluationBugCount = evaluationBugCount;
            this.evaluationTimeoutCount = evaluationTimeoutCount;
            this.evaluationFailureCount = evaluationFailureCount;
        }

        public long mutationAttemptTotal() {
            return mutationSuccessCount + mutationSkipCount + mutationFailureCount;
        }

        public double mutationSuccessRate() {
            long total = mutationAttemptTotal();
            return (total > 0L) ? mutationSuccessCount / (double) total : 0.0;
        }

        public long evaluationTotal() {
            return evaluationImprovedCount
                    + evaluationNoChangeCount
                    + evaluationBugCount
                    + evaluationTimeoutCount
                    + evaluationFailureCount;
        }

        public double evaluationImprovementRate() {
            long total = evaluationTotal();
            return (total > 0L) ? evaluationImprovedCount / (double) total : 0.0;
        }
    }

    public static final class FinalMetrics {
        public final long totalDispatched;
        public final long totalTests;
        public final long scoredTests;
        public final long failedCompilations;
        public final long foundBugs;
        public final long uniqueFeatures;
        public final long totalFeatures;
        public final long uniquePairs;
        public final long totalPairs;
        public final double avgScore;
        public final double maxScore;
        public final long corpusSize;
        public final long corpusAccepted;
        public final long corpusReplaced;
        public final long corpusRejected;
        public final long corpusDiscarded;

        public FinalMetrics(long totalDispatched,
                long totalTests,
                long scoredTests,
                long failedCompilations,
                long foundBugs,
                long uniqueFeatures,
                long totalFeatures,
                long uniquePairs,
                long totalPairs,
                double avgScore,
                double maxScore,
                long corpusSize,
                long corpusAccepted,
                long corpusReplaced,
                long corpusRejected,
                long corpusDiscarded) {
            this.totalDispatched = totalDispatched;
            this.totalTests = totalTests;
            this.scoredTests = scoredTests;
            this.failedCompilations = failedCompilations;
            this.foundBugs = foundBugs;
            this.uniqueFeatures = uniqueFeatures;
            this.totalFeatures = totalFeatures;
            this.uniquePairs = uniquePairs;
            this.totalPairs = totalPairs;
            this.avgScore = avgScore;
            this.maxScore = maxScore;
            this.corpusSize = corpusSize;
            this.corpusAccepted = corpusAccepted;
            this.corpusReplaced = corpusReplaced;
            this.corpusRejected = corpusRejected;
            this.corpusDiscarded = corpusDiscarded;
        }

        public double featureCoverageRatio() {
            return totalFeatures == 0L ? 0.0 : (double) uniqueFeatures / (double) totalFeatures;
        }
    }
}
