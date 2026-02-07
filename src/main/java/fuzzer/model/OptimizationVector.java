package fuzzer.model;


public class OptimizationVector {

    public final int NUM_FEATURES = Features.values().length;
    public final int[] counts = new int[NUM_FEATURES];
    public OptimizationVector() {
        for (int i = 0; i < NUM_FEATURES; i++) {
            counts[i] = 0;
        }
    }
    public void incCount(Features feature) {
        counts[feature.ordinal()]++;
    }

    public void addCount(Features feature, int value) {
        counts[feature.ordinal()] += value;
    }

    public int getCount(Features feature) {
        return counts[feature.ordinal()];
    }

    public static enum Features {
        OptEvent_LoopUnrolling,
        OptEvent_LoopPeeling,
        OptEvent_ParallelInductionVars,
        OptEvent_SplitIf,
        OptEvent_LoopUnswitching,
        OptEvent_ConditionalExpressionElimination,
        OptEvent_FunctionInlining,
        OptEvent_Deoptimization,
        OptEvent_EscapeAnalysis,
        OptEvent_EliminateLocks,
        OptEvent_LockCoarsening,
        OptEvent_ConditionalConstantPropagation,
        OptEvent_EliminateAutobox,
        OptEvent_BlockElimination,
        OptEvent_NullCheckElimination,
        OptEvent_RangeCheckElimination,
        OptEvent_OptimizePtrCompare,
        OptEvent_MergeStores,
        OptEvent_LoopPredication,
        OptEvent_AutoVectorization,
        OptEvent_PartialPeeling,
        OptEvent_IterGVNIteration,
        OptEvent_LoopIterationSplit,
        OptEvent_ReassociateInvariants,
        OptEvent_LoopIntrinsification,
        OptEvent_Peephole,
        OptEvent_Count
    }

    public static Features FeatureFromIndex(int index) {
        if (index < 0 || index >= Features.values().length) {
            throw new IllegalArgumentException("Invalid feature index: " + index);
        }
        return Features.values()[index];
    }
    

    public static String FeatureName(Features feature) {
        switch (feature) {
            case OptEvent_LoopUnrolling: return "Loop Unrolling";
            case OptEvent_LoopPeeling: return "Loop Peeling";
            case OptEvent_ParallelInductionVars: return "Parallel Induction Variables";
            case OptEvent_SplitIf: return "Split If";
            case OptEvent_LoopUnswitching: return "Loop Unswitching";
            case OptEvent_ConditionalExpressionElimination: return "Conditional Expression Elimination";
            case OptEvent_FunctionInlining: return "Function Inlining";
            case OptEvent_Deoptimization: return "Deoptimization";
            case OptEvent_EscapeAnalysis: return "Escape Analysis";
            case OptEvent_EliminateLocks: return "Eliminate Locks";
            case OptEvent_LockCoarsening: return "Lock Coarsening";
            case OptEvent_ConditionalConstantPropagation: return "Conditional Constant Propagation";
            case OptEvent_EliminateAutobox: return "Eliminate Autobox";
            case OptEvent_BlockElimination: return "Block Elimination";
            case OptEvent_NullCheckElimination: return "Null Check Elimination";
            case OptEvent_RangeCheckElimination: return "Range Check Elimination";
            case OptEvent_OptimizePtrCompare: return "Optimize Ptr Compare";
            case OptEvent_MergeStores: return "Merge Stores";
            case OptEvent_LoopPredication: return "Loop Predication";
            case OptEvent_AutoVectorization: return "Auto Vectorization";
            case OptEvent_PartialPeeling: return "Partial Peeling";
            case OptEvent_IterGVNIteration: return "Iterative GVN Iterations";
            case OptEvent_LoopIterationSplit: return "Loop Iteration Split";
            case OptEvent_ReassociateInvariants: return "Reassociate Invariants";
            case OptEvent_LoopIntrinsification: return "Loop Intrinsification";
            case OptEvent_Peephole: return "Peephole";
            default: return "Unknown Feature";
        }
    }

    public static Features FeatureFromName(String name) {
        switch (name) {
            case "Loop Unrolling": return Features.OptEvent_LoopUnrolling;
            case "Loop Peeling": return Features.OptEvent_LoopPeeling;
            case "Parallel Induction Variables": return Features.OptEvent_ParallelInductionVars;
            case "Split If": return Features.OptEvent_SplitIf;
            case "Loop Unswitching": return Features.OptEvent_LoopUnswitching;
            case "Conditional Expression Elimination": return Features.OptEvent_ConditionalExpressionElimination;
            case "Function Inlining": return Features.OptEvent_FunctionInlining;
            case "Deoptimization": return Features.OptEvent_Deoptimization;
            case "Escape Analysis": return Features.OptEvent_EscapeAnalysis;
            case "Eliminate Locks": return Features.OptEvent_EliminateLocks;
            case "Lock Coarsening":
            case "Locks Coarsening":
                return Features.OptEvent_LockCoarsening;
            case "Conditional Constant Propagation": return Features.OptEvent_ConditionalConstantPropagation;
            case "Eliminate Autobox": return Features.OptEvent_EliminateAutobox;
            case "Block Elimination": return Features.OptEvent_BlockElimination;
            case "Null Check Elimination": return Features.OptEvent_NullCheckElimination;
            case "Range Check Elimination": return Features.OptEvent_RangeCheckElimination;
            case "Optimize Ptr Compare": return Features.OptEvent_OptimizePtrCompare;
            case "Merge Stores": return Features.OptEvent_MergeStores;
            case "Loop Predication": return Features.OptEvent_LoopPredication;
            case "Auto Vectorization": return Features.OptEvent_AutoVectorization;
            case "Partial Peeling": return Features.OptEvent_PartialPeeling;
            case "Iterative GVN Iterations":
            case "IterGVN Iteration":
                return Features.OptEvent_IterGVNIteration;
            case "Loop Iteration Split": return Features.OptEvent_LoopIterationSplit;
            case "Reassociate Invariants": return Features.OptEvent_ReassociateInvariants;
            case "Loop Intrinsification": return Features.OptEvent_LoopIntrinsification;
            case "Peephole": return Features.OptEvent_Peephole;
            default: throw new IllegalArgumentException("Unknown feature name: " + name);
        }
    }
}
