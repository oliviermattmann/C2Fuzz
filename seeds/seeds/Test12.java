public class Test12 {
    public void foo(int N, int a) {
        int m = 0;
        m = a + a;
        System.out.println(m);
    }


    public void main(String[] args) {
        for (int i = 0; i < 10000; i++)
        foo(i, 5);
    }
}