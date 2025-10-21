package fuzzer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

import fuzzer.util.LoggingConfig;
import fuzzer.util.MethodOptimizationVector;
import fuzzer.util.OptimizationVector;
import fuzzer.util.OptimizationVectors;
import fuzzer.util.TestCase;

public class InterestingnessScorer {
    private final GlobalStats globalStats;
    private final long targetRuntime;


    private static final Logger LOGGER = LoggingConfig.getLogger(InterestingnessScorer.class);
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
        return PF_IDF_AllVectors(testCase, methodVectors, averageFrequencies, true);
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
        return PF_IDF_AllVectors(null, methodVectors, averageFrequencies, false);
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

    private double PF_IDF_AllVectors(TestCase testCase, ArrayList<MethodOptimizationVector> methodOptVectors, double[] averageFrequencies, boolean updateState) {
        double eps = 1e-6;
        // cap for the lift so optimizations like canonicalization does not skew the score too much
        double lift_cap = 8.0;

        int[] finalPairIndices = new int[0];
        int[] finalOptimizations = new int[OptimizationVector.Features.values().length];
        double maxScore = Double.NEGATIVE_INFINITY;

        for (int v = 0; v < methodOptVectors.size(); v++) {
            // here we could also filter out OSR compilations if we wanted to
            MethodOptimizationVector methodOptVector = methodOptVectors.get(v);
            List<Integer> indexes = new ArrayList<>();
            int[] optimizations = methodOptVector.getOptimizations().counts;
            LOGGER.fine(String.format("Method optimization vector %d: %s", v, Arrays.toString(methodOptVector.getOptimizations().counts)));
            double[] lifts = new double[optimizations.length];
            
            // compute lift for each optimization
            for (int i = 0; i < lifts.length; i++) {
                double averageFreq = averageFrequencies[i];
                int optCount = optimizations[i];
                if (optCount > 0) {
                    // add index to list
                    indexes.add(i);
                    // compute the lift
                    // the lifts are always non negative
                    double lift = (double) optCount / (averageFreq + eps);
                    lifts[i] = Math.min(lift, lift_cap);
                }
                
            }

            // we need at least two non zero opt counts to compute a score (need pairs)
            int m = indexes.size();
            int [] tmpPairIndices = new int[m * (m - 1) / 2];
            if (m < 2) {
                LOGGER.fine("Not enough optimizations in vector to compute pairwise score");
                return 0.0;
            }

            double rawRunCount = globalStats.getRunCount();
            double N = Math.max(1.0, rawRunCount);
            double denominator = Math.log(N + 1.0);
            if (denominator <= 0.0) {
                denominator = 1e-9;
            }
            double sum = 0.0;
            int numPairs = 0;

            // compute pairwise score
            int tmp_ctr = 0;
            for (int a = 0; a < m; a++) {
                int i = indexes.get(a);
                for (int b = a + 1; b < m; b++) {
                    int j = indexes.get(b);
                    tmpPairIndices[tmp_ctr++] = globalStats.pairIdx(i, j);
                    double s = Math.sqrt(lifts[i] * lifts[j]) - 1.0;
                    if (s <= 0.0) {
                        // very common pair are considered neutral (ie nothing added to the sum)
                        LOGGER.finer("s is negative");
                        continue;
                    } 
                    double nij = (double) globalStats.getPairCount(i, j);
                    double w = Math.log((N + 1.0) / (nij + 1.0)) / denominator;
                    double pairTerm =  s * w;
                    sum += pairTerm;
                    numPairs++;
                }
                
            }

            sum = (numPairs > 0) ? sum / (double) numPairs : 0.0;

            if (sum > maxScore) {
                maxScore = sum;
                finalPairIndices = Arrays.copyOf(tmpPairIndices, tmp_ctr);
                finalOptimizations = Arrays.copyOf(optimizations, optimizations.length);
            }

        }

        if (!updateState) {
            return Math.max(0.0, maxScore);
        }

        if (maxScore <= Double.NEGATIVE_INFINITY) {
            return 0.0;
        }

        if (finalPairIndices == null) {
            finalPairIndices = new int[0];
        }

        globalStats.addRunFromPairIndices(finalPairIndices);
        if (testCase != null) {
            testCase.setScore(maxScore);
            testCase.setHashedOptVector(bucketCounts(finalOptimizations));
        }
        return maxScore;
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


}
