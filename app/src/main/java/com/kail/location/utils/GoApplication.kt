package com.kail.location.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.preference.PreferenceManager
import com.baidu.location.LocationClient
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer
import com.google.firebase.FirebaseApp
import com.kail.location.R
import com.kail.location.auth.AuthManager
import com.kail.location.auth.UsageManager
import com.kail.location.sandbox.SandboxManager
import com.kail.location.sandbox.SandboxSettingsManager
import com.kail.location.service.Root.RootDeployer
import java.io.File
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration

class GoApplication : Application(), Application.ActivityLifecycleCallbacks {

    companion object {
        const val APP_NAME = "KailLocation"
        private const val KEY_BAIDU_MAP_KEY = "setting_baidu_map_key"

        private fun isMainProcess(context: Context): Boolean {
            val packageName = context.packageName
            val processName = try {
                val activityThread = Class.forName("android.app.ActivityThread")
                    .getMethod("currentProcessName")
                    .invoke(null) as String?
                activityThread
            } catch (e: Exception) {
                null
            }
            return processName == null || processName == packageName
        }
    }

    private var currentActivity: Activity? = null
    private var sandboxInitialized = false
    private var isMainProc = true

    private fun writeCrashToFile(thread: Thread, ex: Throwable) {
        // 写入统一日志（带线程/堆栈，便于定位）。
        KailLog.logCrash(this, thread, ex)
        // 同时保留一份独立崩溃文件，便于快速取证。
        try {
            val logPath = getExternalFilesDir("Logs") ?: return
            val crashFile = java.io.File(logPath, "crash_${System.currentTimeMillis()}.txt")
            val pw = java.io.PrintWriter(crashFile)
            ex.printStackTrace(pw)
            pw.flush()
            pw.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var mDefaultHandler: Thread.UncaughtExceptionHandler? = null

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        isMainProc = isMainProcess(this)

        try {
            BlackBoxCore.get().closeCodeInit()
            BlackBoxCore.get().onBeforeMainApplicationAttach(this, base)

            BlackBoxCore.get().doAttachBaseContext(
                this,
                object : ClientConfiguration() {
                    override fun getHostPackageName(): String = packageName
                    override fun isHideRoot(): Boolean = false
                    override fun isEnableDaemonService(): Boolean = false
                    override fun isUseVpnNetwork(): Boolean = false
                    override fun isDisableFlagSecure(): Boolean = false
                    override fun requestInstallPackage(file: File?, userId: Int): Boolean = false
                }
            )

            BlackBoxCore.get().onAfterMainApplicationAttach(this, base)
            sandboxInitialized = true
        } catch (e: Exception) {
            android.util.Log.e("GoApplication", "Failed to init BlackBoxCore: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (sandboxInitialized) {
            try {
                BlackBoxCore.get().doCreate()
                SandboxManager.init(this)
                SandboxSettingsManager.init(this)
                android.util.Log.d("GoApplication", "BlackBoxCore initialized, isMain=${BlackBoxCore.get().isMainProcess()}, isServer=${BlackBoxCore.get().isServerProcess()}")
            } catch (e: Exception) {
                android.util.Log.e("GoApplication", "Failed to doCreate BlackBoxCore: ${e.message}")
            }
        }

        if (!isMainProc) {
            return
        }

        PreferenceManager.setDefaultValues(this, R.xml.preferences_main, false)
        FirebaseApp.initializeApp(this)

        AuthManager.init(this)
        UsageManager.init(this)

        registerActivityLifecycleCallbacks(this)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val logEnabled = prefs.getBoolean("setting_log_enabled", false)
        Thread({ RootDeployer.syncInjectLogMarkers(this) }, "KailLogMarkerSync").start()
        KailLog.i(this, APP_NAME, "App startup (main process), fileLog=$logEnabled")

        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrashToFile(thread, throwable)
            throwable.printStackTrace()
            mDefaultHandler?.uncaughtException(thread, throwable)
        }

        // 跨重启取证：上一次「开始模拟」若把 system_server 注入崩溃导致整机重启，
        // 当时的诊断报告可能来不及输出。这里在启动时检查注入哨兵，若发现上次注入后
        // 设备重启过，就输出一条「上次开始模拟疑似导致崩溃重启」诊断。
        runCatching {
            Thread({ InjectionCrashSentinel.checkAndReport(this) }, "KailInjectCrashCheck").start()
        }

        SDKInitializer.setAgreePrivacy(this, true)
        LocationClient.setAgreePrivacy(true)

        try {
            val customKey = prefs.getString(KEY_BAIDU_MAP_KEY, "")
            if (!customKey.isNullOrEmpty()) {
                SDKInitializer.setApiKey(customKey)
                LocationClient.setKey(customKey)
            }
            SDKInitializer.initialize(this)
            SDKInitializer.setCoordType(CoordType.BD09LL)
        } catch (e: Throwable) {
            KailLog.e(this, APP_NAME, "Baidu Map SDK init failed: ${e.message}")
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {
        currentActivity = activity
    }
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
