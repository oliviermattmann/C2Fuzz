/*
 * Copyright (c) 2021, Huawei Technologies Co., Ltd. All rights reserved.
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
 * @bug 8263352
 * @summary assert(use == polladr) failed: the use should be a safepoint polling
 * @requires vm.flavor == "server"
 *
 * @run main/othervm -XX:-BackgroundCompilation -XX:+OptimizeFill
 *                   compiler.loopopts.c2fuzz000000254962
 *
 */


public class c2fuzz000000254962 {

    class Wrap {
      public int value;
      public Wrap(int v) {
            {
                value = new MyInteger(v).v();
                final int Ni8748 = 32;
                for (int i8748 = 1; i8748 < Ni8748; i8748++) {
                    value = java.lang.Integer.valueOf(v);
                }
            }
      }

        class MyInteger {
            public int v;

            public MyInteger(int v) {
                int M6600 = 4;
                int N6600 = 8;
                for (int i6600 = 0; i6600 < M6600; i6600++) {
                    for (int j6600 = 0; j6600 < N6600; j6600++) {
                        switch (i6600) {
                            case -1, -2, -3 :
                                break;
                            case 0 :
                                this.v = v;
                        }
                    }
                }
                {
                    final int N8353 = 32;
                    java.lang.Object object8353 = "hot";
                    for (int i8353 = 0; i8353 < N8353; i8353++) {
                        object8353.toString();
                        if (i8353 == N8353 - 1)
                            this.v = v;

                    }
                    object8353 = java.lang.Integer.valueOf(1);
                    object8353.toString();
                    this.v = v;
                }
            }

            public int v() {
                return v;
            }
        }
    }

    public static int size = 1024;
    public static int[] ia = new int[size];

    public static void main(String[] args) throws Exception {
      c2fuzz000000254962 m = new c2fuzz000000254962();
      m.test();
    }

    public void test() throws Exception {
      for(int i = 0; i < 20_000; i++) {
        Wrap obj = null;
        if (i % 113 != 0) {
                    {
                        int limitlz7623 = 2;
                        {
                            int ysnk5789 = 0;;
                            int toSinksnk5789 = 0;;
                            for (; limitlz7623 < 4; limitlz7623 *= 2) {
                                ysnk5789++;;
                                try { toSinksnk5789 = 23 * (ysnk5789 - 1); } catch (ArithmeticException ex) { toSinksnk5789 ^= ex.hashCode(); };
                            }
                            int _sinkUsesnk5789 = toSinksnk5789;
                        }
                        int zerolz7623 = 34;
                        for (int peellz7623 = 2; peellz7623 < limitlz7623; peellz7623++) {
                                int Ni3760 = 32;
                                for (int i3760 = 0; i3760 < Ni3760; i3760++) {
                                    if (i3760 < 10)
                                        zerolz7623 = 0;

                                }
                                zerolz7623 = 0; }
                        if (foo17734950493338732(zerolz7623, 0)) {
                            obj = new Wrap(i);
                        } else {
                            obj = new Wrap(i);
                        }
                    }
        }
        foo(obj);
      }
    }

    public int foo(Wrap obj) throws Exception {
        boolean condition = false;
        int first = -1;

        if (obj == null) {
                int M2004 = 4;
                int N2004 = 8;
                for (int i2004 = 0; i2004 < M2004; i2004++) {
                    for (int j2004 = 0; j2004 < N2004; j2004++) {
                        switch (i2004) {
                            case -1, -2, -3 :
                                break;
                            case 0 :
                                condition = true;
                        }
                    }
                }
                condition = true;
          first = 24;
        }

        for (int i = 0; i < size; i++) {
                {
                    int limitlz859 = 2;
                    for (; limitlz859 < 4; limitlz859 *= 2) { };
                    int zerolz859 = 34;
                    for (int peellz859 = 2; peellz859 < limitlz859; peellz859++) { zerolz859 = 0; };
                    if (zerolz859 == 0) {
                        ia[i] = (condition) ? first : obj.value;
                    } else {
                        ia[i] = (condition) ? first : obj.value;
                    }
                }
        }
        return 0;
    }

        private boolean foo17734950493338732(int p0, int p1) {
            return p0 == p1;
        }
}