package com.github.ulresh.people_test;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Камера → самый свежий кадр → поиск поз → треки → фигурки.
 * Пока обрабатывается один кадр, новые кадры с камеры заменяют друг друга, так что
 * по окончании обработки берётся актуальный кадр, а промежуточные теряются.
 */
final class Pipeline {
    private static final int DISPLAY_LONG_SIDE = 640;

    static final class Settings {
        String cameraId;
        Size size;
        Range<Integer> fps;
        /** Поворот по часовой стрелке, который нужно применить к кадру, чтобы он стал прямым. */
        int rotation;
    }

    interface Host {
        void onStatus(String text);

        void onFrame(Snapshot snapshot);

        /** Доля обработанных кадров (0–100, −1 — пока нет данных) и среднее время обработки. */
        void onStats(int percent, int avgMs);
    }

    private final Context context;
    private final Handler cameraHandler;
    private final Settings s;
    private final Host host;

    private final Object lock = new Object();
    private Image pending;
    private long total;
    private long processed;
    private volatile boolean stopped;
    private ImageReader reader;
    private CameraController camera;
    private Thread worker;
    private String errors = "";
    private boolean cameraReady;

    Pipeline(Context context, Handler cameraHandler, Settings settings, Host host) {
        this.context = context;
        this.cameraHandler = cameraHandler;
        this.s = settings;
        this.host = host;
    }

    void start() {
        reader = ImageReader.newInstance(s.size.getWidth(), s.size.getHeight(), ImageFormat.YUV_420_888, 3);
        reader.setOnImageAvailableListener(r -> {
            Image im;
            try {
                im = r.acquireNextImage();
            } catch (RuntimeException e) {
                return;
            }
            if (im == null) return;
            synchronized (lock) {
                if (stopped) {
                    im.close();
                    return;
                }
                total++;
                if (pending != null) pending.close(); // не успели обработать — кадр потерян
                pending = im;
                lock.notifyAll();
            }
        }, cameraHandler);

        worker = new Thread(this::run, "pose");
        worker.start();

        List<Surface> surfaces = new ArrayList<>();
        surfaces.add(reader.getSurface());
        camera = new CameraController(context, cameraHandler, new CameraController.Listener() {
            @Override
            public void onCameraStarted() {
                cameraReady = true;
                publish();
            }

            @Override
            public void onCameraError(String message) {
                addError(message);
            }
        });
        camera.open(s.cameraId, surfaces, s.fps);
        publish();
    }

    /** Останавливает камеру; рабочий поток освобождает остальное сам, когда закончит кадр. */
    void stop() {
        synchronized (lock) {
            stopped = true;
            lock.notifyAll();
        }
        if (camera != null) camera.close();
    }

    private void run() {
        PoseDetector detector;
        try {
            detector = new PoseDetector(context);
        } catch (Throwable e) {
            addError("модель: " + e);
            cleanup(null);
            return;
        }
        Tracker tracker = new Tracker();
        int[] argb = null;
        int aw = 0, ah = 0;
        long msSum = 0;
        int msCount = 0;
        long lastStats = 0;
        while (true) {
            Image img;
            synchronized (lock) {
                while (pending == null && !stopped) {
                    try {
                        lock.wait(500);
                    } catch (InterruptedException e) {
                        stopped = true;
                    }
                    if (System.nanoTime() - lastStats > 500_000_000L) break;
                }
                if (stopped) break;
                img = pending;
                pending = null;
            }
            long now = System.nanoTime();
            if (now - lastStats > 500_000_000L) {
                lastStats = now;
                long t, p;
                synchronized (lock) {
                    t = total;
                    p = processed;
                }
                host.onStats(t == 0 ? -1 : (int) (p * 100 / t), msCount == 0 ? 0 : (int) (msSum / msCount));
                if (msCount >= 20) {
                    msSum = 0;
                    msCount = 0;
                }
            }
            if (img == null) continue;
            try {
                boolean swap = s.rotation == 90 || s.rotation == 270;
                int rw = swap ? img.getHeight() : img.getWidth();
                int rh = swap ? img.getWidth() : img.getHeight();
                float k = Math.min(1f, (float) DISPLAY_LONG_SIDE / Math.max(rw, rh));
                int dw = Math.max(1, Math.round(rw * k)), dh = Math.max(1, Math.round(rh * k));
                if (argb == null || dw != aw || dh != ah) {
                    aw = dw;
                    ah = dh;
                    argb = new int[dw * dh];
                }
                long t0 = System.nanoTime();
                FrameConverter.convert(img, s.rotation, argb, dw, dh);
                img.close();
                img = null;
                List<Person> people = detector.detect(argb, dw, dh);
                tracker.update(people);
                Snapshot snap = new Snapshot();
                snap.bitmap = Bitmap.createBitmap(argb, dw, dh, Bitmap.Config.ARGB_8888);
                for (Track t : tracker.tracks()) {
                    if (t.missed == 0) t.pose = t.figure.build(t.person, dw, dh);
                    Snapshot.TrackInfo ti = new Snapshot.TrackInfo();
                    ti.id = t.id;
                    ti.slot = t.slot;
                    ti.missed = t.missed;
                    ti.person = t.person;
                    ti.pose = t.pose;
                    ti.trail = new float[t.trail.size() * 2];
                    int i = 0;
                    for (float[] pt : t.trail) {
                        ti.trail[i++] = pt[0];
                        ti.trail[i++] = pt[1];
                    }
                    snap.tracks.add(ti);
                }
                Collections.sort(snap.tracks, (a, b) -> Integer.compare(a.slot, b.slot));
                msSum += (System.nanoTime() - t0) / 1_000_000L;
                msCount++;
                synchronized (lock) {
                    processed++;
                }
                if (!stopped) host.onFrame(snap);
            } catch (Throwable e) {
                addError(e.toString());
                break;
            } finally {
                if (img != null) img.close();
            }
        }
        cleanup(detector);
    }

    private void cleanup(PoseDetector detector) {
        stopped = true;
        if (camera != null) camera.close();
        synchronized (lock) {
            if (pending != null) {
                pending.close();
                pending = null;
            }
        }
        if (reader != null) reader.close();
        if (detector != null) detector.close();
    }

    private synchronized void addError(String e) {
        if (stopped) return;
        errors += (errors.isEmpty() ? "" : "\n") + "Ошибка: " + e;
        publish();
    }

    private synchronized void publish() {
        if (stopped) return;
        String text = cameraReady ? "Камера работает" : "Запуск камеры…";
        if (!errors.isEmpty()) text += "\n" + errors;
        host.onStatus(text);
    }
}
