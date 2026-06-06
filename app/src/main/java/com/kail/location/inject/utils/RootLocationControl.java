package com.kail.location.inject.utils;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import com.kail.location.lib.lhooker.LHooker;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;

public final class RootLocationControl {
    private static final String TAG = "RootLocationControl";
    private static volatile boolean started;
    private static volatile Context context;
    private static volatile String controlPath = RootControlPaths.LEGACY_CONTROL_PATH;
    private static volatile String ackPath = RootControlPaths.LEGACY_ACK_PATH;
    private static long lastModified;
    private static long lastLength;
    private static long applyCount;
    private static volatile boolean lastStepEnabled;
    private static volatile float lastStepSpm = -1.0f;
    private static volatile int lastStepMode = -1;
    private static volatile int lastStepScheme = -1;
    private static volatile String lastStepStatus = "disabled";
    private static volatile String lastStepError;

    private RootLocationControl() {
    }

    public static void start(Context appContext) {
        if (appContext != null) {
            context = appContext;
            controlPath = RootControlPaths.controlPath(appContext);
            ackPath = RootControlPaths.ackPath(appContext);
        }
        if (started) return;
        started = true;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "KailRootLocationControl");
        thread.setDaemon(true);
        thread.start();
        writeAck("started", null, null);
        InjectLog.persist(TAG, "started context=", context, " control=", controlPath);
    }

    private static void loop() {
        while (true) {
            try {
                File file = new File(controlPath);
                long modified = file.exists() ? file.lastModified() : 0L;
                long length = file.exists() ? file.length() : 0L;
                if (modified != lastModified || length != lastLength) {
                    lastModified = modified;
                    lastLength = length;
                    apply(file);
                }
                Thread.sleep(250L);
            } catch (Throwable t) {
                InjectLog.e(TAG, "loop error", t);
                writeAck("error", null, t.toString());
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private static void apply(File file) throws Exception {
        if (!file.exists() || file.length() <= 0) return;
        Control control = read(file);
        if (!control.enabled) {
            MockLocationHookManager.stopMockLocation();
            MockLocationHookManager.setMockGpsStatus(false);
            applyStepControl(control);
            writeAck("disabled", control, null);
            InjectLog.persist(TAG, "disabled by control file");
            return;
        }
        ensureLocationHooks();
        if (!MockLocationHookManager.initialized) {
            writeAck("init_failed", control, "MockLocationHookManager not initialized");
            return;
        }
        MockLocationHookManager.setSafeApps(null);
        MockLocationHookManager.setIntervalTimeout(control.intervalMs);
        MockLocationHookManager.setMockGpsStatus(true);
        MockLocationHookManager.startMockLocation();

        Location location = new Location(LocationManager.GPS_PROVIDER);
        location.setLatitude(control.lat);
        location.setLongitude(control.lng);
        location.setAltitude(control.alt);
        location.setBearing((float) control.bearing);
        location.setSpeed((float) control.speed);
        location.setAccuracy(1.0f);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        Bundle extras = new Bundle();
        extras.putString("from", "rocker");
        location.setExtras(extras);
        MockLocationHookManager.setMockLocation(location);
        MockLocationHookManager.callLocationChanged(new Location(location));
        applyStepControl(control);
        writeAck("applied", control, null);
        InjectLog.persist(TAG, "applied lat=", control.lat, " lng=", control.lng,
                " interval=", control.intervalMs, " step=", lastStepStatus,
                " synth=", NativeStepHook.getStepSynthEvents());
    }

    private static void applyStepControl(Control control) {
        boolean requested = control != null && control.enabled && control.stepEnabled;
        if (!requested) {
            if (lastStepEnabled || MockStepSensorManager.isStepSensorMocking()) {
                try {
                    MockStepSensorManager.stopStepSensorMock();
                    InjectLog.persist(TAG, "step control stopped");
                } catch (Throwable t) {
                    lastStepError = t.toString();
                    InjectLog.e(TAG, "step control stop failed", t);
                }
            }
            lastStepEnabled = false;
            lastStepSpm = -1.0f;
            lastStepMode = -1;
            lastStepScheme = -1;
            lastStepStatus = "disabled";
            if (!MockStepSensorManager.isStepSensorMocking()) {
                lastStepError = null;
            }
            return;
        }

        boolean changed = !lastStepEnabled
                || Math.abs(lastStepSpm - control.stepSpm) > 0.01f
                || lastStepMode != control.stepMode
                || lastStepScheme != control.stepScheme;
        if (!changed) {
            lastStepStatus = MockStepSensorManager.isStepSensorMocking()
                    ? (NativeStepHook.isHookInstalled() ? "running" : "mocking_no_hook")
                    : "stopped";
            lastStepError = NativeStepHook.isHookInstalled() ? null : "NativeStepHook not installed";
            return;
        }

        try {
            MockStepSensorManager.setStepSpeed(control.stepSpm / 60.0f);
            MockStepSensorManager.startStepSensorMock(control.stepMode, control.stepScheme);
            lastStepEnabled = true;
            lastStepSpm = control.stepSpm;
            lastStepMode = control.stepMode;
            lastStepScheme = control.stepScheme;
            boolean mocking = MockStepSensorManager.isStepSensorMocking();
            boolean hookInstalled = NativeStepHook.isHookInstalled();
            lastStepStatus = mocking && hookInstalled ? "running" : mocking ? "mocking_no_hook" : "stopped";
            lastStepError = hookInstalled ? null : "NativeStepHook not installed";
            InjectLog.persist(TAG, "step control applied enabled=", control.stepEnabled,
                    " spm=", control.stepSpm, " mode=", control.stepMode,
                    " scheme=", control.stepScheme, " status=", lastStepStatus);
        } catch (Throwable t) {
            lastStepStatus = "error";
            lastStepError = t.toString();
            InjectLog.e(TAG, "step control apply failed", t);
        }
    }

    private static void writeAck(String status, Control control, String error) {
        try {
            applyCount++;
            StringBuilder sb = new StringBuilder();
            sb.append("status=").append(status).append('\n');
            sb.append("pid=").append(Process.myPid()).append('\n');
            sb.append("time_ms=").append(System.currentTimeMillis()).append('\n');
            sb.append("count=").append(applyCount).append('\n');
            sb.append("control_path=").append(controlPath).append('\n');
            sb.append("lhooker_initialized=").append(LHooker.initialized).append('\n');
            sb.append("mock_initialized=").append(MockLocationHookManager.initialized).append('\n');
            if (control != null) {
                sb.append("enabled=").append(control.enabled ? 1 : 0).append('\n');
                sb.append("lat=").append(control.lat).append('\n');
                sb.append("lng=").append(control.lng).append('\n');
                sb.append("interval=").append(control.intervalMs).append('\n');
                sb.append("step_enabled=").append(control.stepEnabled ? 1 : 0).append('\n');
                sb.append("step_spm=").append(control.stepSpm).append('\n');
                sb.append("step_mocking=").append(MockStepSensorManager.isStepSensorMocking() ? 1 : 0).append('\n');
                sb.append("step_hook_installed=").append(NativeStepHook.isHookInstalled() ? 1 : 0).append('\n');
                sb.append("step_counter_handle=").append(MockStepSensorManager.getStepCounterHandle()).append('\n');
                sb.append("step_detector_handle=").append(MockStepSensorManager.getStepDetectorHandle()).append('\n');
                sb.append("step_synth_events=").append(NativeStepHook.getStepSynthEvents()).append('\n');
                sb.append("step_status=").append(lastStepStatus).append('\n');
                if (lastStepError != null) {
                    sb.append("step_error=").append(lastStepError.replace('\n', ' ')).append('\n');
                }
            }
            if (error != null) {
                sb.append("error=").append(error.replace('\n', ' ')).append('\n');
            }
            File file = new File(ackPath);
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            FileOutputStream out = new FileOutputStream(file, false);
            try {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            file.setReadable(true, false);
            file.setWritable(true, false);
        } catch (Throwable t) {
            InjectLog.e(TAG, "write ack failed", t);
        }
    }

    private static void ensureLocationHooks() {
        if (MockLocationHookManager.initialized) return;
        if (!LHooker.initialized) {
            String path = LHooker.isDeviceX86_64()
                    ? "/data/kail-loc/liblhookerx64.so"
                    : LHooker.isDeviceX86()
                    ? "/data/kail-loc/liblhookerx.so"
                    : LHooker.isDeviceArm64()
                    ? "/data/kail-loc/liblhooker64.so"
                    : "/data/kail-loc/liblhooker.so";
            InjectLog.persist(TAG, "LHooker not initialized; retry load path=", path);
            LHooker.loadHookLibrary(path);
            InjectLog.persist(TAG, "LHooker initialized after retry=", LHooker.initialized);
        }
        if (!LHooker.initialized) {
            InjectLog.e(TAG, "cannot init MockLocationHookManager: LHooker not initialized");
            return;
        }
        Context ctx = context;
        if (ctx == null) {
            InjectLog.e(TAG, "cannot init MockLocationHookManager: context is null");
            return;
        }
        InjectLog.persist(TAG, "initializing MockLocationHookManager");
        MockLocationHookManager.init(ctx);
        InjectLog.persist(TAG, "MockLocationHookManager initialized=", MockLocationHookManager.initialized);
    }

    private static Control read(File file) throws Exception {
        Control control = new Control();
        BufferedReader reader = new BufferedReader(new FileReader(file));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                int idx = line.indexOf('=');
                if (idx <= 0) continue;
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                if ("enabled".equals(key)) control.enabled = "1".equals(value) || "true".equalsIgnoreCase(value);
                else if ("lat".equals(key)) control.lat = Double.parseDouble(value);
                else if ("lng".equals(key)) control.lng = Double.parseDouble(value);
                else if ("alt".equals(key)) control.alt = Double.parseDouble(value);
                else if ("bearing".equals(key)) control.bearing = Double.parseDouble(value);
                else if ("speed".equals(key)) control.speed = Double.parseDouble(value);
                else if ("interval".equals(key)) control.intervalMs = Long.parseLong(value);
                else if ("step_enabled".equals(key)) control.stepEnabled = "1".equals(value) || "true".equalsIgnoreCase(value);
                else if ("step_spm".equals(key)) control.stepSpm = Float.parseFloat(value);
                else if ("step_mode".equals(key)) control.stepMode = Integer.parseInt(value);
                else if ("step_scheme".equals(key)) control.stepScheme = Integer.parseInt(value);
            }
        } finally {
            reader.close();
        }
        return control;
    }

    private static final class Control {
        boolean enabled;
        double lat;
        double lng;
        double alt;
        double bearing;
        double speed;
        long intervalMs = 1000L;
        boolean stepEnabled;
        float stepSpm = 120.0f;
        int stepMode;
        int stepScheme;
    }
}
