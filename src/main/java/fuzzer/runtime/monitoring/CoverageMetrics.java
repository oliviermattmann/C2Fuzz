package fuzzer.runtime.monitoring;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

final class CoverageMetrics {
    private final int featureCount;
    private final int[] rowOffset;
    private final AtomicLongArray pairCounts;
    private final LongAdder evaluations = new LongAdder();
    private final AtomicLongArray featureCounts;

    CoverageMetrics(int featureCount) {
        this.featureCount = featureCount;
        int nPairs = (featureCount * (featureCount - 1)) / 2;
        this.pairCounts = new AtomicLongArray(nPairs);
        this.rowOffset = new int[featureCount];
        this.featureCounts = new AtomicLongArray(featureCount);
        for (int i = 0; i < featureCount; i++) {
            rowOffset[i] = i * (featureCount - 1) - (i * (i - 1)) / 2;
        }
    }

    int pairIdx(int i, int j) {
        if (i == j) {
            throw new IllegalArgumentException("i == j");
        }
        if (i > j) {
            int t = i;
            i = j;
            j = t;
        }
        return rowOffset[i] + (j - i - 1);
    }

    void addRunFromPresent(int[] present, int m) {
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

    long getPairCount(int i, int j) {
        if (i == j) {
            return 0L;
        }
        return pairCounts.get(pairIdx(i, j));
    }

    int getFeatureSlots() {
        return featureCounts.length();
    }

    long[] snapshotPairCounts() {
        int len = pairCounts.length();
        long[] copy = new long[len];
        for (int i = 0; i < len; i++) {
            copy[i] = pairCounts.get(i);
        }
        return copy;
    }

    long getFeatureCount(int idx) {
        if (idx < 0 || idx >= featureCount) {
            return 0L;
        }
        return featureCounts.get(idx);
    }

    long getRunCount() {
        return evaluations.sum();
    }

    long countUniqueFeatures() {
        long unique = 0L;
        for (int i = 0; i < featureCounts.length(); i++) {
            if (featureCounts.get(i) > 0L) {
                unique++;
            }
        }
        return unique;
    }
}
