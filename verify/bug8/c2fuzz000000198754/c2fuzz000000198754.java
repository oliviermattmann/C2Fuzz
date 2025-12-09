

/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug 8260716
 * @summary Test for correct code generation by the JIT
 * @run main/othervm -Xbatch -XX:CompileCommand=compileonly,*c2fuzz000000198754.test
 *                   -XX:+IgnoreUnrecognizedVMOptions
 *                   -XX:+UnlockDiagnosticVMOptions -XX:-IdealizeClearArrayNode
 *                   compiler.codegen.c2fuzz000000198754
 */


public class c2fuzz000000198754 {
    static int[] f1;

    private static void test() {
        {
            {
                f1 = new int[8];
                final int Ni2791 = 32;
                for (int i2791 = 1; i2791 < Ni2791; i2791++) {
                    {
                        int limitlz1049 = 2;
                        {
                            boolean flagus1072 = _mutatorFlip();
                            int limitus1072 = 2;
                            for (; limitus1072 < 4; limitus1072 *= 2) { }
                            int zerous1072 = 34;
                            for (int peelus1072 = 2; peelus1072 < limitus1072; peelus1072++) { zerous1072 = 0; }
                            {
                                int ysnk4223 = 0;
                                int toSinksnk4223 = 0;
                                for (; limitlz1049 < java.lang.Integer.valueOf(4) ; limitlz1049 *= 2) {
                                    ysnk4223++;;
                                    if (flagus1072) {
                                        break;
                                    }
                                    if (zerous1072 == 0) {
                                    } else {
                                        int _unswitchMarkus1072 = 0;
                                    }
                                    try { toSinksnk4223 = 23 * (ysnk4223 - 1); } catch (ArithmeticException ex) { toSinksnk4223 ^= ex.hashCode(); };
                                }
                                int _sinkUsesnk4223 = toSinksnk4223;
                            }
                        }
                        int zerolz1049 = 34;
                            {
                                int ysnk2572 = 0;;
                                int toSinksnk2572 = 0;;
                                for (int peellz1049 = 2; peellz1049 < limitlz1049; peellz1049++) {
                                    ysnk2572++;;
                                    {
                                        synchronized(c2fuzz000000198754.class) {
                                            zerolz1049 = 0;
                                            final int Ni9611 = 32;
                                            {
                                                boolean flagus8465 = _mutatorFlip();
                                                int limitus8465 = 2;
                                                for (; limitus8465 < 4; limitus8465 *= 2) {
                                                }
                                                int zerous8465 = 34;
                                                for (int peelus8465 = 2; peelus8465 < limitus8465; peelus8465++) {
                                                    zerous8465 = 0;
                                                }
                                                for (int i9611 = 1; i9611 < Ni9611; i9611++) {
                                                    if (flagus8465) {
                                                        break;
                                                    }
                                                    if (zerous8465 == 0) {
                                                        zerolz1049 = 0;
                                                    } else {
                                                        zerolz1049 = 0;
                                                        int _unswitchMarkus8465 = 0;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    try { toSinksnk2572 = 23 * (ysnk2572 - 1); } catch (ArithmeticException ex) { toSinksnk2572 ^= ex.hashCode(); };
                                }
                                int _sinkUsesnk2572 = toSinksnk2572;
                            }
                        if (zerolz1049 == 0) {
                            f1 = new int[8];
                        } else {
                                int M9674 = 4;
                                int N9674 = 8;
                                for (int i9674 = 0; i9674 < M9674; i9674++) {
                                    {
                                        boolean flagus1848 = _mutatorFlip();
                                        int limitus1848 = 2;
                                        for (; limitus1848 < 4; limitus1848 *= 2) { }
                                        int zerous1848 = 34;
                                        for (int peelus1848 = 2; peelus1848 < limitus1848; peelus1848++) { zerous1848 = 0; }
                                            {
                                                boolean flagus6776 = _mutatorFlip();
                                                int limitus6776 = 2;
                                                for (; limitus6776 < 4; limitus6776 *= 2) { };
                                                int zerous6776 = 34;
                                                for (int peelus6776 = 2; peelus6776 < limitus6776; peelus6776++) { zerous6776 = 0; };
                                                for (int j9674 = 0; j9674 < N9674; j9674++) {
                                                    if (flagus6776)
                                                        break;

                                                    if (zerous6776 == 0) {
                                                        if (flagus1848) {
                                                            break;
                                                        }
                                                        if (zerous1848 == 0) {
                                                            switch (i9674) {
                                                                case -1, -2, -3 :
                                                                    break;
                                                                case 0 :
                                                                    f1 = new int[8];
                                                            }
                                                        } else {
                                                            switch (i9674) {
                                                                case -1, -2, -3 :
                                                                    break;
                                                                case 0 :
                                                                    f1 = new int[8];
                                                            }
                                                            int _unswitchMarkus1848 = 0;
                                                        }
                                                    } else {
                                                        if (flagus1848) {
                                                            break;
                                                        }
                                                        if (zerous1848 == 0) {
                                                            switch (i9674) {
                                                                case -1, -2, -3 :
                                                                    break;
                                                                case 0 :
                                                                    f1 = new int[8];
                                                            }
                                                        } else {
                                                            switch (i9674) {
                                                                case -1, -2, -3 :
                                                                    break;
                                                                case 0 :
                                                                    f1 = new int[8];
                                                            }
                                                            int _unswitchMarkus1848 = 0;
                                                        }
                                                        int _unswitchMarkus6776 = 0;;
                                                    }
                                                }
                                            }
                                    }
                                }
                                f1 = new int[8];
                        }
                    }
                }
            }
            final int Ni3094 = 32;
            for (int i3094 = 1; foo17718003525629962(i3094, Ni3094); i3094++) {
                    int M3716 = 4;
                    int N3716 = 8;
                    for (int i3716 = 0; i3716 < M3716; i3716++) {
                        for (int j3716 = 0; j3716 < N3716; j3716++) {
                            switch (i3716) {
                                case -1, -2, -3 :
                                    break;
                                case 0 :
                                    f1 = new int[8];
                            }
                        }
                    }
                    {
                        int limitlz7442 = 2;
                        for (; limitlz7442 < 4; limitlz7442 *= 2) { };
                        int zerolz7442 = 34;
                        for (int peellz7442 = 2; peellz7442 < limitlz7442; peellz7442++) { zerolz7442 = 0; };
                        if (zerolz7442 == 0) {
                            f1 = new int[8];
                        } else {
                            f1 = new int[8];
                        }
                    }
                    synchronized(c2fuzz000000198754.class) {
                        int Ni2783 = 32;
                        {
                            int ysnk3948 = 0;
                            int toSinksnk3948 = 0;
                            for (int i2783 = 0; i2783 < Ni2783; i2783++) {
                                ysnk3948++;
                                if (i2783 < 10) {
                                    f1 = new int[8];
                                    f1 = new int[8];
                                }
                                try {
                                    toSinksnk3948 = 23 * (ysnk3948 - 1);
                                } catch (ArithmeticException ex) {
                                    toSinksnk3948 ^= ex.hashCode();
                                }
                            }
                            int _sinkUsesnk3948 = toSinksnk3948;
                        }
                    }
                    synchronized(c2fuzz000000198754.class) {
                        {
                            int limitlz1637 = 2;
                            for (; limitlz1637 < 4; limitlz1637 *= 2) { };
                            int zerolz1637 = 34;
                            for (int peellz1637 = 2; peellz1637 < limitlz1637; peellz1637++) { zerolz1637 = 0; };
                            if (zerolz1637 == 0) {
                                f1 = new int[8];
                            } else {
                                f1 = new int[8];
                            }
                        }
                    }
            }
        }
    }

    public static void main(String[] args) {
        for (int i=0; i<15000; i++) {
            test();
        }
    }

    private static boolean foo17718003525629962(int p0, int p1) {
        return foo17719779650652106(p0, p1);
    }

    private static volatile boolean _mutatorToggle = false;

    private static boolean _mutatorFlip() {
        synchronized(c2fuzz000000198754.class) {
            _mutatorToggle = new MyBoolean(!c2fuzz000000198754._mutatorToggle).v();
            return _mutatorToggle;
        }
    }

    private static boolean foo17719779650652106(int p0, int p1) {
        return p0 < p1;
    }

    static class MyBoolean {
        public boolean v;

        public MyBoolean(boolean v) {
            int Ni1884 = 32;
            for (int i1884 = 0; i1884 < Ni1884; i1884++) {
                if (i1884 < 10)
                    this.v = v;

            }
            {
                int limitlz3326 = 2;
                for (; limitlz3326 < 4; limitlz3326 *= 2) { };
                int zerolz3326 = 34;
                for (int peellz3326 = 2; peellz3326 < limitlz3326; peellz3326++) { zerolz3326 = 0; };
                if (zerolz3326 == 0) {
                    this.v = v;
                } else {
                    this.v = v;
                }
            }
        }

        public boolean v() {
            return v;
        }
    }
}
