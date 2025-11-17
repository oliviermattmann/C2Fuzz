package fuzzer.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

import fuzzer.util.MethodOptimizationVector;
import fuzzer.util.OptimizationVector;
import fuzzer.util.OptimizationVectors;
import fuzzer.util.TestCase;

public class InterestingnessScorer {
    private final GlobalStats globalStats;
    private final long targetRuntime;

    private static final Logger LOGGER = Logger.getLogger(InterestingnessScorer.class.getName());

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
            case PF_IDF -> previewPFIDF(testCase, optVectors).score(); // should never be hit, kept so switch is happy, default is PF_IDF and is handled in evaluator
            case ABSOLUTE_COUNT -> absoluteCountScore(testCase, optVectors);
            case PAIR_COVERAGE -> pairCoverageScore(testCase, optVectors);
            case INTERACTION_DIVERSITY -> interactionDiversityScore(testCase, optVectors);
            case NOVEL_FEATURE_BONUS -> novelFeatureBonusScore(testCase, optVectors);
        };
    }

    // computes the PF-IDF score with the latest global stats and does not modify the test case
    public PFIDFResult previewPFIDF(TestCase testCase, OptimizationVectors optVectors) {
        if (optVectors == null) {
            if (testCase != null) {
                testCase.setScore(0.0);
                testCase.setHashedOptVector(new int[OptimizationVector.Features.values().length]);
                logZeroScore(testCase, ScoringMode.PF_IDF, "no optimization vectors available");
            }
            return null;
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            if (testCase != null) {
                testCase.setScore(0.0);
                testCase.setHashedOptVector(new int[OptimizationVector.Features.values().length]);
                logZeroScore(testCase, ScoringMode.PF_IDF, "optimization vectors empty");
            }
            return null;
        }
        // SEED test cases should use neutral averages, that way they are considered equally regardless of execution order
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
            if (score <= 0.0) {
                String reason = (result != null && result.zeroReason() != null)
                        ? result.zeroReason()
                        : "PF-IDF score was non-positive";
                logZeroScore(testCase, ScoringMode.PF_IDF, reason);
            }
        }
        return result;
    }

    // here we commit the PF-IDF result to the test case and global stats
    // this happens if a test case is selected as a champion (ACCEPTED OR REPLACED)
    public double commitPFIDF(TestCase testCase, PFIDFResult result) {
        if (result == null) {
            if (testCase != null) {
                testCase.setScore(0.0);
            }
            return 0.0;
        }
        //globalStats.addRunFromPairIndices(result.pairIndicesView());
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
        String methodName = "";
        String className = "";
        double maxScore = Double.NEGATIVE_INFINITY;
        boolean found = false;
        String bestZeroReason = null;

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
                String reason = String.format(
                        "method vector %d exposes %d optimization feature(s); PF-IDF requires at least 2",
                        v,
                        m);
                return new PFIDFResult(0.0, new int[0], new int[featureCount], reason, "", "");
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
            String reasonForVector = null;
            if (score <= 0.0) {
                if (numPairs <= 0) {
                    reasonForVector = "no optimization pair produced positive lift";
                } else {
                    reasonForVector = "PF-IDF score averaged to zero";
                }
            }

            if (!found || score > maxScore) {
                maxScore = score;
                found = true;
                bestPairIndices = Arrays.copyOf(tmpPairIndices, tmpCtr);
                bestOptimizations = Arrays.copyOf(optimizations, optimizations.length);
                methodName = methodOptVector.getMethodName();
                className = methodOptVector.getClassName();

                if (score <= 0.0) {
                    bestZeroReason = reasonForVector;
                } else {
                    bestZeroReason = null;
                }
            }
        }

        if (!found) {
            return new PFIDFResult(0.0, new int[0], new int[featureCount],
                    "no method optimization vector contained optimization data", "", "");
        }

        String zeroReason = (maxScore > 0.0)
                ? null
                : (bestZeroReason != null ? bestZeroReason : "computed PF-IDF score was non-positive");
        return new PFIDFResult(maxScore, bestPairIndices, bestOptimizations, zeroReason, methodName, className);
    }

    private double[] buildAverageFrequencies() {
        double[] averageFrequencies = new double[OptimizationVector.Features.values().length];

        int[] absoluteFrequencies = globalStats.getOpFreqMap().values().stream().mapToInt(LongAdder::intValue).toArray();
        int total = Math.max(1, (int) globalStats.getTotalTestsExecuted());
        for (int i = 0; i < averageFrequencies.length; i++) {
            double freq = (i < absoluteFrequencies.length) ? absoluteFrequencies[i] : 0.0;
            averageFrequencies[i] = freq / (double) total;
        }
        return averageFrequencies;
    }

    /*
     * Absolute count scoring: simply counts the total number of optimizations
     * observed in the best method optimization vector.
     */
    public double absoluteCountScore(TestCase testCase, OptimizationVectors optVectors) {
        if (optVectors == null) {
            if (testCase != null) {
                testCase.setScore(0.0);
                testCase.setHashedOptVector(new int[OptimizationVector.Features.values().length]);
                logZeroScore(testCase, ScoringMode.ABSOLUTE_COUNT, "no optimization vectors available");
            }
            return 0.0;
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            if (testCase != null) {
                testCase.setScore(0.0);
                testCase.setHashedOptVector(new int[OptimizationVector.Features.values().length]);
                logZeroScore(testCase, ScoringMode.ABSOLUTE_COUNT, "optimization vectors empty");
            }
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
            globalStats.addRunFromCounts(bestCounts);
        }
        if (testCase != null) {
            if (best <= 0.0) {
                logZeroScore(testCase, ScoringMode.ABSOLUTE_COUNT, "no positive optimization counts observed");
            }
            testCase.setHashedOptVector(bucketCounts(bestCounts));
            testCase.setScore(best);
        }
        return best;
    }


    /*
     * Pair coverage scoring: counts the number of unique optimization pairs
     * observed in the best method optimization vector.
     */
    public double pairCoverageScore(TestCase testCase, OptimizationVectors optVectors) {
        if (optVectors == null) {
            if (testCase != null) {
                logZeroScore(testCase, ScoringMode.PAIR_COVERAGE, "no optimization vectors available");
            }
            return 0.0;
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            if (testCase != null) {
                logZeroScore(testCase, ScoringMode.PAIR_COVERAGE, "optimization vectors empty");
            }
            return 0.0;
        }

        double bestScore = Double.NEGATIVE_INFINITY;
        int[] bestCounts = new int[OptimizationVector.Features.values().length];
        int[] bestPresent = new int[OptimizationVector.Features.values().length];
        int bestM = 0;
        int bestNewPairs = -1;
        long bestNewPairOccurrences = -1L;

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
            long unseenFeatureOccurrences = 0L;
            for (int i = 0; i < featureCount; i++) {
                if (counts[i] > 0) {
                    present[m++] = i;
                    if (!globalStats.hasSeenFeature(i)) {
                        unseenFeatureOccurrences += counts[i];
                    }
                }
            }
            int newPairs = 0;
            long newPairOccurrences = 0L;
            long seenPairOccurrences = 0L;
            for (int a = 0; a < m; a++) {
                int i = present[a];
                for (int b = a + 1; b < m; b++) {
                    int j = present[b];
                    int pairFrequency = Math.min(counts[i], counts[j]);
                    if (pairFrequency <= 0) {
                        continue;
                    }
                    if (globalStats.getPairCount(i, j) == 0L) {
                        newPairs++;
                        newPairOccurrences += pairFrequency;
                    } else {
                        seenPairOccurrences += pairFrequency;
                    }
                }
            }

            long totalPairOccurrences = newPairOccurrences + seenPairOccurrences;
            double score = newPairOccurrences
                    + unseenFeatureOccurrences * PAIR_COVERAGE_SINGLE_FEATURE_WEIGHT
                    + seenPairOccurrences * PAIR_COVERAGE_SEEN_PAIR_WEIGHT;

            if (score <= 0.0) {
                if (totalPairOccurrences > 0) {
                    score = Math.max(PAIR_COVERAGE_MIN_SCORE,
                            totalPairOccurrences * PAIR_COVERAGE_SEEN_PAIR_WEIGHT);
                } else if (m > 0) {
                    score = PAIR_COVERAGE_MIN_SCORE;
                }
            }

            if (score > bestScore
                    || (score == bestScore && newPairOccurrences > bestNewPairOccurrences)
                    || (score == bestScore && newPairOccurrences == bestNewPairOccurrences && newPairs > bestNewPairs)
                    || (score == bestScore && newPairOccurrences == bestNewPairOccurrences
                            && newPairs == bestNewPairs && m > bestM)) {
                bestScore = score;
                bestNewPairs = newPairs;
                bestNewPairOccurrences = newPairOccurrences;
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
        double finalScore = Math.max(bestScore, 0.0);
        if (finalScore <= 0.0 && testCase != null) {
            String reason = (bestM <= 0)
                    ? "no optimization features observed"
                    : "no unseen optimization pairs discovered";
            logZeroScore(testCase, ScoringMode.PAIR_COVERAGE, reason);
        }
        return finalScore;
    }


    /*
     * Interaction diversity scoring: counts the number of different optimization
     * types observed in the best method optimization vector, discounting the most
     * frequent one.
     */
    public double interactionDiversityScore(TestCase testCase, OptimizationVectors optVectors) {
        if (optVectors == null) {
            if (testCase != null) {
                testCase.setScore(0.0);
                logZeroScore(testCase, ScoringMode.INTERACTION_DIVERSITY, "no optimization vectors available");
            }
            return 0.0;
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            if (testCase != null) {
                testCase.setScore(0.0);
                logZeroScore(testCase, ScoringMode.INTERACTION_DIVERSITY, "optimization vectors empty");
            }
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
        if (testCase != null && (!found || bestScore <= 0.0)) {
            if (!found) {
                logZeroScore(testCase, ScoringMode.INTERACTION_DIVERSITY, "no optimization counts above zero");
            } else {
                logZeroScore(testCase, ScoringMode.INTERACTION_DIVERSITY, "diversity score not positive");
            }
            if (testCase.getHashedOptVector() == null) {
                testCase.setHashedOptVector(new int[OptimizationVector.Features.values().length]);
            }
            testCase.setScore(0.0);
        }
        return bestScore;
    }

    /*
     * Novel feature bonus scoring: counts the number of previously unseen
     * optimization features observed in the best method optimization vector,
     * with a small bonus for total optimizations.
     */
    private static final double NOVEL_FEATURE_ALPHA = 0.1;
    public double novelFeatureBonusScore(TestCase testCase, OptimizationVectors optVectors) {
        if (optVectors == null) {
            if (testCase != null) {
                testCase.setScore(0.0);
                logZeroScore(testCase, ScoringMode.NOVEL_FEATURE_BONUS, "no optimization vectors available");
            }
            return 0.0;
        }
        ArrayList<MethodOptimizationVector> methodVectors = optVectors.vectors();
        if (methodVectors == null || methodVectors.isEmpty()) {
            if (testCase != null) {
                testCase.setScore(0.0);
                logZeroScore(testCase, ScoringMode.NOVEL_FEATURE_BONUS, "optimization vectors empty");
            }
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
        } else if (testCase != null) {
            String reason = found
                    ? "no previously unseen optimization features observed"
                    : "no optimization counts above zero";
            logZeroScore(testCase, ScoringMode.NOVEL_FEATURE_BONUS, reason);
            if (testCase.getHashedOptVector() == null) {
                testCase.setHashedOptVector(new int[OptimizationVector.Features.values().length]);
            }
            testCase.setScore(0.0);
        }
        return bestScore;
    }
   
    /*
     * Runtime scoring: scores test cases based on their runtime, where testcases closer to the target runtime score higher.
     */
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

    private void logZeroScore(TestCase testCase, ScoringMode mode, String reason) {
        if (!LOGGER.isLoggable(Level.FINE)) {
            return;
        }
        String testCaseName = (testCase != null && testCase.getName() != null && !testCase.getName().isEmpty())
                ? testCase.getName()
                : "<unknown>";
        String modeName = (mode != null) ? mode.displayName() : "UNKNOWN";
        String message = (reason != null && !reason.isEmpty())
                ? String.format("Test case %s scored 0.0 in %s: %s", testCaseName, modeName, reason)
                : String.format("Test case %s scored 0.0 in %s", testCaseName, modeName);
        LOGGER.fine(message);
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
        private final String zeroReason;
        private final String methodName;
        private final String className;

        PFIDFResult(double score, int[] pairIndices, int[] optimizations, String zeroReason, String methodName, String className) {
            this.methodName = methodName;
            this.className = className;
            this.score = score;
            this.pairIndices = (pairIndices != null) ? Arrays.copyOf(pairIndices, pairIndices.length) : new int[0];
            this.optimizations = (optimizations != null) ? Arrays.copyOf(optimizations, optimizations.length)
                    : new int[OptimizationVector.Features.values().length];
            this.zeroReason = zeroReason;
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

        public String zeroReason() {
            return zeroReason;
        }

        int[] pairIndicesView() {
            return pairIndices;
        }

        int[] optimizationsView() {
            return optimizations;
        }

        public String methodName() {
            return methodName;
        }
        public String className() {
            return className;
        }
    }

}
