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
        optimizationCounts = optimizationCounts != null ? optimizationCounts.clone() : null;
        if (presentVectors != null) {
            int[][] copy = new int[presentVectors.length][];
            for (int i = 0; i < presentVectors.length; i++) {
                copy[i] = presentVectors[i] != null ? presentVectors[i].clone() : null;
            }
            presentVectors = copy;
        }
    }

    @Override
    public int[] optimizationCounts() {
        return optimizationCounts != null ? optimizationCounts.clone() : null;
    }

    @Override
    public int[][] presentVectors() {
        if (presentVectors == null) {
            return null;
        }
        int[][] copy = new int[presentVectors.length][];
        for (int i = 0; i < presentVectors.length; i++) {
            copy[i] = presentVectors[i] != null ? presentVectors[i].clone() : null;
        }
        return copy;
    }

    @Override
    public String hotMethodName() {
        return hotMethodName;
    }

    @Override
    public String hotClassName() {
        return hotClassName;
    }
}
