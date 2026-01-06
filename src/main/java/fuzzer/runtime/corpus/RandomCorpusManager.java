package fuzzer.runtime.corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.logging.Logger;

import fuzzer.mutators.MutatorType;
import fuzzer.runtime.scoring.ScorePreview;
import fuzzer.util.LoggingConfig;
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

    public RandomCorpusManager(int capacity, double acceptProbability, Random random) {
        this.capacity = capacity;
        this.acceptProbability = Math.min(Math.max(acceptProbability, 0.0), 1.0);
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public CorpusDecision evaluate(TestCase testCase, ScorePreview preview) {
        if (testCase == null) {
            return CorpusDecision.discarded("Null test case");
        }

        if (testCase.getMutation() == MutatorType.SEED) {

        } 
        if (!shouldAccept()) {
            if (testCase.getMutation() != MutatorType.SEED) {
                return CorpusDecision.discarded("Random rejection");
            }
        }

        // Avoid duplicates when the evaluator retries the same instance.
        corpus.remove(testCase);

        if (capacity <= 0) {
            return CorpusDecision.discarded("Corpus capacity disabled");
        }

        if (corpus.size() < capacity) {
            corpus.add(testCase);
            LOGGER.fine(() -> String.format("Accepted %s into random corpus (size=%d/%d)",
                    testCase.getName(), corpus.size(), capacity));
            return CorpusDecision.accepted(List.of());
        }

        int victimIndex = random.nextInt(corpus.size());
        TestCase evicted = corpus.set(victimIndex, testCase);
        LOGGER.fine(() -> String.format(
                "Random corpus replaced %s with %s (victim index %d)",
                evicted != null ? evicted.getName() : "<null>",
                testCase.getName(),
                victimIndex));
        return CorpusDecision.replaced(evicted, List.of());
    }

    @Override
    public void synchronizeChampionScore(TestCase testCase) {
        // Random policy does not maintain auxiliary score data.
    }

    @Override
    public int corpusSize() {
        return corpus.size();
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
}
