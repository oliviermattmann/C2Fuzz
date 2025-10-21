// AutoBoxElimTrigger.java
public final class AutoBoxElimDemo {
    private static final int WARMUP = 20_000;

    private static int boxedAccumulator(int n) {
        Integer acc = Integer.valueOf(0);
        for (int i = 0; i < n; i++) {
            acc = Integer.valueOf(acc.intValue() + 1); // creates a box each trip
        }
        return acc.intValue(); // only the primitive escapes
    }

    public static void main(String[] args) {
        for (int i = 0; i < WARMUP; i++) {
            boxedAccumulator(512); // make it hot
        }
        int result = boxedAccumulator(1_000_000);
        System.out.println(result);
    }
}
