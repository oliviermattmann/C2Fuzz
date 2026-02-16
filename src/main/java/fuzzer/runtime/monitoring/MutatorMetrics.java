package fuzzer.runtime.monitoring;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

import fuzzer.mutators.MutatorType;
import fuzzer.runtime.scheduling.MutatorScheduler.EvaluationOutcome;
import fuzzer.runtime.scheduling.MutatorScheduler.MutationAttemptStatus;

final class MutatorMetrics {
    private static final int MUTATION_SELECTION_BUCKETS = 64;

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

    private final AtomicLongArray mutationSelectionHistogram =
            new AtomicLongArray(MUTATION_SELECTION_BUCKETS);

    MutatorMetrics(MutatorType[] mutatorTypes) {
        int size = mutatorTypes.length;
        this.mutatorTimeoutCounts = new LongAdder[size];
        this.mutatorCompileFailCounts = new LongAdder[size];
        this.mutatorMutationSuccessCounts = new LongAdder[size];
        this.mutatorMutationSkipCounts = new LongAdder[size];
        this.mutatorMutationFailureCounts = new LongAdder[size];
        this.mutatorEvaluationImprovedCounts = new LongAdder[size];
        this.mutatorEvaluationNoChangeCounts = new LongAdder[size];
        this.mutatorEvaluationBugCounts = new LongAdder[size];
        this.mutatorEvaluationTimeoutCounts = new LongAdder[size];
        this.mutatorEvaluationFailureCounts = new LongAdder[size];
        for (int i = 0; i < size; i++) {
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

    void recordMutationSelection(int timesSelected) {
        int idx = Math.max(0, Math.min(timesSelected, MUTATION_SELECTION_BUCKETS - 1));
        mutationSelectionHistogram.incrementAndGet(idx);
    }

    long[] snapshotMutationSelectionHistogram() {
        long[] snapshot = new long[MUTATION_SELECTION_BUCKETS];
        for (int i = 0; i < MUTATION_SELECTION_BUCKETS; i++) {
            snapshot[i] = mutationSelectionHistogram.get(i);
        }
        return snapshot;
    }

    void recordMutatorMutationAttempt(MutatorType mutatorType, MutationAttemptStatus status) {
        if (mutatorType == null || mutatorType == MutatorType.SEED || status == null) {
            return;
        }
        int index = mutatorType.ordinal();
        if (!validIndex(index, mutatorMutationSuccessCounts.length)) {
            return;
        }
        switch (status) {
            case SUCCESS -> mutatorMutationSuccessCounts[index].increment();
            case NOT_APPLICABLE -> mutatorMutationSkipCounts[index].increment();
            case FAILED -> mutatorMutationFailureCounts[index].increment();
        }
    }

    void recordMutatorEvaluation(MutatorType mutatorType, EvaluationOutcome outcome) {
        if (mutatorType == null || mutatorType == MutatorType.SEED || outcome == null) {
            return;
        }
        int index = mutatorType.ordinal();
        if (!validIndex(index, mutatorEvaluationImprovedCounts.length)) {
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

    void recordMutatorTimeout(MutatorType mutatorType) {
        if (mutatorType == null || mutatorType == MutatorType.SEED) {
            return;
        }
        int index = mutatorType.ordinal();
        if (!validIndex(index, mutatorTimeoutCounts.length)) {
            return;
        }
        mutatorTimeoutCounts[index].increment();
    }

    void recordMutatorCompileFailure(MutatorType mutatorType) {
        if (mutatorType == null || mutatorType == MutatorType.SEED) {
            return;
        }
        int index = mutatorType.ordinal();
        if (!validIndex(index, mutatorCompileFailCounts.length)) {
            return;
        }
        mutatorCompileFailCounts[index].increment();
    }

    GlobalStats.MutatorStats[] snapshotMutatorStats(MutatorType[] types) {
        GlobalStats.MutatorStats[] stats = new GlobalStats.MutatorStats[types.length];
        for (int i = 0; i < types.length; i++) {
            stats[i] = new GlobalStats.MutatorStats(
                    types[i],
                    mutatorTimeoutCounts[i].sum(),
                    mutatorCompileFailCounts[i].sum(),
                    mutatorMutationSuccessCounts[i].sum(),
                    mutatorMutationSkipCounts[i].sum(),
                    mutatorMutationFailureCounts[i].sum(),
                    mutatorEvaluationImprovedCounts[i].sum(),
                    mutatorEvaluationNoChangeCounts[i].sum(),
                    mutatorEvaluationBugCounts[i].sum(),
                    mutatorEvaluationTimeoutCounts[i].sum(),
                    mutatorEvaluationFailureCounts[i].sum());
        }
        return stats;
    }

    private static boolean validIndex(int index, int length) {
        return index >= 0 && index < length;
    }
}
