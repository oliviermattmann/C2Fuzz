public class LoopUnswitchingTest {

    public static void main(String[] args) {
        int[] arr = new int[1000];
        int result = 0;
        for (int k = 0; k < 50_000; k++) {
            result += compute(arr, (k & 1) == 0);
        }
        System.out.println("Final result: " + result); // Prevent dead-code elimination
    }
    
    

    private static int compute(int[] arr, boolean flag) {
        int s = 0;
        for (int i = 0; i < arr.length; i++) {
            if (flag) {
                s += arr[i] * 2;   // heavy enough
            } else {
                s += arr[i] * 3;
            }
        }
        return s;
    }
    
    
}
