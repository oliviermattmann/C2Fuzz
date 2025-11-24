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
        int[] bestCounts = new int[OptimizationVector.Features.values().length];
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
            int[] present = new int[featureCount];
            int m = 0;
            long unseenFeatureOccurrences = 0L;
            for (int i = 0; i < featureCount; i++) {
                if (counts[i] > 0) {
                    present[m++] = i;
                    if (!globalStats.hasSeenFeature(i)) {
                        unseenFeatureOccurrences += counts[i];
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
            if (score > bestScore) {
                bestScore = score;
                bestCounts = counts.clone();
                bestM = m;
            }
        }

        if (bestScore < MIN_SCORE) {
            bestScore = 0.0;
        }
        if (testCase != null) {
            if (bestScore > 0.0) {
                testCase.setHashedOptVector(bucketCounts(bestCounts));
                testCase.setScore(bestScore);
            } else {
                logZeroScore(testCase, mode(), "no optimization pairs exceeded minimum coverage threshold");
                testCase.setHashedOptVector(emptyHashedVector());
                testCase.setScore(0.0);
            }
        } else if (bestScore <= 0.0) {
            logZeroScore(testCase, mode(), "no optimization pairs exceeded minimum coverage threshold");
        }
        return new SimpleScorePreview(bestScore, bestCounts, presentVectors.toArray(new int[0][]));
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
