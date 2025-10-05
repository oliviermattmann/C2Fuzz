// ForceSplitIf.java
public class ForceSplitIf {
    static volatile int sink;

    // Compile only this to keep the graph tiny and deterministic
    static int work(int[] a, int i) {
        // Two related tests on the same value + guarded array accesses.
        // This is a textbook Split-If candidate in PhaseIdealLoop.
        if (i >= 0 && i < a.length) {
            int x = a[i];
            if (i + 1 < a.length) {   // another guard that tightens the range of i
                x += a[i + 1];
            }
            sink = x;                 // anti-escape, keep it side-effectful
            return x;
        }
        return 0;
    }

    public static void main(String[] args) {
        int[] a = new int[1024];
        for (int j = 0; j < a.length; j++) a[j] = j;

        int sum = 0;
        // Run hot enough with varied i so C2 compiles 'work'
        for (int k = 0; k < 5_000_000; k++) {
            int i = (k * 1103515245) & 2047; // wide range; sometimes out of bounds
            sum += work(a, i - 512);
        }
        System.out.println(sum); // keep it alive
    }
}
