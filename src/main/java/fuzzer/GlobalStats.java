package fuzzer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;



public class GlobalStats {
    // moving counts for rarity
    ConcurrentHashMap<String, LongAdder> opFreq = new ConcurrentHashMap<>();
    LongAdder totalTestsExecuted = new LongAdder();
    LongAdder failedCompilations = new LongAdder();
    LongAdder foundBugs = new LongAdder();






    // running maxima for normalization
    final ConcurrentHashMap<String, Double> opMax = new ConcurrentHashMap<>(); // per-op max count seen
    final AtomicDouble optScoreMax = new AtomicDouble(1e-9);
    final AtomicDouble noveltyMax  = new AtomicDouble(1e-9);

    // archive of features for novelty
    final CopyOnWriteArrayList<double[]> archive = new CopyOnWriteArrayList<>();
    final int archiveCap = 256;

    // === new metrics fields ===
    private final LongAdder accumulatedExecNanos = new LongAdder();
    private final LongAdder scoreCount = new LongAdder();   // number of scored tests
    private final DoubleAdder scoreSum  = new DoubleAdder();// sum of scores
    private final DoubleAccumulator scoreMax =
            new DoubleAccumulator(Math::max, Double.NEGATIVE_INFINITY);

    // optional: if each test has a duration and you want duration-based avg speed too
    // private final LongAdder totalExecNanos = new LongAdder();

    /** Call this from worker threads when a test finishes. */
    public void recordTest(double score /*, long execNanos */) {
        totalTestsExecuted.increment();
        scoreCount.increment();
        scoreSum.add(score);
        scoreMax.accumulate(score);
        // totalExecNanos.add(execNanos);
    }

    // === readings for the dashboard (cheap snapshots) ===

    public void recordExecTimeNanos(long nanos) {
        totalTestsExecuted.increment();
        accumulatedExecNanos.add(nanos);
    }
    
    public double getAvgExecTimeNanos() {
        long n = totalTestsExecuted.sum();
        return (n == 0) ? 0.0 : accumulatedExecNanos.sum() / (double) n;
    }
    
    public double getAvgExecTimeMillis() {
        return getAvgExecTimeNanos() / 1_000_000.0;
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
}
