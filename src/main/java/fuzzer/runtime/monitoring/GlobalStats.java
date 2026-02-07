package fuzzer.runtime.monitoring;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

import fuzzer.mutators.MutatorType;
import fuzzer.runtime.scheduling.MutatorScheduler.EvaluationOutcome;
import fuzzer.runtime.scheduling.MutatorScheduler.MutationAttemptStatus;



public final class GlobalStats {
    private final LongAdder totalTestsDispatched = new LongAdder();
    private final LongAdder totalTestsEvaluated = new LongAdder();
    private final LongAdder failedCompilations = new LongAdder();
    private final LongAdder foundBugs = new LongAdder();
    private final LongAdder jitTimeouts = new LongAdder();
    private final LongAdder intTimeouts = new LongAdder();
    private final LongAdder uniqueBugBuckets = new LongAdder();
    private final ConcurrentHashMap<String, Boolean> bugBucketIds = new ConcurrentHashMap<>();

    private final int p;                                  // number of optimizations (fixed)
    private final int[] rowOffset;                        // (i,j)->flat upper-tri index
    private final AtomicLongArray pairCounts; // n_ij for i<j
    private final LongAdder evaluations = new LongAdder(); // N
    private final AtomicLongArray featureCounts;          // individual feature coverage
    private final LongAdder championAccepted = new LongAdder();
    private final LongAdder championReplaced = new LongAdder();
    private final LongAdder championRejected = new LongAdder();
    private final LongAdder championDiscarded = new LongAdder();
    private final AtomicLong corpusSize = new AtomicLong();

    private final LongAdder[] mutatorTimeoutCounts;
    private final LongAdder[] mutatorCompileFailCounts;
    private final LongAdder[] mutatorMutationSuccessCounts;
    private final LongAdder[] mutatorMutationSkipCounts;
    private final LongAdder[] mutatorMutationFailureCounts;
    private final LongAdder[] mutatorEvaluationImprovedCounts;
    private final LongAdder[] mutatorEvaluationNoChangeCounts;
    private final LongAdder[] mutatorEvaluationBugCounts;
    private final LongAdder[] mutatorEvaluationTimeoutCounts;
    private final LongAdder[] mutatorEvaluationFailureCounts;


    private static final int MUTATION_SELECTION_BUCKETS = 64;
    private final AtomicLongArray mutationSelectionHistogram =
            new AtomicLongArray(MUTATION_SELECTION_BUCKETS);


    // === new metrics fields ===
    private final LongAdder accumulatedIntExecNanos = new LongAdder();
    private final LongAdder accumulatedJitExecNanos = new LongAdder();
    private final LongAdder execCount = new LongAdder();
    private final LongAdder accumulatedCompilationNanos = new LongAdder();
    private final LongAdder compilationCount = new LongAdder();
    private final LongAdder scoreCount = new LongAdder();   // number of scored tests
    private final DoubleAdder scoreSum  = new DoubleAdder();// sum of scores
    private final DoubleAccumulator scoreMax =
            new DoubleAccumulator(Math::max, Double.NEGATIVE_INFINITY);
    private final DoubleAdder runtimeWeightSum = new DoubleAdder();
    private final DoubleAccumulator runtimeWeightMax =
            new DoubleAccumulator(Math::max, Double.NEGATIVE_INFINITY);
    private final DoubleAccumulator runtimeWeightMin =
            new DoubleAccumulator(Math::min, Double.POSITIVE_INFINITY);
    private final LongAdder runtimeWeightCount = new LongAdder();


