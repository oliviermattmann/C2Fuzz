package fuzzer.model;


public class MethodOptimizationVector {
    private final String className;
    private final String methodName;
    private final String methodSignature; // detects overloads
    private final boolean osr;
    private final int entryBci;
    private final int compileId;
    private final OptimizationVector optimizations;

    public MethodOptimizationVector(String className, String methodName, String methodSignature, String compilationType, int entryBci, int compileId, OptimizationVector optimizations) {
        this.className = className;
        this.methodName = methodName;
        this.methodSignature = methodSignature;
        this.osr = "OSR".equalsIgnoreCase(compilationType);
        this.entryBci = entryBci; 
        this.compileId = compileId;
        this.optimizations = optimizations;
    }

    public int getCompileId() {
        return compileId;
    }  

    public String getMethodName() {
        return methodName;
    }

    public String getMethodSignature() {
        return methodSignature;
    }

    public String getClassName() {
        return className;
    }

    public boolean isOsr() {
        return osr;
    }

    public int getEntryBci() {
        return entryBci;
    }

    public OptimizationVector getOptimizations() {
        return optimizations;
    }
}
