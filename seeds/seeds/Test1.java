public class Test1 {
    public void main(String[] args) {

        for (int i = 0; i < 20000; i++) {
            runTest();
        }
    }

    void runTest() {
        int i = 5;
        int res = i++ + ++i;
        System.out.println(res);
    }
}