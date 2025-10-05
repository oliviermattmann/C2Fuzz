package fuzzer.util;

/*
 * Represents the result of executing a test case (Either Interpreter or JIT)
 */
public class ExecutionResult {
    final int exitCode;
    final String stdout;
    final String stderr;
    final long executionTime;
    final boolean timedOut;

    public ExecutionResult(int exitCode, String stdout, String stderr, long executionTime, boolean timedOut) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.executionTime = executionTime;
        this.timedOut = timedOut;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ExecutionResult)) return false;
        ExecutionResult other = (ExecutionResult) obj;

        return exitCode == other.exitCode &&
                stdout.equals(other.stdout) &&
                stderr.equals(other.stderr);
    }

    @Override
    public String toString() {
        return "exit=" + exitCode +
                "\nstdout:\n" + stdout +
                "\nstderr:\n" + stderr;
    }

    public int exitCode() {
        return exitCode;
    }

    public String stdout() {
        return stdout;
    }

    public String stderr() {
        return stderr;
    }

    public long executionTime() {
        return executionTime;
    }

    public boolean timedOut() {
        return timedOut;
    }
}
