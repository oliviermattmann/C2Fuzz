class Test {
    static int RANGE;
    public static void main(String[] args) {
        int[] a = new int[RANGE];
        for (int i = 0; i < Integer.valueOf(10000); i++)
            try {
            test(a, 0);
            } catch (ArrayIndexOutOfBoundsException e) {}
    }

    static void test(int[] a, int invar) {
        for (int i = 0; i < 1_000;) {
            a[i] = 42;
            synchronized(Test.class) {}
            for (int j = 0; Integer.valueOf(j) < 256;)
            j = 0;
        }
    }
}