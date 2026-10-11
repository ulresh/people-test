package com.github.ulresh.people_test;

/** Один найденный в кадре человек: 17 ключевых точек (формат COCO) и рамка. Координаты — доли кадра. */
final class Person {
    static final int NOSE = 0, L_EYE = 1, R_EYE = 2, L_EAR = 3, R_EAR = 4,
            L_SHO = 5, R_SHO = 6, L_ELB = 7, R_ELB = 8, L_WRI = 9, R_WRI = 10,
            L_HIP = 11, R_HIP = 12, L_KNE = 13, R_KNE = 14, L_ANK = 15, R_ANK = 16;
    static final int N = 17;
    static final float MIN_SCORE = 0.2f;

    final float[] x = new float[N];
    final float[] y = new float[N];
    final float[] s = new float[N];
    float left, top, right, bottom;
    float score;

    boolean has(int k) {
        return s[k] >= MIN_SCORE;
    }

    float centerX() {
        return (left + right) / 2f;
    }

    float centerY() {
        return (top + bottom) / 2f;
    }
}
