package fuzzer.runtime.scoring;

import java.util.ArrayList;
import java.util.logging.Logger;

import fuzzer.runtime.GlobalStats;
import fuzzer.runtime.ScoringMode;
import fuzzer.util.LoggingConfig;
import fuzzer.util.MethodOptimizationVector;
import fuzzer.util.OptimizationVector;
import fuzzer.util.OptimizationVectors;
import fuzzer.util.TestCase;

final class PairCoverageScorer extends AbstractScorer {

    private static final Logger LOGGER = LoggingConfig.getLogger(PairCoverageScorer.class);
    private static final double SINGLE_FEATURE_WEIGHT = 0.5;
    private static final double SEEN_PAIR_WEIGHT = 0.05;
    private static final double MIN_SCORE = 0.1;

    PairCoverageScorer(GlobalStats globalStats) {
        super(globalStats, LOGGER);
    }

    @Override
    public ScoringMode mode() {
        return ScoringMode.PAIR_COVERAGE;
    }

    @Override
    public ScorePreview previewScore(TestCase testCase, OptimizationVectors optVectors) {
        if (optVectors == null) {
            if (testCase != null) {
                logZeroScore(testCase, mode(), "no optimization vectors available");
                testCase.setHashedOptVector(emptyHashedVector());
            }
            return new SimpleScorePreview(0.0, emptyHashedVector(), new int[0][], null, null);
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            if (testCase != null) {
                logZeroScore(testCase, mode(), "optimization vectors empty");
                testCase.setHashedOptVector(emptyHashedVector());
            }
            return new SimpleScorePreview(0.0, emptyHashedVector(), new int[0][], null, null);
        }

        ArrayList<int[]> presentVectors = new ArrayList<>();
        int featureCount = OptimizationVector.Features.values().length;
        int[] mergedCounts = new int[featureCount];

        double hotScore = Double.NEGATIVE_INFINITY;
        String hotMethod = null;
        String hotClass = null;

        for (MethodOptimizationVector methodVector : methodVectors) {
            if (methodVector == null || methodVector.getOptimizations() == null) {
                continue;
            }
            int[] counts = methodVector.getOptimizations().counts;
            if (counts == null) {
                continue;
            }

            int len = Math.min(counts.length, featureCount);
            int[] present = new int[len];
            int m = 0;
            long unseenFeatureOccurrences = 0L;
            for (int i = 0; i < len; i++) {
                int count = counts[i];
                if (count > 0) {
                    present[m++] = i;
                    mergedCounts[i] += count;
                    if (!globalStats.hasSeenFeature(i)) {
                        unseenFeatureOccurrences += count;
                    }
                }
            }
            if (m > 0) {
                int[] trimmed = new int[m];
                System.arraycopy(present, 0, trimmed, 0, m);
                presentVectors.add(trimmed);
            }
            int newPairs = 0;
            long newPairOccurrences = 0L;
            long seenPairOccurrences = 0L;
            for (int a = 0; a < m; a++) {
                int i = present[a];
                for (int b = a + 1; b < m; b++) {
                    int j = present[b];
                    long pairCount = globalStats.getPairCount(i, j);
                    if (pairCount == 0L) {
                        newPairs++;
                        newPairOccurrences += counts[i] + counts[j];
                    } else {
                        seenPairOccurrences += counts[i] + counts[j];
                    }
                }
            }

            double score = newPairs;
            score += unseenFeatureOccurrences * SINGLE_FEATURE_WEIGHT;
            score += seenPairOccurrences * SEEN_PAIR_WEIGHT;
            if (score > hotScore) {
                hotScore = score;
                hotMethod = methodVector.getMethodName();
                hotClass = methodVector.getClassName();
            }
        }

        int[] mergedPresent = extractPresent(mergedCounts);
        int mergedM = mergedPresent != null ? mergedPresent.length : 0;
        long mergedUnseenFeatureOccurrences = 0L;
        if (mergedPresent != null) {
            for (int idx : mergedPresent) {
                if (!globalStats.hasSeenFeature(idx)) {
                    mergedUnseenFeatureOccurrences += mergedCounts[idx];
                }
            }
        }

        int mergedNewPairs = 0;
        long mergedSeenPairOccurrences = 0L;
        if (mergedPresent != null) {
            for (int a = 0; a < mergedM; a++) {
                int i = mergedPresent[a];
                for (int b = a + 1; b < mergedM; b++) {
                    int j = mergedPresent[b];
                    long pairCount = globalStats.getPairCount(i, j);
                    if (pairCount == 0L) {
                        mergedNewPairs++;
                    } else {
                        mergedSeenPairOccurrences += mergedCounts[i] + mergedCounts[j];
                    }
                }
            }
        }

        double mergedScore = mergedNewPairs;
        mergedScore += mergedUnseenFeatureOccurrences * SINGLE_FEATURE_WEIGHT;
        mergedScore += mergedSeenPairOccurrences * SEEN_PAIR_WEIGHT;
        if (mergedScore < MIN_SCORE) {
            mergedScore = 0.0;
        }
        double compressed = compressScore(mergedScore);
        if (testCase != null) {
            testCase.setHashedOptVector(bucketCounts(mergedCounts));
            if (mergedScore > 0.0) {
                testCase.setScore(compressed);
            } else {
                logZeroScore(testCase, mode(), "no optimization pairs exceeded minimum coverage threshold");
                testCase.setScore(0.0);
            }
        } else if (mergedScore <= 0.0) {
            logZeroScore(testCase, mode(), "no optimization pairs exceeded minimum coverage threshold");
        }
        return new SimpleScorePreview(compressed, mergedCounts, presentVectors.toArray(new int[0][]), hotClass, hotMethod);
    }

    @Override
    public double commitScore(TestCase testCase, ScorePreview preview) {
        if (preview == null) {
            return 0.0;
        }
        recordPresentVectors(preview.presentVectors());
        return preview.score();
    }
}
