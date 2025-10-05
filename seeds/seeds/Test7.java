public class Test7 {
    public void main(String[] args) {
        int a = 12; // 1100
        int b = 5;  // 0101
        int result = 0;
        for (int i = 0; i < 10000; i++)
            result = (a & b) | (a ^ b);
        System.out.println(result);
    }
}