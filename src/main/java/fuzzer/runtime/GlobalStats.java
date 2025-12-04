package fuzzer.runtime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

import fuzzer.mutators.MutatorType;
import fuzzer.runtime.scheduling.MutatorScheduler.EvaluationOutcome;
import fuzzer.runtime.scheduling.MutatorScheduler.MutationAttemptStatus;
import fuzzer.util.OptimizationVector;



public class GlobalStats {
    // moving counts for rarity
    private ConcurrentHashMap<String, LongAdder> opFreq = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, LongAdder> opPairFreq = new ConcurrentHashMap<>();
    private final LongAdder totalTestsDispatched = new LongAdder();
    private final LongAdder totalTestsEvaluated = new LongAdder();
    private final LongAdder totalTestsExecuted = new LongAdder();
    private final LongAdder failedCompilations = new LongAdder();
    private final LongAdder foundBugs = new LongAdder();
    private final LongAdder jitTimeouts = new LongAdder();
    private final LongAdder intTimeouts = new LongAdder();
    private final LongAdder uniqueBugBuckets = new LongAdder();
    private final ConcurrentHashMap<String, Boolean> bugBucketIds = new ConcurrentHashMap<>();

    private final int p;                                  // number of optimizations (fixed)
    private final int[] rowOffset;                        // (i,j)->flat upper-tri index
    private final java.util.concurrent.atomic.AtomicLongArray pairCounts; // n_ij for i<j
    private final java.util.concurrent.atomic.LongAdder evaluations = new java.util.concurrent.atomic.LongAdder(); // N
    private final AtomicLongArray featureCounts;          // individual feature coverage
    private final LongAdder championAccepted = new LongAdder();
    private final LongAdder championReplaced = new LongAdder();
    private final LongAdder championRejected = new LongAdder();
    private final LongAdder championDiscarded = new LongAdder();
    private final AtomicLong corpusSize = new AtomicLong();

    private static final double MUTATOR_BASE_WEIGHT = 1.0;
    private static final double MUTATOR_MIN_WEIGHT = 0.1;
    private static final double MUTATOR_MAX_WEIGHT = 5.0;

    private final DoubleAdder[] mutatorRewardSums;
    private final LongAdder[] mutatorAttemptCounts;
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




    // running maxima for normalization
    private final ConcurrentHashMap<String, Double> opMax = new ConcurrentHashMap<>(); // per-op max count seen
    private final ConcurrentHashMap<String, Double> opPairMax = new ConcurrentHashMap<>();
    final AtomicDouble interactionScoreMax = new AtomicDouble(1e-9);


    // === new metrics fields ===
    private final LongAdder accumulatedIntExecNanos = new LongAdder();
    private final LongAdder accumulatedJitExecNanos = new LongAdder();
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
        this.pairCounts = new java.util.concurrent.atomic.AtomicLongArray(nPairs);
        this.rowOffset = new int[p];
        this.featureCounts = new AtomicLongArray(p);
        for (int i = 0; i < p; i++) {
            rowOffset[i] = (i * (i + 1)) / 2;
        }
        MutatorType[] mutatorTypes = MutatorType.values();
        this.mutatorRewardSums = new DoubleAdder[mutatorTypes.length];
        this.mutatorAttemptCounts = new LongAdder[mutatorTypes.length];
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
            mutatorRewardSums[i] = new DoubleAdder();
            mutatorAttemptCounts[i] = new LongAdder();
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
        totalTestsExecuted.increment();
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


    public ConcurrentHashMap<String, LongAdder> getOpFreqMap() {
        return opFreq;
    }

    public ConcurrentHashMap<String, LongAdder> getOpPairFreqMap() {
        return opPairFreq;
    }

