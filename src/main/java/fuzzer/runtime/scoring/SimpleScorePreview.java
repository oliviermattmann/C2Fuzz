package fuzzer.runtime.scoring;

/**
 * Basic immutable preview containing only a score and optional hashed counts.
 */
public record SimpleScorePreview(
        double score,
        int[] optimizationCounts,
        int[][] presentVectors,
        String hotClassName,
        String hotMethodName)
        implements ScorePreview {

    public SimpleScorePreview {
        optimizationCounts = optimizationCounts != null ? optimizationCounts : EMPTY_INT_ARRAY;
        hotClassName = hotClassName != null ? hotClassName : "";
        hotMethodName = hotMethodName != null ? hotMethodName : "";
        presentVectors = presentVectors != null ? presentVectors : EMPTY_INT_MATRIX;
    }

    @Override
    public int[] optimizationCounts() {
        return optimizationCounts;
    }

    @Override
    public int[][] presentVectors() {
        return presentVectors;
    }
}
