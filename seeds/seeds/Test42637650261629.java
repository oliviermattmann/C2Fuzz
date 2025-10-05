public class Test42637650261629 {
    private static final int N = 1000;

    private static final int ITERATIONS = 50000;// warmup count


    public static void main(java.lang.String[] args) {
        long sum = 0;
        for (int k = 0; k < Test42637650261629.ITERATIONS; k++) {
            sum += Test42637650261629.doWork(true);// run with flag = true

            sum += Test42637650261629.doWork(false);// run with flag = false

        }
        // Prevent dead-code elimination
        java.lang.System.out.println("Final sum: " + sum);
    }

    // Hot method: will be JIT compiled after enough iterations
    private static int doWork(boolean flag) {
        int[] arr = new int[Test42637650261629.N];
        int result = 0;
        for (int i = 0; i < arr.length; i++) {
            if (i == 0) {
                // candidate for loop peeling
                arr[i] = 42;
            } else if (flag) {
                // loop-invariant condition → unswitching candidate
                arr[i] = arr[i - 1] + 1;
            } else {
                arr[i] = arr[i - 1] - 1;
            }
            synchronized(Test42637650261629.class) {
                result += arr[i];// accumulate so work can't be optimized away

            }
        }
        return result;
    }
}

