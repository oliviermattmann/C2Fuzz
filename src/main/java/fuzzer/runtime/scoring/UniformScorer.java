package fuzzer.runtime.scoring;

import java.util.ArrayList;
import java.util.Objects;
import java.util.logging.Logger;

import fuzzer.logging.LoggingConfig;
import fuzzer.model.MethodOptimizationVector;
import fuzzer.model.OptimizationVector;
import fuzzer.model.OptimizationVectors;
import fuzzer.model.TestCase;
import fuzzer.runtime.monitoring.GlobalStats;

/**
 * Scorer that assigns a constant score of 1.0 to every test case while still
 * propagating hashed optimization vectors for corpus bookkeeping.
 */
final class UniformScorer extends AbstractScorer {

    private static final Logger LOGGER = LoggingConfig.getLogger(UniformScorer.class);

    UniformScorer(GlobalStats globalStats) {
        super(globalStats, LOGGER);
    }

    @Override
    public ScoringMode mode() {
        return ScoringMode.UNIFORM;
    }

    @Override
    public ScorePreview previewScore(TestCase testCase, OptimizationVectors optVectors) {
        Objects.requireNonNull(testCase, "testCase");
        int featureCount = OptimizationVector.Features.values().length;
        int[] mergedCounts = new int[featureCount];
        ArrayList<int[]> presentVectors = new ArrayList<>();

        if (optVectors != null && optVectors.vectors() != null) {
            for (MethodOptimizationVector methodVector : optVectors.vectors()) {
                if (methodVector == null || methodVector.getOptimizations() == null) {
                    continue;
                }
                int[] counts = methodVector.getOptimizations().counts;
                if (counts == null) {
                    continue;
                }
                int len = Math.min(counts.length, mergedCounts.length);
                for (int i = 0; i < len; i++) {
                    int value = counts[i];
                    if (value > 0) {
                        mergedCounts[i] += value;
                    }
                }
                int[] present = extractPresent(counts);
                if (present != null) {
                    presentVectors.add(present);
                }
            }
        }

        double compressed = compressScore(1.0);
        testCase.setScore(compressed);
        testCase.setHashedOptVector(bucketCounts(mergedCounts));

        return new SimpleScorePreview(compressed, mergedCounts, presentVectors.toArray(new int[0][]), null, null);
    }

    @Override
    public double commitScore(TestCase testCase, ScorePreview preview) {
        Objects.requireNonNull(testCase, "testCase");
        double compressed = compressScore(1.0);
        testCase.setScore(compressed);
        testCase.setHashedOptVector(bucketCounts(preview.optimizationCounts()));
        recordPresentVectors(preview.presentVectors());
        return compressed;
    }
}