    public GlobalStats(int numOptimizations) {
        this.p = numOptimizations;
        int nPairs = (p * (p - 1)) / 2;
        this.pairCounts = new AtomicLongArray(nPairs);
        this.rowOffset = new int[p];
        this.featureCounts = new AtomicLongArray(p);
        for (int i = 0; i < p; i++) {
            // Number of entries before row i in the upper triangle (excluding diagonal):
            // sum_{k=0}^{i-1} (p - k - 1) = i*(p-1) - i*(i-1)/2.
            rowOffset[i] = i * (p - 1) - (i * (i - 1)) / 2;
        }
        MutatorType[] mutatorTypes = MutatorType.values();
        this.mutatorTimeoutCounts = new LongAdder[mutatorTypes.length];
        this.mutatorCompileFailCounts = new LongAdder[mutatorTypes.length];
        this.mutatorMutationSuccessCounts = new LongAdder[mutatorTypes.length];
        this.mutatorMutationSkipCounts = new LongAdder[mutatorTypes.length];
        this.mutatorMutationFailureCounts = new LongAdder[mutatorTypes.length];
        this.mutatorEvaluationImprovedCounts = new LongAdder[mutatorTypes.length];
        this.mutatorEvaluationNoChangeCounts = new LongAdder[mutatorTypes.length];
        this.mutatorEvaluationBugCounts = new LongAdder[mutatorTypes.length];
        this.mutatorEvaluationTimeoutCounts = new LongAdder[mutatorTypes.length];
        this.mutatorEvaluationFailureCounts = new LongAdder[mutatorTypes.length];
        for (int i = 0; i < mutatorTypes.length; i++) {
            mutatorTimeoutCounts[i] = new LongAdder();
            mutatorCompileFailCounts[i] = new LongAdder();
            mutatorMutationSuccessCounts[i] = new LongAdder();
            mutatorMutationSkipCounts[i] = new LongAdder();
            mutatorMutationFailureCounts[i] = new LongAdder();
            mutatorEvaluationImprovedCounts[i] = new LongAdder();
            mutatorEvaluationNoChangeCounts[i] = new LongAdder();
            mutatorEvaluationBugCounts[i] = new LongAdder();
            mutatorEvaluationTimeoutCounts[i] = new LongAdder();
            mutatorEvaluationFailureCounts[i] = new LongAdder();
        }
    }

    /** Record that a test was pulled from the execution queue. */
    public void recordTestDispatched() {
        totalTestsDispatched.increment();
    }

    /** Record that a test reached the evaluator. */
    public void recordTestEvaluated() {
        totalTestsEvaluated.increment();
    }

    /** Call this from worker threads when a test finishes. */
    public void recordTest(double score, double runtimeWeight) {
        scoreCount.increment();
        scoreSum.add(score);
        scoreMax.accumulate(score);
        if (Double.isFinite(runtimeWeight) && runtimeWeight > 0.0) {
            runtimeWeightCount.increment();
            runtimeWeightSum.add(runtimeWeight);
            runtimeWeightMax.accumulate(runtimeWeight);
            runtimeWeightMin.accumulate(runtimeWeight);
        }
    }

    public void incrementJitTimeouts() {
        jitTimeouts.increment();
    }

    public void incrementIntTimeouts() {
        intTimeouts.increment();
    }

    public void incrementFailedCompilations() {
        failedCompilations.increment();
    }

    public void incrementFoundBugs() {
        foundBugs.increment();
    }

    public boolean recordBugBucket(String bucketId) {
        if (bucketId == null || bucketId.isBlank()) {
            return false;
        }
        Boolean previous = bugBucketIds.putIfAbsent(bucketId, Boolean.TRUE);
        if (previous == null) {
            uniqueBugBuckets.increment();
            return true;
        }
        return false;
    }

    public long getUniqueBugBuckets() {
        return uniqueBugBuckets.sum();
    }

    public void recordExecTimesNanos(long intNanos, long jitNanos) {
        accumulatedIntExecNanos.add(intNanos);
        accumulatedJitExecNanos.add(jitNanos);
        execCount.increment();
    }

    public void recordCompilationTimeNanos(long nanos) {
        accumulatedCompilationNanos.add(nanos);
        compilationCount.increment();
    }

    public long getTotalTestsDispatched() {
        return totalTestsDispatched.sum();
    }

    public long getTotalTestsEvaluated() {
        return totalTestsEvaluated.sum();
    }

    public long getFailedCompilations() {
        return failedCompilations.sum();
    }

    public long getFoundBugs() {
        return foundBugs.sum();
    }

    public long getJitTimeouts() {
        return jitTimeouts.sum();
    }

    public long getIntTimeouts() {
        return intTimeouts.sum();
    }
    
    public double getAvgIntExecTimeNanos() {
        long n = execCount.sum();
        return (n == 0) ? 0.0 : accumulatedIntExecNanos.sum() / (double) n;
    }

    public double getAvgJitExecTimeNanos() {
        long n = execCount.sum();
        return (n == 0) ? 0.0 : accumulatedJitExecNanos.sum() / (double) n;
    }

    public double getAvgIntExecTimeMillis() {
        return getAvgIntExecTimeNanos() / 1_000_000.0;
    }

    public double getAvgJitExecTimeMillis() {
        return getAvgJitExecTimeNanos() / 1_000_000.0;
    }

    public double getAvgExecTimeNanos() {
        long n = execCount.sum();
        if (n == 0) {
            return 0.0;
        }
        double total = accumulatedIntExecNanos.sum() + accumulatedJitExecNanos.sum();
        return total / (2.0 * n);
    }
    
    public double getAvgExecTimeMillis() {
        return getAvgExecTimeNanos() / 1_000_000.0;
    }

