package fuzzer.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import fuzzer.util.MethodOptimizationVector;
import fuzzer.util.OptimizationVector;
import fuzzer.util.OptimizationVectors;
import fuzzer.util.TestCase;

public class InterestingnessScorer {
    private final GlobalStats globalStats;
    private final long targetRuntime;


    private static final double PAIR_COVERAGE_SINGLE_FEATURE_WEIGHT = 0.5;
    private static final double PAIR_COVERAGE_SEEN_PAIR_WEIGHT = 0.05;
    private static final double PAIR_COVERAGE_MIN_SCORE = 0.1;



    public InterestingnessScorer(GlobalStats globalStats,
            long targetRuntime) {
        this.globalStats = globalStats;
        this.targetRuntime = targetRuntime;
    }

    public double score(TestCase testCase, OptimizationVectors optVectors, ScoringMode mode) {
        if (mode == null) {
            mode = ScoringMode.PF_IDF;
        }
        return switch (mode) {
            case PF_IDF -> interactionScore_PF_IDF(testCase, optVectors);
            case ABSOLUTE_COUNT -> absoluteCountScore(testCase, optVectors);
            case PAIR_COVERAGE -> pairCoverageScore(testCase, optVectors);
            case INTERACTION_DIVERSITY -> interactionDiversityScore(testCase, optVectors);
            case NOVEL_FEATURE_BONUS -> novelFeatureBonusScore(testCase, optVectors);
        };
    }

    public double interactionScore_PF_IDF(TestCase testCase, OptimizationVectors optVectors) {
        if (optVectors == null) {
            return 0.0;
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            return 0.0;
        }
        double[] averageFrequencies = buildAverageFrequencies();
        PFIDFResult result = computePFIDF(methodVectors, averageFrequencies, false);
        if (testCase != null) {
            double score = (result != null) ? Math.max(0.0, result.score()) : 0.0;
            testCase.setScore(score);
            testCase.setHashedOptVector(result != null
                    ? bucketCounts(result.optimizationsView())
                    : new int[OptimizationVector.Features.values().length]);
        }
        return commitPFIDF(testCase, result);
    }

    public double debugInteractionScore_PF_IDF(OptimizationVectors optVectors) {
        if (optVectors == null) {
            return 0.0;
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            return 0.0;
        }
        double[] averageFrequencies = buildAverageFrequencies();
        PFIDFResult result = computePFIDF(methodVectors, averageFrequencies, false);
        return (result != null) ? Math.max(0.0, result.score()) : 0.0;
    }

    public PFIDFResult previewPFIDF(TestCase testCase, OptimizationVectors optVectors) {
        if (optVectors == null) {
            if (testCase != null) {
                testCase.setScore(0.0);
                testCase.setHashedOptVector(new int[OptimizationVector.Features.values().length]);
            }
            return null;
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            if (testCase != null) {
                testCase.setScore(0.0);
                testCase.setHashedOptVector(new int[OptimizationVector.Features.values().length]);
            }
            return null;
        }
        boolean neutral = shouldUseNeutralAverages(testCase);
        double[] averageFrequencies = neutral
                ? buildNeutralAverageFrequencies()
                : buildAverageFrequencies();
        PFIDFResult result = computePFIDF(methodVectors, averageFrequencies, neutral);
        double score = (result != null) ? Math.max(0.0, result.score()) : 0.0;
        if (testCase != null) {
            testCase.setScore(score);
            testCase.setHashedOptVector(result != null
                    ? bucketCounts(result.optimizationsView())
                    : new int[OptimizationVector.Features.values().length]);
        }
        return result;
    }

    public double commitPFIDF(TestCase testCase, PFIDFResult result) {
        if (result == null) {
            if (testCase != null) {
                testCase.setScore(0.0);
            }
            return 0.0;
        }
        globalStats.addRunFromPairIndices(result.pairIndicesView());
        double score = Math.max(0.0, result.score());
        if (testCase != null) {
            testCase.setScore(score);
            testCase.setHashedOptVector(bucketCounts(result.optimizationsView()));
        }
        return score;
    }

