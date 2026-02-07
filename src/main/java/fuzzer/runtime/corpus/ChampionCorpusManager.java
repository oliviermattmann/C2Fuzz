package fuzzer.runtime.corpus;

import java.util.ArrayList;
import java.util.Arrays;
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

import fuzzer.runtime.monitoring.GlobalStats;
import fuzzer.runtime.scoring.ScoringMode;
import fuzzer.runtime.scoring.ScorePreview;
import fuzzer.runtime.scoring.Scorer;
import fuzzer.logging.LoggingConfig;
import fuzzer.model.OptimizationVectors;
import fuzzer.model.TestCase;

/**
 * Corpus manager that mirrors the existing champion/bucketing behaviour.
 */
public final class ChampionCorpusManager implements CorpusManager {

    private static final Logger LOGGER = LoggingConfig.getLogger(ChampionCorpusManager.class);
    private static final double SCORE_EPS = 1e-2;
    private static final double SEED_SHARE_CAP = 0.10;
    private static final long SEED_SHARE_LOG_INTERVAL_MS = 60_000L;
    private static final AtomicLong LAST_SEED_SHARE_LOG_MS = new AtomicLong(0L);

    private final Random random;

    private final Map<IntArrayKey, ChampionEntry> champions = new HashMap<>();
    private final int capacity;
    private final BlockingQueue<TestCase> mutationQueue;
    private final ScoringMode scoringMode;
    private final Scorer scorer;
    private final GlobalStats globalStats;

    public ChampionCorpusManager(
            int capacity,
            BlockingQueue<TestCase> mutationQueue,
            ScoringMode scoringMode,
            Scorer scorer,
            GlobalStats globalStats,
            Random random) {
        this.random = random;
        this.capacity = capacity;
        this.mutationQueue = Objects.requireNonNull(mutationQueue, "mutationQueue");
        this.scoringMode = Objects.requireNonNull(scoringMode, "scoringMode");
        this.scorer = Objects.requireNonNull(scorer, "scorer");
        this.globalStats = globalStats;
    }

    @Override
    public synchronized CorpusDecision evaluate(TestCase testCase, ScorePreview preview) {
        if (testCase == null) {
            return CorpusDecision.discarded("Null test case");
        }

        int[] hashedCounts = testCase.getHashedOptVector();
        if (hashedCounts == null) {
            return CorpusDecision.discarded("Missing optimization vector");
        }
        boolean hasActivity = false;
        for (int value : hashedCounts) {
            if (value > 0) {
                hasActivity = true;
                break;
            }
        }
        if (!hasActivity) {
            return CorpusDecision.discarded("No active optimizations observed");
        }

        IntArrayKey key = new IntArrayKey(hashedCounts);
        double score = testCase.getScore();
        ChampionEntry existing = champions.get(key);
        if (existing == null) {
            ChampionEntry entry = new ChampionEntry(key, hashedCounts, testCase, score);
            champions.put(key, entry);
            refreshChampionScore(entry);
            ArrayList<TestCase> evicted = enforceChampionCapacity();
            if (evicted.remove(testCase)) {
                return CorpusDecision.discarded(String.format(
                        "Corpus capacity reached; %s score below retention threshold",
                        scoringMode.displayName()));
            }
            evicted.addAll(enforceSeedShareLimit(testCase));
            logSeedSharesIfDue();
            return CorpusDecision.accepted(evicted);
        }

        double incumbentScore = existing.score;
        if (requiresChampionRescore()) {
            incumbentScore = refreshChampionScore(existing);
        }
        
        if (score == incumbentScore && scoringMode == ScoringMode.UNIFORM) {
            // randomly pick one to keep
            if (random.nextBoolean()) {
                return CorpusDecision.rejected(existing.testCase, String.format(
                        "Scores equal in %s mode; retained existing champion",
                        scoringMode.displayName()));
            } else {
                TestCase previous = existing.testCase;
                existing.update(testCase, hashedCounts, score);
                refreshChampionScore(existing);
                ArrayList<TestCase> evicted = enforceChampionCapacity();
                evicted.addAll(enforceSeedShareLimit(testCase));
                logSeedSharesIfDue();
                return CorpusDecision.replaced(previous, evicted);
            }

        }
        if (score > incumbentScore) {
            TestCase previous = existing.testCase;
            existing.update(testCase, hashedCounts, score);
            refreshChampionScore(existing);
            ArrayList<TestCase> evicted = enforceChampionCapacity();
            evicted.addAll(enforceSeedShareLimit(testCase));
            logSeedSharesIfDue();
            return CorpusDecision.replaced(previous, evicted);
        }

        return CorpusDecision.rejected(existing.testCase, String.format(
                "Incumbent has higher or equal %s score",
                scoringMode.displayName()));
    }

