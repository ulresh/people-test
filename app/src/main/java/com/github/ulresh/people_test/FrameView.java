package com.github.ulresh.people_test;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Кадр, который сейчас в обработке, с рамками, траекториями и скелетами людей. */
final class FrameView extends View {
    private static final int[][] BONES = {
            {Person.L_SHO, Person.R_SHO}, {Person.L_SHO, Person.L_ELB}, {Person.L_ELB, Person.L_WRI},
            {Person.R_SHO, Person.R_ELB}, {Person.R_ELB, Person.R_WRI}, {Person.L_SHO, Person.L_HIP},
            {Person.R_SHO, Person.R_HIP}, {Person.L_HIP, Person.R_HIP}, {Person.L_HIP, Person.L_KNE},
            {Person.L_KNE, Person.L_ANK}, {Person.R_HIP, Person.R_KNE}, {Person.R_KNE, Person.R_ANK},
            {Person.NOSE, Person.L_EYE}, {Person.NOSE, Person.R_EYE},
    };

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private Snapshot snap;
    private boolean overlay = true;

    FrameView(Context context) {
        super(context);
        setBackgroundColor(0xFF000000);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setTextSize(14 * getResources().getDisplayMetrics().density);
    }

    void setOverlay(boolean on) {
        overlay = on;
        invalidate();
    }

    void show(Snapshot s) {
        Bitmap old = snap == null ? null : snap.bitmap;
        snap = s;
        if (old != null && old != s.bitmap) old.recycle();
        invalidate();
    }

    void clear() {
        Bitmap old = snap == null ? null : snap.bitmap;
        snap = null;
        if (old != null) old.recycle();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (snap == null) return;
        Bitmap b = snap.bitmap;
        float k = Math.min((float) getWidth() / b.getWidth(), (float) getHeight() / b.getHeight());
        float w = b.getWidth() * k, h = b.getHeight() * k;
        float ox = (getWidth() - w) / 2, oy = (getHeight() - h) / 2;
        dst.set(ox, oy, ox + w, oy + h);
        paint.setStyle(Paint.Style.FILL);
        c.drawBitmap(b, null, dst, paint);
        if (!overlay) return;
        float line = getResources().getDisplayMetrics().density * 2;
        paint.setStrokeWidth(line);
        for (Snapshot.TrackInfo t : snap.tracks) {
            if (t.slot < 0) continue;
            int color = Palette.color(t.slot);
            paint.setColor(color);
            paint.setAlpha(t.missed > 0 ? 110 : 255);
            Person p = t.person;
            paint.setStyle(Paint.Style.STROKE);
            if (t.missed == 0) {
                c.drawRect(ox + p.left * w, oy + p.top * h, ox + p.right * w, oy + p.bottom * h, paint);
                for (int[] bone : BONES) {
                    if (p.has(bone[0]) && p.has(bone[1])) {
                        c.drawLine(ox + p.x[bone[0]] * w, oy + p.y[bone[0]] * h,
                                ox + p.x[bone[1]] * w, oy + p.y[bone[1]] * h, paint);
                    }
                }
            }
            float[] tr = t.trail;
            for (int i = 2; i + 1 < tr.length; i += 2) {
                c.drawLine(ox + tr[i - 2] * w, oy + tr[i - 1] * h, ox + tr[i] * w, oy + tr[i + 1] * h, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            c.drawText(String.valueOf(t.id), ox + p.left * w + line, oy + p.top * h + paint.getTextSize(), paint);
        }
        paint.setAlpha(255);
    }
}
