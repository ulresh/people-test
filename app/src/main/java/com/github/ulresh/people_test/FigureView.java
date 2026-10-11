package com.github.ulresh.people_test;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Схематичные фигурки людей: по одной на человека, на постоянных местах. */
final class FigureView extends View {
    private static final int MIN_CELLS = 3;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Snapshot snap;

    FigureView(Context context) {
        super(context);
        setBackgroundColor(0xFF1B2327);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setTextSize(14 * getResources().getDisplayMetrics().density);
    }

    void show(Snapshot s) {
        snap = s;
        invalidate();
    }

    void clear() {
        snap = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (snap == null) return;
        int cells = MIN_CELLS;
        for (Snapshot.TrackInfo t : snap.tracks) cells = Math.max(cells, t.slot + 1);
        float cellW = (float) getWidth() / cells;
        float scale = Math.min(cellW / (2 * FigurePose.EXTENT_X), getHeight() / (2 * FigurePose.EXTENT_Y));
        for (Snapshot.TrackInfo t : snap.tracks) {
            if (t.slot < 0 || t.pose == null) continue;
            float cx = cellW * (t.slot + 0.5f);
            float cy = getHeight() / 2f;
            int color = Palette.color(t.slot);
            drawFigure(c, t.pose, cx, cy, scale, color, t.missed > 0 ? 100 : 255);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setAlpha(255);
            c.drawText(String.valueOf(t.id), cellW * t.slot + 4, paint.getTextSize() + 2, paint);
        }
    }

    private static int lighter(int color) {
        int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
        return 0xFF000000 | ((r + (255 - r) / 2) << 16) | ((g + (255 - g) / 2) << 8) | (b + (255 - b) / 2);
    }

    private void seg(Canvas c, FigurePose f, int a, int b, float cx, float cy, float s, int color, int alpha, float w) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(w * s);
        paint.setColor(color);
        paint.setAlpha(alpha);
        c.drawLine(cx + f.x(a) * s, cy + f.y(a) * s, cx + f.x(b) * s, cy + f.y(b) * s, paint);
    }

    private void drawFigure(Canvas c, FigurePose f, float cx, float cy, float s, int color, int alpha) {
        int left = lighter(color);
        // ноги и руки: левая сторона светлее, чтобы различать стороны
        seg(c, f, FigurePose.PELVIS, FigurePose.L_HIP, cx, cy, s, left, alpha, 0.09f);
        seg(c, f, FigurePose.PELVIS, FigurePose.R_HIP, cx, cy, s, color, alpha, 0.09f);
        seg(c, f, FigurePose.L_HIP, FigurePose.L_KNE, cx, cy, s, left, alpha, 0.1f);
        seg(c, f, FigurePose.L_KNE, FigurePose.L_ANK, cx, cy, s, left, alpha, 0.09f);
        seg(c, f, FigurePose.L_ANK, FigurePose.L_FOOT, cx, cy, s, left, alpha, 0.07f);
        seg(c, f, FigurePose.R_HIP, FigurePose.R_KNE, cx, cy, s, color, alpha, 0.1f);
        seg(c, f, FigurePose.R_KNE, FigurePose.R_ANK, cx, cy, s, color, alpha, 0.09f);
        seg(c, f, FigurePose.R_ANK, FigurePose.R_FOOT, cx, cy, s, color, alpha, 0.07f);
        seg(c, f, FigurePose.PELVIS, FigurePose.NECK, cx, cy, s, color, alpha, 0.2f);
        seg(c, f, FigurePose.NECK, FigurePose.L_SHO, cx, cy, s, left, alpha, 0.1f);
        seg(c, f, FigurePose.NECK, FigurePose.R_SHO, cx, cy, s, color, alpha, 0.1f);
        seg(c, f, FigurePose.L_SHO, FigurePose.L_ELB, cx, cy, s, left, alpha, 0.09f);
        seg(c, f, FigurePose.L_ELB, FigurePose.L_WRI, cx, cy, s, left, alpha, 0.08f);
        seg(c, f, FigurePose.L_WRI, FigurePose.L_HAND, cx, cy, s, left, alpha, 0.06f);
        seg(c, f, FigurePose.R_SHO, FigurePose.R_ELB, cx, cy, s, color, alpha, 0.09f);
        seg(c, f, FigurePose.R_ELB, FigurePose.R_WRI, cx, cy, s, color, alpha, 0.08f);
        seg(c, f, FigurePose.R_WRI, FigurePose.R_HAND, cx, cy, s, color, alpha, 0.06f);
        drawHead(c, f, cx, cy, s, alpha);
    }

    private void drawHead(Canvas c, FigurePose f, float cx, float cy, float s, int alpha) {
        float hx = cx + f.x(FigurePose.HEAD) * s, hy = cy + f.y(FigurePose.HEAD) * s;
        float r = FigurePose.HEAD_R * s;
        // шея
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.08f * s);
        paint.setColor(0xFFFFCC80);
        paint.setAlpha(alpha);
        c.drawLine(cx + f.x(FigurePose.NECK) * s, cy + f.y(FigurePose.NECK) * s, hx, hy, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(f.back ? 0xFF8D6E63 : 0xFFFFE0B2);
        paint.setAlpha(alpha);
        c.drawCircle(hx, hy, r, paint);
        if (f.back) return;

        float rx = f.rightX, ry = f.rightY;
        float dx = -f.upX, dy = -f.upY; // «вниз» головы
        double ya = f.yaw * Math.toRadians(65);
        paint.setColor(0xFF263238);
        paint.setAlpha(alpha);
        // глаза
        for (int side = -1; side <= 1; side += 2) {
            double ang = Math.toRadians(28) * side + ya;
            float cos = (float) Math.cos(ang);
            if (cos < 0.15f) continue;
            float a = 0.9f * (float) Math.sin(ang);
            float b = -0.2f + f.pitch * 0.25f;
            float er = 0.13f * r * (0.5f + 0.5f * cos);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(hx + (rx * a + dx * b) * r, hy + (ry * a + dy * b) * r, er * 1.6f, paint);
        }
        // нос: от переносицы к кончику, кончик выдаётся в сторону поворота
        float sin = (float) Math.sin(ya);
        float cos = (float) Math.cos(ya);
        if (cos > 0.15f) {
            float a = 0.9f * sin;
            float b = 0.08f + f.pitch * 0.25f;
            float tip = a + 0.3f * sin;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.035f * s);
            c.drawLine(hx + (rx * a + dx * (b - 0.15f)) * r, hy + (ry * a + dy * (b - 0.15f)) * r,
                    hx + (rx * tip + dx * (b + 0.12f)) * r, hy + (ry * tip + dy * (b + 0.12f)) * r, paint);
            // рот
            float m = 0.9f * sin;
            float mb = 0.5f + f.pitch * 0.2f;
            float hw = 0.32f * cos;
            c.drawLine(hx + (rx * (m - hw) + dx * mb) * r, hy + (ry * (m - hw) + dy * mb) * r,
                    hx + (rx * (m + hw) + dx * mb) * r, hy + (ry * (m + hw) + dy * mb) * r, paint);
        }
    }
}
