package fuzzer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

import fuzzer.util.GraphResult;
import fuzzer.util.LoggingConfig;

public class InterestingnessScorer {
    private final GlobalStats globalStats;
    private final List<String> optOrder;
    private final List<String> nodeOrder;
    private final long targetRuntime;

    private final double OPT_WEIGHT = 0.9;
    private final double NOVELTY_WEIGHT = 0.3;
    private final double RUNTIME_WEIGHT = 0.05;
    private final double GRAPH_WEIGHT = 0.05;

    private static final Logger LOGGER = LoggingConfig.getLogger(InterestingnessScorer.class);



    public InterestingnessScorer(GlobalStats globalStats, List<String> optOrder, List<String> nodeOrder,
            long targetRuntime) {
        this.globalStats = globalStats;
        this.optOrder = optOrder;
        this.nodeOrder = nodeOrder;
        this.targetRuntime = targetRuntime;
    }
    // optMapCount, graphResults, runtime, 
    public double score(Map<String, Integer> optCounts, List<GraphResult> graphResults, long runtime) {

        double optScore = computeOptScore(optCounts);
        double novelty = 0; //computeNoveltyScore();
        double runtimeScore = computeRuntimeScore(runtime);
        double graphScore = 0.0;
        if (!graphResults.isEmpty()) {
            //graphScore = computeOverallGraphScore(graphResults);
            
        }

        LOGGER.severe(String.format("Scores: opt=%.4f, novelty=%.4f, runtime=%.4f, graph=%.4f", 
            optScore, novelty, runtimeScore, graphScore));
        double score = OPT_WEIGHT * optScore + NOVELTY_WEIGHT * novelty + RUNTIME_WEIGHT * runtimeScore
                + GRAPH_WEIGHT * graphScore;
        return score;
    }


    private double computeOptScore(Map<String, Integer> optCounts) {
        globalStats.totalTestsExecuted.increment();
        double N = globalStats.totalTestsExecuted.doubleValue();
        double sum = 0.0;

        // for each optimization behavior that we record
        for (var e : optCounts.entrySet()) {
            LOGGER.severe(e.toString());
            String opt = e.getKey();
            int count = e.getValue();
            if (count <= 0) continue;
            globalStats.opFreq.computeIfAbsent(opt, k -> new LongAdder()).add(count);
            globalStats.opMax.merge(opt, (double)count, Math::max);

            double freq = globalStats.opFreq.get(opt).doubleValue();
            double idf = Math.max(0.0, Math.log((N + 1.0) / (freq + 1.0))); // camp it because it can be negative
            double val = Math.log1p(count) / Math.log1p(globalStats.opMax.get(opt));
            sum += idf * val;
        }

        double prevMax = globalStats.optScoreMax.get();
        if (sum > prevMax) globalStats.optScoreMax.set(sum);
        return sum;
    }

    private double computeNoveltyScore() { 
        // TODO: implement
        return 0.0;
    }

    private double computeRuntimeScore(long runtime) {
        double runtimeScore =  Math.exp(- (double) runtime / (double)targetRuntime);
        return runtimeScore;

    }

    private double computeGraphScore(GraphResult gr, 
                                 double maxDistinct, 
                                 double maxNodes, 
                                 double maxEntropy) {
        double normDistinct = maxDistinct > 0 
            ? (double) gr.getDistinctNodeTypes() / maxDistinct 
            : 0.0;

        double normNodes = maxNodes > 0 
            ? (double) gr.getTotalNodes() / maxNodes 
            : 0.0;

        double normEntropy = maxEntropy > 0 
            ? gr.getEntropy() / maxEntropy 
            : 0.0;

        return (normDistinct + normNodes + normEntropy) / 3.0; // balanced average
    }

    private double computeOverallGraphScore(List<GraphResult> graphResults) {
        // find feature maxima across all graphs
        double maxDistinct = 0.0, maxNodes = 0.0, maxEntropy = 0.0;
        for (GraphResult gr : graphResults) {
            maxDistinct = Math.max(maxDistinct, gr.getDistinctNodeTypes());
            maxNodes    = Math.max(maxNodes, gr.getTotalNodes());
            maxEntropy  = Math.max(maxEntropy, gr.getEntropy());
        }
    
        double sumScores = 0.0;
        double maxScore = Double.NEGATIVE_INFINITY;
        for (GraphResult gr : graphResults) {
            double score = computeGraphScore(gr, maxDistinct, maxNodes, maxEntropy);
            sumScores += score;
            maxScore = Math.max(maxScore, score);
        }
        double avgScore = sumScores / graphResults.size();
    
        // global entropy normalization
        Map<String, Integer> globalNodeCounts = computeGlobalNodeCounts(graphResults);
        double globalEntropy = GraphResult.computeEntropyFor(globalNodeCounts);
        double normGlobalEntropy = maxEntropy > 0 ? globalEntropy / maxEntropy : 0.0;
    
        return (avgScore + maxScore + normGlobalEntropy) / 3.0;
    }

    public Map<String, Integer> computeGlobalNodeCounts(List<GraphResult> graphResults) {
        Map<String, Integer> globalNodeCounts = new LinkedHashMap<>();
        for (GraphResult gr : graphResults) {
            for (var e: gr.getNodeCounts().entrySet()) {
                globalNodeCounts.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        return globalNodeCounts;
    }

}
