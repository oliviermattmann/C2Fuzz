public class Test2 {
    public void main(String[] args) {
        double x = 15.5;
        int y = 4;
        double result = 0.0;
        for (int i = 0; i < 10000; i++) {
            result += (x / 2.0) - (y * 3.5) + (x * y);
        }
        System.out.println(result);
    }
}