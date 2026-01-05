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

final class AbsoluteCountScorer extends AbstractScorer {

    private static final Logger LOGGER = LoggingConfig.getLogger(AbsoluteCountScorer.class);

    AbsoluteCountScorer(GlobalStats globalStats) {
        super(globalStats, LOGGER);
    }

    @Override
    public ScoringMode mode() {
        return ScoringMode.ABSOLUTE_COUNT;
    }

    @Override
    public ScorePreview previewScore(TestCase testCase, OptimizationVectors optVectors) {
        if (optVectors == null) {
            if (testCase != null) {
                testCase.setScore(0.0);
                logZeroScore(testCase, mode(), "no optimization vectors available");
            }
            if (testCase != null) {
                testCase.setHashedOptVector(emptyHashedVector());
            }
            return new SimpleScorePreview(0.0, emptyHashedVector(), new int[0][], null, null);
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            if (testCase != null) {
                testCase.setScore(0.0);
                logZeroScore(testCase, mode(), "optimization vectors empty");
            }
            if (testCase != null) {
                testCase.setHashedOptVector(emptyHashedVector());
            }
            return new SimpleScorePreview(0.0, emptyHashedVector(), new int[0][], null, null);
        }

        int featureCount = OptimizationVector.Features.values().length;
        int[] mergedCounts = new int[featureCount];

        ArrayList<int[]> presentVectors = new ArrayList<>();

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
            int len = Math.min(counts.length, mergedCounts.length);
            int total = 0;
            int[] present = extractPresent(counts);
            if (present != null) {
                presentVectors.add(present);
            }
            for (int i = 0; i < len; i++) {
                int count = counts[i];
                if (count > 0) {
                    total += count;
                    mergedCounts[i] += count;
                }
            }
            if (total > hotScore) {
                hotScore = total;
                hotMethod = methodVector.getMethodName();
                hotClass = methodVector.getClassName();
            }
        }

        double mergedTotal = 0.0;
        for (int count : mergedCounts) {
            if (count > 0) {
                mergedTotal += count;
            }
        }

        if (testCase != null) {
            testCase.setHashedOptVector(bucketCounts(mergedCounts));
            testCase.setScore(mergedTotal);
            if (mergedTotal <= 0.0) {
                logZeroScore(testCase, mode(), "no positive optimization counts observed");
            }
        }
        return new SimpleScorePreview(mergedTotal, mergedCounts, presentVectors.toArray(new int[0][]), hotClass, hotMethod);
    }

    @Override
    public double commitScore(TestCase testCase, ScorePreview preview) {
        if (preview == null) {
            return 0.0;
        }
        int[] counts = preview.optimizationCounts();
        if (counts != null) {
            globalStats.recordBestVectorFeatures(counts);
        }
        recordPresentVectors(preview.presentVectors());
        return preview.score();
    }
}
