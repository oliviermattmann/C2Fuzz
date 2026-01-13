package fuzzer.util;

/*
 * Represents the result of executing a test case both with and without JIT
 * Contains references to the test case and the execution results (both jit and int)
 */
public class TestCaseResult {
    private final TestCase testCase;
    private final ExecutionResult intExecutionResult;
    private final ExecutionResult jitExecutionResult;
    private final boolean compilable;
    private final boolean newCoverage;
    private final int newEdgeCount;



    public TestCaseResult(TestCase testCase, ExecutionResult intExecutionResult, ExecutionResult jitExecutionResult, boolean compilable, boolean newCoverage, int newEdgeCount) {
        this.testCase = testCase;
        this.intExecutionResult = intExecutionResult;
        this.jitExecutionResult = jitExecutionResult;
        this.compilable = compilable;
        this.newCoverage = newCoverage;
        this.newEdgeCount = newEdgeCount;
    }

    public boolean isCompilable() {
        return compilable;
    }

    public TestCase testCase() {
        return testCase;
    }

    public ExecutionResult intExecutionResult() {
        return intExecutionResult;
    }

    public ExecutionResult jitExecutionResult() {
        return jitExecutionResult;
    }

    public boolean hasNewCoverage() {
        return newCoverage;
    }

    public int newEdgeCount() {
        return newEdgeCount;
    }

}
