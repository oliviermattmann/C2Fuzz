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
    RANGE_CHECK_PREDICATION_EVOKE,
    DEOPTIMIZATION_EVOKE,
    LATE_ZERO_MUTATOR,
    SPLIT_IF_STRESS,
    UNSWITCH_SCAFFOLD,
    SINKABLE_MUL,
    TEMPLATE_PREDICATE,
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
