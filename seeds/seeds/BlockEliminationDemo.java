interface FlagProvider {
    boolean flag();
}

final class ConstantProvider implements FlagProvider {
    public boolean flag() { return false; }
}

public final class BlockEliminationDemo {
    private static final FlagProvider PROVIDER = new ConstantProvider();

    private static int branchy(int x) {
        // At parse time this is still a virtual call, so both arms stay in the graph.
        if (PROVIDER.flag()) {
            // This whole block is dead, but it isn’t removed until CFG cleanup.
            return x * 42;
        } else {
            return x + 1;
        }
    }

    public static void main(String[] args) {
        int acc = 0;
        for (int i = 0; i < 200_000; i++) {
            acc += branchy(i);
        }
        if (acc == 42) {
            System.out.println(acc);
        }
    }
}
