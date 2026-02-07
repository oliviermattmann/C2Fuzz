package fuzzer.runtime;

import fuzzer.model.TestCase;

/* Currently not in use, might be useful though to keep throughput more stable 
   by lowering the score of long executing test cases */
public final class RuntimeWeighter {

    private static final long TARGET_RUNTIME_NANOS = 1_000_000_000L;

    private RuntimeWeighter() {}

    public static double weight(TestCase testCase) {
        if (testCase == null) {
            return 1.0;
        }
        long interpreterRuntime = testCase.getInterpreterRuntimeNanos();
        long jitRuntime = testCase.getJitRuntimeNanos();
        long runtime = Math.max(interpreterRuntime, jitRuntime);
        if (runtime <= 0L) {
            return 1.0;
        }
        double weight = Math.exp(-(double) runtime / (double) TARGET_RUNTIME_NANOS);
        if (!Double.isFinite(weight)) {
            return 0.0;
        }
        return Math.max(0.0, weight);
    }
}
