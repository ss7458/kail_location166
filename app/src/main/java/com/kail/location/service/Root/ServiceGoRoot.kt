package com.kail.location.service.Root

import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.preference.PreferenceManager
import com.kail.location.R
import com.kail.location.geo.GeoPredict
import com.kail.location.inject.fakelocation.aidl.IMockLocationManager
import com.kail.location.inject.utils.ServiceManagerBridge
import com.kail.location.root.NativeSensorHook
import com.kail.location.service.Developer.MockLocationProvider
import com.kail.location.utils.GoUtils
import com.kail.location.utils.KailLog
import com.kail.location.utils.MapUtils
import com.kail.location.utils.ShellUtils
import com.kail.location.utils.service.RouteEngine
import com.kail.location.utils.service.ServiceConstants
import com.kail.location.utils.service.ServiceNotificationHelper
import com.kail.location.viewmodels.JoystickViewModel
import com.kail.location.views.joystick.JoystickWindowManager
import com.kail.location.views.locationpicker.LocationPickerActivity

/**
 * Foreground service for the "root" run mode.
 *
 * Mocks location safely without ptrace-injecting system_server. Concretely:
 *
 *   1. [RootDeployer.ensureBaseline] stages every FakeLocation loader/hook
 *      .so (libfakeloc_init.so / libfakeloc_initzygote.so /
 *      libfakeloc_apphook.so / liblhooker.so / libStepSensor.so /
 *      libantidetect.so) into /data/kail-loc/, drops the kail_inject ptrace
 *      injector + libkail_native_hook.so into /data/local/kail-lib/, and
 *      copies the host APK to /data/kail-loc/libfakeloc.so as the dex
 *      payload. The full FakeLocation toolchain is therefore present on the
 *      device for an operator who wants to bootstrap it manually via
 *      [RootDeployer.bootstrapInjection], but the service does NOT run that
 *      step automatically — ptrace-injecting a production system_server
 *      regularly deadlocks the dynamic-linker lock and freezes the phone.
 *   2. The service grants the host package the AppOps `mock_location`
 *      permission via `appops set` so the standard Android test-provider
 *      mechanism works without the user toggling Developer Settings.
 *   3. Location updates are pushed via [MockLocationProvider] (the same code
 *      Developer mode uses) which calls
 *      `LocationManager.setTestProviderLocation` for both GPS and NETWORK
 *      providers. This is exactly the mechanism Developer mode would use,
 *      but root mode handles the AppOps grant for you.
 *   4. Sensor/step-cadence simulation runs through the in-app
 *      [NativeSensorHook] binding to libkail_native_hook.so. The full hook
 *      on system_server still requires the FakeLocation injection chain
 *      above to be online, so step mocking degrades gracefully when only
 *      ensureBaseline ran.
 *
 * The route engine, joystick, foreground notification, and control-action
 * surface mirror [com.kail.location.service.Xposed.ServiceGoXposed] so the
 * existing UI plugs in unchanged.
 */
class ServiceGoRoot : Service() {

    private var mCurLat = ServiceConstants.DEFAULT_LAT
    private var mCurLng = ServiceConstants.DEFAULT_LNG
    private var mCurAlt = ServiceConstants.DEFAULT_ALT
    private var mCurBea = ServiceConstants.DEFAULT_BEA
    private var mSpeed = 1.2

    private lateinit var mLocManager: LocationManager
    private lateinit var mLocHandlerThread: HandlerThread
    private lateinit var mLocHandler: Handler
    private var isStop = false

    private lateinit var mJoystickManager: JoystickWindowManager
    private lateinit var mJoystickViewModel: JoystickViewModel

    private val mBinder = ServiceGoRootBinder()
    private val mRouteEngine = RouteEngine()
    private val mNotificationHelper by lazy {
        ServiceNotificationHelper(
            service = this,
            channelId = "SERVICE_GO_ROOT_NOTE",
            channelName = "SERVICE_GO_ROOT_NOTE",
            noteId = SERVICE_GO_NOTE_ID,
            onShowJoystick = { mJoystickManager.show() },
            onHideJoystick = { mJoystickManager.hide() }
        )
    }

    private var locationLoopStarted: Boolean = false
    private var speedFluctuation: Boolean = false
    private var stepEnabled: Boolean = false
    private var stepCadence: Float = 120f
    private var stepMode: Int = 0
    private var stepScheme: Int = 0

    private var nativeHookReady: Boolean = false
    private var nativeHookAttempted: Boolean = false

    /** Drives Android's standard test-provider mechanism. Same code Developer mode uses. */
    private val mMockLocationProvider by lazy { MockLocationProvider(this, mLocManager) }

    /**
     * Cached binder into the FakeLocation injection layer. Resolved after
     * RootDeployer.ensureBaseline runs kail_inject on system_server. When
     * non-null, location updates go through it; otherwise the service falls
     * back to [mMockLocationProvider].
     */
    private var mockLocService: IMockLocationManager? = null

    /**
     * Target-app allow-list driven by the "独立模拟" (Independent Simulation)
     * screen. When non-empty, every mock surface (location / GNSS / WiFi /
     * cell) is restricted to ONLY these packages via FakeLocation's
     * setAllowMockPackages; all other apps read real data. Empty means the
     * default FakeLocation behaviour (mock for all apps).
     *
     * Read from prefs on demand so a mock started from any screen always
     * picks up the latest independent-mode selection.
     */
    private val independentAllowPackages: List<String>
        get() = runCatching {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val enabled = prefs.getBoolean("independent_enabled", false)
            if (!enabled) return emptyList()
            (prefs.getString("independent_target_packages", "") ?: "")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())

    /**
     * Set true only when the start intent flagged WIFI_ONLY/CELL_ONLY — those
     * modes intentionally do not run the location loop.
     */
    private var modeWifiOnly: Boolean = false
    private var modeCellOnly: Boolean = false

    /**
     * WiFi / cell networks selected in the UI for spoofing. Populated from the
     * start intent's [EXTRA_WIFI_LIST] / [EXTRA_CELL_LIST] parcelable extras.
     * Pushed into the FakeLocation injection layer via the service_mock_wifi /
     * service_mock_location binders once the inject is online.
     */
    private var pendingWifiList: List<com.kail.location.models.WifiInfo> = emptyList()
    private var pendingCellList: List<com.kail.location.models.CellInfo> = emptyList()

