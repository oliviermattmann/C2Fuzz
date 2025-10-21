package fuzzer.corpus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import fuzzer.util.MethodOptimizationVector;
import fuzzer.util.OptimizationVector;
import fuzzer.util.OptimizationVectors;
import fuzzer.util.TestCase;

/**
 * === Rare Optimization Corpus Manager (thesis approach) ===
 *
 * Maintains a bounded corpus of champions keyed by canonicalized optimization-count vectors.
 * New testcases are evaluated with the rarity-based score described in the thesis spec and
 * either accepted, rejected, or used to replace an existing champion. Feature and pair
 * frequencies are only updated when a new canonical vector is accepted, ensuring scores remain
 * stable over time.
 */
public class RarityCorpusManager {

    private static final Logger LOGGER = LoggingConfig.getLogger(RarityCorpusManager.class);

    private static final double EPS = 1e-9;
    private static final int[] DEFAULT_BUCKETS = {0, 1, 2, 3, 4, 8, 16, 32, 64};

    private final int numFeatures;
    private final boolean usePairRarity;
    private final double alpha;
    private final double alphaPairs;
    private final double lambda;
    private final double beta;
    private final int corpusCapacity;
    private final int[] canonicalBuckets;

    private final Map<Integer, ArrayList<CorpusEntry>> championsByHash = new HashMap<>();
    private final ArrayList<CorpusEntry> championEntries = new ArrayList<>();
    private final long[] featureFrequencies;
    private final long[] pairFrequencies;

    public RarityCorpusManager(int numFeatures) {
        this(numFeatures, Parameters.defaults());
    }

    public RarityCorpusManager(int numFeatures, int corpusCapacity) {
        this(numFeatures, Parameters.builder().corpusCapacity(corpusCapacity).build());
    }

    public RarityCorpusManager(int numFeatures, Parameters parameters) {
        this.numFeatures = numFeatures;
        this.usePairRarity = parameters.usePairRarity;
        this.alpha = parameters.alpha;
        this.alphaPairs = parameters.alphaPairs;
        this.lambda = parameters.lambda;
        this.beta = parameters.beta;
        this.corpusCapacity = parameters.corpusCapacity;
        this.canonicalBuckets = Arrays.copyOf(parameters.canonicalBuckets, parameters.canonicalBuckets.length);
        this.featureFrequencies = new long[numFeatures];
        this.pairFrequencies = new long[numFeatures * numFeatures];
    }

    public synchronized Decision consider(TestCase testCase, OptimizationVectors optVectors, long runtimeNanos) {
        if (optVectors == null) {
            return Decision.noData("Missing optimization vectors");
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            return Decision.noData("Empty method vectors");
        }

        Candidate bestCandidate = null;
        for (MethodOptimizationVector mov : methodVectors) {
            if (mov == null || mov.getOptimizations() == null) {
                continue;
            }
            int[] counts = Arrays.copyOf(mov.getOptimizations().counts, numFeatures);
            Candidate candidate = evaluateCandidate(counts, runtimeNanos);
            if (candidate == null) {
                continue;
            }
            if (bestCandidate == null || candidate.objective > bestCandidate.objective + EPS) {
                bestCandidate = candidate;
            } else if (bestCandidate != null && Math.abs(candidate.objective - bestCandidate.objective) <= EPS
                    && candidate.runtimeMillis < bestCandidate.runtimeMillis) {
                bestCandidate = candidate;
            }
        }

        if (bestCandidate == null || bestCandidate.activeFeatures.length == 0) {
            return Decision.noData("No active optimizations");
        }

        int[] canonical = canonicalize(bestCandidate.rawCounts);
        int hash = Arrays.hashCode(canonical);
        ArrayList<CorpusEntry> bucket = championsByHash.computeIfAbsent(hash, k -> new ArrayList<>());

        for (CorpusEntry entry : bucket) {
            if (Arrays.equals(entry.canonicalCounts, canonical)) {
                if (bestCandidate.objective > entry.objective + EPS
                        || (Math.abs(bestCandidate.objective - entry.objective) <= EPS
                                && bestCandidate.runtimeMillis < entry.runtimeMillis)) {
                    TestCase previousChampion = entry.getChampion();
                    entry.update(bestCandidate, testCase);
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.log(Level.FINE, String.format(
                                "[RarityCorpus] Replaced champion for %s with score %.4f and runtime %.2fms",
                                testCase.getName(), bestCandidate.rarityScore, bestCandidate.runtimeMillis));
                    }
                    List<TestCase> evicted = enforceCapacity();
                    return Decision.replaced(entry, bestCandidate, canonical, previousChampion, evicted);
                }
                return Decision.rejected(entry, bestCandidate, canonical);
            }
        }

