package fuzzer.runtime.scoring;

import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.runtime.monitoring.GlobalStats;
import fuzzer.model.OptimizationVector;
import fuzzer.model.TestCase;

abstract class AbstractScorer implements Scorer {

    protected final GlobalStats globalStats;
    protected final Logger logger;

    AbstractScorer(GlobalStats globalStats, Logger logger) {
        this.globalStats = globalStats;
        this.logger = logger;
    }

    protected void logZeroScore(TestCase testCase, ScoringMode mode, String reason) {
        if (logger == null || !logger.isLoggable(Level.FINE)) {
            return;
        }
        String testCaseName = (testCase != null && testCase.getName() != null && !testCase.getName().isEmpty())
                ? testCase.getName()
                : "<unknown>";
        String modeName = (mode != null) ? mode.displayName() : "UNKNOWN";
        String message = (reason != null && !reason.isEmpty())
                ? String.format("Test case %s scored 0.0 in %s: %s", testCaseName, modeName, reason)
                : String.format("Test case %s scored 0.0 in %s", testCaseName, modeName);
        logger.fine(message);
    }

    protected int[] bucketCounts(int[] counts) {
        if (counts == null) {
            return null;
        }
        int[] bucketed = new int[counts.length];
        for (int i = 0; i < counts.length; i++) {
            bucketed[i] = bucketCount(counts[i]);
        }
        return bucketed;
    }

    private int bucketCount(int count) {
        if (count <= 0) {
            return 0;
        }
        if (count == 1) {
            return 1;
        }
        if (count == 2) {
            return 2;
        }
        int highest = Integer.highestOneBit(count);
        if (highest <= 0) {
            return 0;
        }
        if (highest >= (1 << 30)) {
            return Integer.MAX_VALUE;
        }
        int bucket = highest << 1;
        if (bucket <= 0) {
            return Integer.MAX_VALUE;
        }
        return bucket;
    }

    protected int[] emptyHashedVector() {
        return new int[OptimizationVector.Features.values().length];
    }

    protected int[] extractPresent(int[] counts) {
        if (counts == null) {
            return null;
        }
        int[] present = new int[counts.length];
        int m = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) {
                present[m++] = i;
            }
        }
        if (m == 0) {
            return null;
        }
        int[] trimmed = new int[m];
        System.arraycopy(present, 0, trimmed, 0, m);
        return trimmed;
    }

    protected void recordPresentVectors(int[][] presentVectors) {
        if (presentVectors == null || globalStats == null) {
            return;
        }
        int featureCount = OptimizationVector.Features.values().length;
        boolean[] seen = new boolean[featureCount];
        int total = 0;
        for (int[] vector : presentVectors) {
            if (vector == null || vector.length == 0) {
                continue;
            }
            for (int idx : vector) {
                if (idx < 0 || idx >= featureCount) {
                    continue;
                }
                if (!seen[idx]) {
                    seen[idx] = true;
                    total++;
                }
            }
        }
        if (total == 0) {
            return;
        }
        int[] mergedPresent = new int[total];
        int m = 0;
        for (int i = 0; i < seen.length; i++) {
            if (seen[i]) {
                mergedPresent[m++] = i;
            }
        }
        globalStats.addRunFromPresent(mergedPresent, mergedPresent.length);
    }

    protected double compressScore(double score) {
        if (!Double.isFinite(score) || score <= 0.0) {
            return 0.0;
        }
        return Math.log1p(score);
    }
}
