public class Test10 {
    public void main(String[] args) {
        for (int i = 0; i < 10; i++) {
            

        Whatever w = new Whatever();
        w.doNothing();
        int x = 10;
        int y = x + 5;
        System.out.println(y);
    }
    }
}

class Whatever {
        public void doNothing() {
            // This method intentionally left blank
        }
}