    companion object {
        const val DEFAULT_LAT = ServiceConstants.DEFAULT_LAT
        const val DEFAULT_LNG = ServiceConstants.DEFAULT_LNG
        const val DEFAULT_ALT = ServiceConstants.DEFAULT_ALT
        const val DEFAULT_BEA = ServiceConstants.DEFAULT_BEA

        private const val TAG = "ServiceGoRoot"
        private const val HANDLER_MSG_ID = 0
        private const val DEFAULT_LOCATION_UPDATE_INTERVAL_MS = 200L
        private const val SERVICE_GO_HANDLER_NAME = "ServiceGoRootLocation"
        private const val SERVICE_GO_NOTE_ID = 3

        const val SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW = ServiceNotificationHelper.ACTION_JOYSTICK_SHOW
        const val SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE = ServiceNotificationHelper.ACTION_JOYSTICK_HIDE

        const val EXTRA_ROUTE_POINTS = ServiceConstants.EXTRA_ROUTE_POINTS
        const val EXTRA_ROUTE_LOOP = ServiceConstants.EXTRA_ROUTE_LOOP
        const val EXTRA_JOYSTICK_ENABLED = ServiceConstants.EXTRA_JOYSTICK_ENABLED
        const val EXTRA_ROUTE_SPEED = ServiceConstants.EXTRA_ROUTE_SPEED
        const val EXTRA_COORD_TYPE = ServiceConstants.EXTRA_COORD_TYPE
        const val EXTRA_CONTROL_ACTION = ServiceConstants.EXTRA_CONTROL_ACTION
        const val EXTRA_SPEED_FLUCTUATION = ServiceConstants.EXTRA_SPEED_FLUCTUATION
        const val EXTRA_SEEK_RATIO = ServiceConstants.EXTRA_SEEK_RATIO

        const val EXTRA_STEP_ENABLED = "EXTRA_STEP_ENABLED"
        const val EXTRA_STEP_FREQ = "EXTRA_STEP_FREQ"
        const val EXTRA_STEP_MODE = "EXTRA_STEP_MODE"
        const val EXTRA_STEP_SCHEME = "EXTRA_STEP_SCHEME"
        const val EXTRA_WIFI_ONLY = "EXTRA_WIFI_ONLY"
        const val EXTRA_CELL_ONLY = "EXTRA_CELL_ONLY"
        const val EXTRA_WIFI_LIST = "EXTRA_WIFI_LIST"
        const val EXTRA_CELL_LIST = "EXTRA_CELL_LIST"

        const val CONTROL_PAUSE = ServiceConstants.CONTROL_PAUSE
        const val CONTROL_RESUME = ServiceConstants.CONTROL_RESUME
        const val CONTROL_STOP = ServiceConstants.CONTROL_STOP
        const val CONTROL_SEEK = ServiceConstants.CONTROL_SEEK
        const val CONTROL_SET_SPEED = ServiceConstants.CONTROL_SET_SPEED
        const val CONTROL_SET_SPEED_FLUCTUATION = ServiceConstants.CONTROL_SET_SPEED_FLUCTUATION
        const val CONTROL_SET_STEP = "set_step"
        const val CONTROL_STOP_WIFI = "stop_wifi"
        const val CONTROL_STOP_CELL = "stop_cell"
        const val CONTROL_SET_ALLOW_PACKAGES = "set_allow_packages"
        const val EXTRA_ALLOW_PACKAGES = "EXTRA_ALLOW_PACKAGES"

        const val COORD_WGS84 = ServiceConstants.COORD_WGS84
        const val COORD_BD09 = ServiceConstants.COORD_BD09
        const val COORD_GCJ02 = ServiceConstants.COORD_GCJ02

        const val ACTION_STATUS_CHANGED = ServiceConstants.ACTION_STATUS_CHANGED
        const val EXTRA_IS_SIMULATING = ServiceConstants.EXTRA_IS_SIMULATING
        const val EXTRA_IS_PAUSED = ServiceConstants.EXTRA_IS_PAUSED

        /**
         * True while a ServiceGoRoot instance is alive. Lets the Independent
         * Simulation screen decide whether to push a live allow-list update
         * (only meaningful when a mock session is actually running).
         */
        @Volatile
        @JvmStatic
        var isRunning: Boolean = false
            private set

        // service_mock_wifi (IMockWifiManager) raw-transaction constants.
        private const val WIFI_DESCRIPTOR = "com.kail.location.aidl.IMockWifiManager"
        private const val TXN_START_MOCK_WIFI = 2
        private const val TXN_STOP_MOCK_WIFI = 3
        private const val TXN_SET_MOCK_WIFI_NETWORKS = 9
    }

