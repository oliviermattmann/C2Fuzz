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

final class InteractionDiversityScorer extends AbstractScorer {

    private static final Logger LOGGER = LoggingConfig.getLogger(InteractionDiversityScorer.class);

    InteractionDiversityScorer(GlobalStats globalStats) {
        super(globalStats, LOGGER);
    }

    @Override
    public ScoringMode mode() {
        return ScoringMode.INTERACTION_DIVERSITY;
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
            int peak = 0;
            int len = Math.min(counts.length, mergedCounts.length);
            for (int idx = 0; idx < len; idx++) {
                int count = counts[idx];
                if (count <= 0) {
                    continue;
                }
                total += count;
                mergedCounts[idx] += count;
                if (count > peak) {
                    peak = count;
                }
            }

            if (total == 0) {
                continue;
            }

            double score = total - peak;
            if (score > hotScore) {
                hotScore = score;
                hotMethod = methodVector.getMethodName();
                hotClass = methodVector.getClassName();
            }
        }

        int mergedTotal = 0;
        int mergedPeak = 0;
        for (int count : mergedCounts) {
            if (count > 0) {
                mergedTotal += count;
                if (count > mergedPeak) {
                    mergedPeak = count;
                }
            }
        }

        double finalScore = (mergedTotal > 0) ? Math.max(mergedTotal - mergedPeak, 0.0) : 0.0;

        double compressed = compressScore(finalScore);
        if (testCase != null) {
            testCase.setHashedOptVector(bucketCounts(mergedCounts));
            if (finalScore > 0.0) {
                testCase.setScore(compressed);
            } else {
                logZeroScore(testCase, mode(), (mergedTotal > 0) ? "diversity score not positive" : "no optimization counts above zero");
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
