public class Test6 {
    public void main(String[] args) {
        char c1 = 'A';
        char c2 = 'B';
        int result = 0;
        for (int i = 0; i < 10000; i++)
            result += (c1 + c2) - 10;
        System.out.println(result);
    }
}