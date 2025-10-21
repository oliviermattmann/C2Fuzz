public final class BlockEliminationTrigger {
    private static int guardedSwitch(int x) {
        if (x < 0 || x > 2) {
            return -1;
        }
        switch (x) {
            case 0: return 10;
            case 1: return 20;
            case 2: return 30;
            default:
                // CFG still creates a block for this, but it’s unreachable at fixup.
                return 99;
        }
    }

    private static int test() {
        int acc = 0;
        for (int i = 0; i < 200_000; i++) {
            acc += guardedSwitch(i & 3); // keep actual values 0..3
        }
        if (acc == 42) {
            System.out.println(acc);
        }
        return acc;
    }

    public static void main(String[] args) {
        int acc = test();
        if (acc == 42) {
            System.out.println(acc);
        }
    }
}