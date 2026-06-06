package com.kail.location.inject.fakelocation;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Process;
import com.kail.location.inject.fakelocation.service.AntiDetectionManagerService;
import com.kail.location.inject.fakelocation.service.HideRootManagerService;
import com.kail.location.inject.fakelocation.service.MockLocationManagerService;
import com.kail.location.inject.fakelocation.service.MockWifiManagerService;
import com.kail.location.inject.fakelocation.service.NativeCatchManagerService;
import com.kail.location.inject.utils.HiddenApiBypass;
import com.kail.location.inject.utils.PackageSignatureVerifier;
import com.kail.location.inject.utils.RootLocationControl;
import com.kail.location.inject.utils.ServiceManagerBridge;
import com.kail.location.lib.lhooker.LHooker;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import com.kail.location.inject.fakelocation.hook.phone.PhoneInterfaceManagerHook;
import com.kail.location.inject.fakelocation.hook.app.AppProcessHook;

public class InjectDex {

    private static final String BOOTSTRAP_STATE_PATH = "/data/system/kail-loc/injectdex_state.txt";

    public static List<?> activeHooks = Collections.synchronizedList(new ArrayList());

    static List<InitializationCallback> initializationCallbacks = Collections.synchronizedList(new ArrayList());

    private static Handler mainHandler;

    private static Context applicationContext;

    public static void setHookLibraryPath(String libraryPath) {
        LHooker.setSessionLibraryPath(libraryPath);
        com.kail.location.inject.utils.InjectLog.persist("InjectDex", "hook library path=", libraryPath);
    }

    public interface InitializationCallback {
        void onInitialized();
    }

    public static Object[] hookApplication(Object contextObject) {
        String message;
        applicationContext = (Context) contextObject;
        log("App: " + contextObject);
        try {
            HiddenApiBypass.bypassHiddenApiRestrictions();
            String packageName = ((Context) contextObject).getPackageName();
            if (packageName.equals("com.android.phone")) {
                if (!LHooker.isDeviceX86_64()) {
                    if (!LHooker.isDeviceX86()) {
                        if (LHooker.isDeviceArm64()) {
                            LHooker.loadHookLibrary("/data/kail-loc/liblhooker64.so");
                        } else {
                            LHooker.loadHookLibrary("/data/kail-loc/liblhooker.so");
                        }
                    }
                    LHooker.loadHookLibrary("/data/kail-loc/liblhookerx.so");
                }
                LHooker.loadHookLibrary("/data/kail-loc/liblhookerx64.so");
            } else {
                String nativeLibraryAbi = new File("" + ((Context) contextObject).getPackageManager().getApplicationInfo(packageName.split(":")[0], 0).nativeLibraryDir).getName();
                log("App abi: " + nativeLibraryAbi);
                if (LHooker.isX86_64Abi(nativeLibraryAbi)) {
                    LHooker.loadHookLibrary("/data/kail-loc/liblhookerx64.so");
                } else if (LHooker.isX86Abi(nativeLibraryAbi)) {
                    LHooker.loadHookLibrary("/data/kail-loc/liblhookerx.so");
                } else {
                    if (LHooker.isArm64Abi(nativeLibraryAbi)) {
                        LHooker.loadHookLibrary("/data/kail-loc/liblhooker64.so");
                    }
                    LHooker.loadHookLibrary("/data/kail-loc/liblhooker.so");
                }
            }
            if (!LHooker.initialized) {
                com.kail.location.inject.utils.InjectLog.e("InjectDex", "hookApplication aborted: LHooker not initialized");
                return null;
            }
            LHooker.suspendAll();
            if (packageName.equals("com.android.phone")) {
                PhoneInterfaceManagerHook.hook(((Context) contextObject).getClassLoader());
                message = "App finished.";
            } else {
                AppProcessHook.applyHookToApp((Context) contextObject, packageName);
                message = "App[" + packageName + "] finished.";
            }
            log(message);
            return null;
        } catch (Throwable th) {
            th.printStackTrace();
            com.kail.location.inject.utils.InjectLog.e("InjectDex", "hookApplication error", th);
            return null;
        }
    }

