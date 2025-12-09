public class c2fuzz000000052795 {

    long instanceCount;
    int iFld;
    volatile boolean _mutatorToggle;

    void vMeth(int i, long l) {
        int i1;
        int i19 = -845;

        for (i1 = 5; i1 > new MyInteger(1).v; i1 -= 2) {
            try {
                try {
                    for (Object temp = new byte[i19]; ; ) {
                    }
                } finally {
                    boolean flagus2549 = _mutatorFlip();
                    boolean flagus326 = _mutatorFlip();
                    for (;;) {
                        if (flagus326) {
                            break;
                        }
                        if (flagus2549) {
                            break;
                        }
                    }
                }
            } catch (Throwable ax$3) {
                // Swallowing all Throwables (including Errors) – be careful with this in real code.
            }
        }
    }

    void mainTest(String[] strArr1) {
        vMeth(iFld, instanceCount);
    }

    public static void main(String[] strArr) {
        c2fuzz000000052795 _instance = new c2fuzz000000052795();
        for (int i = 0; i < 10_000; ++i) {
            _instance.mainTest(strArr);
        }
    }

    boolean _mutatorFlip() {
        _mutatorToggle = !_mutatorToggle;
        return _mutatorToggle;
    }

    class MyInteger {
        int v;

        MyInteger(int v) {
            int Ni1958 = 32;
            for (int i1958 = 0; i1958 < Ni1958; i1958++) {
                this.v = v;
            }
        }
    }
}