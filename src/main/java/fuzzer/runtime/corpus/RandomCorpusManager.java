package fuzzer.runtime.corpus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
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
    private static final double SEED_SHARE_CAP = 0.10;
    private static final long SEED_SHARE_LOG_INTERVAL_MS = 60_000L;
    private static final AtomicLong LAST_SEED_SHARE_LOG_MS = new AtomicLong(0L);

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
            List<TestCase> evicted = enforceSeedShareLimit(testCase);
            logSeedSharesIfDue();
            return CorpusDecision.accepted(evicted);
        }

        TestCase evicted = corpus.set(victimIndex, testCase);
        LOGGER.fine(() -> String.format(
                "Random corpus replaced %s with %s (victim index %d)",
                evicted != null ? evicted.getName() : "<null>",
                testCase.getName(),
                victimIndex));
        List<TestCase> evictedSeeds = enforceSeedShareLimit(testCase);
        logSeedSharesIfDue();
        return CorpusDecision.replaced(evicted, evictedSeeds);
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

    private List<TestCase> enforceSeedShareLimit(TestCase retained) {
        if (SEED_SHARE_CAP <= 0.0 || retained == null) {
            return List.of();
        }
        String seedName = retained.getSeedName();
        if (seedName == null || seedName.isBlank()) {
            return List.of();
        }

        ArrayList<TestCase> sameSeed = new ArrayList<>();
        Map<String, Integer> seedCounts = new HashMap<>();
        for (TestCase candidate : corpus) {
            String candidateSeed = candidate.getSeedName();
            String normalizedSeed = (candidateSeed == null || candidateSeed.isBlank()) ? "<unknown>" : candidateSeed;
            seedCounts.merge(normalizedSeed, 1, Integer::sum);
            if (seedName.equals(candidate.getSeedName())) {
                sameSeed.add(candidate);
            }
        }
        if (sameSeed.size() <= 1) {
            return List.of();
        }

        int distinctSeeds = Math.max(1, seedCounts.size());
        double effectiveCap = Math.max(SEED_SHARE_CAP, 1.0 / distinctSeeds);

        sameSeed.remove(retained);

        int total = corpus.size();
        int count = sameSeed.size() + 1;
        int maxAllowed = Math.max(1, (int) Math.floor(total * effectiveCap));
        if (count <= maxAllowed) {
            return List.of();
        }

        ArrayList<TestCase> evicted = new ArrayList<>();
        while (count > maxAllowed && !sameSeed.isEmpty()) {
            int victimIndex = random.nextInt(sameSeed.size());
            TestCase candidate = sameSeed.remove(victimIndex);
            evicted.add(candidate);
            count--;
            total--;
            maxAllowed = Math.max(1, (int) Math.floor(total * effectiveCap));
        }

        if (!evicted.isEmpty()) {
            corpus.removeAll(evicted);
            LOGGER.fine(() -> String.format(
                    "Seed share cap evicted %d case(s) for seed %s (cap %.0f%%)",
                    evicted.size(),
                    seedName,
                    effectiveCap * 100.0));
        }
        return evicted;
    }

    private void logSeedSharesIfDue() {
        if (!LOGGER.isLoggable(Level.INFO)) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = LAST_SEED_SHARE_LOG_MS.get();
        if (now - last < SEED_SHARE_LOG_INTERVAL_MS) {
            return;
        }
        if (!LAST_SEED_SHARE_LOG_MS.compareAndSet(last, now)) {
            return;
        }

        int total = corpus.size();
        if (total == 0) {
            LOGGER.info("Seed shares: corpus empty");
            return;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (TestCase testCase : corpus) {
            String seed = testCase.getSeedName();
            String normalized = (seed == null || seed.isBlank()) ? "<unknown>" : seed;
            counts.merge(normalized, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder summary = new StringBuilder();
        summary.append("Seed shares (total=").append(total)
                .append(", seeds=").append(entries.size())
                .append("): ");
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, Integer> entry = entries.get(i);
            double share = (entry.getValue() * 100.0) / total;
            summary.append(entry.getKey())
                    .append('=')
                    .append(String.format(Locale.ROOT, "%.1f%%", share))
                    .append(" (")
                    .append(entry.getValue())
                    .append(')');
            if (i + 1 < entries.size()) {
                summary.append(", ");
            }
        }
        LOGGER.info(summary.toString());
    }
}
