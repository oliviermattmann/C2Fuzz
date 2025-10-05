package fuzzer.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class GraphResult {
    private final String methodName;
    private final int graphId;
    private final Map<String, Integer> nodeCounts;
    private final int totalNodes;
    private final int distinctNodeTypes;
    private final double entropy;

    public GraphResult(String methodName, int graphId, Map<String, Integer> counts) {
        this.methodName = methodName;
        this.graphId = graphId;
        
        // Use LinkedHashMap to preserve order
        this.nodeCounts = new LinkedHashMap<>();
        for (String key : GraphNodeMap.newFeatureMap().keySet()) {
            this.nodeCounts.put(key, counts.getOrDefault(key, 0));
        }

        // Compute summary stats
        this.totalNodes = this.nodeCounts.values().stream().mapToInt(Integer::intValue).sum();
        this.distinctNodeTypes = (int) this.nodeCounts.values().stream().filter(v -> v > 0).count();
        this.entropy = computeEntropy();
    }

    private double computeEntropy() {
        double h = 0.0;
        if (totalNodes > 0) {
            for (int count : nodeCounts.values()) {
                if (count > 0) {
                    double p = (double) count / totalNodes;
                    h -= p * (Math.log(p) / Math.log(2));
                }
            }
        }
        return h;
    }

    public static double computeEntropyFor(Map<String, Integer> counts) {
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        double h = 0.0;
        if (total > 0) {
            for (int count : counts.values()) {
                if (count > 0) {
                    double p = (double) count / total;
                    h -= p * (Math.log(p) / Math.log(2));
                }
            }
        }
        return h;
    }


    public String getMethodName() { 
        return methodName; 
    }

    public int getGraphId() { 
        return graphId; 
    }
    public int getTotalNodes() { 
        return totalNodes; 
    }

    public int getDistinctNodeTypes() { 
        return distinctNodeTypes; 
    }

    public double getEntropy() { 
        return entropy; 
    }


    public int getCount(String nodeType) {
        return nodeCounts.getOrDefault(nodeType, 0);
    }

    public Map<String, Integer> getNodeCounts() {
        return Collections.unmodifiableMap(nodeCounts);
    }

    // convenience for printing
    @Override
    public String toString() {
        return String.format(
            "GraphResult(method=%s, graphId=%d, totalNodes=%d, distinctTypes=%d, entropy=%.3f)",
            methodName, graphId, totalNodes, distinctNodeTypes, entropy
        );
    }
}
