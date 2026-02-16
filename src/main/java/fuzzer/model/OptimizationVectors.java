package fuzzer.model;

import java.util.ArrayList;

public class OptimizationVectors {
    private final ArrayList<MethodOptimizationVector> vectors;
    private final OptimizationVector mergedCounts;
    private String parentPublicClass;

    public OptimizationVectors(ArrayList<MethodOptimizationVector> vectors, OptimizationVector mergedCounts) {
        this.vectors =  vectors;
        this.mergedCounts = mergedCounts;
    }

    public ArrayList<MethodOptimizationVector> vectors() {
        return vectors;
    }

    public OptimizationVector mergedCounts() {
        return mergedCounts;
    }

    public String getParentPublicClass() {
        return parentPublicClass;
    }

    public void setParentPublicClass(String parentPublicClass) {
        this.parentPublicClass = parentPublicClass;
    }
}