    public ConcurrentHashMap<String, Double> getOpMaxMap() {
        return opMax;
    }
    public ConcurrentHashMap<String, Double> getOpPairMaxMap() {
        return opPairMax;
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

    public long getTotalTestsExecuted() {
        return totalTestsExecuted.sum();
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
        long n = totalTestsExecuted.sum();
        return (n == 0) ? 0.0 : accumulatedIntExecNanos.sum() / (double) n;
    }

    public double getAvgJitExecTimeNanos() {
        long n = totalTestsExecuted.sum();
        return (n == 0) ? 0.0 : accumulatedJitExecNanos.sum() / (double) n;
    }

    public double getAvgIntExecTimeMillis() {
        return getAvgIntExecTimeNanos() / 1_000_000.0;
    }

    public double getAvgJitExecTimeMillis() {
        return getAvgJitExecTimeNanos() / 1_000_000.0;
    }

    public double getAvgExecTimeNanos() {
        long n = totalTestsExecuted.sum();
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

    public void addRunFromCounts(int[] counts) {
        int[] present = new int[p];
        int m = 0;
        for (int i = 0; i < p; i++) {
            if (counts[i] > 0) {
                present[m++] = i;
            }
        }
        addRunFromPresent(present, m);
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

    public void addRunFromPairIndices(int[] pairIndices) {
        for (int k = 0; k < pairIndices.length; k++) {
            int idx = pairIndices[k];
            pairCounts.addAndGet(idx, 1L);
        }
        evaluations.increment();
    }

    public void incrementPair(int i, int j) {
        pairCounts.addAndGet(pairIdx(i, j), 1L);
    }
    
    /** Read n_ij = #evaluations where both i and j were present. */
    public long getPairCount(int i, int j) {
        if (i == j) return 0L;
        return pairCounts.get(pairIdx(i, j));
    }

    public long getFeatureCount(int idx) {
        if (idx < 0 || idx >= p) {
            return 0L;
        }
        return featureCounts.get(idx);
    }

    public boolean hasSeenFeature(int idx) {
        if (idx < 0 || idx >= p) {
            return false;
        }
        return featureCounts.get(idx) > 0L;
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

    public void recordMutatorReward(MutatorType mutatorType, double reward) {
        if (mutatorType == null || mutatorType == MutatorType.SEED) {
            return;
        }
        int index = mutatorType.ordinal();
        if (index < 0 || index >= mutatorRewardSums.length) {
            return;
        }
        mutatorRewardSums[index].add(reward);
        mutatorAttemptCounts[index].increment();
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
            long attempts = mutatorAttemptCounts[i].sum();
            double reward = mutatorRewardSums[i].sum();
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
                    attempts,
                    reward,
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

    public double[] getMutatorWeights(MutatorType[] mutators) {
        if (mutators == null || mutators.length == 0) {
            return new double[0];
        }
        double[] weights = new double[mutators.length];
        for (int i = 0; i < mutators.length; i++) {
            MutatorType mutator = mutators[i];
            if (mutator == null) {
                weights[i] = MUTATOR_BASE_WEIGHT;
                continue;
            }
            int index = mutator.ordinal();
            if (index < 0 || index >= mutatorRewardSums.length) {
                weights[i] = MUTATOR_BASE_WEIGHT;
                continue;
            }
            long attempts = mutatorAttemptCounts[index].sum();
            double rewardSum = mutatorRewardSums[index].sum();
            double average = (attempts > 0L) ? rewardSum / (double) attempts : 0.0;
            if (!Double.isFinite(average)) {
                average = 0.0;
            }
            double weight = MUTATOR_BASE_WEIGHT + average;
            if (!Double.isFinite(weight)) {
                weight = MUTATOR_BASE_WEIGHT;
            }
            if (weight < MUTATOR_MIN_WEIGHT) {
                weight = MUTATOR_MIN_WEIGHT;
            } else if (weight > MUTATOR_MAX_WEIGHT) {
                weight = MUTATOR_MAX_WEIGHT;
            }
            weights[i] = weight;
        }
        return weights;
    }

    public static final class MutatorStats {
        public final MutatorType mutatorType;
        public final long attempts;
        public final double rewardSum;
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
                long attempts,
                double rewardSum,
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
            this.attempts = attempts;
            this.rewardSum = rewardSum;
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

        public double averageReward() {
            return (attempts > 0L) ? rewardSum / (double) attempts : 0.0;
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
        long totalTests = totalTestsExecuted.sum();
        long scored = scoreCount.sum();
        long bugs = foundBugs.sum();
        long failed = failedCompilations.sum();
        long uniqueFeatures = 0L;
        for (int i = 0; i < featureCounts.length(); i++) {
            if (featureCounts.get(i) > 0L) {
                uniqueFeatures++;
            }
        }
        long featureSlots = featureCounts.length();
        long uniquePairs = 0L;
        int pairLen = pairCounts.length();
        for (int i = 0; i < pairLen; i++) {
            if (pairCounts.get(i) > 0L) {
                uniquePairs++;
            }
        }
        long totalPairs = pairLen;
        double avgScore = getAvgScore();
        double maxScore = getMaxScore();
        long corpusSizeSnapshot = getCorpusSize();
        long corpusAccepted = getChampionAccepted();
        long corpusReplaced = getChampionReplaced();
        long corpusRejected = getChampionRejected();
        long corpusDiscarded = getChampionDiscarded();
        return new FinalMetrics(
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

    public void recordBestVectorFeatures(int[] counts) {
        if (counts == null) return;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] <= 0) continue;
            OptimizationVector.Features feature = OptimizationVector.FeatureFromIndex(i);
            String featureName = OptimizationVector.FeatureName(feature);
            LongAdder adder = opFreq.get(featureName);
            if (adder == null) {
                adder = new LongAdder();
                LongAdder existing = opFreq.putIfAbsent(featureName, adder);
                if (existing != null) {
                    adder = existing;
                }
            }
            adder.increment();
        }
    }

    /** Atomic double implementation using AtomicLong for bitwise storage. */
    final class AtomicDouble {
        private final AtomicLong bits;

        public AtomicDouble(double initialValue) {
            this.bits = new AtomicLong(Double.doubleToRawLongBits(initialValue));
        }

        public final double get() {
            return Double.longBitsToDouble(bits.get());
        }

        public final void set(double newValue) {
            bits.set(Double.doubleToRawLongBits(newValue));
        }

        public final double getAndSet(double newValue) {
            long newBits = Double.doubleToRawLongBits(newValue);
            return Double.longBitsToDouble(bits.getAndSet(newBits));
        }

        public final boolean compareAndSet(double expect, double update) {
            return bits.compareAndSet(
                Double.doubleToRawLongBits(expect),
                Double.doubleToRawLongBits(update));
        }

        public final double getAndAdd(double delta) {
            while (true) {
            long cur = bits.get();
            double curVal = Double.longBitsToDouble(cur);
            double nextVal = curVal + delta;
            long next = Double.doubleToRawLongBits(nextVal);
            if (bits.compareAndSet(cur, next)) return curVal;
            }
        }

        public final double addAndGet(double delta) {
            while (true) {
            long cur = bits.get();
            double nextVal = Double.longBitsToDouble(cur) + delta;
            long next = Double.doubleToRawLongBits(nextVal);
            if (bits.compareAndSet(cur, next)) return nextVal;
            }
        }

        public final double updateAndGet(DoubleUnaryOperator updateFn) {
            while (true) {
            long cur = bits.get();
            double curVal = Double.longBitsToDouble(cur);
            double nextVal = updateFn.applyAsDouble(curVal);
            long next = Double.doubleToRawLongBits(nextVal);
            if (bits.compareAndSet(cur, next)) return nextVal;
            }
        }

        public final double accumulateAndGet(double x, DoubleBinaryOperator op) {
            while (true) {
            long cur = bits.get();
            double curVal = Double.longBitsToDouble(cur);
            double nextVal = op.applyAsDouble(curVal, x);
            long next = Double.doubleToRawLongBits(nextVal);
            if (bits.compareAndSet(cur, next)) return nextVal;
            }
        }
    }

    /*
     * Final Metrics to log at the end of a fuzzing session.
     */

    public static final class FinalMetrics {
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

        public FinalMetrics(long totalTests,
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

        public double pairCoverageRatio() {
            return totalPairs == 0L ? 0.0 : (double) uniquePairs / (double) totalPairs;
        }
    }
}
