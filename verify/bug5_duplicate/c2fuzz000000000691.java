public class c2fuzz000000000691 {
    private static volatile int volatileField;

    public static void main(String[] args) {
        for (int i = 0; i < 20_000; i++) {
            test1(1, 0, 0, false);
            helper1(1, 0, 0, true);
            helper2(0, 0);
        }
    }

    private static int test1(int i, int j, int k, boolean flag) {
        int l;
        for (l = 0; l < 10; l++) {
        }
        j = helper2(j, l);
        return helper1(i, j, k, flag);
    }

    private static int helper2(int j, int l) {
        if (l == 10) {
            j = Integer.MAX_VALUE-1;
        }
        return j;
    }

    private static int helper1(int i, int j, int k, boolean flag) {
        if (flag) {
            synchronized(c2fuzz000000000691.class) {
                k = Integer.max(k, -2);
                int[] array = new int[i + k];
                notInlined(array);
                return array[j];
            }
        }
        volatileField = 42;
        return volatileField;
    }

    private static void notInlined(int[] array) {
    }
}