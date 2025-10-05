public class Test86006936042006 {
    public static final int N = 400;

    public static long instanceCount = 3024694135L;

    public static boolean bFld = true;

    public int iFld = -11;

    public static long iMeth_check_sum = 0;

    public static void vMeth(int i3, int i4, int i5) {
        int i6 = -71;
        int i7 = 88;
        int i8 = 217;
        int i9 = 14;
        int i10 = 9677;
        int i18 = -244;
        int i19 = 107;
        int iArr[] = new int[Test86006936042006.N];
    }

    public static void init(int[] a, int seed) {
        for (int j = 0; j < a.length; j++) {
            a[j] = ((j % 2) == 0) ? seed + j : seed - j;
        }
    }

    public static long checkSum(int[] a) {
        long sum = 0;
        for (int j = 0; j < a.length; j++) {
            sum += (a[j] / (j + 1)) + (a[j] % (j + 1));
        }
        return sum;
    }

    public static int iMeth(boolean b, int i2) {
        byte by = 81;
        int i21 = -24074;
        int i22 = 7;
        int i23 = -7;
        int i24 = -70;
        int iArr2[] = new int[Test86006936042006.N];
        boolean b2 = false;
        Test86006936042006.init(iArr2, -27);
        Test86006936042006.vMeth(189, i2, i2);
        for (int i20 : iArr2) {
            by *= ((byte) (Test86006936042006.instanceCount));
            for (i23 = 1; i23 < 4; ++i23) {
                i24 -= i23;
                int M9322 = 16;
                int N9322 = 32;
                for (int i9322 = 0; i9322 < M9322; i9322++) {
                    for (int j9322 = 0; j9322 < N9322; j9322++) {
                        switch (i9322) {
                            case -1, -2, -3 :
                                break;
                            case 0 :
                                Test86006936042006.bFld = b2;
                        }
                    }
                }
                Test86006936042006.bFld = b2;
            }
        }
        long meth_res = ((((((((b ? 1 : 0) + i2) + by) + i21) + i22) + i23) + i24) + (b2 ? 1 : 0)) + Test86006936042006.checkSum(iArr2);
        Test86006936042006.iMeth_check_sum += meth_res;
        return ((int) (meth_res));
    }

    public void mainTest(java.lang.String[] strArr1) {
        int i;
        int i1;
        int i25;
        int i26 = 9;
        int i27;
        int i28;
        byte by1 = 35;
        float f2;
        for (i = 17; 310 > i; ++i) {
            i1 = (Test86006936042006.iMeth(Test86006936042006.bFld, iFld) - iFld) + by1;
        }
        i1 = 231;
        iFld += -13496;
        for (i25 = 2; i25 < 271; i25++) {
            i26 -= i;
            if (Test86006936042006.bFld)
                break;

        }
        i26 = i;
        iFld += ((int) (1.338F));
        iFld += 30984;
        i27 = 1;
        do {
            iFld *= i25;
            for (i28 = 4; i28 < 75; ++i28) {
                i1 += i25;
            }
        } while ((++i27) < 335 );
        f2 = 210;
        do {
            iFld -= i25;
        } while ((--f2) > 0 );
        java.lang.System.out.println("iFld = " + iFld);
    }

    public static void main(java.lang.String[] strArr) {
        Test86006936042006 _instance = new Test86006936042006();
        _instance.mainTest(strArr);
        int iFld_sav = _instance.iFld;
        for (int i = 0; i < 10; i++) {
            _instance.iFld = -11;
            _instance.mainTest(strArr);
            if (_instance.iFld != iFld_sav) {
                throw new java.lang.RuntimeException((("incorrect execution " + _instance.iFld) + " != ") + iFld_sav);
            }
        }
    }
}

