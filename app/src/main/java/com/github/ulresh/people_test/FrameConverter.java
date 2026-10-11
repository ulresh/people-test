package com.github.ulresh.people_test;

import android.media.Image;

import java.nio.ByteBuffer;

/** YUV_420_888 → ARGB с поворотом и уменьшением. */
final class FrameConverter {
    private FrameConverter() {
    }

    /**
     * @param rotation поворот по часовой стрелке (0, 90, 180, 270), который нужно применить к кадру
     * @param dw       ширина результата
     * @param dh       высота результата (пропорции — как у повёрнутого кадра)
     */
    static void convert(Image img, int rotation, int[] dst, int dw, int dh) {
        Image.Plane[] pl = img.getPlanes();
        ByteBuffer yb = pl[0].getBuffer(), ub = pl[1].getBuffer(), vb = pl[2].getBuffer();
        int ys = pl[0].getRowStride();
        int us = pl[1].getRowStride(), up = pl[1].getPixelStride();
        int vs = pl[2].getRowStride(), vp = pl[2].getPixelStride();
        int sw = img.getWidth(), sh = img.getHeight();
        boolean swap = rotation == 90 || rotation == 270;
        int rw = swap ? sh : sw;
        int rh = swap ? sw : sh;
        for (int oy = 0; oy < dh; oy++) {
            int ry = (int) ((long) oy * rh / dh);
            for (int ox = 0; ox < dw; ox++) {
                int rx = (int) ((long) ox * rw / dw);
                int sx, sy;
                switch (rotation) {
                    case 90:
                        sx = ry;
                        sy = sh - 1 - rx;
                        break;
                    case 180:
                        sx = sw - 1 - rx;
                        sy = sh - 1 - ry;
                        break;
                    case 270:
                        sx = sw - 1 - ry;
                        sy = rx;
                        break;
                    default:
                        sx = rx;
                        sy = ry;
                }
                int y = (yb.get(sy * ys + sx) & 255) - 16;
                int u = (ub.get((sy >> 1) * us + (sx >> 1) * up) & 255) - 128;
                int v = (vb.get((sy >> 1) * vs + (sx >> 1) * vp) & 255) - 128;
                if (y < 0) y = 0;
                int c = 298 * y;
                int r = (c + 409 * v + 128) >> 8;
                int g = (c - 100 * u - 208 * v + 128) >> 8;
                int b = (c + 516 * u + 128) >> 8;
                r = r < 0 ? 0 : Math.min(r, 255);
                g = g < 0 ? 0 : Math.min(g, 255);
                b = b < 0 ? 0 : Math.min(b, 255);
                dst[oy * dw + ox] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }
}
