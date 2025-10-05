package fuzzer.util;

import java.util.Map;

import fuzzer.mutators.MutatorType;

/*
 * implements comparable to be used in priority queue
 */
public class TestCase implements Comparable<TestCase>{
    private String name;
    private String path;
    private Map<String, Integer> occurences;
    private double score;
    private double priority;
    private int timesSelected;

    // reference to parent needed because we need to see whether there there are new optimization behaviors
    private final String parentName;
    private final String parentPath;
    private final Map<String, Integer> parentOccurences;
    private MutatorType appliedMutation;





    public TestCase(String parentName, String parentPath, Map<String,Integer> parentOccurences) {
        this.name = null;
        this.path = null;
        this.occurences = null;
        this.parentName = parentName;
        this.parentPath = parentPath;
        this.parentOccurences = parentOccurences;
        this.score = Double.NEGATIVE_INFINITY;
        this.priority = Double.NEGATIVE_INFINITY;
        this.timesSelected = 0;
        this.appliedMutation = null;
    }

    public TestCase(String parentName, String parentPath, Map<String,Integer> parentOccurences, MutatorType targetMutation) {
        this.name = null;
        this.path = null;
        this.occurences = null;
        this.parentName = parentName;
        this.parentPath = parentPath;
        this.parentOccurences = parentOccurences;
        this.score = Double.NEGATIVE_INFINITY;
        this.priority = Double.NEGATIVE_INFINITY;
        this.timesSelected = 0;
        this.appliedMutation = null;
    }


    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Map<String, Integer> getOccurences() {
        return occurences;
    }

    public void setOccurences(Map<String, Integer> occurences) {
        this.occurences = occurences;
    }

    public String getParentName() {
        return parentName;
    }

    public String getParentPath() {
        return parentPath;
    }

    public Map<String, Integer> getParentOccurences() {
        return parentOccurences;
    }

    public void setMutation(MutatorType mutation) {
        this.appliedMutation = mutation;
    }

    public MutatorType getMutation() {
        return appliedMutation;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
        this.priority = score;
        this.timesSelected = 0;
    }

    public int getTimesSelected() {
        return timesSelected;
    }

    // TODO change this to some decay or energy based system
    // so that a test case can be selected multiple times if it has a high score
    
    public void markSelected() {
        timesSelected++;
        this.priority = score / (1.0 + timesSelected);
    }

    @Override
    public int compareTo(TestCase other) {
        // higher score = higher priority
        return Double.compare(other.priority, this.priority);
    }

}