    @Override
    public synchronized int corpusSize() {
        return champions.size();
    }

    @Override
    public synchronized void synchronizeChampionScore(TestCase testCase) {
        if (testCase == null) {
            return;
        }
        int[] hashed = testCase.getHashedOptVector();
        if (hashed == null) {
            return;
        }
        ChampionEntry entry = champions.get(new IntArrayKey(hashed));
        if (entry != null && entry.testCase == testCase) {
            entry.score = testCase.getScore();
        }
    }

    @Override
    public synchronized boolean remove(TestCase testCase, String reason) {
        if (testCase == null) {
            return false;
        }
        boolean removed = false;
        int[] hashed = testCase.getHashedOptVector();
        if (hashed != null) {
            IntArrayKey key = new IntArrayKey(hashed);
            ChampionEntry entry = champions.get(key);
            if (entry != null && entry.testCase == testCase) {
                champions.remove(key);
                removed = true;
            }
        }
        if (!removed) {
            IntArrayKey toRemove = null;
            for (Map.Entry<IntArrayKey, ChampionEntry> entry : champions.entrySet()) {
                if (entry.getValue().testCase == testCase) {
                    toRemove = entry.getKey();
                    break;
                }
            }
            if (toRemove != null) {
                champions.remove(toRemove);
                removed = true;
            }
        }
        if (removed && LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info(String.format(
                    "Removed %s from corpus%s",
                    testCase.getName(),
                    reason != null && !reason.isBlank() ? ": " + reason : ""));
        }
        return removed;
    }

    private ArrayList<TestCase> enforceChampionCapacity() {
        ArrayList<TestCase> evicted = new ArrayList<>();
        if (capacity <= 0 || champions.size() <= capacity) {
            return evicted;
        }

        if (SEED_SHARE_CAP > 0.0) {
            while (champions.size() > capacity) {
                SeedStats[] stats = buildSeedStats();
                if (stats.length == 0) {
                    break;
                }
                double effectiveCap = Math.max(SEED_SHARE_CAP, 1.0 / stats.length);
                int maxAllowed = Math.max(1, (int) Math.floor(champions.size() * effectiveCap));
                ChampionEntry candidate = null;
                for (SeedStats stat : stats) {
                    if (stat.count > maxAllowed && stat.count > 1 && stat.minEntry != null) {
                        if (candidate == null || stat.minEntry.score < candidate.score) {
                            candidate = stat.minEntry;
                        }
                    }
                }
                if (candidate == null) {
                    break;
                }
                if (champions.remove(candidate.key) != null) {
                    evicted.add(candidate.testCase);
                } else {
                    break;
                }
            }
        }

        if (champions.size() > capacity) {
            ArrayList<ChampionEntry> entries = new ArrayList<>(champions.values());
            entries.sort(Comparator.comparingDouble(entry -> entry.score));
            int index = 0;
            while (champions.size() > capacity && index < entries.size()) {
                ChampionEntry candidate = entries.get(index++);
                if (champions.remove(candidate.key) != null) {
                    evicted.add(candidate.testCase);
                }
            }
        }
        return evicted;
    }

    private SeedStats[] buildSeedStats() {
        Map<String, SeedStats> statsBySeed = new HashMap<>();
        for (ChampionEntry entry : champions.values()) {
            TestCase candidate = entry.testCase;
            String seed = (candidate == null || candidate.getSeedName() == null || candidate.getSeedName().isBlank())
                    ? "<unknown>"
                    : candidate.getSeedName();
            SeedStats stats = statsBySeed.computeIfAbsent(seed, ignored -> new SeedStats());
            stats.count++;
            if (stats.minEntry == null || entry.score < stats.minEntry.score) {
                stats.minEntry = entry;
            }
        }
        return statsBySeed.values().toArray(new SeedStats[0]);
    }

