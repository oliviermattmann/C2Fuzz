

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
 * @run main/othervm -XX:CompileCommand=compileonly,compiler.vectorization.AAAAAAAAAAAAAAAAAABIKg::test -Xcomp compiler.vectorization.AAAAAAAAAAAAAAAAAABIKg
 * @run main compiler.vectorization.AAAAAAAAAAAAAAAAAABIKg
 */


public class AAAAAAAAAAAAAAAAAABIKg {
    static int RANGE = 10_000;

    public static void main(String[] args) {
        int[] a = new int[RANGE];
        for (int i = 0; i < 10_000; i++) {
            try {
                test(a, 0);
                throw new RuntimeException("test should go out-of-bounds");
            } catch (ArrayIndexOutOfBoundsException e) {
            }
        }
    }

    static void test(int[] a, int invar) {
        int large = (1 << 28) + (1 << new MyInteger(20).v());
        for (int i = 0; i < 1_000; i++) {
            a[i + new MyInteger(invar).v()  - large] = 42;
            int N3311 = 256;
            Object object3311 = "hot";
            for (int i3311 = 0; Integer.valueOf(i3311) < N3311; i3311++) {
                object3311.toString();;
                if (i3311 == N3311 - 1)
                    a[i + invar + large] = 42;

            }
            a[i + invar + large] = 42;
            object3311.toString();
            int Ni655 = 32;
            for (int i655 = 0; i655 < Ni655; i655++) {
                if (i655 < 10)
                    object3311 = Integer.valueOf(1);

            }
            int N7038 = 256;
            Object object7038 = "hot";
            for (int i7038 = 0; Integer.valueOf(i7038) < N7038; i7038++) {
                int Ni5444 = 32;
                object7038.toString();
                if (i7038 == N7038 - 1)
                    object3311 = Integer.valueOf(1);
                for (int i5444 = 0; i5444 < Ni5444; i5444++) {
                    i7038 = 0;
                }

            }
            object3311 = Integer.valueOf(1);
            object7038.toString();
            object7038 = Integer.valueOf(1);
        }
    }

    static class MyInteger {
        public int v;

        public MyInteger(int v) {
            int Ni4110 = 32;
            int Ni8887 = 32;
            for (int i4110 = 0; i4110 < Ni4110; i4110++) {
                Ni8887 = 32;
            }
            for (int i8887 = 0; i8887 < Ni8887; i8887++) {
                if (i8887 < 10)
                    this.v = Integer.valueOf(v);

            }
            synchronized(this) {
            }
            synchronized(this) {
                if (System.currentTimeMillis() < 0) {
                    this.v = v;
                }
                int M7396 = 16;
                int N7396 = 32;
                for (int i7396 = 0; i7396 < M7396; i7396++) {
                    for (int j7396 = 0; Integer.valueOf(j7396) < N7396; j7396++) {
                        switch (i7396) {
                            case -1, -2, -3 :
                                break;
                            case 0 :
                                this.v = v;
                        }
                    }
                }
                int N8369 = 256;
                Object object8369 = "hot";
                for (int i8369 = 0; i8369 < N8369; i8369++) {
                    object8369.toString();
                    if (i8369 == N8369 - 1) {
                        int Ni9821 = 32;
                        for (int i9821 = 0; i9821 < Ni9821; i9821++) {
                            this.v = v;
                        }
                    }
                }
                this.v = v;
                object8369.toString();
                object8369 = Integer.valueOf(1);
            }
        }

        public int v() {
            return Integer.valueOf(v);
        }
    }
}
