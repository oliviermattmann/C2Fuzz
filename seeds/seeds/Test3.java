public class Test3 {
    private boolean checkA() { return true; }
    private boolean checkB() { return false; }
    private int getVal() { return 5; }

    public void main(String[] args) {
        boolean result = false;
        for (int i = 0; i < 10000; i++) result |= (checkA() && checkB()) || (getVal() > 3);
        System.out.println(result);
    }
}