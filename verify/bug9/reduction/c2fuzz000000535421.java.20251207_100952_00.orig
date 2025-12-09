/*
 * Copyright (c) 2022, Red Hat, Inc. All rights reserved.
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
 * bug 8288184
 * @summary C2 compilation asserts with "Bad graph detected in compute_lca_of_uses"
 * @run main/othervm -XX:-BackgroundCompilation c2fuzz000000535421
 */

public class c2fuzz000000535421 {
    long instanceCount;
    int iFld;

    void vMeth(int i, long l) {int i1;
        int i19 = -845;
        for (i1 = 5; i1 > 1; i1 -= 2)
            try {
                int ax$0 = i19;
                try {
                    for (Object temp = new byte[i19]; ; i19 = "1".equals("0") ? 2 : 1) {}
                } finally {
                    {
                        {
                            {
                                int limitlz6086 = 2;
                                for (; limitlz6086 < 4; limitlz6086 *= 2) { };
                                int zerolz6086 = 34;
                                for (int peellz6086 = 2; peellz6086 < limitlz6086; peellz6086++) { zerolz6086 = 0; };
                                if (zerolz6086 == 0) {
                                    i19 = ax$0;
                                } else {
                                    i19 = ax$0;
                                }
                            }
                            final int Ni8663 = 32;
                            for (int i8663 = 1; i8663 < Ni8663; i8663++) {
                                i19 = ax$0;
                            }
                        }
                        final int Ni7801 = 32;
                            {
                                boolean flagus2549 = _mutatorFlip();
                                int limitus2549 = 2;
                                for (; limitus2549 < 4; limitus2549 *= 2) { }
                                int zerous2549 = 34;
                                {
                                    boolean flagus8038 = _mutatorFlip();
                                    int limitus8038 = 2;
                                    for (; limitus8038 < 4; limitus8038 *= 2) { };
                                    int zerous8038 = 34;
                                    for (int peelus8038 = 2; peelus8038 < limitus8038; peelus8038++) { zerous8038 = 0; };
                                    for (int peelus2549 = 2; peelus2549 < limitus2549; peelus2549++) {
                                        if (flagus8038)
                                            break;

                                        if (zerous8038 == 0) {
                                            zerous2549 = 0;
                                        } else {
                                            zerous2549 = 0;
                                            int _unswitchMarkus8038 = 0;;
                                        }
                                    }
                                }
                                for (int i7801 = 1; i7801 < Ni7801; i7801++) {
                                    if (flagus2549)
                                        break;

                                    if (zerous2549 == 0) {
                                        i19 = ax$0;
                                    } else {
                                        i19 = ax$0;
                                        int _unswitchMarkus2549 = 0;;
                                    }
                                }
                            }
                    }
                }
            } catch (Throwable ax$3) {
            }
    }

    void mainTest(String[] strArr1) {
        vMeth(iFld, instanceCount);
    }

    public static void main(String[] strArr) {
        c2fuzz000000535421 _instance = new c2fuzz000000535421();
        for (int i = 0; i < 10_000; ++i) _instance.mainTest(strArr);
    }

        private static volatile boolean _mutatorToggle = false;

        private static boolean _mutatorFlip() {
            _mutatorToggle = !_mutatorToggle;;
            return _mutatorToggle;
        }
}