    public double getAvgCompilationTimeNanos() {
        long n = compilationCount.sum();
        return (n == 0) ? 0.0 : accumulatedCompilationNanos.sum() / (double) n;
    }

    public double getAvgCompilationTimeMillis() {
        return getAvgCompilationTimeNanos() / 1_000_000.0;
    }

    /** Average score over all recorded scores. */
    public double getAvgScore() {
        long n = scoreCount.sum();
        return (n == 0) ? 0.0 : scoreSum.sum() / n;
    }

    /** Maximum score observed so far. */
    public double getMaxScore() {
        double m = scoreMax.get();
        return (m == Double.NEGATIVE_INFINITY) ? 0.0 : m;
    }

    public double getAvgRuntimeWeight() {
        long n = runtimeWeightCount.sum();
        return (n == 0L) ? 0.0 : runtimeWeightSum.sum() / (double) n;
    }

    public double getMaxRuntimeWeight() {
        double max = runtimeWeightMax.get();
        return (max == Double.NEGATIVE_INFINITY) ? 0.0 : max;
    }

    public double getMinRuntimeWeight() {
        double min = runtimeWeightMin.get();
        return (min == Double.POSITIVE_INFINITY) ? 0.0 : min;
    }


    public int pairIdx(int i, int j) {
        if (i == j) throw new IllegalArgumentException("i == j");
        if (i > j) { int t = i; i = j; j = t; }
        return rowOffset[i] + (j - i - 1);
    }
    
    /** Worker: record one run when you already have the present indices. Increments N. */
    public void addRunFromPresent(int[] present, int m) {
        for (int idx = 0; idx < m; idx++) {
            featureCounts.incrementAndGet(present[idx]);
        }
        for (int a = 0; a < m; a++) {
            int i = present[a];
            for (int b = a + 1; b < m; b++) {
                int j = present[b];
                pairCounts.addAndGet(pairIdx(i, j), 1L);
            }
        }
        evaluations.increment();
    }
    
    /** Read n_ij = #evaluations where both i and j were present. */
    public long getPairCount(int i, int j) {
        if (i == j) return 0L;
        return pairCounts.get(pairIdx(i, j));
    }

    /** Number of feature slots being tracked (excludes sentinel). */
    public int getFeatureSlots() {
        return featureCounts.length();
    }

    /** Snapshot the pair counts array for consistent reporting. */
    public long[] snapshotPairCounts() {
        int len = pairCounts.length();
        long[] copy = new long[len];
        for (int i = 0; i < len; i++) {
            copy[i] = pairCounts.get(i);
        }
        return copy;
    }

    public long getFeatureCount(int idx) {
        if (idx < 0 || idx >= p) {
            return 0L;
        }
        return featureCounts.get(idx);
    }
    
    public void recordChampionAccepted() {
        championAccepted.increment();
    }

    public void recordChampionReplaced() {
        championReplaced.increment();
    }

    public void recordChampionRejected() {
        championRejected.increment();
    }

    public void recordChampionDiscarded() {
        championDiscarded.increment();
    }

    public long getChampionAccepted() {
        return championAccepted.sum();
    }

    public long getChampionReplaced() {
        return championReplaced.sum();
    }

    public long getChampionRejected() {
        return championRejected.sum();
    }

    public long getChampionDiscarded() {
        return championDiscarded.sum();
    }

    public void updateCorpusSize(long size) {
        long normalized = Math.max(0L, size);
        corpusSize.set(normalized);
    }

    public long getCorpusSize() {
        long size = corpusSize.get();
        return (size < 0L) ? 0L : size;
    }

    public void recordMutationSelection(int timesSelected) {
        int idx = Math.max(0, Math.min(timesSelected, MUTATION_SELECTION_BUCKETS - 1));
        mutationSelectionHistogram.incrementAndGet(idx);
    }

    public long[] snapshotMutationSelectionHistogram() {
        long[] snapshot = new long[MUTATION_SELECTION_BUCKETS];
        for (int i = 0; i < MUTATION_SELECTION_BUCKETS; i++) {
            snapshot[i] = mutationSelectionHistogram.get(i);
        }
        return snapshot;
    }

    public void recordMutatorMutationAttempt(MutatorType mutatorType, MutationAttemptStatus status) {
        if (mutatorType == null || mutatorType == MutatorType.SEED || status == null) {
            return;
        }
        int index = mutatorType.ordinal();
        if (index < 0 || index >= mutatorMutationSuccessCounts.length) {
            return;
        }
        switch (status) {
            case SUCCESS -> mutatorMutationSuccessCounts[index].increment();
            case NOT_APPLICABLE -> mutatorMutationSkipCounts[index].increment();
            case FAILED -> mutatorMutationFailureCounts[index].increment();
        }
    }

