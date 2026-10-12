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

import java.nio.ByteBuffer;
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
        /** Длинная сторона входа сети, пикселей. */
        int inputSide;
        /** Сколько кадров обрабатывается одновременно и сколько потоков занято одним кадром. */
        int workers;
        int threads;
    }

    interface Host {
        void onStatus(String text);

        void onFrame(Snapshot snapshot);

        /** Доля обработанных кадров (0–100, −1 — пока нет данных) и среднее время обработки. */
        void onStats(int percent, int avgMs, int netMs);
    }

    private final Context context;
    private final Handler cameraHandler;
    private final Settings s;
    private final Host host;

    private final Object lock = new Object();
    private Image pending;
    private long pendingSeq;
    private long pendingTime;
    private long lastShown;
    private long lastStats;
    private long msSum;
    private long netSum;
    private int msCount;
    private int workers;
    private int running;
    private final Tracker tracker = new Tracker();
    private long total;
    private long processed;
    private volatile boolean stopped;
    private ImageReader reader;
    private CameraController camera;
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
                pendingSeq = total;
                pendingTime = System.nanoTime();
                if (pending != null) pending.close(); // не успели обработать — кадр потерян
                pending = im;
                lock.notifyAll();
            }
        }, cameraHandler);

        workers = Math.max(1, s.workers);
        running = workers;
        ByteBuffer model;
        try {
            model = PoseDetector.loadModel(context);
        } catch (Throwable e) {
            running = 0;
            addError("модель: " + e);
            stopped = true;
            reader.close();
            return;
        }
        for (int i = 0; i < workers; i++) new Thread(() -> run(model), "pose-" + i).start();

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

    /** Один из параллельных обработчиков: каждый берёт самый свежий кадр и ведёт его до конца. */
    private void run(ByteBuffer model) {
        PoseDetector detector = null;
        try {
            detector = new PoseDetector(model, Math.max(1, s.threads));
            int[] argb = null;
            int aw = 0, ah = 0;
            while (true) {
                Image img;
                long seq, arrival;
                synchronized (lock) {
                    while (pending == null && !stopped) {
                        try {
                            lock.wait(500);
                        } catch (InterruptedException e) {
                            stopped = true;
                        }
                        postStats();
                    }
                    if (stopped) break;
                    img = pending;
                    seq = pendingSeq;
                    arrival = pendingTime;
                    pending = null;
                }
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
                    FrameConverter.convert(img, s.rotation, argb, dw, dh);
                    img.close();
                    img = null;
                    List<Person> people = detector.detect(argb, dw, dh, s.inputSide);
                    Bitmap bmp = Bitmap.createBitmap(argb, dw, dh, Bitmap.Config.ARGB_8888);
                    long ms = (System.nanoTime() - arrival) / 1_000_000L; // задержка от прихода кадра до результата
                    publishResult(seq, people, bmp, dw, dh, ms, detector.lastInferMs);
                } finally {
                    if (img != null) img.close();
                }
            }
        } catch (Throwable e) {
            addError(e.toString());
        } finally {
            if (detector != null) detector.close();
            workerDone();
        }
    }

    /** Результаты применяются строго по порядку кадров; запоздавший результат старого кадра отбрасывается. */
    private void publishResult(long seq, List<Person> people, Bitmap bmp, int dw, int dh, long ms, long netMs) {
        Snapshot snap = null;
        synchronized (lock) {
            processed++;
            msSum += ms;
            netSum += netMs;
            msCount++;
            postStats();
            if (seq > lastShown && !stopped) {
                lastShown = seq;
                tracker.update(people);
                snap = new Snapshot();
                snap.bitmap = bmp;
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
            }
        }
        if (snap != null) host.onFrame(snap);
        else bmp.recycle();
    }

    /** Вызывается под lock. */
    private void postStats() {
        long now = System.nanoTime();
        if (now - lastStats < 500_000_000L) return;
        lastStats = now;
        host.onStats(total == 0 ? -1 : (int) (processed * 100 / total), msCount == 0 ? 0 : (int) (msSum / msCount),
                msCount == 0 ? 0 : (int) (netSum / msCount));
        if (msCount >= 20 * workers) {
            msSum = 0;
            netSum = 0;
            msCount = 0;
        }
    }

    private void workerDone() {
        synchronized (lock) {
            stopped = true;
            lock.notifyAll();
            if (--running > 0) return;
            if (pending != null) {
                pending.close();
                pending = null;
            }
        }
        if (camera != null) camera.close();
        if (reader != null) reader.close();
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
