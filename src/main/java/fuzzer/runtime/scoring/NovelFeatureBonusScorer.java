package fuzzer.runtime.scoring;

import java.util.ArrayList;
import java.util.logging.Logger;

import fuzzer.runtime.monitoring.GlobalStats;
import fuzzer.runtime.scoring.ScoringMode;
import fuzzer.logging.LoggingConfig;
import fuzzer.model.MethodOptimizationVector;
import fuzzer.model.OptimizationVector;
import fuzzer.model.OptimizationVectors;
import fuzzer.model.TestCase;

final class NovelFeatureBonusScorer extends AbstractScorer {

    private static final Logger LOGGER = LoggingConfig.getLogger(NovelFeatureBonusScorer.class);
    private static final double NOVEL_FEATURE_ALPHA = 0.1;

    NovelFeatureBonusScorer(GlobalStats globalStats) {
        super(globalStats, LOGGER);
    }

    @Override
    public ScoringMode mode() {
        return ScoringMode.NOVEL_FEATURE_BONUS;
    }

    @Override
    public ScorePreview previewScore(TestCase testCase, OptimizationVectors optVectors) {
        if (optVectors == null) {
            if (testCase != null) {
                testCase.setScore(0.0);
                logZeroScore(testCase, mode(), "no optimization vectors available");
                testCase.setHashedOptVector(emptyHashedVector());
            }
            return new SimpleScorePreview(0.0, emptyHashedVector(), new int[0][], null, null);
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            if (testCase != null) {
                testCase.setScore(0.0);
                logZeroScore(testCase, mode(), "optimization vectors empty");
                testCase.setHashedOptVector(emptyHashedVector());
            }
            return new SimpleScorePreview(0.0, emptyHashedVector(), new int[0][], null, null);
        }

        ArrayList<int[]> presentVectors = new ArrayList<>();
        int featureCount = OptimizationVector.Features.values().length;
        int[] mergedCounts = new int[featureCount];

        double hotScore = 0.0;
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
            int[] present = extractPresent(counts);
            if (present != null) {
                presentVectors.add(present);
            }

            int total = 0;
            int unseenFeatures = 0;
            int len = Math.min(counts.length, mergedCounts.length);
            for (int i = 0; i < len; i++) {
                int count = counts[i];
                if (count <= 0) {
                    continue;
                }
                total += count;
                mergedCounts[i] += count;
                if (!globalStats.hasSeenFeature(i)) {
                    unseenFeatures++;
                }
            }

            if (total == 0) {
                continue;
            }

            double score = unseenFeatures + NOVEL_FEATURE_ALPHA * total;
            if (score > hotScore) {
                hotScore = score;
                hotMethod = methodVector.getMethodName();
                hotClass = methodVector.getClassName();
            }
        }

        int mergedTotal = 0;
        int mergedUnseen = 0;
        for (int i = 0; i < mergedCounts.length; i++) {
            int count = mergedCounts[i];
            if (count > 0) {
                mergedTotal += count;
                if (!globalStats.hasSeenFeature(i)) {
                    mergedUnseen += count;
                }
            }
        }

        double score = (mergedTotal > 0)
                ? mergedUnseen + NOVEL_FEATURE_ALPHA * mergedTotal
                : 0.0;

        double compressed = compressScore(score);
        if (testCase != null) {
            testCase.setHashedOptVector(bucketCounts(mergedCounts));
            if (score > 0.0) {
                testCase.setScore(compressed);
            } else {
                String reason = "no optimization counts above zero";
                logZeroScore(testCase, mode(), reason);
                testCase.setScore(0.0);
            }
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
