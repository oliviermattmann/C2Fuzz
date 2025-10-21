public class Test15 {
    public void run() {
        long sum = 0;
        Helper1 h = new Helper1(10);
        for (int j = 0; j < 20_000; j++) {   // run enough iterations
            sum += hotMethod(j, h);
        }
        if (sum == 42) {
            System.out.println("Unlikely");
        }
    }

    int hotMethod(int x, Helper1 h) {
        int r = 0;
        for (int i = 0; i < 10; i++) {
            r += (x * i) ^ (i >>> 2) - h.getValue();  // some arithmetic + shift
        }
        return r;
    }


    
    public static void main(String[] args) {
        Test15 t = new Test15();
        t.run();
    }
}

class Helper1 {
    int value;

    Helper1(int v) {
        value = v;
    }

    int getValue() {
        return value;
    }
}