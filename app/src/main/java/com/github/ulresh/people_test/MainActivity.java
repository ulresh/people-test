package com.github.ulresh.people_test;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity implements Pipeline.Host {
    private static final int REQ_PERMISSIONS = 1;
    private static final int[] INPUT_SIDES = {64, 96, 128, 160, 192, 256};

    private static final class Cam {
        String id;
        String label;
        int sensor;
        boolean front;
        List<Size> sizes = new ArrayList<>();
        List<Range<Integer>> fps = new ArrayList<>();

        @Override
        public String toString() {
            return label;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraManager cameraManager;
    private SharedPreferences prefs;

    private FrameView frameView;
    private FigureView figureView;
    private Spinner spCamera, spSize, spFps, spInput, spWorkers, spThreads;
    private Switch swOverlay;
    private TextView tvProcessed, tvStatus;

    private final List<Cam> cams = new ArrayList<>();
    private boolean loading;
    private boolean resumed;
    private Pipeline pipeline;

    private final Runnable applyRunnable = this::apply;

    private final CameraManager.AvailabilityCallback availability = new CameraManager.AvailabilityCallback() {
        @Override
        public void onCameraAvailable(String id) {
            ui.post(MainActivity.this::onCameraListMaybeChanged);
        }

        @Override
        public void onCameraUnavailable(String id) {
            ui.post(MainActivity.this::onCameraListMaybeChanged);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

        // слева: сверху кадр, снизу фигурки
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        frameView = new FrameView(this);
        figureView = new FigureView(this);
        left.addView(frameView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        left.addView(figureView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(left, new LinearLayout.LayoutParams(
                landscape ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                landscape ? ViewGroup.LayoutParams.MATCH_PARENT : 0, landscape ? 3f : 2f));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(8));

        spCamera = addSpinner(panel, "Камера");
        spSize = addSpinner(panel, "Разрешение");

        spInput = addSpinner(panel, "Размер входа сети (длинная сторона)");
        String[] inLabels = new String[INPUT_SIDES.length];
        for (int i = 0; i < INPUT_SIDES.length; i++) inLabels[i] = INPUT_SIDES[i] + " пикселей";
        spInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, inLabels));
        spInput.setSelection(prefs.getInt("input", 2));

        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        String[] counts = new String[cores];
        for (int i = 0; i < cores; i++) counts[i] = String.valueOf(i + 1);
        spWorkers = addSpinner(panel, "Кадров обрабатывается одновременно");
        spWorkers.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, counts));
        spWorkers.setSelection(Math.min(cores - 1, prefs.getInt("workers", 0)));
        spThreads = addSpinner(panel, "Потоков на один кадр");
        spThreads.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, counts));
        spThreads.setSelection(Math.min(cores - 1, prefs.getInt("threads", 0)));

        addLabel(panel, "Частота кадров и доля обработанных кадров");
        LinearLayout fpsRow = new LinearLayout(this);
        fpsRow.setOrientation(LinearLayout.HORIZONTAL);
        fpsRow.setGravity(Gravity.CENTER_VERTICAL);
        spFps = new Spinner(this);
        fpsRow.addView(spFps, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tvProcessed = new TextView(this);
        tvProcessed.setTextSize(13);
        tvProcessed.setPadding(dp(8), 0, 0, 0);
        fpsRow.addView(tvProcessed, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(fpsRow);

        swOverlay = new Switch(this);
        swOverlay.setText("Рамки, скелеты и треки на кадре");
        swOverlay.setChecked(prefs.getBoolean("overlay", true));
        swOverlay.setPadding(0, dp(6), 0, dp(6));
        swOverlay.setOnCheckedChangeListener((b, c) -> {
            prefs.edit().putBoolean("overlay", c).apply();
            frameView.setOverlay(c);
        });
        frameView.setOverlay(swOverlay.isChecked());
        panel.addView(swOverlay);

        tvStatus = new TextView(this);
        tvStatus.setTextIsSelectable(true);
        tvStatus.setPadding(0, dp(8), 0, 0);
        panel.addView(tvStatus);

        AdapterView.OnItemSelectedListener l = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (loading) return;
                if (p == spCamera) {
                    loading = true;
                    fillCameraCaps();
                    loading = false;
                }
                scheduleApply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        };
        for (Spinner sp : new Spinner[]{spCamera, spSize, spFps, spInput, spWorkers, spThreads}) sp.setOnItemSelectedListener(l);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        root.addView(scroll, new LinearLayout.LayoutParams(
                landscape ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                landscape ? ViewGroup.LayoutParams.MATCH_PARENT : 0, landscape ? 2f : 3f));
        setContentView(root);
    }

    private void addLabel(LinearLayout p, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setPadding(0, dp(6), 0, 0);
        p.addView(t);
    }

    private Spinner addSpinner(LinearLayout p, String label) {
        addLabel(p, label);
        Spinner s = new Spinner(this);
        p.addView(s);
        return s;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        cameraThread = new HandlerThread("camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        if (!granted(Manifest.permission.CAMERA)) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERMISSIONS);
        }
        cameraManager.registerAvailabilityCallback(availability, ui);
        loadCameras();
        scheduleApply();
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(applyRunnable);
        cameraManager.unregisterAvailabilityCallback(availability);
        stopPipeline();
        cameraThread.quitSafely();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        loadCameras();
        scheduleApply();
    }

    private boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------------- cameras

    private void onCameraListMaybeChanged() {
        if (!resumed) return;
        try {
            if (cameraManager.getCameraIdList().length != cams.size()) {
                loadCameras();
                scheduleApply();
            }
        } catch (CameraAccessException ignored) {
        }
    }

    private void loadCameras() {
        List<Cam> list = new ArrayList<>();
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
                StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;
                Cam c = new Cam();
                c.id = id;
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                c.front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
                String f = facing == null ? "" : facing == CameraCharacteristics.LENS_FACING_BACK ? "задняя"
                        : c.front ? "фронтальная" : "USB/внешняя";
                c.label = "Камера " + id + (f.isEmpty() ? "" : " (" + f + ")");
                Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
                c.sensor = so == null ? 0 : so;
                Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                if (sizes != null) c.sizes.addAll(Arrays.asList(sizes));
                // от меньшего разрешения к большему: тестирование начинаем с минимального
                c.sizes.sort((a, b) -> Integer.compare(a.getWidth() * a.getHeight(), b.getWidth() * b.getHeight()));
                Range<Integer>[] ranges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                if (ranges != null) {
                    c.fps.addAll(Arrays.asList(ranges));
                    c.fps.sort((a, b) -> a.getUpper() != b.getUpper()
                            ? Integer.compare(b.getUpper(), a.getUpper())
                            : Integer.compare(b.getLower(), a.getLower()));
                }
                if (c.fps.isEmpty()) c.fps.add(new Range<>(30, 30));
                if (!c.sizes.isEmpty()) list.add(c);
            }
        } catch (CameraAccessException e) {
            tvStatus.setText("Ошибка доступа к камерам: " + e.getMessage());
        }
        cams.clear();
        cams.addAll(list);

        String wanted = prefs.getString("camera", "");
        loading = true;
        spCamera.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, cams));
        for (int i = 0; i < cams.size(); i++) {
            if (cams.get(i).id.equals(wanted)) spCamera.setSelection(i);
        }
        fillCameraCaps();
        loading = false;
    }

    private static String fpsLabel(Range<Integer> r) {
        return r.getLower().equals(r.getUpper()) ? r.getUpper() + " к/с" : r.getLower() + "–" + r.getUpper() + " к/с";
    }

    private static String sizeLabel(Size s) {
        return s.getWidth() + "×" + s.getHeight();
    }

    private void fillCameraCaps() {
        Cam c = selectedCam();
        List<String> sizes = new ArrayList<>();
        List<String> fps = new ArrayList<>();
        if (c != null) {
            for (Size s : c.sizes) sizes.add(sizeLabel(s));
            for (Range<Integer> r : c.fps) fps.add(fpsLabel(r));
        }
        setItems(spSize, sizes, prefs.getString("size", sizes.isEmpty() ? "" : sizes.get(0)));
        setItems(spFps, fps, prefs.getString("fps", ""));
    }

    private void setItems(Spinner sp, List<String> items, String wanted) {
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        int i = items.indexOf(wanted);
        if (i >= 0) sp.setSelection(i);
    }

    private Cam selectedCam() {
        int i = spCamera.getSelectedItemPosition();
        return i >= 0 && i < cams.size() ? cams.get(i) : null;
    }

    // ---------------------------------------------------------------- pipeline

    private void scheduleApply() {
        ui.removeCallbacks(applyRunnable);
        ui.postDelayed(applyRunnable, 300);
    }

    private void stopPipeline() {
        if (pipeline != null) {
            pipeline.stop();
            pipeline = null;
        }
    }

    @SuppressWarnings("deprecation")
    private int displayDegrees() {
        int r = getWindowManager().getDefaultDisplay().getRotation();
        return r == Surface.ROTATION_90 ? 90 : r == Surface.ROTATION_180 ? 180 : r == Surface.ROTATION_270 ? 270 : 0;
    }

    private void apply() {
        if (!resumed) return;
        stopPipeline();
        frameView.clear();
        figureView.clear();
        tvProcessed.setText("");
        Cam c = selectedCam();
        if (!granted(Manifest.permission.CAMERA)) {
            tvStatus.setText("Нет разрешения на камеру");
            return;
        }
        if (c == null || spSize.getSelectedItemPosition() < 0 || spFps.getSelectedItemPosition() < 0) {
            tvStatus.setText("Камеры не найдены");
            return;
        }

        Pipeline.Settings s = new Pipeline.Settings();
        s.cameraId = c.id;
        s.size = c.sizes.get(spSize.getSelectedItemPosition());
        s.fps = c.fps.get(spFps.getSelectedItemPosition());
        s.inputSide = INPUT_SIDES[spInput.getSelectedItemPosition()];
        s.workers = spWorkers.getSelectedItemPosition() + 1;
        s.threads = spThreads.getSelectedItemPosition() + 1;
        int deg = displayDegrees();
        s.rotation = c.front ? (c.sensor + deg) % 360 : (c.sensor - deg + 360) % 360;

        prefs.edit()
                .putString("camera", c.id)
                .putString("size", sizeLabel(s.size))
                .putString("fps", fpsLabel(s.fps))
                .putInt("input", spInput.getSelectedItemPosition())
                .putInt("workers", spWorkers.getSelectedItemPosition())
                .putInt("threads", spThreads.getSelectedItemPosition())
                .apply();

        pipeline = new Pipeline(this, cameraHandler, s, this);
        pipeline.start();
    }

    @Override
    public void onStatus(String text) {
        ui.post(() -> tvStatus.setText(text));
    }

    @Override
    public void onFrame(Snapshot snapshot) {
        ui.post(() -> {
            if (!resumed) {
                snapshot.bitmap.recycle();
                return;
            }
            frameView.show(snapshot);
            figureView.show(snapshot);
        });
    }

    @Override
    public void onStats(int percent, int avgMs, int netMs) {
        android.util.Log.i("people_stats", "processed=" + percent + " latency=" + avgMs + " net=" + netMs);
        ui.post(() -> tvProcessed.setText(percent < 0 ? "" : "обработано " + percent + "%\n" + "задержка " + avgMs + " мс, сеть " + netMs + " мс"));
    }
}
