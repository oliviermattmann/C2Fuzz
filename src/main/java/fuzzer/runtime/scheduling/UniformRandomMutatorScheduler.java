package fuzzer.runtime.scheduling;

import java.util.List;
import java.util.Objects;
import java.util.Random;

import fuzzer.mutators.MutatorType;
import fuzzer.model.TestCase;

/**
 * Baseline scheduler that mirrors the previous uniform mutator selection.
 */
public final class UniformRandomMutatorScheduler implements MutatorScheduler {

    private final List<MutatorType> mutatorTypes;
    private final Random random;

    public UniformRandomMutatorScheduler(List<MutatorType> mutatorTypes, Random random) {
        if (mutatorTypes == null || mutatorTypes.isEmpty()) {
            throw new IllegalArgumentException("Mutator list must not be empty.");
        }
        this.mutatorTypes = List.copyOf(mutatorTypes);
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public MutatorType pickMutator(TestCase parent) {
        Objects.requireNonNull(parent, "parent");
        int idx = random.nextInt(mutatorTypes.size());
        return mutatorTypes.get(idx);
    }

    @Override
    public void recordMutationAttempt(MutatorType mutatorType, MutationAttemptStatus status) {
        // Uniform policy ignores feedback.
    }

    @Override
    public void recordEvaluation(EvaluationFeedback feedback) {
        // Uniform policy ignores feedback.
    }
}
