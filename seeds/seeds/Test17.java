public class Test17 {
    public static void main(String[] args) {
        int x = 1;
        for (int k = 0; k < 100_000; k++) {
            int y = 42 / x; // speculated never zero
            if (k == 90_000) x = 0;  // uncommon trap, deopt
        }

    }
    
}
