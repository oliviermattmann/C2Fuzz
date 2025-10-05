public class LoopPeelingTest {
    public static void main(String[] args) {
        int[] arr = new int[1000];
        for (int k = 0; k < 50_000; k++) {
            sumArray(arr);
        }
    }

    private static int sumArray(int[] arr) {
        int s = 0;
        for (int i = 0; i < arr.length; i++) {
            s += arr[i];
        }
        return s;
    }
    
    
}
