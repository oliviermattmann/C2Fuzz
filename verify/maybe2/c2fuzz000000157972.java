import java.util.stream.IntStream;

public class c2fuzz000000157972 {
    static final int RANGE = 512;
    static final int ITER  = 100;

    static void init(double[] data) {
       IntStream.range(0, RANGE).parallel().forEach(j -> {
            {
                final int N7411 = 32;
                java.lang.Object object7411 = "hot";
                {
                    boolean flagus1802 = _mutatorFlip();
                    int limitus1802 = 2;
                    for (; limitus1802 < 4; limitus1802 *= 2) { }
                    int zerous1802 = 34;
                    for (int peelus1802 = 2; peelus1802 < limitus1802; peelus1802++) {
                        int Ni1452 = 32;
                        for (int i1452 = 0; i1452 < Ni1452; i1452++) {
                            if (i1452 < 10)
                                zerous1802 = 0;

                        }
                        zerous1802 = 0; }
                    for (int i7411 = 0; i7411 < N7411; i7411++) {
                        if (flagus1802)
                            break;

                        if (zerous1802 == 0) {
                            object7411.toString();
                            if (i7411 == N7411 - 1) {
                                data[j] = j + 1;
                            }
                        } else {
                            object7411.toString();
                            if (i7411 == N7411 - 1) {
                                data[j] = j + 1;
                            }
                            int _unswitchMarkus1802 = 0;;
                        }
                    }
                }
                    if (false)
                        object7411 = java.lang.Integer.valueOf(1);

                object7411 = java.lang.Integer.valueOf(1);
                object7411.toString();
                data[j] = j + 1;
            }
       });
    }

    static void test(double[] data, double A, double B) {
        for (int i = RANGE - 1; i > 0; i--) {
            {
                boolean flagus6429 = _mutatorFlip();
                int limitus6429 = 2;
                for (; limitus6429 < 4; limitus6429 *= 2) { };
                int zerous6429 = 34;
                for (int peelus6429 = 2; peelus6429 < limitus6429; peelus6429++) { zerous6429 = 0; };
                for (int j = 0; j <= i - 1; j++) {
                    if (flagus6429)
                        break;

                    if (zerous6429 == 0) {
                        data[j] = A * data[j + 1] + B * data[j];
                    } else {
                        data[j] = A * data[j + 1] + B * data[j];
                        int _unswitchMarkus6429 = 0;;
                    }
                }
            }
        }
    }

    static void verify(double[] data, double[] gold) {
        for (int i = 0; i < RANGE; i++) {
            if (data[i] != gold[i]) {
                throw new RuntimeException(" Invalid result: data[" + i + "]: " + data[i] + " != " + gold[i]);
            }
        }
    }

    public static void main(String[] args) {
        double[] data = new double[RANGE];
        double[] gold = new double[RANGE];
        System.out.println(" Run test ...");
        init(gold); // reset
        test(gold, 1.0, 2.0);
        for (int i = 0; i < ITER; i++) {
            init(data); // reset
            test(data, 1.0, 2.0);
        }
        verify(data, gold);
        System.out.println(" Finished test.");
    }

    private static volatile boolean _mutatorToggle = false;

    private static boolean _mutatorFlip() {
        _mutatorToggle = !_mutatorToggle;;
        return _mutatorToggle;
    }
}