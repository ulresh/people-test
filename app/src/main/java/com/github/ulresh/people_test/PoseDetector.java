package com.github.ulresh.people_test;

import android.content.Context;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** MoveNet MultiPose Lightning (TFLite): до 6 человек, 17 ключевых точек у каждого. */
final class PoseDetector {
    private static final String MODEL = "movenet_multipose_lightning.tflite";
    private static final int MAX_PEOPLE = 6;
    private static final int ROW = 56;
    private static final float MIN_PERSON_SCORE = 0.25f;
    /** Длинная сторона входа сети; размеры должны быть кратны 32. */
    private static final int LONG_SIDE = 256;

    private final Interpreter interpreter;
    private final DataType inType;
    private final float[][][] out = new float[1][MAX_PEOPLE][ROW];
    private int inW, inH;
    private ByteBuffer in;

    PoseDetector(Context context) throws IOException {
        ByteBuffer buf = ByteBuffer.allocateDirect(16 << 20).order(ByteOrder.nativeOrder());
        try (InputStream is = context.getAssets().open(MODEL)) {
            byte[] chunk = new byte[64 << 10];
            for (int n; (n = is.read(chunk)) > 0; ) buf.put(chunk, 0, n);
        }
        buf.flip();
        Interpreter.Options o = new Interpreter.Options();
        o.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
        interpreter = new Interpreter(buf, o);
        inType = interpreter.getInputTensor(0).dataType();
    }

    /** Размер входа сети для кадра заданных пропорций. */
    static int[] inputSize(int frameW, int frameH) {
        float k = (float) LONG_SIDE / Math.max(frameW, frameH);
        int w = Math.max(32, Math.round(frameW * k / 32f) * 32);
        int h = Math.max(32, Math.round(frameH * k / 32f) * 32);
        return new int[]{w, h};
    }

    /** argb — кадр размером fw×fh; он сжимается до входа сети. */
    List<Person> detect(int[] argb, int fw, int fh) {
        int[] sz = inputSize(fw, fh);
        if (in == null || sz[0] != inW || sz[1] != inH) {
            inW = sz[0];
            inH = sz[1];
            interpreter.resizeInput(0, new int[]{1, inH, inW, 3});
            interpreter.allocateTensors();
            int bytes = inType == DataType.UINT8 ? 1 : 4;
            in = ByteBuffer.allocateDirect(inW * inH * 3 * bytes).order(ByteOrder.nativeOrder());
        }
        in.rewind();
        for (int j = 0; j < inH; j++) {
            int row = (int) ((long) j * fh / inH) * fw;
            for (int i = 0; i < inW; i++) {
                int p = argb[row + (int) ((long) i * fw / inW)];
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                if (inType == DataType.UINT8) {
                    in.put((byte) r).put((byte) g).put((byte) b);
                } else if (inType == DataType.FLOAT32) {
                    in.putFloat(r).putFloat(g).putFloat(b);
                } else {
                    in.putInt(r).putInt(g).putInt(b);
                }
            }
        }
        in.rewind();
        interpreter.run(in, out);

        List<Person> res = new ArrayList<>();
        for (int i = 0; i < MAX_PEOPLE; i++) {
            float[] r = out[0][i];
            if (r[55] < MIN_PERSON_SCORE) continue;
            Person p = new Person();
            for (int k = 0; k < Person.N; k++) {
                p.y[k] = r[3 * k];
                p.x[k] = r[3 * k + 1];
                p.s[k] = r[3 * k + 2];
            }
            p.top = r[51];
            p.left = r[52];
            p.bottom = r[53];
            p.right = r[54];
            p.score = r[55];
            fixBox(p);
            res.add(p);
        }
        return res;
    }

    /** Если рамка у сети пустая — берём рамку по уверенным ключевым точкам. */
    private static void fixBox(Person p) {
        if (p.right - p.left > 0.01f && p.bottom - p.top > 0.01f) return;
        float l = 1, t = 1, r = 0, b = 0;
        for (int k = 0; k < Person.N; k++) {
            if (!p.has(k)) continue;
            l = Math.min(l, p.x[k]);
            r = Math.max(r, p.x[k]);
            t = Math.min(t, p.y[k]);
            b = Math.max(b, p.y[k]);
        }
        p.left = l;
        p.right = Math.max(r, l + 0.01f);
        p.top = t;
        p.bottom = Math.max(b, t + 0.01f);
    }

    void close() {
        interpreter.close();
    }
}
