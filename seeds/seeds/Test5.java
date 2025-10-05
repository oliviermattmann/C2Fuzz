public class Test5 {
    public static boolean hotMethod(boolean a, boolean b) {
        boolean res = false;
        for (int i = 0; i < 10000; i++)
            res |= b && !(b || a);
        return res;
    }

    public static void main(String[] args) {
        for (int j = 0; j < 20000; j++) {
            hotMethod(true, false);
        }
    }
}