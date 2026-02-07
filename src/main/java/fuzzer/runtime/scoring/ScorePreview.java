package fuzzer.runtime.scoring;

/**
 * Lightweight view of a scoring result that can be inspected before deciding
 * whether to commit it.
 */
public interface ScorePreview {
    int[] EMPTY_INT_ARRAY = new int[0];
    int[][] EMPTY_INT_MATRIX = new int[0][];

    double score();

    default int[] optimizationCounts() {
        return EMPTY_INT_ARRAY;
    }

    default int[] pairIndices() {
        return EMPTY_INT_ARRAY;
    }

    default String hotMethodName() {
        return "";
    }

    default String hotClassName() {
        return "";
    }

    /**
     * Per-method present-feature indices (feature id where count > 0).
     * Used during commit to update global feature/pair occurrence statistics.
     */
    default int[][] presentVectors() {
        return EMPTY_INT_MATRIX;
    }
}
