package fuzzer.runtime.scheduling;

import java.util.List;
import java.util.Objects;
import java.util.Random;

import fuzzer.mutators.MutatorType;
import fuzzer.runtime.scheduling.MutatorScheduler.EvaluationFeedback;
import fuzzer.runtime.scheduling.MutatorScheduler.MutationAttemptStatus;
import fuzzer.util.TestCase;

/**
 * Scheduler that mirrors MopFuzzer's profile-guided weighting scheme. Each
 * mutator starts with weight 1 and is reweighted based on the increase of
 * merged optimization counts (Δ) it triggers.
 */
public final class MopMutatorScheduler implements MutatorScheduler {

    private static final double INITIAL_WEIGHT = 1.0;
    private static final double MIN_WEIGHT = 1e-6;
    private static final double MAX_WEIGHT = 1e6;
    private static final double EPSILON = 0.1; // small explore rate

    private final Entry[] entries;
    private final Entry[] entriesByOrdinal;
    private final Random random;

    public MopMutatorScheduler(List<MutatorType> mutatorTypes, Random random) {
        if (mutatorTypes == null || mutatorTypes.isEmpty()) {
            throw new IllegalArgumentException("Mutator list must not be empty.");
        }
        this.entries = mutatorTypes.stream()
                .map(Entry::new)
                .toArray(Entry[]::new);
        this.random = Objects.requireNonNull(random, "random");
        Entry[] lookup = new Entry[MutatorType.values().length];
        for (Entry entry : entries) {
            lookup[entry.mutator.ordinal()] = entry;
        }
        this.entriesByOrdinal = lookup;
    }

    @Override
    public MutatorType pickMutator(TestCase parent) {
        Objects.requireNonNull(parent, "parent");
        if (random.nextDouble() < EPSILON) {
            return entries[random.nextInt(entries.length)].mutator;
        }
        double total = 0.0;
        for (Entry entry : entries) {
            total += Math.max(entry.weight, MIN_WEIGHT);
        }
        if (!(total > 0.0) || !Double.isFinite(total)) {
            int idx = random.nextInt(entries.length);
            return entries[idx].mutator;
        }
        double r = random.nextDouble(total);
        double cumulative = 0.0;
        for (Entry entry : entries) {
            cumulative += Math.max(entry.weight, MIN_WEIGHT);
            if (r <= cumulative) {
                return entry.mutator;
            }
        }
        return entries[entries.length - 1].mutator;
    }

    @Override
    public void recordMutationAttempt(MutatorType mutatorType, MutationAttemptStatus status) {
        // MopFuzzer does not adjust weights for failed attempts.
    }

    @Override
    public void recordEvaluation(EvaluationFeedback feedback) {
        if (feedback == null) {
            return;
        }
        Entry entry = lookup(feedback.mutatorType());
        if (entry == null) {
            return;
        }
        double delta = euclideanDelta(feedback.parentMergedCounts(), feedback.childMergedCounts());
        double magnitude = euclideanNorm(feedback.childMergedCounts());
        if (!(delta > 0.0) || !(magnitude > 0.0)) {
            return;
        }
        double multiplier = 1.0 + (delta / magnitude);
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            return;
        }
        double newWeight = entry.weight * multiplier;
        if (!Double.isFinite(newWeight) || newWeight <= 0.0) {
            entry.weight = MIN_WEIGHT;
        } else {
            entry.weight = Math.min(newWeight, MAX_WEIGHT);
        }
    }

    private Entry lookup(MutatorType mutatorType) {
        if (mutatorType == null) {
            return null;
        }
        int ordinal = mutatorType.ordinal();
        if (ordinal < 0 || ordinal >= entriesByOrdinal.length) {
            return null;
        }
        return entriesByOrdinal[ordinal];
    }

    private static double euclideanDelta(int[] parentCounts, int[] childCounts) {
        if (childCounts == null || childCounts.length == 0) {
            return 0.0;
        }
        double sumSq = 0.0;
        for (int i = 0; i < childCounts.length; i++) {
            int childVal = childCounts[i];
            int parentVal = (parentCounts != null && i < parentCounts.length) ? parentCounts[i] : 0;
            int diff = childVal - parentVal;
            if (diff > 0) {
                sumSq += (double) diff * diff;
            }
        }
        return Math.sqrt(sumSq);
    }

    private static double euclideanNorm(int[] counts) {
        if (counts == null || counts.length == 0) {
            return 0.0;
        }
        double sumSq = 0.0;
        for (int value : counts) {
            if (value != 0) {
                sumSq += (double) value * value;
            }
        }
        return Math.sqrt(sumSq);
    }

    private static final class Entry {
        final MutatorType mutator;
        double weight = INITIAL_WEIGHT;

        Entry(MutatorType mutator) {
            this.mutator = Objects.requireNonNull(mutator, "mutator");
        }
    }
}
