package fuzzer.runtime.scoring;

import java.util.ArrayList;
import java.util.Objects;
import java.util.logging.Logger;

import fuzzer.runtime.monitoring.GlobalStats;
import fuzzer.logging.LoggingConfig;
import fuzzer.model.MethodOptimizationVector;
import fuzzer.model.OptimizationVector;
import fuzzer.model.OptimizationVectors;
import fuzzer.model.TestCase;

final class InteractionPairWeightedScorer extends AbstractScorer {

    private static final Logger LOGGER = LoggingConfig.getLogger(InteractionPairWeightedScorer.class);
    private static final double SEEN_WEIGHT = 0.2;
    private static final double MIN_SCORE = 0.05;

    InteractionPairWeightedScorer(GlobalStats globalStats) {
        super(globalStats, LOGGER);
    }

    @Override
    public ScoringMode mode() {
        return ScoringMode.INTERACTION_PAIR_WEIGHTED;
    }

    @Override
    public ScorePreview previewScore(TestCase testCase, OptimizationVectors optVectors) {
        Objects.requireNonNull(testCase, "testCase");
        if (optVectors == null) {
            logZeroScore(testCase, mode(), "no optimization vectors available");
            testCase.setHashedOptVector(emptyHashedVector());
            return new SimpleScorePreview(0.0, emptyHashedVector(), new int[0][], null, null);
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            logZeroScore(testCase, mode(), "optimization vectors empty");
            testCase.setHashedOptVector(emptyHashedVector());
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
            double[] normalizedCounts = new double[featureCount];
            int[] present = new int[len];
            int m = 0;
            for (int idx = 0; idx < len; idx++) {
                int count = counts[idx];
                if (count > 0) {
                    present[m++] = idx;
                    mergedCounts[idx] += count;
                    long featureFreq = globalStats.getFeatureCount(idx);
                    double freqScale = Math.sqrt(1.0 + (double) featureFreq);
                    double magnitude = Math.log1p(count);
                    normalizedCounts[idx] = (freqScale > 0.0) ? magnitude / freqScale : magnitude;
                }
            }
            if (m > 0) {
                int[] trimmed = new int[m];
                System.arraycopy(present, 0, trimmed, 0, m);
                presentVectors.add(trimmed);
            }
            if (m < 2) {
                continue;
            }

            double newWeight = 0.0;
            double seenWeight = 0.0;
            double totalWeight = 0.0;
            for (int a = 0; a < m; a++) {
                int i = present[a];
                for (int b = a + 1; b < m; b++) {
                    int j = present[b];
                    double weight = normalizedCounts[i] * normalizedCounts[j];
                    if (weight <= 0.0) {
                        continue;
                    }
                    totalWeight += weight;
                    if (globalStats.getPairCount(i, j) == 0L) {
                        newWeight += weight;
                    } else {
                        seenWeight += weight;
                    }
                }
            }

            if (totalWeight <= 0.0) {
                continue;
            }

            double score = newWeight + (seenWeight * SEEN_WEIGHT);
            if (score <= 0.0) {
                score = Math.max(MIN_SCORE, totalWeight * SEEN_WEIGHT);
            }

            if (score > hotScore) {
                hotScore = score;
                hotMethod = methodVector.getMethodName();
                hotClass = methodVector.getClassName();
            }
        }

        int[] mergedPresent = extractPresent(mergedCounts);
        int mergedM = mergedPresent != null ? mergedPresent.length : 0;
        double[] mergedNormalizedCounts = new double[featureCount];
        if (mergedPresent != null) {
            for (int idx : mergedPresent) {
                long featureFreq = globalStats.getFeatureCount(idx);
                double freqScale = Math.sqrt(1.0 + (double) featureFreq);
                double magnitude = Math.log1p(mergedCounts[idx]);
                mergedNormalizedCounts[idx] = (freqScale > 0.0) ? magnitude / freqScale : magnitude;
            }
        }

        double mergedNewWeight = 0.0;
        double mergedSeenWeight = 0.0;
        double mergedTotalWeight = 0.0;
        if (mergedPresent != null) {
            for (int a = 0; a < mergedM; a++) {
                int i = mergedPresent[a];
                for (int b = a + 1; b < mergedM; b++) {
                    int j = mergedPresent[b];
                    double weight = mergedNormalizedCounts[i] * mergedNormalizedCounts[j];
                    if (weight <= 0.0) {
                        continue;
                    }
                    mergedTotalWeight += weight;
                    if (globalStats.getPairCount(i, j) == 0L) {
                        mergedNewWeight += weight;
                    } else {
                        mergedSeenWeight += weight;
                    }
                }
            }
        }

        double mergedScore = 0.0;
        if (mergedTotalWeight > 0.0 && mergedM >= 2) {
            mergedScore = mergedNewWeight + (mergedSeenWeight * SEEN_WEIGHT);
            if (mergedScore <= 0.0) {
                mergedScore = Math.max(MIN_SCORE, mergedTotalWeight * SEEN_WEIGHT);
            }
        }

        double finalScore = Math.max(mergedScore, 0.0);
        double compressed = compressScore(finalScore);
        testCase.setHashedOptVector(bucketCounts(mergedCounts));
        if (finalScore > 0.0) {
            testCase.setScore(compressed);
        } else {
            logZeroScore(testCase, mode(), "no optimization pairs with positive weight observed");
            testCase.setScore(0.0);
        }
        return new SimpleScorePreview(compressed, mergedCounts, presentVectors.toArray(new int[0][]), hotClass, hotMethod);
    }

    @Override
    public double commitScore(TestCase testCase, ScorePreview preview) {
        Objects.requireNonNull(testCase, "testCase");
        recordPresentVectors(preview.presentVectors());
        return preview.score();
    }
}
