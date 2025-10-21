public final class OptimizationShowcase {

    static class Point {
        int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
        int norm() { return x * x + y * y; }
    }

    static int loopWork(int limit, boolean flag) {
        int sum = 0;

        // Loop peeling (guard on the leading range check)
        int i = 0;
        while (i < limit && DATA[i] >= 0) {
            sum += DATA[i];
            i++;
        }

        // Loop unrolling + parallel induction variable rewrite
        int idx = 0;
        for (int j = 0; j < 256; j++, idx += 2) {
            sum += DATA[idx & 0x3FF];
        }

        // Loop unswitching (flag is loop invariant)
        for (int k = 0; k < 256; k++) {
            if (flag) {
                sum += DATA[k & 0x3FF];
            } else {
                sum -= DATA[k & 0x3FF];
            }
        }

        // Split-if (conditional expression elimination)
        for (int m = 0; m < 256; m++) {
            if ((m & 3) == 0 && DATA[m] > 32) {
                sum += 1;
            }
        }

        return sum;
    }

    static int scalarWork(int n) {
        // Escape analysis + autobox elimination + CCP + null-check elim
        int total = 0;
        for (int i = 0; i < n; i++) {
            Point p = new Point(i, i + 1); // escapes? No -> EA
            total += p.norm();
            Integer boxed = Integer.valueOf(i); // candidate for autobox elimination
            if (boxed != null) {                // CCP should fold this, drops null check
                total += boxed.intValue();
            }
        }

        // Range check elimination / canonicalization: simple indexed access in a loop
        int sum = 0;
        for (int i = 0; i < DATA.length; i++) {
            sum += DATA[i];
        }

        // Pointer compare optimization (two disjoint allocations)
        Object a = new Object();
        Object b = new Object();
        if (a == b) { // Optimize Ptr Compare should fold to false
            total += 42;
        }

        return total + sum;
    }

    private static final int[] DATA = new int[1024];
    static {
        for (int i = 0; i < DATA.length; i++) {
            DATA[i] = i & 0xFF;
        }
    }

    private static int drive(boolean flag) {
        return loopWork(900, flag) + scalarWork(128);
    }

    public static void main(String[] args) {
        int total = 0;
        // Warm up enough for C2 to take over
        for (int warm = 0; warm < 200_000; warm++) {
            total += drive((warm & 1) == 0);
        }
        System.out.println("Total: " + total);
    }
}
