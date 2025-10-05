public class Test9 {
    public void main(String[] args) {
        int x = 10;
        int y = 20;
        boolean result = false;
        for (int i = 0; i < 10000; i++)
            result |= (x > y) && (y < 30);
        System.out.println(result);
    }
}