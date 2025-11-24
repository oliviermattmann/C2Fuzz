package fuzzer.runtime.scoring;

import fuzzer.runtime.GlobalStats;
import fuzzer.runtime.ScoringMode;

public final class ScorerFactory {

    private ScorerFactory() {}

    public static Scorer createScorer(GlobalStats globalStats, ScoringMode mode) {
        ScoringMode effective = (mode != null) ? mode : ScoringMode.PF_IDF;
        return switch (effective) {
            case ABSOLUTE_COUNT -> new AbsoluteCountScorer(globalStats);
            case PAIR_COVERAGE -> new PairCoverageScorer(globalStats);
            case INTERACTION_DIVERSITY -> new InteractionDiversityScorer(globalStats);
            case NOVEL_FEATURE_BONUS -> new NovelFeatureBonusScorer(globalStats);
            case INTERACTION_PAIR_WEIGHTED -> new InteractionPairWeightedScorer(globalStats);
            case UNIFORM -> new UniformScorer(globalStats);
            case PF_IDF -> new PfIdfScorer(globalStats);
        };
    }
}
