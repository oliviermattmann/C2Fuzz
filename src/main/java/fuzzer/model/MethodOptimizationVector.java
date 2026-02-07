package fuzzer.model;


public class MethodOptimizationVector {
    private final String className;
    private final String methodName;
    private final String methodSignature; // detects overloads
    private final boolean osr;
    private final int entryBci; // this allows us to distinguish different osr compilations
                                // it is conservative in the sense that code insertions can change it
                                // thus we cannot fully rely on it to match with the parent
                                // maybe only use the count of osr compilations as a bonus
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