        CorpusEntry newEntry = new CorpusEntry(canonical, bestCandidate, testCase);
        bucket.add(newEntry);
        championEntries.add(newEntry);
        incrementFrequencies(bestCandidate);
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, String.format(
                    "[RarityCorpus] Accepted new champion %s score %.4f objective %.4f runtime %.2fms",
                    testCase.getName(), bestCandidate.rarityScore, bestCandidate.objective, bestCandidate.runtimeMillis));
        }
        List<TestCase> evicted = enforceCapacity();
        return Decision.accepted(newEntry, evicted);
    }

    private Candidate evaluateCandidate(int[] counts, long runtimeNanos) {
        int[] active = collectActiveFeatures(counts);
        if (active.length == 0) {
            return null;
        }

        double s1 = 0.0;
        for (int feature : active) {
            s1 += 1.0 / (featureFrequencies[feature] + alpha);
        }

        double s2 = 0.0;
        if (usePairRarity && active.length > 1) {
            int pairCount = 0;
            for (int i = 0; i < active.length; i++) {
                for (int j = i + 1; j < active.length; j++) {
                    int idx = pairIndex(active[i], active[j]);
                    s2 += 1.0 / (pairFrequencies[idx] + alphaPairs);
                    pairCount++;
                }
            }
            if (pairCount > 0) {
                s2 /= pairCount;
            }
        }

        double rarityScore = s1 + (usePairRarity ? lambda * s2 : 0.0);
        if (rarityScore <= 0.0) {
            return null;
        }

        double runtimeMillis = Math.max(0.0, runtimeNanos / 1_000_000.0);
        double objective = rarityScore / Math.pow(runtimeMillis + 1.0, beta);
        return new Candidate(counts, active, rarityScore, objective, runtimeMillis);
    }

    private int[] canonicalize(int[] counts) {
        int[] canonical = new int[counts.length];
        for (int i = 0; i < counts.length; i++) {
            canonical[i] = canonicalizeValue(counts[i]);
        }
        return canonical;
    }

    private int canonicalizeValue(int value) {
        if (value <= 0) {
            return 0;
        }
        for (int i = 1; i < canonicalBuckets.length; i++) {
            if (value <= canonicalBuckets[i]) {
                return canonicalBuckets[i];
            }
        }
        int bucket = canonicalBuckets[canonicalBuckets.length - 1];
        while (bucket < value && bucket > 0) {
            int next = bucket << 1;
            if (next <= 0 || next <= bucket) {
                return Integer.MAX_VALUE;
            }
            bucket = Math.min(next, Integer.MAX_VALUE);
        }
        return bucket;
    }

    private int[] collectActiveFeatures(int[] counts) {
        int[] tmp = new int[counts.length];
        int m = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) {
                tmp[m++] = i;
            }
        }
        return Arrays.copyOf(tmp, m);
    }

    private void incrementFrequencies(Candidate candidate) {
        for (int feature : candidate.activeFeatures) {
            featureFrequencies[feature]++;
        }

        if (usePairRarity && candidate.activeFeatures.length > 1) {
            for (int i = 0; i < candidate.activeFeatures.length; i++) {
                for (int j = i + 1; j < candidate.activeFeatures.length; j++) {
                    int idx = pairIndex(candidate.activeFeatures[i], candidate.activeFeatures[j]);
                    pairFrequencies[idx]++;
                }
            }
        }
    }

    private int pairIndex(int a, int b) {
        if (a == b) {
            throw new IllegalArgumentException("pairIndex called with identical indices");
        }
        if (a > b) {
            int tmp = a;
            a = b;
            b = tmp;
        }
        return a * numFeatures + b;
    }

    private List<TestCase> enforceCapacity() {
        if (corpusCapacity <= 0 || championEntries.size() <= corpusCapacity) {
            return Collections.emptyList();
        }
        CorpusShrinkResult shrinkResult = shrinkToSize(corpusCapacity);
        if (shrinkResult.removedEntries().isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<TestCase> evicted = new ArrayList<>();
        for (CorpusEntry entry : shrinkResult.removedEntries()) {
            TestCase champion = entry.getChampion();
            if (champion != null) {
                evicted.add(champion);
            }
        }
        return evicted;
    }

    public synchronized CorpusShrinkResult shrinkToSize(int targetSize) {
        int currentSize = championEntries.size();
        if (targetSize <= 0) {
            List<CorpusEntry> removed = List.copyOf(championEntries);
            clear();
            return new CorpusShrinkResult(0, currentSize, Collections.emptyList(), removed);
        }
        if (currentSize <= targetSize) {
            return new CorpusShrinkResult(targetSize, currentSize, List.copyOf(championEntries), Collections.emptyList());
        }

        ArrayList<CorpusEntry> pool = new ArrayList<>(championEntries);
        Map<CorpusEntry, Set<Integer>> coverage = new HashMap<>();
        Set<Integer> uncovered = new HashSet<>();
        for (CorpusEntry entry : pool) {
            Set<Integer> features = new HashSet<>();
            for (int f : entry.activeFeatures) {
                features.add(f);
            }
            coverage.put(entry, features);
            uncovered.addAll(features);
        }

        ArrayList<CorpusEntry> selected = new ArrayList<>();
        Set<CorpusEntry> used = new HashSet<>();

        while (!uncovered.isEmpty() && selected.size() < targetSize) {
            CorpusEntry best = null;
            int bestCover = -1;
            for (CorpusEntry entry : pool) {
                if (used.contains(entry)) {
                    continue;
                }
                Set<Integer> features = coverage.get(entry);
                int cover = 0;
                for (Integer feature : features) {
                    if (uncovered.contains(feature)) {
                        cover++;
                    }
                }
                if (cover == 0) {
                    continue;
                }
                if (cover > bestCover) {
                    best = entry;
                    bestCover = cover;
                } else if (cover == bestCover && best != null) {
                    if (entry.objective > best.objective + EPS
                            || (Math.abs(entry.objective - best.objective) <= EPS
                                    && entry.runtimeMillis < best.runtimeMillis)) {
                        best = entry;
                    }
                }
            }
            if (best == null) {
                break;
            }
            selected.add(best);
            used.add(best);
            for (Integer feature : coverage.get(best)) {
                uncovered.remove(feature);
            }
        }

        if (selected.size() < targetSize) {
            ArrayList<CorpusEntry> remaining = new ArrayList<>();
            for (CorpusEntry entry : pool) {
                if (!used.contains(entry)) {
                    remaining.add(entry);
                }
            }
            remaining.sort(Comparator
                    .comparingDouble((CorpusEntry e) -> -e.objective)
                    .thenComparingDouble(e -> e.runtimeMillis));
            for (CorpusEntry entry : remaining) {
                if (selected.size() >= targetSize) {
                    break;
                }
                selected.add(entry);
            }
        }

        HashSet<CorpusEntry> selectedSet = new HashSet<>(selected);
        ArrayList<CorpusEntry> removed = new ArrayList<>();
        for (CorpusEntry entry : championEntries) {
            if (!selectedSet.contains(entry)) {
                removed.add(entry);
            }
        }

        rebuild(selected);
        return new CorpusShrinkResult(targetSize, currentSize, List.copyOf(selected), List.copyOf(removed));
    }

    private void rebuild(List<CorpusEntry> entries) {
        championsByHash.clear();
        championEntries.clear();
        Arrays.fill(featureFrequencies, 0L);
        Arrays.fill(pairFrequencies, 0L);
        for (CorpusEntry entry : entries) {
            int hash = Arrays.hashCode(entry.canonicalCounts);
            championsByHash.computeIfAbsent(hash, k -> new ArrayList<>()).add(entry);
            championEntries.add(entry);
            incrementFrequencies(entry.asCandidate());
        }
    }

    public synchronized void clear() {
        championsByHash.clear();
        championEntries.clear();
        Arrays.fill(featureFrequencies, 0L);
        Arrays.fill(pairFrequencies, 0L);
    }

    public synchronized List<CorpusEntry> snapshot() {
        return List.copyOf(championEntries);
    }

    public static final class Parameters {
        final boolean usePairRarity;
        final double alpha;
        final double alphaPairs;
        final double lambda;
        final double beta;
        final int corpusCapacity;
        final int[] canonicalBuckets;

        private Parameters(boolean usePairRarity, double alpha, double alphaPairs, double lambda, double beta,
                int corpusCapacity, int[] canonicalBuckets) {
            this.usePairRarity = usePairRarity;
            this.alpha = alpha;
            this.alphaPairs = alphaPairs;
            this.lambda = lambda;
            this.beta = beta;
            this.corpusCapacity = corpusCapacity;
            this.canonicalBuckets = canonicalBuckets;
        }

        public static Parameters defaults() {
            return new Parameters(true, 1.0, 1.0, 0.3, 0.5, Integer.MAX_VALUE, DEFAULT_BUCKETS);
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private boolean usePairRarity = true;
            private double alpha = 1.0;
            private double alphaPairs = 1.0;
            private double lambda = 0.3;
            private double beta = 0.5;
            private int corpusCapacity = Integer.MAX_VALUE;
            private int[] canonicalBuckets = DEFAULT_BUCKETS;

            public Builder usePairRarity(boolean value) {
                this.usePairRarity = value;
                return this;
            }

            public Builder alpha(double value) {
                this.alpha = value;
                return this;
            }

            public Builder alphaPairs(double value) {
                this.alphaPairs = value;
                return this;
            }

            public Builder lambda(double value) {
                this.lambda = value;
                return this;
            }

            public Builder beta(double value) {
                this.beta = value;
                return this;
            }

            public Builder corpusCapacity(int value) {
                this.corpusCapacity = value;
                return this;
            }

            public Builder canonicalBuckets(int[] values) {
                this.canonicalBuckets = Arrays.copyOf(values, values.length);
                return this;
            }

            public Parameters build() {
                return new Parameters(usePairRarity, alpha, alphaPairs, lambda, beta, corpusCapacity, canonicalBuckets);
            }
        }
    }

    public static final class CorpusEntry {
        private final int[] canonicalCounts;
        private int[] rawCounts;
        private int[] activeFeatures;
        private double rarityScore;
        private double objective;
        private double runtimeMillis;
        private TestCase champion;

        private CorpusEntry(int[] canonicalCounts, Candidate candidate, TestCase testCase) {
            this.canonicalCounts = Arrays.copyOf(canonicalCounts, canonicalCounts.length);
            update(candidate, testCase);
        }

        public String getTestCaseName() {
            return champion != null ? champion.getName() : "<unknown>";
        }

        public double getRarityScore() {
            return rarityScore;
        }

        public double getObjective() {
            return objective;
        }

        public double getRuntimeMillis() {
            return runtimeMillis;
        }

        public int[] getCanonicalCounts() {
            return Arrays.copyOf(canonicalCounts, canonicalCounts.length);
        }

        public int[] getRawCounts() {
            return Arrays.copyOf(rawCounts, rawCounts.length);
        }

        public int[] getActiveFeatures() {
            return Arrays.copyOf(activeFeatures, activeFeatures.length);
        }

        public TestCase getChampion() {
            return champion;
        }

        private void update(Candidate candidate, TestCase testCase) {
            this.rawCounts = Arrays.copyOf(candidate.rawCounts, candidate.rawCounts.length);
            this.activeFeatures = Arrays.copyOf(candidate.activeFeatures, candidate.activeFeatures.length);
            this.rarityScore = candidate.rarityScore;
            this.objective = candidate.objective;
            this.runtimeMillis = candidate.runtimeMillis;
            this.champion = testCase;
        }

        private Candidate asCandidate() {
            return new Candidate(rawCounts, activeFeatures, rarityScore, objective, runtimeMillis);
        }
    }

    public enum Outcome {
        NO_DATA,
        REJECTED,
        ACCEPTED,
        REPLACED
    }

    public static final class Decision {
        private final Outcome outcome;
        private final CorpusEntry entry;
        private final Candidate candidate;
        private final int[] canonicalCounts;
        private final String reason;
        private final TestCase previousChampion;
        private final List<TestCase> evictedChampions;

        private Decision(Outcome outcome, CorpusEntry entry, Candidate candidate, int[] canonicalCounts,
                String reason, TestCase previousChampion, List<TestCase> evictedChampions) {
            this.outcome = outcome;
            this.entry = entry;
            this.candidate = candidate;
            this.canonicalCounts = canonicalCounts == null ? null : Arrays.copyOf(canonicalCounts, canonicalCounts.length);
            this.reason = reason;
            this.previousChampion = previousChampion;
            this.evictedChampions = (evictedChampions == null || evictedChampions.isEmpty())
                    ? Collections.emptyList()
                    : List.copyOf(evictedChampions);
        }

        public static Decision noData(String reason) {
            return new Decision(Outcome.NO_DATA, null, null, null, reason, null, Collections.emptyList());
        }

        public static Decision rejected(CorpusEntry entry, Candidate candidate, int[] canonicalCounts) {
            return new Decision(Outcome.REJECTED, entry, candidate, canonicalCounts, null, null, Collections.emptyList());
        }

        public static Decision accepted(CorpusEntry entry, List<TestCase> evictedChampions) {
            return new Decision(Outcome.ACCEPTED, entry, entry.asCandidate(), entry.getCanonicalCounts(), null,
                    null, evictedChampions);
        }

        public static Decision replaced(CorpusEntry entry, Candidate candidate, int[] canonicalCounts,
                TestCase previousChampion, List<TestCase> evictedChampions) {
            return new Decision(Outcome.REPLACED, entry, candidate, canonicalCounts, null,
                    previousChampion, evictedChampions);
        }

        public Outcome outcome() {
            return outcome;
        }

        public CorpusEntry entry() {
            return entry;
        }

        public Candidate candidate() {
            return candidate;
        }

        public String reason() {
            return reason;
        }

        public int[] canonicalCounts() {
            return canonicalCounts == null ? null : Arrays.copyOf(canonicalCounts, canonicalCounts.length);
        }

        public TestCase previousChampion() {
            return previousChampion;
        }

        public List<TestCase> evictedChampions() {
            return evictedChampions;
        }
    }

    public static final class Candidate {
        private final int[] rawCounts;
        private final int[] activeFeatures;
        private final double rarityScore;
        private final double objective;
        private final double runtimeMillis;

        private Candidate(int[] rawCounts, int[] activeFeatures, double rarityScore, double objective,
                double runtimeMillis) {
            this.rawCounts = Arrays.copyOf(rawCounts, rawCounts.length);
            this.activeFeatures = Arrays.copyOf(activeFeatures, activeFeatures.length);
            this.rarityScore = rarityScore;
            this.objective = objective;
            this.runtimeMillis = runtimeMillis;
        }

        public int[] rawCounts() {
            return Arrays.copyOf(rawCounts, rawCounts.length);
        }

        public int[] activeFeatures() {
            return Arrays.copyOf(activeFeatures, activeFeatures.length);
        }

        public double rarityScore() {
            return rarityScore;
        }

        public double objective() {
            return objective;
        }

        public double runtimeMillis() {
            return runtimeMillis;
        }
    }

    public static final class CorpusShrinkResult {
        private final int targetSize;
        private final int previousSize;
        private final List<CorpusEntry> retained;
        private final List<CorpusEntry> removed;

        private CorpusShrinkResult(int targetSize, int previousSize, List<CorpusEntry> retained, List<CorpusEntry> removed) {
            this.targetSize = targetSize;
            this.previousSize = previousSize;
            this.retained = retained;
            this.removed = removed;
        }

        public int targetSize() {
            return targetSize;
        }

        public int previousSize() {
            return previousSize;
        }

        public int retainedSize() {
            return retained.size();
        }

        public List<CorpusEntry> retainedEntries() {
            return retained;
        }

        public List<CorpusEntry> removedEntries() {
            return removed;
        }
    }
}
