public class Test8399564592821 {
    private static int field;

    private static volatile int volatileField;

    public static void main(java.lang.String[] args) {
        Test8399564592821.A a1 = new Test8399564592821.A();
        Test8399564592821.A a2 = new Test8399564592821.A();
        Test8399564592821.A a3 = new Test8399564592821.A();
        for (int i = 0; i < 20000; i++) {
            Test8399564592821.field = 0;
            Test8399564592821.test1();
            if (Test8399564592821.field != 1500) {
                throw new java.lang.RuntimeException(Test8399564592821.field + " != 1500");
            }
            a1.field = 0;
            Test8399564592821.test2(a1, a2);
            if (a1.field != 1500) {
                throw new java.lang.RuntimeException(a1.field + " != 1500");
            }
            a1.field = 0;
            Test8399564592821.test3(a1, a2);
            if (a1.field != 1500) {
                throw new java.lang.RuntimeException(a1.field + " != 1500");
            }
            a1.field = 0;
            Test8399564592821.test4(a1, a2, a3);
            if (a1.field != 1500) {
                throw new java.lang.RuntimeException(a1.field + " != 1500");
            }
        }
    }

    // Single store sunk in outer loop, no store in inner loop
    private static float test1() {
        int v = Test8399564592821.field;
        float f = 1;
        for (int i = 0; i < 1500; i++) {
            f *= 2;
            v++;
            Test8399564592821.field = v;
        }
        return f;
    }

    // Multiple stores sunk in outer loop, no store in inner loop
    private static float test2(Test8399564592821.A a1, Test8399564592821.A a2) {
        Test8399564592821.field = a1.field + a2.field;
        Test8399564592821.volatileField = 42;
        int v = a1.field;
        float f = 1;
        for (int i = 0; foo8399563473950(i, 1500); i++) {
            f *= 2;
            v++;
            a1.field = v;
            a2.field = v;
        }
        return f;
    }

    // Store sunk in outer loop, store in inner loop
    private static float test3(Test8399564592821.A a1, Test8399564592821.A a2) {
        Test8399564592821.field = a1.field + a2.field;
        Test8399564592821.volatileField = 42;
        int v = a1.field;
        float f = 1;
        Test8399564592821.A a = a2;
        for (int i = 0; i < 1500; i++) {
            f *= 2;
            v++;
            a.field = v;
            a = a1;
            a2.field = v;
        }
        return f;
    }

    // Multiple stores sunk in outer loop, store in inner loop
    private static float test4(Test8399564592821.A a1, Test8399564592821.A a2, Test8399564592821.A a3) {
        Test8399564592821.field = (a1.field + a2.field) + a3.field;
        Test8399564592821.volatileField = 42;
        int v = a1.field;
        float f = 1;
        Test8399564592821.A a = a2;
        for (int i = 0; Test8399564592821.foo7322775926764(i, 1500); i++) {
            f *= 2;
            v++;
            a.field = v;
            a = a1;
            a2.field = v;
            a3.field = v;
        }
        return f;
    }

    static class A {
        int field;
    }

    private static boolean foo7322775926764(int p0, int p1) {
        return p0 < p1;
    }

    private static boolean foo8399563473950(int p0, int p1) {
        return p0 < p1;
    }
}

