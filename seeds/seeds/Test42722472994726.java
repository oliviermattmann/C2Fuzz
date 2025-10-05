public class Test42722472994726 {
    public void main(java.lang.String[] args) {
        int i = 5;
        int j = 10;
        int result = 0;
        for (int k = 0; k < 10000; k++) {
            synchronized(this) {
                result += ((i--) * 2) + i;
            }
        }
        java.lang.System.out.println(result);
        java.lang.System.out.println(i);
        java.lang.System.out.println(j);
    }
}

