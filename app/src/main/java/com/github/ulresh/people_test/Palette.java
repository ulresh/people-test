package com.github.ulresh.people_test;

/** Цвета ячеек: по одному цвету на человека — и на кадре, и у его фигурки. */
final class Palette {
    private static final int[] COLORS = {
            0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA, 0xFF00ACC1,
    };

    private Palette() {
    }

    static int color(int slot) {
        return COLORS[Math.floorMod(slot, COLORS.length)];
    }
}
