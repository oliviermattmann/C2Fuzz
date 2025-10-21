public final class LoopOptShowcase {
    private static final int[] DATA = new int[1024];

    static {
        for (int i = 0; i < DATA.length; i++) {
            DATA[i] = i & 0xFF;
        }
    }

    private static int exercise(int limit, int mode) {
        int sum = 0;

        // Loop peeling (pre-loop to guard the range check)
        int i = 0;
        while (i < limit && DATA[i] >= 0) {
            sum += DATA[i];
            i++;
        }

        // Loop unrolling + parallel induction variable rewrite
        int idx = 0;
        for (int j = 0; j < 256; j++, idx += 2) {
            sum += DATA[(idx) & 0x3FF];
        }

        // Loop unswitching (condition depends on loop-invariant “mode”)
        for (int k = 0; k < 256; k++) {
            if ((mode & 1) == 0) {
                sum += DATA[k & 0x3FF];
            } else {
                sum -= DATA[k & 0x3FF];
            }
        }

        // Split-if (C2 clones the if through the incoming phi)
        for (int m = 0; m < 256; m++) {
            if ((m & 3) == 0 && DATA[m] > 32) {
                sum += 1;
            }
        }

        return sum;
    }

    public static void main(String[] args) {
        int total = 0;
        for (int warm = 0; warm < 50_000; warm++) {
            total += exercise(900, warm);
        }
        System.out.println("Total: " + total);
    }
}