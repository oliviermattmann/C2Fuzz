package fuzzer.runtime.corpus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.runtime.RuntimeWeighter;
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
    private static final double SCORE_EPS = 1e-9;

    private final Map<IntArrayKey, ChampionEntry> champions = new HashMap<>();
    private final int capacity;
    private final BlockingQueue<TestCase> mutationQueue;
    private final ScoringMode scoringMode;
    private final Scorer scorer;

    public ChampionCorpusManager(
            int capacity,
            BlockingQueue<TestCase> mutationQueue,
            ScoringMode scoringMode,
            Scorer scorer) {
        this.capacity = capacity;
        this.mutationQueue = Objects.requireNonNull(mutationQueue, "mutationQueue");
        this.scoringMode = Objects.requireNonNull(scoringMode, "scoringMode");
        this.scorer = Objects.requireNonNull(scorer, "scorer");
    }

    @Override
    public CorpusDecision evaluate(TestCase testCase, ScorePreview preview) {
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

        double delta = score - incumbentScore;
        boolean replace = false;
        if (delta > 0) {
            replace = true;
        }
        else if (delta == 0) {
            int candidateDepth = testCase.getMutationDepth();
            int incumbentDepth = existing.testCase != null ? existing.testCase.getMutationDepth() : -1;
            replace = (candidateDepth > incumbentDepth);

        } 

        if (replace) {
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
    public int corpusSize() {
        return champions.size();
    }

    @Override
    public void synchronizeChampionScore(TestCase testCase) {
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
        double combined = normalized;
        if (scoringMode == ScoringMode.PF_IDF) {
            double runtimeWeight = RuntimeWeighter.weight(champion);
            combined = applyRuntimeWeight(normalized, runtimeWeight);
        }
        if (Math.abs(combined - entry.score) > SCORE_EPS) {
            entry.score = combined;
            boolean wasQueued = false;
            if (champion.isActiveChampion()) {
                wasQueued = mutationQueue.remove(champion);
            }
            champion.setScore(combined);
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

    private static double applyRuntimeWeight(double rawScore, double runtimeWeight) {
        if (!Double.isFinite(rawScore) || !Double.isFinite(runtimeWeight)) {
            return 0.0;
        }
        if (rawScore <= 0.0 || runtimeWeight <= 0.0) {
            return 0.0;
        }
        return rawScore * runtimeWeight;
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
