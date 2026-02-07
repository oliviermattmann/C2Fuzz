package fuzzer.runtime.scoring;

import fuzzer.runtime.monitoring.GlobalStats;

public final class ScorerFactory {

    private ScorerFactory() {}

    public static Scorer createScorer(GlobalStats globalStats, ScoringMode mode) {
        ScoringMode effective = (mode != null) ? mode : ScoringMode.PF_IDF;
        return switch (effective) {
            case INTERACTION_DIVERSITY -> new InteractionDiversityScorer(globalStats);
            case INTERACTION_PAIR_WEIGHTED -> new InteractionPairWeightedScorer(globalStats);
            case UNIFORM -> new UniformScorer(globalStats);
            case PF_IDF -> new PfIdfScorer(globalStats);
        };
    }
}
