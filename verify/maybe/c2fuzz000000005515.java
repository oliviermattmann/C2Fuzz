
public class c2fuzz000000005515 {

    public static long instanceCount=-37082278491330812L;
    public static float fFld=1.509F;
    public static int iFld=89;

    public static long vMeth_check_sum = 0;

    public void mainTest() {int i19;
        int i20 = 13736;
        int i21;
        int i24 = 5;
        boolean b2=true;

        double d;
        int i3;

        for (d = 5; d < 131; ++d) {
            i3 = 12;
            while (--i3 > 0) {
                c2fuzz000000005515.fFld *= -31237;
            }
        }
        c2fuzz000000005515.fFld %= 16334;
        c2fuzz000000005515.instanceCount = 0;
        for (i19 = 2; i19 < 281; i19++) {
            try {
                int Ni9819 = 32;
                for (int i9819 = 0; i9819 < Ni9819; i9819++) {
                    if (i9819 < 10)
                        c2fuzz000000005515.iFld = 57 % i20;

                }
                c2fuzz000000005515.iFld = 57 % i20;
            } catch (ArithmeticException a_e) {}
            i20 += (((i19 * c2fuzz000000005515.fFld) + c2fuzz000000005515.instanceCount) - i19);
            for (i21 = i19; i21 < 90; i21++) {
                if (b2) {
                } else {
                    // CastII of b2 to false added here becomes top during igvn. It's used by a Phi
                    // at a Region that merges paths from the switch and if. Some of those paths are
                    // found unreachable at parse time but added to the Region anyway.
                    switch ((((i21 >>> 1) % 4) * 5) + 115) {
                    case 129:
                        break;
                    case 135:
                        i24 |= i24;
                    }
                }
            }
        }

        System.out.println("vMeth_check_sum: " + vMeth_check_sum);
    }
    public static void main(String[] strArr) {
        try {
            c2fuzz000000005515 _instance = new c2fuzz000000005515();
            for (int i = 0; i < 10; i++ ) {
                _instance.mainTest();
            }
         } catch (Exception ex) {
         }
    }
}