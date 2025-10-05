package fuzzer.util;

import java.util.LinkedHashMap;
import java.util.Map;

public class OptFeedbackMap {
    
    public static final Map<String, Integer> TEMPLATE;

    // LinkedHashMap to preserve order
    // If new optimizations behaviors are added to the fuzzer, add them here
    static {
        TEMPLATE = new LinkedHashMap<>();
        TEMPLATE.put("Loop Unrolling", 0);
        TEMPLATE.put("Loop Peeling", 0);
        TEMPLATE.put("Parallel Induction Variables", 0);
        TEMPLATE.put("Split if", 0);
        TEMPLATE.put("Loop Unswitching", 0);
        TEMPLATE.put("Conditional Expression Elimination", 0);
        TEMPLATE.put("Function Inlining", 0);
        TEMPLATE.put("Deoptimization", 0);
        TEMPLATE.put("Escape Analysis", 0);
        TEMPLATE.put("Eliminate Locks", 0);
        TEMPLATE.put("Locks Coarsening", 0);
        TEMPLATE.put("Conditional Constant Propagation", 0);
        TEMPLATE.put("Eliminate Autobox", 0);
        TEMPLATE.put("Block Elimination", 0);
        TEMPLATE.put("simplify Phi Function", 0);
        TEMPLATE.put("Canonicalization", 0);
        TEMPLATE.put("Null Check Elimination", 0);
        TEMPLATE.put("Range Check Elimination", 0);
        TEMPLATE.put("Optimize Ptr Compare", 0);    
    }
    
    
    public static Map<String, Integer> newFeatureMap() {
        return new LinkedHashMap<>(TEMPLATE);
    }
}