    private ArrayList<TestCase> enforceSeedShareLimit(TestCase retained) {
        ArrayList<TestCase> evicted = new ArrayList<>();
        if (SEED_SHARE_CAP <= 0.0 || retained == null) {
            return evicted;
        }
        String seedName = retained.getSeedName();
        if (seedName == null || seedName.isBlank()) {
            return evicted;
        }

        ArrayList<ChampionEntry> sameSeed = new ArrayList<>();
        Map<String, Integer> seedCounts = new HashMap<>();
        for (ChampionEntry entry : champions.values()) {
            TestCase candidate = entry.testCase;
            if (candidate != null) {
                String candidateSeed = candidate.getSeedName();
                String normalizedSeed = (candidateSeed == null || candidateSeed.isBlank()) ? "<unknown>" : candidateSeed;
                seedCounts.merge(normalizedSeed, 1, Integer::sum);
                if (seedName.equals(candidateSeed)) {
                    sameSeed.add(entry);
                }
            }
        }
        if (sameSeed.size() <= 1) {
            return evicted;
        }

        int distinctSeeds = Math.max(1, seedCounts.size());
        double effectiveCap = Math.max(SEED_SHARE_CAP, 1.0 / distinctSeeds);

        sameSeed.sort(Comparator.comparingDouble(entry -> entry.score));

        int total = champions.size();
        int count = sameSeed.size();
        int maxAllowed = Math.max(1, (int) Math.floor(total * effectiveCap));
        for (ChampionEntry entry : sameSeed) {
            if (count <= maxAllowed) {
                break;
            }
            if (entry.testCase == retained) {
                continue;
            }
            if (champions.remove(entry.key) != null) {
                evicted.add(entry.testCase);
                count--;
                total--;
                maxAllowed = Math.max(1, (int) Math.floor(total * effectiveCap));
            }
        }
        if (!evicted.isEmpty() && LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(String.format(
                    "Seed share cap evicted %d champion(s) for seed %s (cap %.0f%%)",
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

        int total = champions.size();
        if (total == 0) {
            LOGGER.info("Seed shares: corpus empty");
            return;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (ChampionEntry entry : champions.values()) {
            TestCase testCase = entry.testCase;
            if (testCase == null) {
                continue;
            }
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

    private double refreshChampionScore(ChampionEntry entry) {
        if (entry == null) {
            return 0.0;
        }
        if (!requiresChampionRescore()) {
            return entry.score;
        }
        TestCase champion = entry.testCase;
        if (champion == null) {
            return entry.score;
        }
        OptimizationVectors vectors = champion.getOptVectors();
        if (vectors == null) {
            return entry.score;
        }
        ScorePreview refreshed = scorer.previewScore(champion, vectors);
        double rescored = (refreshed != null) ? Math.max(0.0, refreshed.score()) : 0.0;
        double normalized = Double.isFinite(rescored) ? Math.max(rescored, 0.0) : 0.0;
        if (normalized <= 0.0 && LOGGER.isLoggable(Level.FINE)) {
            String reason = (refreshed != null && refreshed.hotMethodName() != null)
                    ? "rescored value was non-positive"
                    : "score preview returned null";
            LOGGER.fine(String.format(
                    "Champion %s rescored to 0.0 in %s: %s",
                    champion.getName(),
                    scoringMode.displayName(),
                    reason));
        }
        double weighted = applyRuntimeWeight(champion, normalized);
        // Update champion score if it has changed significantly. (0.01) (avoid expensive removal and re-insertion)
        if (Math.abs(weighted - entry.score) > SCORE_EPS) {
            entry.score = weighted;
            boolean wasQueued = false;
            if (champion.isActiveChampion()) {
                wasQueued = mutationQueue.remove(champion);
            }
            champion.setScore(weighted);
            if (champion.isActiveChampion() && wasQueued) {
                try {
                    mutationQueue.put(champion);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    LOGGER.log(Level.WARNING, "Interrupted while requeuing champion", ie);
                }
            }
        }
        return entry.score;
    }

    private double applyRuntimeWeight(TestCase testCase, double baseScore) {
        return baseScore;
    }

    private boolean requiresChampionRescore() {
        return scoringMode == ScoringMode.PF_IDF
                || scoringMode == ScoringMode.PAIR_COVERAGE
                || scoringMode == ScoringMode.NOVEL_FEATURE_BONUS
                || scoringMode == ScoringMode.INTERACTION_PAIR_WEIGHTED;
    }

    private static final class ChampionEntry {
        final IntArrayKey key;
        TestCase testCase;
        double score;
        int[] counts;

        ChampionEntry(IntArrayKey key, int[] counts, TestCase testCase, double score) {
            this.key = key;
            this.counts = Arrays.copyOf(counts, counts.length);
            this.testCase = testCase;
            this.score = score;
        }

        void update(TestCase newChampion, int[] newCounts, double newScore) {
            this.testCase = newChampion;
            this.score = newScore;
            this.counts = Arrays.copyOf(newCounts, newCounts.length);
        }
    }

    private static final class SeedStats {
        int count;
        ChampionEntry minEntry;
    }
}
