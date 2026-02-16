package fuzzer.runtime.scoring;

public enum ScoringMode {
    PF_IDF,
    INTERACTION_DIVERSITY,
    INTERACTION_PAIR_WEIGHTED,
    UNIFORM;

    public String displayName() {
        return switch (this) {
            case PF_IDF -> "PF-IDF";
            case INTERACTION_DIVERSITY -> "INTERACTION_DIVERSITY";
            case INTERACTION_PAIR_WEIGHTED -> "INTERACTION_PAIR_WEIGHTED";
            case UNIFORM -> "UNIFORM";
        };
    }

    public static ScoringMode parseOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase();
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return ScoringMode.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
