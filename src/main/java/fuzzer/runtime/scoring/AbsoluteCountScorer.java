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
                testCase.setHashedOptVector(emptyHashedVector());
                logZeroScore(testCase, mode(), "no optimization vectors available");
            }
            return new SimpleScorePreview(0.0, emptyHashedVector(), new int[0][]);
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            if (testCase != null) {
                testCase.setScore(0.0);
                testCase.setHashedOptVector(emptyHashedVector());
                logZeroScore(testCase, mode(), "optimization vectors empty");
            }
            return new SimpleScorePreview(0.0, emptyHashedVector(), new int[0][]);
        }

        double best = 0.0;
        int[] bestCounts = new int[OptimizationVector.Features.values().length];

        ArrayList<int[]> presentVectors = new ArrayList<>();

        for (MethodOptimizationVector methodVector : methodVectors) {
            if (methodVector == null || methodVector.getOptimizations() == null) {
                continue;
            }
            int[] counts = methodVector.getOptimizations().counts;
            int[] present = extractPresent(counts);
            if (present != null) {
                presentVectors.add(present);
            }
            int total = 0;
            for (int count : counts) {
                if (count > 0) {
                    total += count;
                }
            }
            if (total > best) {
                best = total;
                bestCounts = Arrays.copyOf(counts, counts.length);
            }
        }

        if (best <= 0.0 && testCase != null) {
            logZeroScore(testCase, mode(), "no positive optimization counts observed");
        }
        if (testCase != null) {
            testCase.setHashedOptVector(bucketCounts(bestCounts));
            testCase.setScore(best);
        }
        return new SimpleScorePreview(best, bestCounts, presentVectors.toArray(new int[0][]));
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
