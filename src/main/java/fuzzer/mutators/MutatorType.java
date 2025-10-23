package fuzzer.mutators;

import java.util.Arrays;
import java.util.Random;

public enum MutatorType {
    LOOP_UNROLLING_EVOKE,
    INLINE_EVOKE,
    REDUNDANT_STORE_ELIMINATION_EVOKE,
    AUTOBOX_ELIMINATION_EVOKE,
    ESCAPE_ANALYSIS_EVOKE,
    LOOP_PEELING_EVOKE,
    LOOP_UNSWITCHING_EVOKE,
    DEOPTIMIZATION_EVOKE,
    ALGEBRAIC_SIMPLIFICATION_EVOKE,
    DEAD_CODE_ELIMINATION_EVOKE,
    LOCK_ELIMINATION_EVOKE,
    LOCK_COARSENING_EVOKE,
    REFLECTION_CALL,
    SEED;

    private static final MutatorType[] MUTATION_CANDIDATES = Arrays.stream(values())
            .filter(type -> type != SEED && type != REFLECTION_CALL)
            .toArray(MutatorType[]::new);

    public static MutatorType[] mutationCandidates() {
        return MUTATION_CANDIDATES;
    }

    public static MutatorType getRandomMutatorType(Random random) {
        MutatorType[] candidates = MUTATION_CANDIDATES;
        return candidates[random.nextInt(candidates.length)];
    }
}