    public static Object[] init(Object contextObject) {
        Context context = (Context) contextObject;
        applicationContext = context;
        writeBootstrapState("entered context=" + contextObject, null);
        com.kail.location.inject.utils.InjectLog.persist("InjectDex", "init: ", contextObject);
        try {
            initializeMainThread(context);
            writeBootstrapState("main_thread_ready", null);
            HiddenApiBypass.bypassHiddenApiRestrictions();
            writeBootstrapState("hidden_api_bypassed", null);
            LHooker.loadHookLibrary(LHooker.isDeviceX86_64() ? "/data/kail-loc/liblhookerx64.so" : LHooker.isDeviceX86() ? "/data/kail-loc/liblhookerx.so" : LHooker.isDeviceArm64() ? "/data/kail-loc/liblhooker64.so" : "/data/kail-loc/liblhooker.so");
            com.kail.location.inject.utils.InjectLog.persist("InjectDex", "LHooker loaded initialized=", LHooker.initialized);
            writeBootstrapState("lhooker_loaded initialized=" + LHooker.initialized, null);
            RootLocationControl.start(context);
            writeBootstrapState("root_location_control_start_called", null);
            PackageSignatureVerifier.verifyPackageSignature(context, "com.kail.location", "oem_manager");
            boolean locOk = ServiceManagerBridge.addService(context.getClassLoader(), "oem_location", new MockLocationManagerService());
            boolean wifiOk = ServiceManagerBridge.addService(context.getClassLoader(), "oem_wifi", new MockWifiManagerService());
            boolean secOk = ServiceManagerBridge.addService(context.getClassLoader(), "oem_security", new AntiDetectionManagerService());
            boolean integrityOk = ServiceManagerBridge.addService(context.getClassLoader(), "oem_integrity", new HideRootManagerService());
            boolean nativeOk = ServiceManagerBridge.addService(context.getClassLoader(), "oem_native", new NativeCatchManagerService());
            com.kail.location.inject.utils.InjectLog.persist("InjectDex",
                    "addService result oem_location=", locOk,
                    " oem_wifi=", wifiOk,
                    " oem_security=", secOk,
                    " oem_integrity=", integrityOk,
                    " oem_native=", nativeOk);
            writeBootstrapState("add_service oem_location=" + locOk
                    + " oem_wifi=" + wifiOk
                    + " oem_security=" + secOk
                    + " oem_integrity=" + integrityOk
                    + " oem_native=" + nativeOk, null);
            PackageSignatureVerifier.verifyPackageSignature(context, "com.kail.location", "oem_bluetooth");
            if (!LHooker.initialized) {
                com.kail.location.inject.utils.InjectLog.e("InjectDex", "init aborted: LHooker not initialized");
                writeBootstrapState("aborted_lhooker_not_initialized", null);
                return null;
            }
            LHooker.suspendAll();
            com.kail.location.inject.utils.InjectLog.persist("InjectDex", "init finished, all services registered");
            writeBootstrapState("finished", null);
            return null;
        } catch (RuntimeException th) {
            com.kail.location.inject.utils.InjectLog.e("InjectDex", "init runtime error", th);
            writeBootstrapState("runtime_error", th);
            return null;
        } catch (Throwable th) {
            th.printStackTrace();
            com.kail.location.inject.utils.InjectLog.e("InjectDex", "init error", th);
            writeBootstrapState("error", th);
            return null;
        }
    }

    public static Object[] initZygote(Object startupParam) {
        String libraryPath;
        String processLibraryPath;
        com.kail.location.inject.utils.InjectLog.i("InjectDex", "initZygote: " + startupParam);
        HiddenApiBypass.bypassHiddenApiRestrictions();
        if (LHooker.isDeviceX86_64() || LHooker.isDeviceX86()) {
            libraryPath = "/data/kail-loc/liblhookerx.so";
            if (Build.VERSION.SDK_INT >= 23 && Process.is64Bit()) {
                processLibraryPath = "/data/kail-loc/liblhookerx64.so";
                LHooker.loadHookLibrary(processLibraryPath);
            }
            LHooker.loadHookLibrary(libraryPath);
        } else {
            libraryPath = "/data/kail-loc/liblhooker.so";
            if (Build.VERSION.SDK_INT >= 23 && Process.is64Bit()) {
                processLibraryPath = "/data/kail-loc/liblhooker64.so";
                LHooker.loadHookLibrary(processLibraryPath);
            }
            LHooker.loadHookLibrary(libraryPath);
        }
        AppProcessHook.hook(ClassLoader.getSystemClassLoader());
        com.kail.location.inject.utils.InjectLog.i("InjectDex", "initZygote finished, initialized=" + LHooker.initialized);
        return null;
    }

    public static Context getApplicationContext() {
        return applicationContext;
    }

    static void log(String message) {
        com.kail.location.inject.utils.InjectLog.d("InjectDex", message);
    }

    private static void initializeMainThread(Context context) {
        mainHandler = new Handler(context.getMainLooper());
        Iterator<InitializationCallback> iterator = initializationCallbacks.iterator();
        while (iterator.hasNext()) {
            try {
                iterator.next().onInitialized();
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    private static void writeBootstrapState(String event, Throwable t) {
        try {
            File file = new File(BOOTSTRAP_STATE_PATH);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            StringBuilder sb = new StringBuilder();
            sb.append("event=").append(event).append('\n');
            sb.append("pid=").append(Process.myPid()).append('\n');
            sb.append("time_ms=").append(System.currentTimeMillis()).append('\n');
            sb.append("thread=").append(Thread.currentThread().getName()).append('\n');
            sb.append("lhooker_initialized=").append(LHooker.initialized).append('\n');
            if (t != null) {
                sb.append("throwable=").append(t).append('\n');
                StringWriter writer = new StringWriter();
                t.printStackTrace(new PrintWriter(writer));
                sb.append("stack=").append(writer.toString().replace('\n', '|')).append('\n');
            }
            sb.append("---\n");
            FileOutputStream out = new FileOutputStream(file, true);
            try {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            file.setReadable(true, false);
            file.setWritable(true, false);
        } catch (Throwable ignored) {
        }
    }
}
