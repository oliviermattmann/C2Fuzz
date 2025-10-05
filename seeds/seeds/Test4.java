public class Test4 {
    public void main(String[] args) {
        int i = 5;
        int j = 10;
        int result = 0;
        for (int k = 0; k < 10000; k++)
            result += (i-- * 2) + i;
        System.out.println(result);
        System.out.println(i);
        System.out.println(j);
    }
}