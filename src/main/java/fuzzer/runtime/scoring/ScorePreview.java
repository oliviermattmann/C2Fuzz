package fuzzer.runtime.scoring;

/**
 * Lightweight view of a scoring result that can be inspected before deciding
 * whether to commit it.
 */
public interface ScorePreview {
    double score();

    default int[] optimizationCounts() {
        return null;
    }

    default int[] pairIndices() {
        return null;
    }

    default String hotMethodName() {
        return null;
    }

    default String hotClassName() {
        return null;
    }

    default int[][] presentVectors() {
        return null;
    }
}