    override fun onBind(intent: Intent): IBinder = mBinder

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        KailLog.i(this, TAG, "onCreate started")
        runCatching { mNotificationHelper.initAndStartForeground() }
            .onFailure { KailLog.e(this, TAG, "initNotification: ${it.message}") }
        runCatching { mLocManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager }
            .onFailure { KailLog.e(this, TAG, "LocationManager init: ${it.message}") }
        runCatching { initGoLocation() }
            .onFailure { KailLog.e(this, TAG, "initGoLocation: ${it.message}") }
        runCatching {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val joystickEnabledPref = prefs.getBoolean("setting_joystick_enabled", false)
            initJoyStick()
            if (joystickEnabledPref) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                    mJoystickManager.show()
                }
            } else {
                mJoystickManager.hide()
            }
        }.onFailure {
            KailLog.e(this, TAG, "init joystick: ${it.message}")
            GoUtils.DisplayToast(applicationContext, getString(R.string.service_overlay_failed, it.message))
        }
        broadcastStatus()
        KailLog.i(this, TAG, "onCreate finished")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val ctrl = intent.getStringExtra(EXTRA_CONTROL_ACTION)
            if (!ctrl.isNullOrBlank()) {
                handleControlAction(ctrl, intent)
                return super.onStartCommand(intent, flags, startId)
            }
            speedFluctuation = intent.getBooleanExtra(EXTRA_SPEED_FLUCTUATION, false)
        }

        mNotificationHelper.startForegroundIfReady()

        if (intent != null) {
            modeWifiOnly = intent.getBooleanExtra(EXTRA_WIFI_ONLY, false)
            modeCellOnly = intent.getBooleanExtra(EXTRA_CELL_ONLY, false)

            // Selected WiFi / cell networks (parcelable lists from the UI).
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(EXTRA_WIFI_LIST, com.kail.location.models.WifiInfo::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<com.kail.location.models.WifiInfo>(EXTRA_WIFI_LIST)
                }
            }.getOrNull()?.let { if (it.isNotEmpty()) pendingWifiList = it }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(EXTRA_CELL_LIST, com.kail.location.models.CellInfo::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<com.kail.location.models.CellInfo>(EXTRA_CELL_LIST)
                }
            }.getOrNull()?.let { if (it.isNotEmpty()) pendingCellList = it }

            val coordType = intent.getStringExtra(EXTRA_COORD_TYPE) ?: COORD_BD09
            mCurLng = intent.getDoubleExtra(LocationPickerActivity.LNG_MSG_ID, DEFAULT_LNG)
            mCurLat = intent.getDoubleExtra(LocationPickerActivity.LAT_MSG_ID, DEFAULT_LAT)
            runCatching {
                when (coordType) {
                    COORD_WGS84 -> { /* keep */ }
                    COORD_GCJ02 -> {
                        val wgs = MapUtils.gcj02towgs84(mCurLng, mCurLat)
                        mCurLng = wgs[0]; mCurLat = wgs[1]
                    }
                    else -> {
                        val wgs = MapUtils.bd2wgs(mCurLng, mCurLat)
                        mCurLng = wgs[0]; mCurLat = wgs[1]
                    }
                }
            }
            mCurAlt = intent.getDoubleExtra(LocationPickerActivity.ALT_MSG_ID, DEFAULT_ALT)
            val joystickEnabled = intent.getBooleanExtra(EXTRA_JOYSTICK_ENABLED, false)
            mSpeed = intent.getFloatExtra(EXTRA_ROUTE_SPEED, mSpeed.toFloat()).toDouble() / 3.6

            val routeArray = intent.getDoubleArrayExtra(EXTRA_ROUTE_POINTS)
            if (routeArray != null && routeArray.size >= 2) {
                mRouteEngine.setupFromArray(routeArray, coordType)
                mRouteEngine.setLoop(intent.getBooleanExtra(EXTRA_ROUTE_LOOP, false))
            }

            stepEnabled = intent.getBooleanExtra(EXTRA_STEP_ENABLED, stepEnabled)
            stepCadence = intent.getFloatExtra(EXTRA_STEP_FREQ, stepCadence)
            stepMode = intent.getIntExtra(EXTRA_STEP_MODE, stepMode)
            stepScheme = intent.getIntExtra(EXTRA_STEP_SCHEME, stepScheme)

            KailLog.i(this, TAG, "onStartCommand lat=$mCurLat lng=$mCurLng wifiOnly=$modeWifiOnly cellOnly=$modeCellOnly step=$stepEnabled spm=$stepCadence")

            // Bring up the in-app sensor hook + the FakeLocation injection
            // chain. The injection step shells out to su + kail_inject, which
            // can take a couple of seconds, so run it off the main thread.
            ensureNativeHookOnce()
            applyStepSimulation()
            Thread({ startMockLocationOnInjection() }, "ServiceGoRootBootstrap").start()

            if (!modeWifiOnly && !modeCellOnly) {
                startLocationLoop()
            }

            runCatching {
                mJoystickViewModel.setCurrentPosition(mCurLng, mCurLat, mCurAlt)
                if (joystickEnabled) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                        if (mRouteEngine.isActive) mJoystickManager.showRouteControl(mSpeed * 3.6)
                        else mJoystickManager.show()
                    } else {
                        GoUtils.DisplayToast(applicationContext, getString(R.string.service_grant_overlay))
                    }
                } else {
                    mJoystickManager.hide()
                }
            }.onFailure { KailLog.e(this, TAG, "joystick show: ${it.message}") }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        KailLog.i(this, TAG, "onDestroy started")
        isRunning = false
        runCatching {
            broadcastStatusStopped()
            isStop = true
            locationLoopStarted = false
            if (this::mLocHandler.isInitialized) mLocHandler.removeCallbacksAndMessages(null)
            if (this::mLocHandlerThread.isInitialized) mLocHandlerThread.quitSafely()
            if (this::mJoystickManager.isInitialized) mJoystickManager.destroy()

            // Tell the injected layer to stop, if present.
            stopMockLocationOnInjection()

            // Tell the in-app native hook to wind down.
            if (nativeHookReady) {
                runCatching { NativeSensorHook.nativeSetMocking(0) }
                runCatching { NativeSensorHook.nativeSetStepSimEnabled(false) }
                runCatching { NativeSensorHook.nativeSetRouteSimulation(false, 120f, 0) }
                runCatching { NativeSensorHook.nativeReset() }
            }

            mNotificationHelper.stopForeground()
        }.onFailure { KailLog.e(this, TAG, "onDestroy: ${it.message}") }
        super.onDestroy()
        KailLog.i(this, TAG, "onDestroy finished")
    }

    // ------------------------------------------------------------------
    // Control intent dispatcher
    // ------------------------------------------------------------------

    private fun handleControlAction(ctrl: String, intent: Intent) {
        when (ctrl) {
            CONTROL_PAUSE -> runCatching {
                isStop = true
                if (this::mJoystickManager.isInitialized) mJoystickManager.setRoutePauseState(true)
                broadcastStatus()
                KailLog.log(this, TAG, "Paused (isStop=true)", isHighFrequency = false)
            }.onFailure { KailLog.e(this, TAG, "Pause: ${it.message}") }

            CONTROL_RESUME -> runCatching {
                isStop = false
                if (this::mJoystickManager.isInitialized) mJoystickManager.setRoutePauseState(false)
                if (locationLoopStarted && this::mLocHandler.isInitialized && !mLocHandler.hasMessages(HANDLER_MSG_ID)) {
                    mLocHandler.sendEmptyMessage(HANDLER_MSG_ID)
                }
                applyStepSimulation()
                broadcastStatus()
                KailLog.log(this, TAG, "Resumed (isStop=false)", isHighFrequency = false)
            }.onFailure { KailLog.e(this, TAG, "Resume: ${it.message}") }

            CONTROL_STOP -> runCatching {
                stopSelf()
                broadcastStatus()
            }.onFailure { KailLog.e(this, TAG, "stop: ${it.message}") }

            CONTROL_STOP_WIFI -> runCatching {
                // Stop only WiFi spoofing; leave any location/cell session intact.
                stopWifiMockOnInjection()
                modeWifiOnly = false
                pendingWifiList = emptyList()
                KailLog.i(this, TAG, "WiFi mock stopped via control")
                if (!isAnyMockActive()) stopSelf()
            }.onFailure { KailLog.e(this, TAG, "stop_wifi: ${it.message}") }

            CONTROL_STOP_CELL -> runCatching {
                // Stop only cell spoofing. Clear the mock cells and the scoped
                // block-list, and turn the master mock switch back off (it was
                // only on to arm the telephony hook).
                runCatching {
                    resolveMockLocService()?.let {
                        it.setMockCells(null)
                        it.setSafeApps(null)
                        it.stopMockLocation()
                        it.setMockGpsStatus(false)
                    }
                }
                modeCellOnly = false
                pendingCellList = emptyList()
                KailLog.i(this, TAG, "Cell mock stopped via control")
                if (!isAnyMockActive()) stopSelf()
            }.onFailure { KailLog.e(this, TAG, "stop_cell: ${it.message}") }

            CONTROL_SET_ALLOW_PACKAGES -> runCatching {
                val pkgs = intent.getStringArrayListExtra(EXTRA_ALLOW_PACKAGES) ?: arrayListOf()
                // Run off the main thread: resolving binders + injecting target
                // apps can block briefly.
                Thread({ applyAllowPackages(pkgs) }, "ServiceGoRootAllowPkgs").start()
            }.onFailure { KailLog.e(this, TAG, "set_allow_packages: ${it.message}") }

            CONTROL_SEEK -> {
                val ratio = intent.getFloatExtra(EXTRA_SEEK_RATIO, 0f).coerceIn(0f, 1f)
                mRouteEngine.seekToRatio(ratio)
                mCurLng = mRouteEngine.currentLng
                mCurLat = mRouteEngine.currentLat
                mCurBea = mRouteEngine.currentBea
                updateJoystickStatus()
            }

            CONTROL_SET_SPEED -> {
                val kmh = intent.getFloatExtra(EXTRA_ROUTE_SPEED, (mSpeed * 3.6).toFloat())
                mSpeed = kmh.toDouble() / 3.6
            }

            CONTROL_SET_SPEED_FLUCTUATION -> {
                speedFluctuation = intent.getBooleanExtra(EXTRA_SPEED_FLUCTUATION, speedFluctuation)
            }

            CONTROL_SET_STEP -> {
                stepEnabled = intent.getBooleanExtra(EXTRA_STEP_ENABLED, stepEnabled)
                stepCadence = intent.getFloatExtra(EXTRA_STEP_FREQ, stepCadence)
                stepMode = intent.getIntExtra(EXTRA_STEP_MODE, stepMode)
                stepScheme = intent.getIntExtra(EXTRA_STEP_SCHEME, stepScheme)
                ensureNativeHookOnce()
                applyStepSimulation()
            }
        }
    }

    // ------------------------------------------------------------------
    // Native (in-app) sensor hook bridge
    // ------------------------------------------------------------------

    /**
     * One-shot best-effort initialization of the in-app NativeSensorHook
     * bindings against [RootDeployer.NATIVE_HOOK_SO].
     *
     * IMPORTANT — actual sensor hook installation is a no-op when run from
     * the controller app process. The `Dobby` hook installer in
     * native_hook/hook.cpp scans `/proc/self/maps` for libsensor.so /
     * libsensorservice.so and only patches them when they are mapped in the
     * caller. Those libraries live in system_server (and individual apps
     * that consume sensors), not in com.kail.location.
     *
     * For the hook to fire, the SO needs to be loaded into the target
     * process by an external loader — the Xposed companion already does
     * this from inside system_server (see [com.kail.locationxposed]); the
     * FakeLocation injection chain (libfakeloc_init.so / libStepSensor.so)
     * would do it post-injection but we never auto-run kail_inject because
     * it freezes system_server on production ROMs.
     *
     * This method therefore initializes the JNI side so step parameters can
     * be plumbed in, but logs a clear notice that no hook is active from
     * this process. The bool it returns is "JNI bindings loaded", not "hook
     * installed". When the SO is loaded in another process via Xposed/
     * Zygisk, that process picks up its own copy and the hook does install.
     */
    private fun ensureNativeHookOnce(): Boolean {
        if (nativeHookReady) return true
        if (nativeHookAttempted) return false
        nativeHookAttempted = true

        if (!ShellUtils.hasRoot()) {
            KailLog.w(this, TAG, "no root; skipping native hook deploy")
            return false
        }

        runCatching { RootDeployer.deployNativeHookLib(this) }
            .onFailure { KailLog.e(this, TAG, "deployNativeHookLib: ${it.message}") }

        // Probe sensor offsets from libsensor.so / libsensorservice.so on
        // disk so a future loader can pick them up via the JNI setters.
        val (writeOffset, convertOffset) = getOffsetsFromSystem()

        val ok = runCatching {
            NativeSensorHook.nativeSetWriteOffset(parseHexOffset(writeOffset))
            NativeSensorHook.nativeSetConvertOffset(parseHexOffset(convertOffset))
            NativeSensorHook.nativeInitHook()
            true
        }.getOrElse {
            KailLog.e(this, TAG, "NativeSensorHook init: ${it.message}")
            false
        }

        nativeHookReady = ok
        if (ok) {
            KailLog.i(
                this,
                TAG,
                "NativeSensorHook JNI initialized in app process; sensor hook on system_server " +
                    "is NOT installed from here — needs Xposed/Zygisk loader. offsets=$writeOffset/$convertOffset"
            )
        } else {
            KailLog.w(this, TAG, "NativeSensorHook JNI init failed; offsets=$writeOffset/$convertOffset")
        }
        return ok
    }

    private fun applyStepSimulation() {
        if (!nativeHookReady) return
        runCatching {
            NativeSensorHook.nativeSetGaitParams(stepCadence, stepMode, stepScheme, stepEnabled)
            NativeSensorHook.nativeSetStepSimEnabled(stepEnabled)
            if (stepEnabled) {
                NativeSensorHook.nativeSetRouteSimulation(true, stepCadence, stepMode)
                NativeSensorHook.nativeSetMocking(1)
            } else {
                NativeSensorHook.nativeSetRouteSimulation(false, stepCadence, stepMode)
                NativeSensorHook.nativeSetMocking(0)
            }
        }.onFailure { KailLog.e(this, TAG, "applyStepSimulation: ${it.message}") }
    }

    // ------------------------------------------------------------------
    // Mock-location bridge
    //
    // Two backends are tried in order:
    //  1. The FakeLocation injection layer (preferred) — only available
    //     after RootDeployer.ensureBaseline has run kail_inject on
    //     system_server and the binder service "service_mock_location" is
    //     registered.  This is the original FakeLocation hook path.
    //  2. Standard Android test-provider via [MockLocationProvider] — same
    //     code Developer mode uses.  Always works once the AppOps grant
    //     lands; harmless if the inject path also runs.
    // ------------------------------------------------------------------

    private fun resolveMockLocService(): IMockLocationManager? {
        mockLocService?.let { return it }
        val binder = runCatching {
            ServiceManagerBridge.getService(ClassLoader.getSystemClassLoader(), "service_mock_location")
        }.getOrNull() ?: return null
        return runCatching { IMockLocationManager.Stub.asInterface(binder) }.getOrNull()?.also {
            mockLocService = it
            KailLog.i(this, TAG, "FakeLocation mock-location binder online")
        }
    }

    /** Like [resolveMockLocService] but retries while the inject finishes registering. */
    private fun resolveMockLocServiceWithRetry(): IMockLocationManager? {
        repeat(10) {
            resolveMockLocService()?.let { return it }
            runCatching { Thread.sleep(300) }
        }
        return null
    }

    /**
     * Push the independent-mode target-app allow-list into the FakeLocation
     * injection layer. With a non-empty list, location/GNSS/WiFi/cell mocking
     * only affects those packages (FakeLocation's setAllowMockPackages /
     * MockWifiConfigManager.setAllowMockPackages); every other app reads real
     * data. An empty list clears the restriction (mock for all apps).
     *
     * Called on every mock start so the latest selection always applies, and
     * directly from the CONTROL_SET_ALLOW_PACKAGES action so toggling
     * independent mode takes effect live.
     */
    private fun applyAllowPackages(pkgs: List<String>) {
        val list = ArrayList(pkgs)
        // service_mock_location (covers location, GNSS, cells — all gated by
        // MockLocationHookManager.isAllowMockPackage).
        runCatching {
            resolveMockLocService()?.setAllowMockPackages(if (list.isEmpty()) null else list)
        }.onFailure { KailLog.e(this, TAG, "setAllowMockPackages(loc): ${it.message}") }
        // service_mock_wifi (MockWifiConfigManager.setAllowMockPackages, code 7).
        runCatching {
            val binder = resolveMockWifiBinder()
            if (binder != null) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(WIFI_DESCRIPTOR)
                    if (list.isEmpty()) data.writeStringList(null) else data.writeStringList(list)
                    binder.transact(7, data, reply, 0) // setAllowMockPackages
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
        }.onFailure { KailLog.e(this, TAG, "setAllowMockPackages(wifi): ${it.message}") }
        KailLog.i(this, TAG, "allowMockPackages applied: ${if (list.isEmpty()) "<all apps>" else list.joinToString()}")
    }

    private fun startMockLocationOnInjection() {
        // Stage the FakeLocation toolchain on disk, run kail_inject (with the
        // 5s watchdog), and grant mock_location AppOps.  RootDeployer is
        // idempotent and never re-runs the inject if the binder is already
        // registered.
        runCatching { RootDeployer.ensureBaseline(this) }
            .onFailure { KailLog.e(this, TAG, "RootDeployer.ensureBaseline: ${it.message}") }

        // WiFi-only / cell-only modes must NOT turn on GNSS satellite
        // mocking, and WiFi-only must not start location mocking at all.
        // They still need the inject to have run (so the service_mock_wifi /
        // service_mock_location binders exist). We route the selected
        // networks into the FakeLocation injection layer below.
        if (modeWifiOnly) {
            // WiFi spoofing is fully independent of location: WifiServiceHook
            // gates only on MockWifiConfigManager.isMockWifiEnabled(). Clear
            // any leftover location/GNSS mock state from a previous (non-wifi)
            // session so enabling only WiFi spoofing doesn't leave GPS faking
            // active, then push the selected WiFi networks.
            runCatching {
                resolveMockLocService()?.let {
                    it.stopMockLocation()
                    it.setMockGpsStatus(false)
                    it.setMockCells(null)
                    it.setSafeApps(null)
                }
            }
            applyWifiMockOnInjection()
            applyAllowPackages(independentAllowPackages)
            KailLog.i(this, TAG, "wifiOnly: inject staged, WiFi networks pushed, location+GNSS skipped")
            return
        }

        if (modeCellOnly) {
            // Cell spoofing goes through TelephonyRegistryHook, whose isHook()
            // requires MockLocationHookManager.isMocking()==true. So cell mode
            // DOES start location mocking (to arm the telephony hook) and
            // seeds a base fix from the cell coordinates, but it keeps GNSS
            // satellite mocking OFF and does not run the moving-location loop.
            applyCellMockOnInjection()
            applyAllowPackages(independentAllowPackages)
            KailLog.i(this, TAG, "cellOnly: inject staged, cell towers pushed, GNSS skipped")
            return
        }

        // Prefer the FakeLocation binder if the inject brought it up.
        val svc = resolveMockLocService()
        if (svc != null) {
            runCatching {
                // Clear any scoped block-list a previous cell-only session may
                // have installed, otherwise normal location mocking would be
                // silently blocked by the "abhf|*" rule.
                svc.setSafeApps(null)
                svc.startMockLocation()
                svc.setIntervalTimeout(currentLocationUpdateIntervalMs())
                pushLocationToInjection()
                applyAllowPackages(independentAllowPackages)
                KailLog.i(this, TAG, "FakeLocation mock-location active lat=$mCurLat lng=$mCurLng")
            }.onFailure { KailLog.e(this, TAG, "startMockLocation (binder): ${it.message}") }
            return
        }

        // Fallback: register GPS + NETWORK test providers and push an initial fix.
        runCatching {
            mMockLocationProvider.ensureProviders()
            pushLocationToInjection()
            KailLog.i(this, TAG, "Test-provider mock-location active lat=$mCurLat lng=$mCurLng")
        }.onFailure { KailLog.e(this, TAG, "ensureProviders: ${it.message}") }
    }

    private fun stopMockLocationOnInjection() {
        runCatching { mockLocService?.stopMockLocation() }
        runCatching { mockLocService?.setMockGpsStatus(false) }
        runCatching { mockLocService?.setMockCells(null) }
        // Clear any scoped block-list left over from cell-only mode so the next
        // normal location session isn't silently blocked.
        runCatching { mockLocService?.setSafeApps(null) }
        // Clear the independent-mode allow-list so a later "mock all apps"
        // session isn't accidentally restricted to stale target packages.
        runCatching { mockLocService?.setAllowMockPackages(null) }
        runCatching { stopWifiMockOnInjection() }
        fakelocStartCalled = false
        runCatching { mMockLocationProvider.cleanup() }
            .onFailure { KailLog.e(this, TAG, "cleanup providers: ${it.message}") }
    }

    // ------------------------------------------------------------------
    // WiFi spoofing bridge (service_mock_wifi)
    //
    // IMockWifiManager ships with only a Binder.Stub (no client-side Proxy),
    // so the controller process talks to the registered service via raw
    // Parcel transactions. Transaction codes mirror the Stub.onTransact
    // switch in IMockWifiManager:
    //   2  startMockWifi()
    //   3  stopMockWifi()
    //   9  setMockWifiNetworks(List<MockWifiNetwork>)
    // ------------------------------------------------------------------

    /**
     * True if any spoofing surface is still active (location loop, WiFi, or
     * cell). Used by the granular stop_wifi / stop_cell control actions to
     * decide whether the whole foreground service can shut down.
     */
    private fun isAnyMockActive(): Boolean {
        if (locationLoopStarted) return true
        if (pendingWifiList.isNotEmpty()) return true
        if (pendingCellList.isNotEmpty()) return true
        return false
    }

    private fun resolveMockWifiBinder(): IBinder? {
        repeat(10) {
            val binder = runCatching {
                ServiceManagerBridge.getService(ClassLoader.getSystemClassLoader(), "service_mock_wifi")
            }.getOrNull()
            if (binder != null) return binder
            runCatching { Thread.sleep(300) }
        }
        return null
    }

    /** Maps a UI [com.kail.location.models.WifiInfo] onto a parceled MockWifiNetwork. */
    private fun writeMockWifiNetwork(dest: Parcel, wifi: com.kail.location.models.WifiInfo) {
        // Field order MUST match MockWifiNetwork.writeToParcel:
        // id, networkType, ssid, bssid, username, password, rssi, linkSpeed,
        // frequency, capabilities.
        dest.writeString(if (wifi.id.isNotEmpty()) wifi.id else System.currentTimeMillis().toString())
        dest.writeString("WIFI")
        dest.writeString(wifi.ssid)
        dest.writeString(wifi.bssid)
        dest.writeString(null) // username
        dest.writeString(null) // password
        dest.writeInt(wifi.rssi)
        dest.writeInt(wifi.linkSpeed)
        dest.writeInt(wifi.frequency)
        dest.writeString(wifi.capabilities)
    }

    private fun applyWifiMockOnInjection() {
        if (pendingWifiList.isEmpty()) {
            KailLog.w(this, TAG, "applyWifiMockOnInjection: no WiFi networks selected")
            return
        }
        val binder = resolveMockWifiBinder()
        if (binder == null) {
            KailLog.w(this, TAG, "service_mock_wifi binder not online yet")
            return
        }
        // setMockWifiNetworks(List<MockWifiNetwork>) — write as a typed list.
        runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(WIFI_DESCRIPTOR)
                data.writeInt(pendingWifiList.size)
                for (wifi in pendingWifiList) {
                    // Non-null typed-list element marker, then the object body.
                    data.writeInt(1)
                    writeMockWifiNetwork(data, wifi)
                }
                binder.transact(TXN_SET_MOCK_WIFI_NETWORKS, data, reply, 0)
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }.onFailure { KailLog.e(this, TAG, "setMockWifiNetworks: ${it.message}") }

        // startMockWifi()
        runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(WIFI_DESCRIPTOR)
                binder.transact(TXN_START_MOCK_WIFI, data, reply, 0)
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }.onFailure { KailLog.e(this, TAG, "startMockWifi: ${it.message}") }

        KailLog.i(this, TAG, "WiFi mock active: ${pendingWifiList.size} networks")
    }

    private fun stopWifiMockOnInjection() {
        val binder = resolveMockWifiBinder() ?: return
        runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(WIFI_DESCRIPTOR)
                binder.transact(TXN_STOP_MOCK_WIFI, data, reply, 0)
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }.onFailure { KailLog.e(this, TAG, "stopMockWifi: ${it.message}") }
    }

    // ------------------------------------------------------------------
    // Cell-tower spoofing bridge (service_mock_location.setMockCells)
    //
    // TelephonyRegistryHook only feeds the spoofed towers when
    // MockLocationHookManager.isMocking() is true, so cell mode arms location
    // mocking + seeds a base fix from the first cell's coordinates, but keeps
    // GNSS satellite mocking OFF and never runs the moving-location loop.
    // ------------------------------------------------------------------

    /** Builds an inject-side CellTowerInfo via Parcel (no public constructor). */
    private fun buildCellTowerInfo(cell: com.kail.location.models.CellInfo): com.kail.location.inject.fakelocation.model.CellTowerInfo? =
        runCatching {
            val p = Parcel.obtain()
            try {
                // Field order MUST match CellTowerInfo(Parcel):
                // radioType, mcc, mnc, lac, psc, cellId, latitude, longitude, accuracy.
                p.writeString(cell.networkType)
                p.writeInt(cell.mcc)
                p.writeInt(cell.mnc)
                p.writeInt(cell.lac)
                p.writeInt(cell.psc)
                p.writeLong(cell.cid)
                p.writeDouble(cell.latitude)
                p.writeDouble(cell.longitude)
                p.writeFloat(cell.radius)
                p.setDataPosition(0)
                com.kail.location.inject.fakelocation.model.CellTowerInfo.CREATOR.createFromParcel(p)
            } finally {
                p.recycle()
            }
        }.getOrNull()

    private fun applyCellMockOnInjection() {
        if (pendingCellList.isEmpty()) {
            KailLog.w(this, TAG, "applyCellMockOnInjection: no cells selected")
            return
        }
        val svc = resolveMockLocServiceWithRetry()
        if (svc == null) {
            KailLog.w(this, TAG, "service_mock_location binder not online yet (cell)")
            return
        }
        val towers = ArrayList<com.kail.location.inject.fakelocation.model.CellTowerInfo>()
        for (cell in pendingCellList) buildCellTowerInfo(cell)?.let { towers.add(it) }
        if (towers.isEmpty()) {
            KailLog.w(this, TAG, "applyCellMockOnInjection: failed to build any CellTowerInfo")
            return
        }
        runCatching {
            // The cell-tower hooks (TelephonyRegistryHook / PhoneInterfaceManagerHook)
            // only fire while MockLocationHookManager.isMocking() is true, so cell
            // mode has to flip the master mock switch on. To avoid ALSO faking the
            // device location (issue: "单独开启基站模拟位置也会模拟"), we install a
            // scoped block-list via setSafeApps: scope letters map to features
            //   a=普通定位  b=路线  h=摇杆  f=GNSS卫星  e=基站
            // ScopedListFilter treats safeApps as a BLOCK list, so "abhf|*" blocks
            // every location/GNSS scope for all packages while leaving "e" (cells)
            // untouched. Result: cells are spoofed, location/GNSS are not.
            svc.setSafeApps(arrayListOf("abhf|*"))
            svc.setMockGpsStatus(false)
            // isMocking must be true for the telephony hook; the scoped block-list
            // above prevents the position itself from being handed to apps.
            svc.startMockLocation()
            svc.setIntervalTimeout(currentLocationUpdateIntervalMs())
            // Seed a base fix so getMockLocation() is non-null — CellInfoFactory and
            // the onCellLocationChanged bundle read it for the base-station lat/lng.
            // It is NOT delivered to apps as a position because scope "a"/"h" are
            // blocked above. Use the first cell with valid coordinates.
            val anchor = pendingCellList.firstOrNull { it.latitude != 0.0 || it.longitude != 0.0 }
            val baseLat = anchor?.latitude ?: mCurLat
            val baseLng = anchor?.longitude ?: mCurLng
            val loc = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = baseLat
                longitude = baseLng
                altitude = mCurAlt
                accuracy = 25.0f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                extras = Bundle().apply { putString("from", "loc") }
            }
            svc.setMockLocation(loc)
            svc.setMockCells(towers)
            KailLog.i(this, TAG, "Cell mock active: ${towers.size} towers, anchor=$baseLat,$baseLng (location scope blocked)")
        }.onFailure { KailLog.e(this, TAG, "applyCellMockOnInjection: ${it.message}") }

        // The synchronous cell-pull APIs (TelephonyManager.getAllCellInfo /
        // getCellLocation) are served by com.android.phone, a separate process
        // that the system_server-only inject never touches. Inject the app-hook
        // loader into it so PhoneInterfaceManagerHook installs there too;
        // otherwise apps that poll cells directly (rather than via a
        // PhoneStateListener push) keep seeing the real towers.
        Thread({
            runCatching { RootDeployer.injectAppProcess("com.android.phone") }
                .onFailure { KailLog.e(this, TAG, "inject com.android.phone: ${it.message}") }
        }, "ServiceGoRootPhoneInject").start()
    }

    private var fakelocStartCalled: Boolean = false


    private fun pushLocationToInjection() {
        // Never push location / enable GNSS mock in WiFi-only or cell-only
        // mode — those modes spoof only WiFi scan results / cell towers.
        if (modeWifiOnly || modeCellOnly) return

        // Path 1: FakeLocation binder.
        // Re-resolve every push so that updates start flowing through the
        // FakeLocation injection layer as soon as kail_inject finishes,
        // even if the location loop began before the binder was online.
        val svc = mockLocService ?: resolveMockLocService()
        if (svc != null) {
            // First time we got the binder, tell it to start mocking.
            if (!fakelocStartCalled) {
                fakelocStartCalled = true
                runCatching {
                    svc.startMockLocation()
                    svc.setIntervalTimeout(currentLocationUpdateIntervalMs())
                    // Enable GNSS / IGnssStatusListener mocking so consumers
                    // see synthetic SV-status events alongside the spoofed
                    // location. Without this flag the GnssStatusCallback*
                    // proxies fall through to the real listener and leak
                    // the actual constellation.
                    svc.setMockGpsStatus(true)
                    KailLog.i(this, TAG, "FakeLocation startMockLocation invoked")
                }.onFailure { KailLog.e(this, TAG, "startMockLocation (binder): ${it.message}") }
            }
            runCatching {
                val loc = Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = mCurLat
                    longitude = mCurLng
                    altitude = mCurAlt
                    bearing = mCurBea
                    speed = mSpeed.toFloat()
                    accuracy = 1.0f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    extras = Bundle().apply { putString("from", "rocker") }
                }
                svc.setMockLocation(loc)
            }.onFailure { KailLog.e(this, TAG, "setMockLocation (binder): ${it.message}") }
            return
        }

        // Path 2: test-provider fallback.
        runCatching {
            mMockLocationProvider.setLocation(mCurLat, mCurLng, mCurAlt, mCurBea, mSpeed, isStop)
        }.onFailure { KailLog.e(this, TAG, "setLocation (provider): ${it.message}") }
    }

    // ------------------------------------------------------------------
    // Location loop / status broadcasts
    // ------------------------------------------------------------------

    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS_CHANGED).apply {
            putExtra(EXTRA_IS_SIMULATING, locationLoopStarted && !isStop)
            putExtra(EXTRA_IS_PAUSED, isStop)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastStatusStopped() {
        val intent = Intent(ACTION_STATUS_CHANGED).apply {
            putExtra(EXTRA_IS_SIMULATING, false)
            putExtra(EXTRA_IS_PAUSED, false)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun initGoLocation() {
        mLocHandlerThread = HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_BACKGROUND)
        mLocHandlerThread.start()
        mLocHandler = object : Handler(mLocHandlerThread.looper) {
            override fun handleMessage(msg: Message) {
                try {
                    if (!isStop) {
                        if (mRouteEngine.isActive) {
                            val speedForStep = if (speedFluctuation) {
                                GeoPredict.randomInRangeWithMean(mSpeed * 0.5, mSpeed * 1.5, mSpeed)
                            } else {
                                mSpeed
                            }
                            val intervalMs = currentLocationUpdateIntervalMs()
                            mRouteEngine.advance(speedForStep * (intervalMs / 1000.0))
                            mCurLng = mRouteEngine.currentLng
                            mCurLat = mRouteEngine.currentLat
                            mCurBea = mRouteEngine.currentBea
                            updateJoystickStatus()
                        }
                        pushLocationToInjection()
                    }
                    if (!isStop) {
                        sendEmptyMessageDelayed(HANDLER_MSG_ID, currentLocationUpdateIntervalMs())
                    }
                } catch (e: InterruptedException) {
                    KailLog.e(this@ServiceGoRoot, TAG, "loop interrupted: ${e.message}")
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    KailLog.e(this@ServiceGoRoot, TAG, "loop: ${e.message}")
                    if (!isStop) sendEmptyMessageDelayed(HANDLER_MSG_ID, currentLocationUpdateIntervalMs())
                }
            }
        }
    }

    private fun currentLocationUpdateIntervalMs(): Long {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return (prefs.getString("setting_report_interval", DEFAULT_LOCATION_UPDATE_INTERVAL_MS.toString())
            ?.toLongOrNull() ?: DEFAULT_LOCATION_UPDATE_INTERVAL_MS).coerceAtLeast(0L)
    }

    private fun startLocationLoop() {
        if (!this::mLocHandler.isInitialized) return
        isStop = false
        if (locationLoopStarted) return
        locationLoopStarted = true
        mLocHandler.sendEmptyMessage(HANDLER_MSG_ID)
        broadcastStatus()
    }

    private fun updateJoystickStatus() {
        if (this::mJoystickManager.isInitialized && mRouteEngine.isActive) {
            val status = mRouteEngine.buildStatusString()
            if (status != null) {
                mJoystickManager.updateRouteStatus(mRouteEngine.progressRatio, status.first, status.second)
            }
        }
    }

    // ------------------------------------------------------------------
    // Joystick wiring
    // ------------------------------------------------------------------

    private fun initJoyStick() {
        mJoystickViewModel = JoystickViewModel(application)
        mJoystickManager = JoystickWindowManager(this, mJoystickViewModel, object : JoystickViewModel.ActionListener {
            override fun onMoveInfo(speed: Double, disLng: Double, disLat: Double, angle: Double) {
                mSpeed = speed
                val next = GeoPredict.nextByDisplacementKm(mCurLng, mCurLat, disLng, disLat)
                mCurLng = next.first
                mCurLat = next.second
                mCurBea = angle.toFloat()
            }

            override fun onPositionInfo(lng: Double, lat: Double, alt: Double) {
                mCurLng = lng
                mCurLat = lat
                mCurAlt = alt
            }

            override fun onRouteControl(action: String) {
                val intent = Intent(this@ServiceGoRoot, ServiceGoRoot::class.java)
                intent.putExtra(EXTRA_CONTROL_ACTION, action)
                startService(intent)
            }

            override fun onRouteSeek(progress: Float) {
                val intent = Intent(this@ServiceGoRoot, ServiceGoRoot::class.java)
                intent.putExtra(EXTRA_CONTROL_ACTION, CONTROL_SEEK)
                intent.putExtra(EXTRA_SEEK_RATIO, progress)
                startService(intent)
            }

            override fun onRouteSpeedChange(speed: Double) {
                mSpeed = speed / 3.6
            }
        })
    }

    // ------------------------------------------------------------------
    // Sensor offset probing (mirrors ServiceGoXposed)
    // ------------------------------------------------------------------

    private fun runSuCommand(cmd: String): String =
        runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            out
        }.getOrDefault("")

    private fun getOffsetsFromSystem(): Pair<String, String> {
        val candidates = listOf("toybox readelf", "readelf", "/system/bin/toybox readelf")
        for (cmd in candidates) {
            try {
                val test = runSuCommand("$cmd 2>&1")
                if (test.contains("not found") || (test.isEmpty() && !cmd.startsWith("/"))) continue

                val sensorOut = runSuCommand("$cmd -Ws /system/lib64/libsensor.so 2>/dev/null | grep _ZN7android7BitTube11sendObjects")
                val sensorServiceOut = runSuCommand("$cmd -Ws /system/lib64/libsensorservice.so 2>/dev/null | grep '_ZN7android8hardware7sensors14implementation20convertToSensorEvent[^4V1]'")
                val sensorServiceV1Out = runSuCommand("$cmd -Ws /system/lib64/libsensorservice.so 2>/dev/null | grep '_ZN7android8hardware7sensors4V1_014implementation20convertToSensorEvent'")

                if (sensorOut.isNotEmpty()) {
                    val sensorOffset = parseReadelfOffset(sensorOut)
                    val sensorServiceOffset = when {
                        sensorServiceOut.isNotEmpty() -> parseReadelfOffset(sensorServiceOut)
                        sensorServiceV1Out.isNotEmpty() -> parseReadelfOffset(sensorServiceV1Out)
                        else -> ""
                    }
                    if (sensorOffset.isNotEmpty() && sensorServiceOffset.isNotEmpty()) {
                        return Pair(
                            if (sensorOffset.startsWith("0x")) sensorOffset else "0x$sensorOffset",
                            if (sensorServiceOffset.startsWith("0x")) sensorServiceOffset else "0x$sensorServiceOffset"
                        )
                    }
                }
            } catch (_: Exception) { /* try next candidate */ }
        }
        KailLog.w(this, TAG, "readelf unavailable; sensor offsets unknown")
        return Pair("0x0", "0x0")
    }

    private fun parseReadelfOffset(output: String): String {
        val trimmed = output.trim().lines().firstOrNull()?.trim() ?: return ""
        val parts = trimmed.split(Regex("\\s+"))
        return parts.firstOrNull { it.matches(Regex("^[0-9a-fA-F]{8,16}$")) } ?: ""
    }

    private fun parseHexOffset(s: String): Long {
        val v = s.removePrefix("0x").removePrefix("0X")
        return v.toLongOrNull(16) ?: 0L
    }

    inner class ServiceGoRootBinder : Binder() {
        fun getService(): ServiceGoRoot = this@ServiceGoRoot
    }
}
