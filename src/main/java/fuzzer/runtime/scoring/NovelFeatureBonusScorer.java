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
            }
            return new SimpleScorePreview(0.0, emptyHashedVector(), new int[0][]);
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            if (testCase != null) {
                testCase.setScore(0.0);
                logZeroScore(testCase, mode(), "optimization vectors empty");
            }
            return new SimpleScorePreview(0.0, emptyHashedVector(), new int[0][]);
        }

        ArrayList<int[]> presentVectors = new ArrayList<>();

        double bestScore = 0.0;
        int[] bestCounts = new int[OptimizationVector.Features.values().length];
        int bestTotal = 0;
        boolean found = false;

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
            for (int i = 0; i < counts.length; i++) {
                int count = counts[i];
                if (count <= 0) {
                    continue;
                }
                total += count;
                if (!globalStats.hasSeenFeature(i)) {
                    unseenFeatures++;
                }
            }

            if (total == 0) {
                continue;
            }

            double score = unseenFeatures + NOVEL_FEATURE_ALPHA * total;
            if (!found || score > bestScore) {
                bestScore = score;
                bestCounts = Arrays.copyOf(counts, counts.length);
                bestTotal = total;
                found = true;
            }
        }

        if (testCase != null) {
            if (found && bestTotal > 0) {
                testCase.setHashedOptVector(bucketCounts(bestCounts));
                testCase.setScore(bestScore);
            } else {
                String reason = found
                        ? "no previously unseen optimization features observed"
                        : "no optimization counts above zero";
                logZeroScore(testCase, mode(), reason);
                if (testCase.getHashedOptVector() == null) {
                    testCase.setHashedOptVector(emptyHashedVector());
                }
                testCase.setScore(0.0);
            }
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