    public void recordMutatorEvaluation(MutatorType mutatorType, EvaluationOutcome outcome) {
        if (mutatorType == null || mutatorType == MutatorType.SEED || outcome == null) {
            return;
        }
        int index = mutatorType.ordinal();
        if (index < 0 || index >= mutatorEvaluationImprovedCounts.length) {
            return;
        }
        switch (outcome) {
            case IMPROVED -> mutatorEvaluationImprovedCounts[index].increment();
            case NO_IMPROVEMENT -> mutatorEvaluationNoChangeCounts[index].increment();
            case BUG -> mutatorEvaluationBugCounts[index].increment();
            case TIMEOUT -> mutatorEvaluationTimeoutCounts[index].increment();
            case FAILURE -> mutatorEvaluationFailureCounts[index].increment();
        }
    }

    public void recordMutatorTimeout(MutatorType mutatorType) {
        if (mutatorType == null || mutatorType == MutatorType.SEED) {
            return;
        }
        int index = mutatorType.ordinal();
        if (index < 0 || index >= mutatorTimeoutCounts.length) {
            return;
        }
        mutatorTimeoutCounts[index].increment();
    }

    public void recordMutatorCompileFailure(MutatorType mutatorType) {
        if (mutatorType == null || mutatorType == MutatorType.SEED) {
            return;
        }
        int index = mutatorType.ordinal();
        if (index < 0 || index >= mutatorCompileFailCounts.length) {
            return;
        }
        mutatorCompileFailCounts[index].increment();
    }

    public MutatorStats[] snapshotMutatorStats() {
        MutatorType[] types = MutatorType.values();
        MutatorStats[] stats = new MutatorStats[types.length];
        for (int i = 0; i < types.length; i++) {
            long timeouts = mutatorTimeoutCounts[i].sum();
            long compileFails = mutatorCompileFailCounts[i].sum();
            long mutationSuccess = mutatorMutationSuccessCounts[i].sum();
            long mutationSkip = mutatorMutationSkipCounts[i].sum();
            long mutationFailure = mutatorMutationFailureCounts[i].sum();
            long evaluationImproved = mutatorEvaluationImprovedCounts[i].sum();
            long evaluationNoChange = mutatorEvaluationNoChangeCounts[i].sum();
            long evaluationBugs = mutatorEvaluationBugCounts[i].sum();
            long evaluationTimeouts = mutatorEvaluationTimeoutCounts[i].sum();
            long evaluationFailures = mutatorEvaluationFailureCounts[i].sum();
            stats[i] = new MutatorStats(
                    types[i],
                    timeouts,
                    compileFails,
                    mutationSuccess,
                    mutationSkip,
                    mutationFailure,
                    evaluationImproved,
                    evaluationNoChange,
                    evaluationBugs,
                    evaluationTimeouts,
                    evaluationFailures);
        }
        return stats;
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

    public FinalMetrics snapshotFinalMetrics() {
        long[] pairCountsSnapshot = snapshotPairCounts();
        long dispatched = totalTestsDispatched.sum();
        long scored = scoreCount.sum();
        long totalTests = scored;
        long bugs = foundBugs.sum();
        long failed = failedCompilations.sum();
        long uniqueFeatures = 0L;
        for (int i = 0; i < featureCounts.length(); i++) {
            if (featureCounts.get(i) > 0L) {
                uniqueFeatures++;
            }
        }
        long featureSlots = featureCounts.length();
        long uniquePairs = java.util.Arrays.stream(pairCountsSnapshot).filter(v -> v > 0L).count();
        long totalPairs = pairCountsSnapshot.length;
        double avgScore = getAvgScore();
        double maxScore = getMaxScore();
        long corpusSizeSnapshot = getCorpusSize();
        long corpusAccepted = getChampionAccepted();
        long corpusReplaced = getChampionReplaced();
        long corpusRejected = getChampionRejected();
        long corpusDiscarded = getChampionDiscarded();
        return new FinalMetrics(
                dispatched,
                totalTests,
                scored,
                failed,
                bugs,
                uniqueFeatures,
                featureSlots,
                uniquePairs,
                totalPairs,
                avgScore,
                maxScore,
                corpusSizeSnapshot,
                corpusAccepted,
                corpusReplaced,
                corpusRejected,
                corpusDiscarded);
    }
    
    /** Read N = #evaluations recorded via addRun*. */
    public long getRunCount() {
        return evaluations.sum();
    }

    /*
     * Final Metrics to log at the end of a fuzzing session.
     */

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
