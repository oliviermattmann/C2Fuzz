package fuzzer.runtime.corpus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.runtime.GlobalStats;
import fuzzer.runtime.ScoringMode;
import fuzzer.runtime.scoring.ScorePreview;
import fuzzer.runtime.scoring.Scorer;
import fuzzer.util.LoggingConfig;
import fuzzer.util.OptimizationVectors;
import fuzzer.util.TestCase;

/**
 * Corpus manager that mirrors the existing champion/bucketing behaviour.
 */
public final class ChampionCorpusManager implements CorpusManager {

    private static final Logger LOGGER = LoggingConfig.getLogger(ChampionCorpusManager.class);
    private static final double SCORE_EPS = 1e-2;
    private static final double RUNTIME_WEIGHT_FLOOR = 0.1;

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
                return CorpusDecision.replaced(previous, evicted);
            }

        }
        double ratio = (incumbentScore > 0.0) ? (score / incumbentScore) : Double.POSITIVE_INFINITY;
        if (ratio > 1.05) {
            TestCase previous = existing.testCase;
            existing.update(testCase, hashedCounts, score);
            refreshChampionScore(existing);
            ArrayList<TestCase> evicted = enforceChampionCapacity();
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

        ArrayList<ChampionEntry> entries = new ArrayList<>(champions.values());
        entries.sort(Comparator.comparingDouble(entry -> entry.score));

        int index = 0;
        while (champions.size() > capacity && index < entries.size()) {
            ChampionEntry candidate = entries.get(index++);
            if (champions.remove(candidate.key) != null) {
                evicted.add(candidate.testCase);
            }
        }
        return evicted;
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
        if (scoringMode == ScoringMode.UNIFORM) {
            return baseScore;
        }
        if (testCase == null) {
            return baseScore;
        }
        if (!Double.isFinite(baseScore) || baseScore <= 0.0) {
            return baseScore;
        }
        double globalAvgExecMillis = (globalStats != null) ? globalStats.getAvgExecTimeMillis() : 0.0;
        double tcAvgExecMillis = testCase.getAvgExecTimeMillis();
        if (globalAvgExecMillis <= 0.0 || tcAvgExecMillis <= 0.0) {
            return baseScore;
        }
        double wTime = 1.0 / (1.0 + (tcAvgExecMillis / globalAvgExecMillis));
        if (!Double.isFinite(wTime)) {
            return baseScore;
        }
        return baseScore * Math.max(RUNTIME_WEIGHT_FLOOR, wTime);
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
}
