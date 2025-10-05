package fuzzer.util;

/*
 * Represents the result of executing a test case both with and without JIT
 * Contains references to the test case and the execution results (both jit and int)
 */
public class TestCaseResult {
    private TestCase testCase;
    private ExecutionResult intExecutionResult;
    private ExecutionResult jitExecutionResult;
    private boolean compilable;



    public TestCaseResult(TestCase testCase, ExecutionResult intExecutionResult, ExecutionResult jitExecutionResult, boolean compilable) {
        this.testCase = testCase;
        this.intExecutionResult = intExecutionResult;
        this.jitExecutionResult = jitExecutionResult;
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

}
