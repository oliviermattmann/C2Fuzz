package fuzzer.runtime.scoring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import fuzzer.runtime.monitoring.GlobalStats;
import fuzzer.logging.LoggingConfig;
import fuzzer.model.MethodOptimizationVector;
import fuzzer.model.OptimizationVector;
import fuzzer.model.OptimizationVectors;
import fuzzer.model.TestCase;

public final class PfIdfScorer extends AbstractScorer {

    private static final Logger LOGGER = LoggingConfig.getLogger(PfIdfScorer.class);

    public PfIdfScorer(GlobalStats globalStats) {
        super(globalStats, LOGGER);
    }

    @Override
    public ScoringMode mode() {
        return ScoringMode.PF_IDF;
    }

    @Override
    public PFIDFResult previewScore(TestCase testCase, OptimizationVectors optVectors) {
        Objects.requireNonNull(testCase, "testCase");
        if (optVectors == null) {
            testCase.setScore(0.0);
            testCase.setHashedOptVector(emptyHashedVector());
            logZeroScore(testCase, ScoringMode.PF_IDF, "no optimization vectors available");
            return emptyResult("no optimization vectors available");
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            testCase.setScore(0.0);
            testCase.setHashedOptVector(emptyHashedVector());
            logZeroScore(testCase, ScoringMode.PF_IDF, "optimization vectors empty");
            return emptyResult("optimization vectors empty");
        }
        boolean neutral = shouldUseNeutralAverages(testCase);
        double[] averageFrequencies = neutral
                ? buildNeutralAverageFrequencies()
                : buildAverageFrequencies();
        PFIDFResult result = computePFIDF(methodVectors, averageFrequencies, neutral);
        double score = result.score();
        testCase.setScore(score);
        testCase.setHashedOptVector(bucketCounts(result.optimizationCounts()));
        if (neutral) {
            testCase.consumeNeutralSeedScore();
        }
        if (score <= 0.0) {
            String reason = result.zeroReason() != null
                    ? result.zeroReason()
                    : "PF-IDF score was non-positive";
            logZeroScore(testCase, ScoringMode.PF_IDF, reason);
        }
        return result;
    }

    @Override
    public double commitScore(TestCase testCase, ScorePreview preview) {
        Objects.requireNonNull(testCase, "testCase");
        if (!(preview instanceof PFIDFResult result)) {
            throw new IllegalArgumentException("PF-IDF scorer requires PFIDFResult preview.");
        }
        double score = result.score();
        testCase.setScore(score);
        testCase.setHashedOptVector(bucketCounts(result.optimizationCounts()));
        recordPresentVectors(result.presentVectors());
        return score;
    }

    private PFIDFResult emptyResult(String reason) {
        int featureCount = OptimizationVector.Features.values().length;
        return new PFIDFResult(0.0, new int[0], new int[featureCount], reason, "", "", new int[0][]);
    }

    private PFIDFResult computePFIDF(ArrayList<MethodOptimizationVector> methodOptVectors,
            double[] averageFrequencies,
            boolean neutralStats) {
        int featureCount = OptimizationVector.Features.values().length;
        int[] mergedCounts = new int[featureCount];
        boolean hasAnyCounts = false;

        double bestMethodScore = Double.NEGATIVE_INFINITY;
        boolean bestMethodFound = false;
        String bestMethodName = "";
        String bestClassName = "";
        // Keep per-method "present" vectors so commit can update global pair stats.
        List<int[]> presentVectors = new ArrayList<>();

        for (MethodOptimizationVector methodOptVector : methodOptVectors) {
            if (methodOptVector == null) {
                continue;
            }
            OptimizationVector vector = methodOptVector.getOptimizations();
            if (vector == null || vector.counts == null) {
                continue;
            }
            int[] optimizations = vector.counts;
            boolean methodHasPositiveCounts = false;
            int len = Math.min(optimizations.length, mergedCounts.length);
            for (int i = 0; i < len; i++) {
                int value = optimizations[i];
                if (value > 0) {
                    mergedCounts[i] += value;
                    methodHasPositiveCounts = true;
                    hasAnyCounts = true;
                }
            }
            if (!methodHasPositiveCounts) {
                continue;
            }
            int[] present = extractPresent(optimizations);
            if (present != null) {
                presentVectors.add(present);
            }
            PFIDFComputation methodComputation = computePFIDFForCounts(optimizations, averageFrequencies, neutralStats);
            if (!bestMethodFound || methodComputation.score > bestMethodScore) {
                bestMethodScore = methodComputation.score;
                bestMethodFound = true;
                bestMethodName = methodOptVector.getMethodName();
                bestClassName = methodOptVector.getClassName();
            }
        }

        if (!hasAnyCounts) {
            return new PFIDFResult(0.0, new int[0], new int[featureCount],
                    "no method optimization vector contained optimization data", "", "", presentVectors.toArray(new int[0][]));
        }

        PFIDFComputation mergedComputation = computePFIDFForCounts(mergedCounts, averageFrequencies, neutralStats);
        String zeroReason = (mergedComputation.score > 0.0)
                ? null
                : (mergedComputation.zeroReason != null ? mergedComputation.zeroReason
                        : "computed PF-IDF score was non-positive");
        String hotMethodName = bestMethodFound ? bestMethodName : "";
        String hotClassName = bestMethodFound ? bestClassName : "";
        double compressedScore = compressScore(mergedComputation.score);
        return new PFIDFResult(compressedScore,
                mergedComputation.pairIndices,
                mergedCounts,
                zeroReason,
                hotMethodName,
                hotClassName,
                presentVectors.toArray(new int[0][]));
    }

