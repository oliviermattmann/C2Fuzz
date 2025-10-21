package fuzzer;

import java.util.Locale;

/**
 * Selector for interestingness scoring strategies.
 *
 * The mode can be overridden via the {@code c2fuzz.scoring} system property or
 * {@code C2FUZZ_SCORING} environment variable. Accepted values are the enum
 * constants (case-insensitive, dashes and spaces allowed).
 */
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

    static ScoringMode parseOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
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
