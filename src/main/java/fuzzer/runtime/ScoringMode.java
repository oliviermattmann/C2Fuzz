package fuzzer;

public enum ScoringMode {
    PF_IDF,
    ABSOLUTE_COUNT,
    PAIR_COVERAGE,
    INTERACTION_DIVERSITY,
    NOVEL_FEATURE_BONUS;

    public String displayName() {
        return switch (this) {
            case PF_IDF -> "PF-IDF";
            case ABSOLUTE_COUNT -> "ABSOLUTE_COUNT";
            case PAIR_COVERAGE -> "PAIR_COVERAGE";
            case INTERACTION_DIVERSITY -> "INTERACTION_DIVERSITY";
            case NOVEL_FEATURE_BONUS -> "NOVEL_FEATURE_BONUS";
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
