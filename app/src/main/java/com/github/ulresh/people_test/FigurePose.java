package com.github.ulresh.people_test;

/**
 * Геометрия фигурки в её собственных координатах: начало — центр таза, ось Y вниз,
 * длина корпуса — 1. Все фигурки строятся из одних и тех же длин сегментов.
 */
final class FigurePose {
    // индексы суставов в массиве pt (по две координаты)
    static final int NECK = 0, L_SHO = 1, R_SHO = 2, L_ELB = 3, R_ELB = 4, L_WRI = 5, R_WRI = 6,
            L_HAND = 7, R_HAND = 8, PELVIS = 9, L_HIP = 10, R_HIP = 11, L_KNE = 12, R_KNE = 13,
            L_ANK = 14, R_ANK = 15, L_FOOT = 16, R_FOOT = 17, HEAD = 18, COUNT = 19;

    static final float HEAD_R = 0.28f;
    /** Половина ширины и высоты области, которую занимает фигурка. */
    static final float EXTENT_X = 1.9f, EXTENT_Y = 1.95f;

    final float[] pt = new float[COUNT * 2];
    /** Единичный вектор «вверх» головы и вектор «вправо» (в координатах фигурки). */
    float upX, upY, rightX, rightY;
    /** Поворот головы: −1 (налево) … 1 (направо) и наклон вверх/вниз −1 … 1. */
    float yaw, pitch;
    /** Человек повёрнут к камере спиной (лица не видно). */
    boolean back;

    float x(int j) {
        return pt[2 * j];
    }

    float y(int j) {
        return pt[2 * j + 1];
    }

    void set(int j, float x, float y) {
        pt[2 * j] = x;
        pt[2 * j + 1] = y;
    }
}
