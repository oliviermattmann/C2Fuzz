package fuzzer.mutators;

import java.util.Arrays;
import java.util.Random;

public enum MutatorType {
    LOOP_UNROLLING,
    INLINE,
    REDUNDANT_STORE_ELIMINATION,
    AUTOBOX_ELIMINATION,
    ESCAPE_ANALYSIS,
    LOOP_PEELING,
    LOOP_UNSWITCHING,
    DEOPTIMIZATION,
    LATE_ZERO,
    SPLIT_IF_STRESS,
    UNSWITCH_SCAFFOLD,
    SINKABLE_MULTIPLY,
    TEMPLATE_PREDICATE,
    ALGEBRAIC_SIMPLIFICATION,
    DEAD_CODE_ELIMINATION,
    LOCK_ELIMINATION,
    LOCK_COARSENING,
    INT_TO_LONG_LOOP,
    ARRAY_TO_MEMORY_SEGMENT,
    ARRAY_MEMORY_SEGMENT_SHADOW,
    SEED;

    private static final MutatorType[] MUTATION_CANDIDATES = Arrays.stream(values())
            .filter(type -> type != SEED)
            .toArray(MutatorType[]::new);

    public static MutatorType[] mutationCandidates() {
        return MUTATION_CANDIDATES;
    }

    public static MutatorType getRandomMutatorType(Random random) {
        MutatorType[] candidates = MUTATION_CANDIDATES;
        return candidates[random.nextInt(candidates.length)];
    }
}
