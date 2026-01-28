package fuzzer.runtime.corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Logger;

import fuzzer.mutators.MutatorType;
import fuzzer.runtime.scoring.Scorer;
import fuzzer.runtime.scoring.ScorePreview;
import fuzzer.util.LoggingConfig;
import fuzzer.util.OptimizationVectors;
import fuzzer.util.TestCase;


/**
 * Corpus manager that uses fully random retention. Every evaluated test case
 * has the same probability of entering the corpus regardless of current
 * capacity. When the corpus is full a uniformly random incumbent is evicted.
 */
public final class RandomCorpusManager implements CorpusManager {

    private static final Logger LOGGER = LoggingConfig.getLogger(RandomCorpusManager.class);

    private final List<TestCase> corpus = new ArrayList<>();
    private final int capacity;
    private final Random random;
    private final double acceptProbability;
    private final Scorer scorer;
    private final BlockingQueue<TestCase> mutationQueue;

    public RandomCorpusManager(int capacity,
                               double acceptProbability,
                               Random random,
                               Scorer scorer,
                               BlockingQueue<TestCase> mutationQueue) {
        this.capacity = capacity;
        this.acceptProbability = Math.min(Math.max(acceptProbability, 0.0), 1.0);
        this.random = Objects.requireNonNull(random, "random");
        this.scorer = Objects.requireNonNull(scorer, "scorer");
        this.mutationQueue = Objects.requireNonNull(mutationQueue, "mutationQueue");
    }

    @Override
    public synchronized CorpusDecision evaluate(TestCase testCase, ScorePreview preview) {
        if (testCase == null) {
            return CorpusDecision.discarded("Null test case");
        }

        // Avoid duplicates when the evaluator retries the same instance.
        corpus.remove(testCase);

        if (capacity <= 0) {
            return CorpusDecision.discarded("Corpus capacity disabled");
        }

        boolean hasCapacity = corpus.size() < capacity;
        int victimIndex = hasCapacity ? -1 : random.nextInt(corpus.size());
        TestCase victim = (victimIndex >= 0) ? corpus.get(victimIndex) : null;

        boolean accept = shouldAccept() || testCase.getMutation() == MutatorType.SEED;
        if (!accept) {
            if (victim != null) {
                rescoreTestCase(victim);
            }
            return CorpusDecision.rejected(testCase, "Random rejection");
        }

        if (hasCapacity) {
            corpus.add(testCase);
            LOGGER.fine(() -> String.format("Accepted %s into random corpus (size=%d/%d)",
                    testCase.getName(), corpus.size(), capacity));
            return CorpusDecision.accepted(List.of());
        }

        TestCase evicted = corpus.set(victimIndex, testCase);
        LOGGER.fine(() -> String.format(
                "Random corpus replaced %s with %s (victim index %d)",
                evicted != null ? evicted.getName() : "<null>",
                testCase.getName(),
                victimIndex));
        return CorpusDecision.replaced(evicted, List.of());
    }

    @Override
    public synchronized void synchronizeChampionScore(TestCase testCase) {
        rescoreTestCase(testCase);
    }

    @Override
    public synchronized int corpusSize() {
        return corpus.size();
    }

    @Override
    public synchronized boolean remove(TestCase testCase, String reason) {
        if (testCase == null) {
            return false;
        }
        boolean removed = corpus.remove(testCase);
        if (removed) {
            LOGGER.info(String.format(
                    "Removed %s from corpus%s",
                    testCase.getName(),
                    reason != null && !reason.isBlank() ? ": " + reason : ""));
        }
        return removed;
    }

    private boolean shouldAccept() {
        if (acceptProbability <= 0.0) {
            return false;
        }
        if (acceptProbability >= 1.0) {
            return true;
        }
        return random.nextDouble() < acceptProbability;
    }

    private void rescoreTestCase(TestCase testCase) {
        if (testCase == null) {
            return;
        }
        OptimizationVectors vectors = testCase.getOptVectors();
        if (vectors == null) {
            return;
        }
        ScorePreview refreshed = scorer.previewScore(testCase, vectors);
        double rescored = (refreshed != null) ? Math.max(0.0, refreshed.score()) : 0.0;
        boolean wasQueued = false;
        if (testCase.isActiveChampion()) {
            wasQueued = mutationQueue.remove(testCase);
        }
        testCase.setScore(rescored);
        if (testCase.isActiveChampion() && wasQueued) {
            mutationQueue.offer(testCase);
        }
    }
}
