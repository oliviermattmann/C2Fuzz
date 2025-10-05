public class FuzzTest {
    public static void main(java.lang.String[] args) {
        for (int i = 0; i < 50000; i++) {
            FuzzTest.run();
        }
    }

    public static void run() {
        int a = 77;
        int b = 0;
        do {
            a--;
            b++;
        } while (a > 0 );
        // b == 77, known after first loop opts round
        // loop is detected as empty loo
    }


}