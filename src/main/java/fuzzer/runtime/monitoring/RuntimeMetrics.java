package fuzzer.runtime.monitoring;

import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

final class RuntimeMetrics {
    private final LongAdder accumulatedIntExecNanos = new LongAdder();
    private final LongAdder accumulatedJitExecNanos = new LongAdder();
    private final LongAdder execCount = new LongAdder();
    private final LongAdder accumulatedCompilationNanos = new LongAdder();
    private final LongAdder compilationCount = new LongAdder();
    private final LongAdder scoreCount = new LongAdder();
    private final DoubleAdder scoreSum = new DoubleAdder();
    private final DoubleAccumulator scoreMax = new DoubleAccumulator(Math::max, Double.NEGATIVE_INFINITY);
    private final DoubleAdder runtimeWeightSum = new DoubleAdder();
    private final DoubleAccumulator runtimeWeightMax = new DoubleAccumulator(Math::max, Double.NEGATIVE_INFINITY);
    private final DoubleAccumulator runtimeWeightMin = new DoubleAccumulator(Math::min, Double.POSITIVE_INFINITY);
    private final LongAdder runtimeWeightCount = new LongAdder();

    void recordTest(double score, double runtimeWeight) {
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

    void recordExecTimesNanos(long intNanos, long jitNanos) {
        accumulatedIntExecNanos.add(intNanos);
        accumulatedJitExecNanos.add(jitNanos);
        execCount.increment();
    }

    void recordCompilationTimeNanos(long nanos) {
        accumulatedCompilationNanos.add(nanos);
        compilationCount.increment();
    }

    long getScoreCount() {
        return scoreCount.sum();
    }

    double getAvgIntExecTimeNanos() {
        long n = execCount.sum();
        return (n == 0) ? 0.0 : accumulatedIntExecNanos.sum() / (double) n;
    }

    double getAvgJitExecTimeNanos() {
        long n = execCount.sum();
        return (n == 0) ? 0.0 : accumulatedJitExecNanos.sum() / (double) n;
    }

    double getAvgExecTimeNanos() {
        long n = execCount.sum();
        if (n == 0) {
            return 0.0;
        }
        double total = accumulatedIntExecNanos.sum() + accumulatedJitExecNanos.sum();
        return total / (2.0 * n);
    }

    double getAvgCompilationTimeNanos() {
        long n = compilationCount.sum();
        return (n == 0) ? 0.0 : accumulatedCompilationNanos.sum() / (double) n;
    }

    double getAvgScore() {
        long n = scoreCount.sum();
        return (n == 0) ? 0.0 : scoreSum.sum() / n;
    }

    double getMaxScore() {
        double m = scoreMax.get();
        return (m == Double.NEGATIVE_INFINITY) ? 0.0 : m;
    }

    double getAvgRuntimeWeight() {
        long n = runtimeWeightCount.sum();
        return (n == 0L) ? 0.0 : runtimeWeightSum.sum() / (double) n;
    }

    double getMaxRuntimeWeight() {
        double max = runtimeWeightMax.get();
        return (max == Double.NEGATIVE_INFINITY) ? 0.0 : max;
    }

    double getMinRuntimeWeight() {
        double min = runtimeWeightMin.get();
        return (min == Double.POSITIVE_INFINITY) ? 0.0 : min;
    }
}
