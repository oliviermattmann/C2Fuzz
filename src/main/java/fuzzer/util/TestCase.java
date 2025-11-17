package fuzzer.util;

import fuzzer.mutators.MutatorType;

/*
 * implements comparable to be used in priority queue
 */
public class TestCase implements Comparable<TestCase>{
    private String testCaseName; // name of the test case without the .java suffix
    private String seedName; // name of the seed from which this test case was generated
    private OptimizationVectors optVectors;
    private OptimizationVectors parentOptVectors;
    private int[] hashedOptVector;
    private double parentScore;
    private String parentName;
    private double score;
    private long interpreterRuntimeNanos;
    private long jitRuntimeNanos;
    private double priority;
    private int timesSelected;
    private MutatorType appliedMutation;
    private volatile boolean activeChampion;
    private int mutationDepth;
    private int mutationCount;
    private String hotClassName;
    private String hotMethodName;

    private static final double PRIORITY_DECAY_EXPONENT = 1.0;

    public TestCase(String name, OptimizationVectors parentOptVectors, MutatorType mutationType, double parentScore, String parentName, String seedName, int mutationDepth, int mutationCount) {
        this.seedName = seedName;
        this.testCaseName = name;
        this.optVectors = null;
        this.parentOptVectors = parentOptVectors;
        this.score = 1.0;
        this.parentScore = parentScore;
        this.parentName = parentName;
        this.priority = this.score;
        this.interpreterRuntimeNanos = -1L;
        this.jitRuntimeNanos = -1L;
        this.timesSelected = 0;
        this.appliedMutation = mutationType;
        this.activeChampion = false;
        this.mutationDepth = mutationDepth;
        this.mutationCount = mutationCount;

    }

    public void incMutationCount() {
        this.mutationCount++;
    }

    public int getMutationCount() {
        return this.mutationCount;
    }

    public int getMutationDepth() {
        return this.mutationDepth;
    }

    public String getName() {
        return this.testCaseName;
    }

    public void setName(String name) {
        this.testCaseName = name;
    }

    public void setOptVectors(OptimizationVectors optVectors) {
        this.optVectors = optVectors;
    }
    
    public void setHashedOptVector(int[] hashedOptVector) {
        this.hashedOptVector = hashedOptVector;
    }

    public int[] getHashedOptVector() {
        return this.hashedOptVector;
    }

    public OptimizationVectors getOptVectors() {
        return optVectors;
    }

    public OptimizationVectors getParentOptVectors() {
        return parentOptVectors;
    }

    public void setHotClassName(String className) {
        this.hotClassName = className;
    }

    public String getHotClassName() {
        return hotClassName;
    }

    public void setHotMethodName(String methodName) {
        this.hotMethodName = methodName;
    }
    
    public String getHotMethodName() {
        return hotMethodName;
    }

    public MutatorType getMutation() {
        return appliedMutation;
    }

    public double getScore() {
        return score;
    }

    public long getInterpreterRuntimeNanos() {
        return interpreterRuntimeNanos;
    }

    public long getJitRuntimeNanos() {
        return jitRuntimeNanos;
    }

    public String getSeedName() {
        return seedName;
    }

    public long getMaxRuntimeNanos() {
        return Math.max(interpreterRuntimeNanos, jitRuntimeNanos);
    }

    public String getParentName() {
        return parentName;
    }

    public double getParentScore() {
        return parentScore;
    }   

    public void setScore(double score) {
        this.score = score;
        this.priority = score;
        this.timesSelected = 0;
    }

    public void setExecutionTimes(long interpreterRuntimeNanos, long jitRuntimeNanos) {
        this.interpreterRuntimeNanos = interpreterRuntimeNanos;
        this.jitRuntimeNanos = jitRuntimeNanos;
    }

    public int getTimesSelected() {
        return timesSelected;
    }


    public void markSelected() {
        // TODO change to some energy based system
        timesSelected++;
        double factor = 1.0 + timesSelected;
        this.priority = score / factor;
    }

    public boolean isActiveChampion() {
        return activeChampion;
    }

    public void activateChampion() {
        this.activeChampion = true;
        this.priority = this.score;
        this.timesSelected = 0;
    }

    public void deactivateChampion() {
        this.activeChampion = false;
    }

    @Override
    public int compareTo(TestCase other) {
        return Double.compare(other.priority, this.priority); // max-heap behavior;
    }
}