    private PFIDFResult computePFIDF(ArrayList<MethodOptimizationVector> methodOptVectors,
            double[] averageFrequencies,
            boolean neutralStats) {
        double eps = 1e-6;
        double liftCap = 8.0;

        int featureCount = OptimizationVector.Features.values().length;
        int[] bestOptimizations = new int[featureCount];
        int[] bestPairIndices = new int[0];
        double maxScore = Double.NEGATIVE_INFINITY;
        boolean found = false;

        for (int v = 0; v < methodOptVectors.size(); v++) {
            MethodOptimizationVector methodOptVector = methodOptVectors.get(v);
            if (methodOptVector == null || methodOptVector.getOptimizations() == null) {
                continue;
            }
            int[] optimizations = methodOptVector.getOptimizations().counts;
            if (optimizations == null) {
                continue;
            }
            List<Integer> indexes = new ArrayList<>();
            double[] lifts = new double[optimizations.length];

            for (int i = 0; i < optimizations.length; i++) {
                double averageFreq = (i < averageFrequencies.length) ? averageFrequencies[i] : 0.0;
                int optCount = optimizations[i];
                if (optCount > 0) {
                    indexes.add(i);
                    double lift = (double) optCount / (averageFreq + eps);
                    lifts[i] = Math.min(lift, liftCap);
                }
            }

            int m = indexes.size();
            if (m < 2) {
                return new PFIDFResult(0.0, new int[0], new int[featureCount]);
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

            if (!found || score > maxScore) {
                maxScore = score;
                found = true;
                bestPairIndices = Arrays.copyOf(tmpPairIndices, tmpCtr);
                bestOptimizations = Arrays.copyOf(optimizations, optimizations.length);
            }
        }

        if (!found) {
            return new PFIDFResult(0.0, new int[0], new int[featureCount]);
        }

        return new PFIDFResult(maxScore, bestPairIndices, bestOptimizations);
    }

    private double[] buildAverageFrequencies() {
        double[] averageFrequencies = new double[OptimizationVector.Features.values().length];
        int[] absoluteFrequencies = globalStats.opFreq.values().stream().mapToInt(LongAdder::intValue).toArray();
        int total = Math.max(1, globalStats.totalTestsExecuted.intValue());
        for (int i = 0; i < averageFrequencies.length; i++) {
            double freq = (i < absoluteFrequencies.length) ? absoluteFrequencies[i] : 0.0;
            averageFrequencies[i] = freq / (double) total;
        }
        return averageFrequencies;
    }

    /**
     * Minimal absolute-count scorer (thesis variant).
     *
     * Treats every method vector independently and keeps the one with the highest
     * total optimization count. The score is simply the sum of counts in that
     * vector; more optimizations == more interesting. No normalisation, no
     * history dependency.
     */
    public double absoluteCountScore(TestCase testCase, OptimizationVectors optVectors) {
        if (optVectors == null) {
            return 0.0;
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            return 0.0;
        }

        double best = 0.0;
        int[] bestCounts = new int[OptimizationVector.Features.values().length];

        for (MethodOptimizationVector methodVector : methodVectors) {
            if (methodVector == null || methodVector.getOptimizations() == null) {
                continue;
            }
            int[] counts = methodVector.getOptimizations().counts;
            int total = 0;
            for (int count : counts) {
                if (count > 0) {
                    total += count;
                }
            }
            if (total > best) {
                best = total;
                bestCounts = Arrays.copyOf(counts, counts.length);
            }
        }

        if (best > 0.0) {
            globalStats.recordBestVectorFeatures(bestCounts);
        }
        testCase.setHashedOptVector(bucketCounts(bestCounts));
        testCase.setScore(best);
        return best;
    }

    public double pairCoverageScore(TestCase testCase, OptimizationVectors optVectors) {
        if (optVectors == null) {
            return 0.0;
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            return 0.0;
        }

        double bestScore = Double.NEGATIVE_INFINITY;
        int[] bestCounts = new int[OptimizationVector.Features.values().length];
        int[] bestPresent = new int[OptimizationVector.Features.values().length];
        int bestM = 0;
        int bestNewPairs = -1;

        for (MethodOptimizationVector methodVector : methodVectors) {
            if (methodVector == null || methodVector.getOptimizations() == null) {
                continue;
            }
            int[] counts = methodVector.getOptimizations().counts;
            if (counts == null) {
                continue;
            }

            int featureCount = counts.length;
            int[] present = new int[featureCount];
            int m = 0;
            int unseenFeatures = 0;
            for (int i = 0; i < featureCount; i++) {
                if (counts[i] > 0) {
                    present[m++] = i;
                    if (!globalStats.hasSeenFeature(i)) {
                        unseenFeatures++;
                    }
                }
            }
            int newPairs = 0;
            for (int a = 0; a < m; a++) {
                int i = present[a];
                for (int b = a + 1; b < m; b++) {
                    int j = present[b];
                    if (globalStats.getPairCount(i, j) == 0L) {
                        newPairs++;
                    }
                }
            }

            int totalPairs = (m >= 2) ? (m * (m - 1)) / 2 : 0;
            double score = newPairs + unseenFeatures * PAIR_COVERAGE_SINGLE_FEATURE_WEIGHT;

            if (score <= 0.0) {
                if (totalPairs > 0) {
                    score = Math.max(PAIR_COVERAGE_MIN_SCORE,
                            totalPairs * PAIR_COVERAGE_SEEN_PAIR_WEIGHT);
                } else if (m > 0) {
                    score = PAIR_COVERAGE_MIN_SCORE;
                }
            }

            if (score > bestScore
                    || (score == bestScore && newPairs > bestNewPairs)
                    || (score == bestScore && newPairs == bestNewPairs && m > bestM)) {
                bestScore = score;
                bestNewPairs = newPairs;
                bestM = m;
                System.arraycopy(present, 0, bestPresent, 0, m);
                bestCounts = Arrays.copyOf(counts, counts.length);
            }
        }

        if (bestM > 0) {
            globalStats.addRunFromPresent(bestPresent, bestM);
            if (testCase != null) {
                testCase.setHashedOptVector(bucketCounts(bestCounts));
                testCase.setScore(Math.max(bestScore, 0.0));
            }
        }
        return Math.max(bestScore, 0.0);
    }

    public double interactionDiversityScore(TestCase testCase, OptimizationVectors optVectors) {
        if (optVectors == null) {
            return 0.0;
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            return 0.0;
        }

        double bestScore = 0.0;
        int[] bestCounts = new int[OptimizationVector.Features.values().length];
        int bestTotal = 0;
        boolean found = false;

        for (MethodOptimizationVector methodVector : methodVectors) {
            if (methodVector == null || methodVector.getOptimizations() == null) {
                continue;
            }
            int[] counts = methodVector.getOptimizations().counts;
            if (counts == null) {
                continue;
            }
            int total = 0;
            int peak = 0;
            for (int count : counts) {
                if (count <= 0) {
                    continue;
                }
                total += count;
                if (count > peak) {
                    peak = count;
                }
            }

            if (total == 0) {
                continue;
            }

            double score = total - peak;
            if (!found || score > bestScore) {
                bestScore = score;
                bestCounts = Arrays.copyOf(counts, counts.length);
                bestTotal = total;
                found = true;
            }
        }

        if (found && bestTotal > 0) {
            globalStats.addRunFromCounts(bestCounts);
            if (testCase != null) {
                testCase.setHashedOptVector(bucketCounts(bestCounts));
                testCase.setScore(bestScore);
            }
        }
        return bestScore;
    }

    private static final double NOVEL_FEATURE_ALPHA = 0.1;

    public double novelFeatureBonusScore(TestCase testCase, OptimizationVectors optVectors) {
        if (optVectors == null) {
            return 0.0;
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            return 0.0;
        }

        double bestScore = 0.0;
        int[] bestCounts = new int[OptimizationVector.Features.values().length];
        int bestTotal = 0;
        boolean found = false;

        for (MethodOptimizationVector methodVector : methodVectors) {
            if (methodVector == null || methodVector.getOptimizations() == null) {
                continue;
            }
            int[] counts = methodVector.getOptimizations().counts;
            if (counts == null) {
                continue;
            }

            int total = 0;
            int unseenFeatures = 0;
            for (int i = 0; i < counts.length; i++) {
                int count = counts[i];
                if (count <= 0) {
                    continue;
                }
                total += count;
                if (!globalStats.hasSeenFeature(i)) {
                    unseenFeatures++;
                }
            }

            if (total == 0) {
                continue;
            }

            double score = unseenFeatures + NOVEL_FEATURE_ALPHA * total;
            if (!found || score > bestScore) {
                bestScore = score;
                bestCounts = Arrays.copyOf(counts, counts.length);
                bestTotal = total;
                found = true;
            }
        }

        if (found && bestTotal > 0) {
            globalStats.addRunFromCounts(bestCounts);
            if (testCase != null) {
                testCase.setHashedOptVector(bucketCounts(bestCounts));
                testCase.setScore(bestScore);
            }
        }
        return bestScore;
    }





    // private double computeOptScore(LinkedHashMap<String, Integer> optCounts) {
    //     double N = globalStats.totalTestsExecuted.doubleValue();
    //     double sum = 0.0;

    //     // for each optimization behavior that we record
    //     for (var e : optCounts.entrySet()) {
    //         String opt = e.getKey();
    //         int count = e.getValue();
    //         if (count <= 0) continue;
    //         globalStats.opFreq.computeIfAbsent(opt, k -> new LongAdder()).add(count);
    //         globalStats.opMax.merge(opt, (double)count, Math::max);

    //         double freq = globalStats.opFreq.get(opt).doubleValue();
    //         double idf = Math.max(0.0, Math.log((N + 1.0) / (freq + 1.0))); // camp it because it can be negative
    //         double val = Math.log1p(count) / Math.log1p(globalStats.opMax.get(opt));
    //         sum += idf * val;
    //     }

    //     double prevMax = globalStats.optScoreMax.get();
    //     if (sum > prevMax) globalStats.optScoreMax.set(sum);
    //     return sum;
    // }


   
    public double computeRuntimeScore(long runtime) {
        double runtimeScore =  Math.exp(- (double) runtime / (double)targetRuntime);
        return runtimeScore;

    }

    private static int[] bucketCounts(int[] counts) {
        if (counts == null) {
            return null;
        }
        int[] bucketed = new int[counts.length];
        for (int i = 0; i < counts.length; i++) {
            bucketed[i] = bucketCount(counts[i]);
        }
        return bucketed;
    }

    private static int bucketCount(int count) {
        if (count <= 0) {
            return 0;
        }
        if (count == 1) {
            return 1;
        }
        if (count == 2) {
            return 2;
        }
        int highest = Integer.highestOneBit(count);
        if (highest <= 0) {
            return 0;
        }
        if (highest >= (1 << 30)) {
            return Integer.MAX_VALUE;
        }
        int bucket = highest << 1;
        if (bucket <= 0) {
            return Integer.MAX_VALUE;
        }
        return bucket;
    }

    private boolean shouldUseNeutralAverages(TestCase testCase) {
        return testCase != null && testCase.getMutation() == fuzzer.mutators.MutatorType.SEED;
    }

    private double[] buildNeutralAverageFrequencies() {
        return new double[OptimizationVector.Features.values().length];
    }


    public static final class PFIDFResult {
        private final double score;
        private final int[] pairIndices;
        private final int[] optimizations;

        PFIDFResult(double score, int[] pairIndices, int[] optimizations) {
            this.score = score;
            this.pairIndices = (pairIndices != null) ? Arrays.copyOf(pairIndices, pairIndices.length) : new int[0];
            this.optimizations = (optimizations != null) ? Arrays.copyOf(optimizations, optimizations.length)
                    : new int[OptimizationVector.Features.values().length];
        }

        public double score() {
            return score;
        }

        public int[] pairIndices() {
            return Arrays.copyOf(pairIndices, pairIndices.length);
        }

        public int[] optimizations() {
            return Arrays.copyOf(optimizations, optimizations.length);
        }

        int[] pairIndicesView() {
            return pairIndices;
        }

        int[] optimizationsView() {
            return optimizations;
        }
    }

}
