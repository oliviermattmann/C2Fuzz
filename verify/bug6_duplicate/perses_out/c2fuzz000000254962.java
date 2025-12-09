public class c2fuzz000000254962 {

    class Wrap {
        int value;

        Wrap(int v) {
            value = new MyInteger(v).v();

            final int Ni8748 = 32;
            for (int i8748 = 1; i8748 < Ni8748; i8748++) {
                value = java.lang.Integer.valueOf(v);
            }
        }

        class MyInteger {
            int v;

            MyInteger(int v) {
                int M6600 = 4;
                int N6600 = 8;

                for (int i6600 = 0; i6600 < M6600; i6600++) {
                    for (int j6600 = 0; j6600 < N6600; j6600++) {
                        switch (i6600) {
                            case -1:
                            case -3:
                                break;
                            case 0:
                                this.v = v;
                                break;
                            default:
                                break;
                        }
                    }
                }

                final int N8353 = 32;
                Object object8353 = "hot";

                for (int i8353 = 0; i8353 < N8353; i8353++) {
                    object8353.toString();
                    if (i8353 == N8353 - 1) {
                        this.v = v;
                    }
                }

                object8353 = 1;
                object8353.toString();
                this.v = v;
            }

            int v() {
                return v;
            }
        }
    }

    static int size = 1024;
    static int[] ia = new int[size];

    public static void main(String[] args) {
        c2fuzz000000254962 m = new c2fuzz000000254962();
        m.test();
    }

    void test() {
        for (int i = 0; i < 20_000; i++) {
            Wrap obj = null;

            if (i % 113 != 0) {
                int limitlz7623 = 2;
                int ysnk5789 = 0;
                int toSinksnk5789 = 0;

                for (; limitlz7623 < 4; limitlz7623 *= 2) {
                    ysnk5789++;
                    try {
                        toSinksnk5789 = 23 * (ysnk5789 - 1);
                    } catch (ArithmeticException ex) {
                        toSinksnk5789 ^= ex.hashCode();
                    }
                }

                int zerolz7623 = 34;
                for (int peellz7623 = 2; peellz7623 < limitlz7623; peellz7623++) {
                    int Ni3760 = 32;
                    for (int i3760 = 0; i3760 < Ni3760; i3760++) {
                        if (i3760 < 10) {
                            zerolz7623 = 0;
                        }
                    }
                    zerolz7623 = 0;
                }

                if (foo17734950493338732(zerolz7623, 0)) {
                    obj = new Wrap(i);
                } else {
                    obj = new Wrap(i);
                }
            }

            foo(obj);
        }
    }

    int foo(Wrap obj) {
        boolean condition = false;
        int first = -1;

        if (obj == null) {
            int M2004 = 4;
            int N2004 = 8;

            for (int i2004 = 0; i2004 < M2004; i2004++) {
                for (int j2004 = 0; j2004 < N2004; j2004++) {
                    switch (i2004) {
                        case -1:
                        case -2:
                        case -3:
                            break;
                        case 0:
                            condition = true;
                            break;
                        default:
                            break;
                    }
                }
            }

            condition = true;
            first = 24;
        }

        for (int i = 0; i < size; i++) {
            int limitlz859 = 2;
            for (; limitlz859 < 4; limitlz859 *= 2) {
                // empty loop body
            }

            int zerolz859 = 34;
            for (int peellz859 = 2; peellz859 < limitlz859; peellz859++) {
                zerolz859 = 0;
            }

            if (zerolz859 == 0) {
                ia[i] = condition ? first : obj.value;
            } else {
                i = first;
            }
        }

        return 0;
    }

    private boolean foo17734950493338732(int p0, int p1) {
        return p0 == p1;
    }
}
