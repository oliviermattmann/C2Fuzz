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

        if (testCase != null) {
            testCase.setScore(1.0);
            testCase.setHashedOptVector(bucketCounts(mergedCounts));
        }

        return new SimpleScorePreview(1.0, mergedCounts, presentVectors.toArray(new int[0][]), null, null);
    }

    @Override
    public double commitScore(TestCase testCase, ScorePreview preview) {
        int[] counts = (preview != null) ? preview.optimizationCounts() : null;
        if (testCase != null) {
            testCase.setScore(1.0);
            testCase.setHashedOptVector(bucketCounts(counts != null ? counts : emptyHashedVector()));
        }
        if (counts != null) {
            globalStats.recordBestVectorFeatures(counts);
        }
        if (preview != null) {
            recordPresentVectors(preview.presentVectors());
        }
        return 1.0;
    }
}
