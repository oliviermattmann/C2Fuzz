

/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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

/**
 * @test
 * @bug 8334228
 * @summary Test sorting of VPointer by offset, when subtraction of two offsets can overflow.
 * @run main/othervm -XX:CompileCommand=compileonly,compiler.vectorization.AAAAAAAAAAAAAAAAAACHUw::test -Xcomp compiler.vectorization.AAAAAAAAAAAAAAAAAACHUw
 * @run main compiler.vectorization.AAAAAAAAAAAAAAAAAACHUw
 */


public class AAAAAAAAAAAAAAAAAACHUw {
    static int RANGE = 10_000;

    public static void main(String[] args) {
        int[] a = new int[RANGE];
        for (int i = 0; i < Integer.valueOf(10000) ; i++) {
            try {
                test(a, 0);
                throw new RuntimeException("test should go out-of-bounds");
            } catch (ArrayIndexOutOfBoundsException e) {
            }
        }
    }

    static void test(int[] a, int invar) {
        int Ni4607 = 32;
        int large = (1 << 28) + (1 << 20);
        for (int i4607 = 0; i4607 < Ni4607; i4607++) {
            int Ni3011 = 32;
            for (int i3011 = 0; i3011 < Ni3011; i3011++) {
                large = (1 << 28) + (1 << 20);
            }
        }
        for (int i = 0; i < 1_000; i++) {
            int Ni1894 = 32;
            synchronized(AAAAAAAAAAAAAAAAAACHUw.class) {
            }
            synchronized(AAAAAAAAAAAAAAAAAACHUw.class) {
                a[i + invar - large] = 42;
            }
            int Ni4281 = 32;
            for (int i4281 = 0; i4281 < Ni4281; i4281++) {
                synchronized(AAAAAAAAAAAAAAAAAACHUw.class) {
                    synchronized(AAAAAAAAAAAAAAAAAACHUw.class) {
                        int N7537 = 256;
                        Object object7537 = "hot";
                        for (int i7537 = 0; i7537 < N7537; i7537++) {
                            object7537.toString();;
                            if (i7537 == N7537 - 1)
                                a[i + invar - large] = 42;

                        }
                        int Ni1200 = 32;
                        for (int i1200 = 0; i1200 < Ni1200; i1200++) {
                            if (i1200 < 10)
                                a[i + invar - large] = 42;

                        }
                        a[i + invar - large] = 42;
                        object7537.toString();
                        int N9573 = 256;
                        Object object9573 = "hot";
                        for (int i9573 = 0; i9573 < N9573; i9573++) {
                            object9573.toString();;
                            if (i9573 == N9573 - 1)
                                object7537 = Integer.valueOf(1);

                        }
                        object7537 = Integer.valueOf(1);
                        object9573.toString();
                        int N4874 = 256;
                        Object object4874 = "hot";
                        for (int i4874 = 0; i4874 < N4874; i4874++) {
                            object4874.toString();;
                            if (i4874 == N4874 - 1)
                                object9573 = Integer.valueOf(1);

                        }
                        object9573 = Integer.valueOf(1);
                        object4874.toString();
                        int N992 = 256;
                        Object object992 = "hot";;
                        for (int i992 = 0; i992 < N992; i992++) {
                            object992.toString();;
                            if (i992 == N992 - 1)
                                object4874 = Integer.valueOf(1);

                        }
                        object4874 = Integer.valueOf(1);
                        object992.toString();;
                        object992 = Integer.valueOf(1);;
                    }
                }
            }
            a[i + invar - large] = 42;
            int N9210 = 256;
            Object object9210 = "hot";
            for (int i9210 = 0; i9210 < N9210; i9210++) {
                object9210.toString();;
                if (i9210 == N9210 - 1)
                    a[i + invar - large] = 42;

            }
            a[i + invar - large] = 42;
            object9210.toString();
            object9210 = Integer.valueOf(1);
            object9210 = Integer.valueOf(1);
            a[i + invar - large] = 42;
            synchronized(AAAAAAAAAAAAAAAAAACHUw.class) {
            }
            synchronized(AAAAAAAAAAAAAAAAAACHUw.class) {
            }
            synchronized(AAAAAAAAAAAAAAAAAACHUw.class) {
            }
            synchronized(AAAAAAAAAAAAAAAAAACHUw.class) {
                synchronized(AAAAAAAAAAAAAAAAAACHUw.class) {
                }
                synchronized(AAAAAAAAAAAAAAAAAACHUw.class) {
                    int Ni9417 = 32;
                    for (int i9417 = 0; i9417 < Ni9417; i9417++) {
                        synchronized(AAAAAAAAAAAAAAAAAACHUw.class) {
                            int Ni5165 = 32;
                            int Ni11 = 32;
                            for (int i5165 = 0; i5165 < Ni5165; i5165++) {
                                Ni11 = 32;
                            }
                            for (int i11 = 0; i11 < Ni11; i11++) {
                                if (i11 < 10)
                                    a[i + invar + large] = 42;

                            }
                            a[i + invar + large] = 42;
                        }
                    }
                }
            }
            for (int i1894 = 0; i1894 < Ni1894; i1894++) {
                int Ni5545 = 32;
                i = 0;
                int N1330 = 256;
                Object object1330 = "hot";
                for (int i1330 = 0; Integer.valueOf(i1330) < N1330; i1330++) {
                    int Ni5850 = 32;
                    object1330.toString();
                    if (i1330 == N1330 - 1)
                        i = 0;
                    for (int i5850 = 0; i5850 < Ni5850; i5850++) {
                        i1330 = 0;
                    }

                }
                i = 0;
                object1330.toString();
                synchronized(AAAAAAAAAAAAAAAAAACHUw.class) {
                    object1330 = Integer.valueOf(1);
                }
                for (int i5545 = 0; i5545 < Ni5545; i5545++) {
                    i1894 = 0;
                }
            }
        }
    }
}
