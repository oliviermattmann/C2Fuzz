package fuzzer.util;

import fuzzer.mutators.MutatorType;

/*
 * implements comparable to be used in priority queue
 */
public class TestCase implements Comparable<TestCase>{
    private String testCaseName; // name of the test case without the .java suffix
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
    private static final double PRIORITY_DECAY_EXPONENT = 1.0;

    public TestCase(String name, OptimizationVectors parentOptVectors, MutatorType mutationType, double parentScore, String parentName) {
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
