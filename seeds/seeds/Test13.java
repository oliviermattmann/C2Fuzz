public class Test13 {
    public void run() {
        long sum = 0;
        for (int j = 0; j < 20_000; j++) {   // run enough iterations
            sum += hotMethod(j);
        }
        if (sum == 42) {
            System.out.println("Unlikely");
        }
    }

    int hotMethod(int x) {
        int r = 0;
        for (int i = 0; i < 1000; i++) {
            r += (x * i) ^ (i >>> 2);  // some arithmetic + shift
        }
        return r;
    }
    public static void main(String[] args) {
        Test13 t = new Test13();
        t.run();
    }
}