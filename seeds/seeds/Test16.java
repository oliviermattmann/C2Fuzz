public class Test16 {
    private static final int N = 1000;
    private static final int ITERATIONS = 50_000; // warmup count

    public static void main(String[] args) {
        long sum = 0;
        for (int k = 0; k < ITERATIONS; k++) {
            sum += doWork(true);   // run with flag = true
            sum += doWork(false);  // run with flag = false
        }
        // Prevent dead-code elimination
        System.out.println("Final sum: " + sum);
    }

    // Hot method: will be JIT compiled after enough iterations
    private static int doWork(boolean flag) {
        int[] arr = new int[N];
        int result = 0;

        for (int i = 0; i < arr.length; i++) {
            if (i == 0) { // candidate for loop peeling
                arr[i] = 42;
            } else {
                if (flag) {          // loop-invariant condition → unswitching candidate
                    arr[i] = arr[i - 1] + 1;
                } else {
                    arr[i] = arr[i - 1] - 1;
                }
            }
            result += arr[i]; // accumulate so work can't be optimized away
        }

        return result;
    }
}