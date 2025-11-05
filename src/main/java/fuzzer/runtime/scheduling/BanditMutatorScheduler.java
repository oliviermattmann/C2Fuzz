package fuzzer.runtime.scheduling;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import fuzzer.mutators.MutatorType;
import fuzzer.runtime.scheduling.MutatorScheduler.EvaluationFeedback;
import fuzzer.runtime.scheduling.MutatorScheduler.MutationAttemptStatus;
import fuzzer.util.TestCase;

/**
 * Thompson-sampling scheduler inspired by BanditFuzz. Each mutator type is an
 * arm with a Beta posterior that is updated based on the runtime feedback.
 */
public final class BanditMutatorScheduler implements MutatorScheduler {

    private static final int SUCCESS_BOOST = 3;
    private final Arm[] armsByOrdinal;
    private final List<Arm> arms;

    public BanditMutatorScheduler(List<MutatorType> mutatorTypes) {
        if (mutatorTypes == null || mutatorTypes.isEmpty()) {
            throw new IllegalArgumentException("Mutator list must not be empty.");
        }
        this.arms = mutatorTypes.stream()
                .map(Arm::new)
                .toList();

        Arm[] ordinalLookup = new Arm[MutatorType.values().length];
        for (Arm arm : arms) {
            ordinalLookup[arm.mutator.ordinal()] = arm;
        }
        this.armsByOrdinal = ordinalLookup;
    }

    @Override
    public MutatorType pickMutator(TestCase parent) {
        Objects.requireNonNull(parent, "parent");
        double bestSample = Double.NEGATIVE_INFINITY;
        MutatorType bestMutator = arms.get(0).mutator;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (Arm arm : arms) {
            double sample = sampleBeta(rng, arm.alpha.get(), arm.beta.get());
            if (sample > bestSample) {
                bestSample = sample;
                bestMutator = arm.mutator;
            }
        }
        return bestMutator;
    }

    @Override
    public void recordMutationAttempt(MutatorType mutatorType, MutationAttemptStatus status) {
        Arm arm = lookup(mutatorType);
        if (arm == null || status == MutationAttemptStatus.SUCCESS) {
            return;
        }

        if (status == MutationAttemptStatus.FAILED) arm.beta.incrementAndGet();
    }

    @Override
    public void recordEvaluation(EvaluationFeedback feedback) {
        if (feedback == null) {
            return;
        }
        Arm arm = lookup(feedback.mutatorType());
        if (arm == null) {
            return;
        }
        switch (feedback.outcome()) {
            case BUG -> arm.alpha.addAndGet(SUCCESS_BOOST);
            case IMPROVED -> arm.alpha.incrementAndGet();
            case NO_IMPROVEMENT, FAILURE, TIMEOUT -> arm.beta.incrementAndGet();
        }
    }

    private Arm lookup(MutatorType mutatorType) {
        if (mutatorType == null) {
            return null;
        }
        int ordinal = mutatorType.ordinal();
        if (ordinal < 0 || ordinal >= armsByOrdinal.length) {
            return null;
        }
        return armsByOrdinal[ordinal];
    }

    private static double sampleBeta(ThreadLocalRandom rng, int alpha, int beta) {
        double x = sampleGamma(rng, alpha);
        double y = sampleGamma(rng, beta);
        return x / (x + y);
    }

    private static double sampleGamma(ThreadLocalRandom rng, double shape) {
        if (shape <= 0.0) {
            throw new IllegalArgumentException("Gamma shape must be positive.");
        }
        if (shape < 1.0) {
            // Weibull algorithm
            double u = rng.nextDouble();
            return sampleGamma(rng, shape + 1.0) * Math.pow(u, 1.0 / shape);
        }
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x = rng.nextGaussian();
            double v = 1.0 + c * x;
            if (v <= 0.0) {
                continue;
            }
            v = v * v * v;
            double u = rng.nextDouble();
            if (u < 1.0 - 0.331 * Math.pow(x, 4)) {
                return d * v;
            }
            if (Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) {
                return d * v;
            }
        }
    }

    @Override
    public String toString() {
        return "BanditMutatorScheduler" + Arrays.toString(arms.toArray());
    }

    private static final class Arm {
        final MutatorType mutator;
        final AtomicInteger alpha = new AtomicInteger(1);
        final AtomicInteger beta = new AtomicInteger(1);

        Arm(MutatorType mutator) {
            this.mutator = Objects.requireNonNull(mutator, "mutator");
        }

        @Override
        public String toString() {
            return mutator.name() + "(α=" + alpha.get() + ",β=" + beta.get() + ")";
        }
    }
}
