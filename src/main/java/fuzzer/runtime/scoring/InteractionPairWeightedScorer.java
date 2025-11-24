package fuzzer.runtime.scoring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Logger;

import fuzzer.runtime.GlobalStats;
import fuzzer.runtime.ScoringMode;
import fuzzer.util.LoggingConfig;
import fuzzer.util.MethodOptimizationVector;
import fuzzer.util.OptimizationVector;
import fuzzer.util.OptimizationVectors;
import fuzzer.util.TestCase;

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
        if (optVectors == null) {
            if (testCase != null) {
                logZeroScore(testCase, mode(), "no optimization vectors available");
            }
            return new SimpleScorePreview(0.0, emptyHashedVector(), new int[0][]);
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            if (testCase != null) {
                logZeroScore(testCase, mode(), "optimization vectors empty");
            }
            return new SimpleScorePreview(0.0, emptyHashedVector(), new int[0][]);
        }

        ArrayList<int[]> presentVectors = new ArrayList<>();

        double bestScore = Double.NEGATIVE_INFINITY;
        double bestNewWeight = -1.0;
        double bestTotalWeight = -1.0;
        int[] bestCounts = new int[OptimizationVector.Features.values().length];
        int[] bestPresent = new int[OptimizationVector.Features.values().length];
        int bestM = 0;

        for (MethodOptimizationVector methodVector : methodVectors) {
            if (methodVector == null || methodVector.getOptimizations() == null) {
                continue;
            }
            int[] counts = methodVector.getOptimizations().counts;
            if (counts == null) {
                continue;
            }
            int featureCount = counts.length;
            double[] normalizedCounts = new double[featureCount];
            int[] present = extractPresent(counts);
            int m = (present != null) ? present.length : 0;
            if (present != null) {
                presentVectors.add(present);
            }
            if (m < 2) {
                continue;
            }
            for (int idx = 0; idx < present.length; idx++) {
                int i = present[idx];
                long featureFreq = globalStats.getFeatureCount(i);
                double freqScale = Math.sqrt(1.0 + (double) featureFreq);
                double magnitude = Math.log1p(counts[i]);
                normalizedCounts[i] = (freqScale > 0.0) ? magnitude / freqScale : magnitude;
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

            if (score > bestScore
                    || (score == bestScore && newWeight > bestNewWeight)
                    || (score == bestScore && newWeight == bestNewWeight && totalWeight > bestTotalWeight)
                    || (score == bestScore && newWeight == bestNewWeight && totalWeight == bestTotalWeight && m > bestM)) {
                bestScore = score;
                bestNewWeight = newWeight;
                bestTotalWeight = totalWeight;
                bestM = m;
                System.arraycopy(present, 0, bestPresent, 0, m);
                bestCounts = Arrays.copyOf(counts, counts.length);
            }
        }

        double finalScore = Math.max(bestScore, 0.0);
        if (bestM > 0) {
            if (testCase != null) {
                testCase.setHashedOptVector(bucketCounts(bestCounts));
                testCase.setScore(finalScore);
            }
        } else if (testCase != null) {
            logZeroScore(testCase, mode(), "no optimization pairs with positive weight observed");
            testCase.setScore(0.0);
            if (testCase.getHashedOptVector() == null) {
                testCase.setHashedOptVector(emptyHashedVector());
            }
        }
        return new SimpleScorePreview(finalScore, bestCounts, presentVectors.toArray(new int[0][]));
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
