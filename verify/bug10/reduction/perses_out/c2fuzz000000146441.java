public class c2fuzz000000146441 {

    void test() {
        Byte x = 1;

        for (int i = 0; i < 10000; i++) {
            if ((i & 1) == 0) {
                int N9791 = 32;
                for (int i9791 = 0; i9791 < N9791; i9791++) {
                    if (i9791 == 1) {
                        x = (byte) x;
                    }
                }

                int N7582 = 32;
                for (int i7582 = 0; i7582 < N7582; i7582++) {
                    Object object1168;
                    object1168 = 1;
                    object1168.toString();

                    final int Ni318 = 32;
                    for (int i318 = 1; i318 < Ni318; i318++) {
                        x = (byte) x;
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        c2fuzz000000146441 instance = new c2fuzz000000146441();
        instance.test();
    }
}
