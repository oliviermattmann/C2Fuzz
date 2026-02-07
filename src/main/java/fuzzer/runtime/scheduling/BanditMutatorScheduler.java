package fuzzer.runtime.scheduling;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
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
    private static final double EPSILON = 0.1; // small explore rate
    private final Arm[] armsByOrdinal;
    private final List<Arm> arms;
    private final Random random;

    public BanditMutatorScheduler(List<MutatorType> mutatorTypes, Random random) {
        if (mutatorTypes == null || mutatorTypes.isEmpty()) {
            throw new IllegalArgumentException("Mutator list must not be empty.");
        }
        this.arms = mutatorTypes.stream()
                .map(Arm::new)
                .toList();
        this.random = Objects.requireNonNull(random, "random");

        Arm[] ordinalLookup = new Arm[MutatorType.values().length];
        for (Arm arm : arms) {
            ordinalLookup[arm.mutator.ordinal()] = arm;
        }
        this.armsByOrdinal = ordinalLookup;
    }

    @Override
    public MutatorType pickMutator(TestCase parent) {
        Objects.requireNonNull(parent, "parent");
        if (random.nextDouble() < EPSILON) {
            return arms.get(random.nextInt(arms.size())).mutator;
        }
        double bestSample = Double.NEGATIVE_INFINITY;
        MutatorType bestMutator = arms.get(0).mutator;
        for (Arm arm : arms) {
            double sample = sampleBeta(arm.alpha.get(), arm.beta.get());
            if (sample > bestSample) {
                bestSample = sample;
                bestMutator = arm.mutator;
            }
        }
        return bestMutator;
    }

    @Override
    public void recordMutationAttempt(MutatorType mutatorType, MutationAttemptStatus status) {
        // Arm arm = lookup(mutatorType);
        // if (arm == null || status == MutationAttemptStatus.SUCCESS) {
        //     return;
        // }
        // if (status == MutationAttemptStatus.FAILED) arm.beta.incrementAndGet();
        // I think we should not penalize failed attempts
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

    private double sampleBeta(int alpha, int beta) {
        double x = sampleGamma(alpha);
        double y = sampleGamma(beta);
        return x / (x + y);
    }

    private double sampleGamma(double shape) {
        if (shape <= 0.0) {
            throw new IllegalArgumentException("Gamma shape must be positive.");
        }
        if (shape < 1.0) {
            // Weibull algorithm should not happen though since we use alpha,beta >= 1
            double u = random.nextDouble();
            return sampleGamma(shape + 1.0) * Math.pow(u, 1.0 / shape);
        }
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x = random.nextGaussian();
            double v = 1.0 + c * x;
            if (v <= 0.0) {
                continue;
            }
            v = v * v * v;
            double u = random.nextDouble();
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
