public class Test14 {
    public void run(String[] args) {
        // Especially with a debug build, the JVM startup can take a while,
        // so it can take a while until our code is executed.
        System.out.println("Run");

        // Repeatedly call the test method, so that it can become hot and
        // get JIT compiled.
        for (int i = 0; i < 10_000; i++) {
            test(i, i + 1);
        }
        System.out.println("Done");
    }
    
    // The test method we will focus on. 
    public  int test(int t, int z) {
        int a = 77;
        int b = 0;
        int c = t+z;
        do {
            a--;
            b++;
        } while (a > 0);
        return b;
        //return multiply(101, a) + multiply(202, a) + multiply(53, b);
    }

    public  int multiply(int a, int b) {
        return a * b;
    }
    
    public static void main(String[] args) {
        Test14 obj = new Test14();
        obj.run(args);
    }
}
