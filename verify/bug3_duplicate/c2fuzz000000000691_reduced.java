class c2fuzz000000000691_reduced {

    static int volatileField;

    public static void main(String[] args) {
        for (int i = 0; i < 20_000; i++) {
            test1(1, 0, 0, false);
            helper1(1, 0, 0, true);
        }
    }

    static int test1(int i, int j, int k, boolean flag) {
        int l;
        for (l = 0; l < 10; l++) {
        }
        j = helper2(j, l);
        return helper1(i, j, k, flag);
    }

    static int helper2(int j, int l) {
        if (l == 10) {
            j = Integer.MAX_VALUE;
        }
        return j;
    }

    static int helper1(int i, int j, int k, boolean flag) {
        if (flag) {
            synchronized (c2fuzz000000000691_reduced.class) {
                k = Integer.max(k, 2);
                int[] array = new int[i + k];
                return array[j];
            }
        }
        return volatileField;
    }
}