public class Test0 {
    public void runTest() {
        String s1 = "hello";
        String s2 = "world";
        int len1 = s1.length();
        int len2 = s2.length();

        len1 = len1 - len2;
        
        String result = (len1 > len2) ? s1 + "!" : s2 + "?";
        System.out.println(result);

    }
    public void main(String[] args) {
        for (int i = 0; i < 20000; i++) {
            runTest();
        }
    }
}