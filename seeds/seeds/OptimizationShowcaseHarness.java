public final class OptimizationShowcaseHarness {

    private static final int WARMUP = 100_000;
    private static final boolean ALWAYS_TRUE = true;
    private static final boolean ALWAYS_FALSE = false;
    private static final int[] DATA = new int[256];

    static {
        for (int i = 0; i < DATA.length; i++) {
            DATA[i] = i;
        }
    }

    private static int blockElimination(int x) {
        int result = 0;
        if (ALWAYS_FALSE) {
            result += x;
        } else if (ALWAYS_TRUE) {
            result += x + 1;
        } else {
            result += 42;
        }
        return result;
    }

    private static int blockEliminationLoop() {
        int acc = 0;
        for (int i = 0; i < WARMUP; i++) {
            acc += blockElimination(i);
        }
        return acc;
    }

    private static int simplifyPhi(int n) {
        int a = 0;
        int b = 1;
        for (int i = 0; i < n; i++) {
            int tmp = a;
            a = b;
            b = tmp;
        }
        return a + b;
    }

    private static int simplifyPhiLoop() {
        int acc = 0;
        for (int i = 0; i < WARMUP; i++) {
            acc += simplifyPhi(i & 15);
        }
        return acc;
    }

    // Helper stays tiny enough that C2 will inline it on its own.
    private static int mask(int value) {
        return value & 1;
    }

    private static int conditionalExpressionElimination() {
        // The parser can’t see through mask(), so the if survives until PhaseCCP.
        if (mask(0) == 0) {
            return 1;
        } else {
            return 2;
        }
    }

    private static int conditionalExpressionEliminationLoop() {
        int acc = 0;
        for (int i = 0; i < WARMUP; i++) {
            acc += conditionalExpressionElimination();
        }
        return acc;
    }

    private static int rangeCheckElimination() {
        int sum = 0;
        for (int i = 0; i < DATA.length; i++) {
            sum += DATA[i];
        }
        return sum;
    }

    private static int rangeCheckEliminationLoop() {
        int acc = 0;
        for (int i = 0; i < WARMUP; i++) {
            acc += rangeCheckElimination();
        }
        return acc;
    }

    private static int nullCheckElimination() {
        Object o = new Object();
        int value = 0;
        if (o != null) {
            value = o.hashCode();
        }
        return value;
    }

    private static int nullCheckEliminationLoop() {
        int acc = 0;
        for (int i = 0; i < WARMUP; i++) {
            acc += nullCheckElimination();
        }
        return acc;
    }

    private static int canonicalization(int x) {
        return ((x + 0) * 1) - 0 + (x - x);
    }

    private static int canonicalizationLoop() {
        int acc = 0;
        for (int i = 0; i < WARMUP; i++) {
            acc += canonicalization(i);
        }
        return acc;
    }

    public static void main(String[] args) {
        int acc = 0;
        acc += blockEliminationLoop();
        acc += simplifyPhiLoop();
        acc += conditionalExpressionEliminationLoop();
        acc += rangeCheckEliminationLoop();
        acc += nullCheckEliminationLoop();
        acc += canonicalizationLoop();
        if (acc == 42) {
            System.out.println("keep me: " + acc);
        }
    }
}
