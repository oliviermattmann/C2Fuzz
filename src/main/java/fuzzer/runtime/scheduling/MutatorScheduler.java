package fuzzer.runtime.scheduling;

import fuzzer.mutators.MutatorType;
import fuzzer.model.TestCase;

/**
 * Strategy interface that decides which mutator to apply next and ingests
 * feedback from the runtime once results are available.
 */
public interface MutatorScheduler {

    /**
     * Pick the next mutator to try for the given parent test case.
     */
    MutatorType pickMutator(TestCase parent);

    /**
     * Notify the scheduler about the evaluated outcome of a test case.
     */
    void recordEvaluation(EvaluationFeedback feedback);

    enum MutationAttemptStatus {
        SUCCESS,
        NOT_APPLICABLE,
        FAILED
    }

    enum EvaluationOutcome {
        IMPROVED,
        NO_IMPROVEMENT,
        BUG,
        TIMEOUT,
        FAILURE
    }

    record EvaluationFeedback(
            MutatorType mutatorType,
            double parentScore,
            double childScore,
            EvaluationOutcome outcome,
            int[] parentMergedCounts,
            int[] childMergedCounts) {}
}