    private PFIDFComputation computePFIDFForCounts(int[] counts, double[] averageFrequencies, boolean neutralStats) {
        if (counts == null) {
            return new PFIDFComputation(0.0, new int[0], "optimization vector contained no data");
        }

        double eps = 1e-6;
        List<Integer> indexes = new ArrayList<>();
        double[] lifts = new double[counts.length];
        for (int i = 0; i < counts.length; i++) {
            int optCount = counts[i];
            if (optCount <= 0) {
                continue;
            }
            indexes.add(i);
            double averageFreq = (i < averageFrequencies.length) ? averageFrequencies[i] : 0.0;
            double lift = (double) optCount / (averageFreq + eps);
            lifts[i] = lift;
        }

        int m = indexes.size();
        if (m < 2) {
            String reason = String.format(
                    "optimization vector exposes %d optimization feature(s); PF-IDF requires at least 2",
                    m);
            return new PFIDFComputation(0.0, new int[0], reason);
        }

        int[] tmpPairIndices = new int[m * (m - 1) / 2];

        double rawRunCount = neutralStats ? 1.0 : globalStats.getRunCount();
        double N = Math.max(1.0, rawRunCount);
        double denominator = neutralStats ? Math.log(2.0) : Math.log(N + 1.0);
        if (denominator <= 0.0) {
            denominator = 1e-9;
        }
        double sum = 0.0;
        int numPairs = 0;

        int tmpCtr = 0;
        for (int a = 0; a < m; a++) {
            int i = indexes.get(a);
            for (int b = a + 1; b < m; b++) {
                int j = indexes.get(b);
                tmpPairIndices[tmpCtr++] = globalStats.pairIdx(i, j);
                double s = Math.sqrt(lifts[i] * lifts[j]) - 1.0;
                if (s <= 0.0) {
                    continue;
                }
                double nij = neutralStats ? 0.0 : (double) globalStats.getPairCount(i, j);
                double numerator = neutralStats ? Math.log(2.0) : Math.log((N + 1.0) / (nij + 1.0));
                double w = numerator / denominator;
                double pairTerm = s * w;
                sum += pairTerm;
                numPairs++;
            }
        }

        double score = (numPairs > 0) ? sum / (double) numPairs : 0.0;
        String zeroReason = null;
        if (score <= 0.0) {
            if (numPairs <= 0) {
                zeroReason = "no optimization pair produced positive lift";
            } else {
                zeroReason = "PF-IDF score averaged to zero";
            }
        }
        return new PFIDFComputation(score, Arrays.copyOf(tmpPairIndices, tmpCtr), zeroReason);
    }

    private double[] buildAverageFrequencies() {
        double[] averageFrequencies = new double[OptimizationVector.Features.values().length];
        int total = Math.max(1, (int) globalStats.getRunCount());
        for (int i = 0; i < averageFrequencies.length; i++) {
            long freq = globalStats.getFeatureCount(i);
            averageFrequencies[i] = (double) freq / (double) total;
        }
        return averageFrequencies;
    }

    private boolean shouldUseNeutralAverages(TestCase testCase) {
        return testCase.getMutation() == fuzzer.mutators.MutatorType.SEED
                && !testCase.hasConsumedNeutralSeedScore();
    }

    private double[] buildNeutralAverageFrequencies() {
        return new double[OptimizationVector.Features.values().length];
    }

    private record PFIDFComputation(double score, int[] pairIndices, String zeroReason) {}

    public static final class PFIDFResult implements ScorePreview {
        private final double score;
        private final int[] pairIndices;
        private final int[] counts;
        private final String zeroReason;
        private final String methodName;
        private final String className;
        private final int[][] presentVectors;

        PFIDFResult(double score, int[] pairIndices, int[] counts, String zeroReason, String methodName, String className, int[][] presentVectors) {
            this.methodName = Objects.requireNonNullElse(methodName, "");
            this.className = Objects.requireNonNullElse(className, "");
            this.score = score;
            this.pairIndices = (pairIndices != null) ? pairIndices : new int[0];
            this.counts = (counts != null) ? counts
                    : new int[OptimizationVector.Features.values().length];
            this.zeroReason = zeroReason;
            this.presentVectors = (presentVectors != null) ? presentVectors : new int[0][];
        }

        @Override
        public double score() {
            return score;
        }

        @Override
        public int[] pairIndices() {
            return pairIndices;
        }

        @Override
        public int[] optimizationCounts() {
            return counts;
        }

        public String zeroReason() {
            return zeroReason;
        }

        @Override
        public String hotMethodName() {
            return methodName;
        }

        @Override
        public String hotClassName() {
            return className;
        }

        @Override
        public int[][] presentVectors() {
            return presentVectors;
        }
    }
}
