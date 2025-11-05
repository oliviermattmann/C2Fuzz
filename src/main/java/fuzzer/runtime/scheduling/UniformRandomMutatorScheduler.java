package fuzzer.runtime.scheduling;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import fuzzer.mutators.MutatorType;
import fuzzer.util.TestCase;

/**
 * Baseline scheduler that mirrors the previous uniform mutator selection.
 */
public final class UniformRandomMutatorScheduler implements MutatorScheduler {

    private final List<MutatorType> mutatorTypes;

    public UniformRandomMutatorScheduler(List<MutatorType> mutatorTypes) {
        if (mutatorTypes == null || mutatorTypes.isEmpty()) {
            throw new IllegalArgumentException("Mutator list must not be empty.");
        }
        this.mutatorTypes = List.copyOf(mutatorTypes);
    }

    @Override
    public MutatorType pickMutator(TestCase parent) {
        Objects.requireNonNull(parent, "parent");
        int idx = ThreadLocalRandom.current().nextInt(mutatorTypes.size());
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
