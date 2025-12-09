public class c2fuzz000000535421 {

    long instanceCount;
    int iFld;
    boolean _mutatorToggle;

    void vMeth(int i, long l) {
        int i1;
        int i19 = -845;

        for (i1 = 5; i1 > 1; i1 -= 2) {
            try {
                int ax$0 = i19;
                try {
                    for (Object temp = new byte[i19]; ; ) {
                        // infinite loop
                    }
                } finally {
                    int limitlz6086 = 2;
                    for (; limitlz6086 < 4; limitlz6086 *= 2) {
                    }

                    int zerolz6086 = 34;
                    for (int peellz6086 = 2; peellz6086 < limitlz6086; peellz6086++) {
                        zerolz6086 = 0;
                    }

                    if (zerolz6086 == 0) {
                        i19 = ax$0;
                    } else {
                        i19 = ax$0;
                    }

                    int limitus2549 = 2;
                    for (; limitus2549 < 4; limitus2549 *= 2) {
                    }

                    boolean flagus8038 = _mutatorFlip();
                    for (int peelus2549 = 2; peelus2549 < limitus2549; peelus2549++) {
                        if (flagus8038) {
                            break;
                        }
                    }
                }
            } catch (Throwable ax$3) {
                // ignored
            }
        }
    }

    void mainTest(String[] strArr1) {
        vMeth(iFld, instanceCount);
    }

    public static void main(String[] strArr) {
        c2fuzz000000535421 _instance = new c2fuzz000000535421();
        for (int i = 0; i < 10_000; ++i) {
            _instance.mainTest(strArr);
        }
    }

    boolean _mutatorFlip() {
        return _mutatorToggle;
    }
}
