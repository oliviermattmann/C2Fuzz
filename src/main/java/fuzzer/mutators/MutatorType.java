package fuzzer.mutators;

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

    public static MutatorType getRandomMutatorType(Random random) {
        MutatorType[] values = MutatorType.values();
        return values[random.nextInt(values.length-2)]; // Exclude SEED and reflection call from random selection
    }
}