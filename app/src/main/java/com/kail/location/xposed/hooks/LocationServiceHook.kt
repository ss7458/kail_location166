@file:Suppress("KotlinConstantConditions")
@file:OptIn(ExperimentalUuidApi::class)

package com.kail.location.xposed.hooks

import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import com.kail.location.xposed.base.BaseLocationHook
import com.kail.location.xposed.core.KailCommandHandler
import com.kail.location.xposed.utils.FakeLoc
import com.kail.location.xposed.utils.BinderUtils
import com.kail.location.utils.KailLog
import com.kail.location.xposed.utils.afterHook
import com.kail.location.xposed.utils.beforeHook
import com.kail.location.xposed.utils.hookAllMethods
import com.kail.location.xposed.utils.onceHookAllMethod
import android.os.Handler
import android.os.HandlerThread
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi

private const val MAX_SATELLITES = 35 // 北斗系统实际可见卫星数上限

// 载噪比范围，考虑不同轨道类型
private const val GEO_MIN_CN0 = 30.0f  // GEO卫星信号较强
private const val GEO_MAX_CN0 = 45.0f
private const val IGSO_MIN_CN0 = 25.0f
private const val IGSO_MAX_CN0 = 42.0f
private const val MEO_MIN_CN0 = 20.0f  // MEO卫星信号相对较弱
private const val MEO_MAX_CN0 = 40.0f

// 北斗频率
private const val BDS_B1I_FREQ = 1561.098f // MHz
private const val BDS_B2I_FREQ = 1207.140f
private const val BDS_B3I_FREQ = 1268.520f

private val satelliteList = listOf(
    BDSSatellite(1, OrbitType.GEO),
    BDSSatellite(2, OrbitType.GEO),
    BDSSatellite(3, OrbitType.GEO),
    BDSSatellite(4, OrbitType.GEO),
    BDSSatellite(5, OrbitType.GEO),
    BDSSatellite(6, OrbitType.IGSO),
    BDSSatellite(7, OrbitType.IGSO),
    BDSSatellite(8, OrbitType.IGSO),
    BDSSatellite(9, OrbitType.IGSO),
    BDSSatellite(10, OrbitType.IGSO),
    BDSSatellite(11, OrbitType.MEO),
    BDSSatellite(12, OrbitType.MEO),
    BDSSatellite(13, OrbitType.IGSO),
    BDSSatellite(14, OrbitType.MEO),
    BDSSatellite(16, OrbitType.IGSO),
    BDSSatellite(19, OrbitType.MEO),
    BDSSatellite(20, OrbitType.MEO),
    BDSSatellite(21, OrbitType.MEO),
    BDSSatellite(22, OrbitType.MEO),
    BDSSatellite(23, OrbitType.MEO),
    BDSSatellite(24, OrbitType.MEO),
    BDSSatellite(25, OrbitType.MEO),
    BDSSatellite(26, OrbitType.MEO),
    BDSSatellite(27, OrbitType.MEO),
    BDSSatellite(28, OrbitType.MEO),
    BDSSatellite(29, OrbitType.MEO),
    BDSSatellite(30, OrbitType.MEO),
    BDSSatellite(31, OrbitType.IGSO),
    BDSSatellite(32, OrbitType.MEO),
    BDSSatellite(33, OrbitType.MEO),
    BDSSatellite(34, OrbitType.MEO),
    BDSSatellite(35, OrbitType.MEO),
    BDSSatellite(36, OrbitType.MEO),
    BDSSatellite(37, OrbitType.MEO),
    BDSSatellite(38, OrbitType.IGSO),
    BDSSatellite(39, OrbitType.IGSO),
    BDSSatellite(40, OrbitType.IGSO),
    BDSSatellite(41, OrbitType.MEO),
    BDSSatellite(42, OrbitType.MEO),
    BDSSatellite(43, OrbitType.MEO),
    BDSSatellite(44, OrbitType.MEO),
    BDSSatellite(45, OrbitType.MEO),
    BDSSatellite(46, OrbitType.MEO),
    BDSSatellite(56, OrbitType.IGSO),
    BDSSatellite(57, OrbitType.MEO),
    BDSSatellite(58, OrbitType.MEO),
    BDSSatellite(59, OrbitType.GEO),
    BDSSatellite(60, OrbitType.GEO),
    BDSSatellite(61, OrbitType.GEO),
    BDSSatellite(62, OrbitType.GEO),
    BDSSatellite(48, OrbitType.MEO),
    BDSSatellite(50, OrbitType.MEO),
    BDSSatellite(47, OrbitType.MEO),
    BDSSatellite(49, OrbitType.MEO),
//    BDSSatellite(130, OrbitType.GEO),
//    BDSSatellite(143, OrbitType.GEO),
//    BDSSatellite(144, OrbitType.GEO),
)

object GnssFlags {
    // 基本标志位
    const val SVID_FLAGS_NONE = 0
    const val SVID_FLAGS_HAS_EPHEMERIS_DATA = (1 shl 0)
    const val SVID_FLAGS_HAS_ALMANAC_DATA = (1 shl 1)
    const val SVID_FLAGS_USED_IN_FIX = (1 shl 2)
    const val SVID_FLAGS_HAS_CARRIER_FREQUENCY = (1 shl 3)
    const val SVID_FLAGS_HAS_BASEBAND_CN0 = (1 shl 4)

    // 位移宽度
    const val SVID_SHIFT_WIDTH = 12
    const val CONSTELLATION_TYPE_SHIFT_WIDTH = 8
    const val CONSTELLATION_TYPE_MASK = 0xf

    // 星座类型（与 Android GnssStatus.CONSTELLATION_ 常量对应）
    const val CONSTELLATION_GPS = 1
    const val CONSTELLATION_SBAS = 2
    const val CONSTELLATION_GLONASS = 3
    const val CONSTELLATION_QZSS = 4
    const val CONSTELLATION_BEIDOU = 5
    const val CONSTELLATION_GALILEO = 6
    const val CONSTELLATION_IRNSS = 7
}

sealed class OrbitType(val minCn0: Float, val maxCn0: Float, val elevationRange: ClosedRange<Float>) {
    object GEO : OrbitType(GEO_MIN_CN0, GEO_MAX_CN0, 35f..50f)
    object IGSO : OrbitType(IGSO_MIN_CN0, IGSO_MAX_CN0, 20f..60f)
    object MEO : OrbitType(MEO_MIN_CN0, MEO_MAX_CN0, 0f..90f)
}

data class BDSSatellite(
    val prn: Int,
    val type: OrbitType,
)

data class MockGnssData(
    val svCount: Int,
    val svidWithFlags: IntArray,
    val cn0s: FloatArray,
    val elevations: FloatArray,
    val azimuths: FloatArray,
    val carrierFreqs: FloatArray
)

private fun buildMockGnssData(): MockGnssData {
    val svCount = Random.nextInt(FakeLoc.minSatellites, MAX_SATELLITES + 1)
    val svidWithFlags = IntArray(svCount)
    val cn0s = FloatArray(svCount)
    val elevations = FloatArray(svCount)
    val azimuths = FloatArray(svCount)
    val carrierFreqs = FloatArray(svCount)

    val selectedSatellites = satelliteList.shuffled().take(svCount)

    selectedSatellites.forEachIndexed { index, sat ->
        val hasEphemeris = Random.nextFloat() > 0.1f
        val hasAlmanac = Random.nextFloat() > 0.05f
        val usedInFix = Random.nextFloat() > 0.3f
        val hasCarrierFreq = true
        val hasBasebandCn0 = true

        var flags = GnssFlags.SVID_FLAGS_NONE
        if (hasEphemeris) flags = flags or GnssFlags.SVID_FLAGS_HAS_EPHEMERIS_DATA
        if (hasAlmanac) flags = flags or GnssFlags.SVID_FLAGS_HAS_ALMANAC_DATA
        if (usedInFix) flags = flags or GnssFlags.SVID_FLAGS_USED_IN_FIX
        if (hasCarrierFreq) flags = flags or GnssFlags.SVID_FLAGS_HAS_CARRIER_FREQUENCY
        if (hasBasebandCn0) flags = flags or GnssFlags.SVID_FLAGS_HAS_BASEBAND_CN0

        svidWithFlags[index] = (sat.prn shl GnssFlags.SVID_SHIFT_WIDTH) or
                ((GnssFlags.CONSTELLATION_BEIDOU and GnssFlags.CONSTELLATION_TYPE_MASK) shl GnssFlags.CONSTELLATION_TYPE_SHIFT_WIDTH) or
                flags

        cn0s[index] = when (sat.type) {
            is OrbitType.GEO -> Random.nextFloat(GEO_MIN_CN0, GEO_MAX_CN0)
            is OrbitType.IGSO -> Random.nextFloat(IGSO_MIN_CN0, IGSO_MAX_CN0)
            is OrbitType.MEO -> Random.nextFloat(MEO_MIN_CN0, MEO_MAX_CN0)
        }
        elevations[index] = Random.nextFloat(sat.type.elevationRange.start, sat.type.elevationRange.endInclusive)
        azimuths[index] = Random.nextFloat(0f, 360f)
        carrierFreqs[index] = when (Random.nextInt(3)) {
            0 -> BDS_B1I_FREQ
            1 -> BDS_B2I_FREQ
            else -> BDS_B3I_FREQ
        }
    }

    return MockGnssData(
        svCount = svCount,
        svidWithFlags = svidWithFlags,
        cn0s = cn0s,
        elevations = elevations,
        azimuths = azimuths,
        carrierFreqs = carrierFreqs
    )
}

private fun buildGnssStatusObject(mockGps: MockGnssData): Any? {
    return runCatching {
        val cGnssStatus = Class.forName("android.location.GnssStatus")
        val constructor = cGnssStatus.declaredConstructors.firstOrNull { ctor ->
            when (ctor.parameterTypes.size) {
                5, 6, 7, 8 -> {
                    ctor.parameterTypes.getOrNull(0) == Int::class.javaPrimitiveType &&
                    ctor.parameterTypes.getOrNull(1) == IntArray::class.java &&
                    ctor.parameterTypes.getOrNull(2) == FloatArray::class.java &&
                    ctor.parameterTypes.getOrNull(3) == FloatArray::class.java &&
                    ctor.parameterTypes.getOrNull(4) == FloatArray::class.java
                }
                else -> false
            }
        }?.also { it.isAccessible = true }

        if (constructor == null) {
            KailLog.e(null, "Kail_Xposed", "GnssStatus constructor not found")
            return null
        }

        val args = mutableListOf<Any>()
        args.add(mockGps.svCount)
        args.add(mockGps.svidWithFlags)
        args.add(mockGps.cn0s)
        args.add(mockGps.elevations)
        args.add(mockGps.azimuths)

        when (constructor.parameterTypes.size) {
            6 -> args.add(mockGps.carrierFreqs)
            7 -> {
                args.add(mockGps.carrierFreqs)
                args.add(FloatArray(mockGps.svCount) { mockGps.cn0s[it] - Random.nextFloat(2f, 5f) })
            }
            8 -> {
                args.add(mockGps.carrierFreqs)
                args.add(FloatArray(mockGps.svCount) { mockGps.cn0s[it] - Random.nextFloat(2f, 5f) })
                args.add(FloatArray(mockGps.svCount))
            }
        }

        constructor.newInstance(*args.toTypedArray())
    }.onFailure {
        KailLog.e(null, "Kail_Xposed", "buildGnssStatusObject failed: ${it.message}")
    }.getOrNull()
}

private fun pushMockGnssToListener(listener: Any) {
    val mockGps = buildMockGnssData()
    val methods = listener.javaClass.declaredMethods.filter { it.name == "onSvStatusChanged" }

    for (m in methods) {
        m.isAccessible = true
        when (m.parameterTypes.size) {
            5 -> m.invoke(listener, mockGps.svCount, mockGps.svidWithFlags, mockGps.cn0s, mockGps.elevations, mockGps.azimuths)
            6 -> m.invoke(listener, mockGps.svCount, mockGps.svidWithFlags, mockGps.cn0s, mockGps.elevations, mockGps.azimuths, mockGps.carrierFreqs)
            7 -> {
                val basebandCn0s = FloatArray(mockGps.svCount) { mockGps.cn0s[it] - Random.nextFloat(2f, 5f) }
                m.invoke(listener, mockGps.svCount, mockGps.svidWithFlags, mockGps.cn0s, mockGps.elevations, mockGps.azimuths, mockGps.carrierFreqs, basebandCn0s)
            }
            1 -> {
                val gnssStatus = buildGnssStatusObject(mockGps)
                if (gnssStatus != null) {
                    m.invoke(listener, gnssStatus)
                }
            }
            else -> continue
        }
        if (FakeLoc.enableDebugLog) {
            KailLog.d(null, "Kail_Xposed", "Pushed GNSS status to ${listener.javaClass.name} via ${m.name}(${m.parameterTypes.size} args)")
        }
        break
    }
}

private val activeGnssListeners = Collections.synchronizedSet(HashSet<Any>())
private var gnssPushStarted = false

private val gnssPushHandler: Handler by lazy {
    val thread = HandlerThread("KailGnssPusher").apply { start() }
    Handler(thread.looper)
}

private val gnssPushRunnable = object : Runnable {
    override fun run() {
        if (!FakeLoc.enable || !FakeLoc.enableMockGnss) {
            gnssPushHandler.postDelayed(this, 1000)
            return
        }

        val listeners = activeGnssListeners.toList()
        for (listener in listeners) {
            runCatching {
                pushMockGnssToListener(listener)
            }.onFailure {
                activeGnssListeners.remove(listener)
                if (FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "Removed dead GNSS listener: ${it.message}")
                }
            }
        }

        gnssPushHandler.postDelayed(this, 1000)
    }
}

internal object LocationServiceHook: BaseLocationHook() {
    val locationListeners = LinkedBlockingQueue<Pair<String, IInterface>>()
    private var pullbackPushStarted = false
    private val pullbackPushHandler: Handler by lazy {
        val thread = HandlerThread("KailPullbackPusher").apply { start() }
        Handler(thread.looper)
    }
    private val pullbackPushRunnable = object : Runnable {
        override fun run() {
            if (FakeLoc.enable && FakeLoc.loopBroadcastLocation && locationListeners.isNotEmpty()) {
                kotlin.runCatching {
                    callOnLocationChanged()
                }.onFailure {
                    KailLog.e(null, "Kail_Xposed", "Pullback broadcast failed: ${it.message}")
                }
            }
            pullbackPushHandler.postDelayed(this, FakeLoc.reportIntervalMs.coerceAtLeast(200).toLong())
        }
    }

    // A random command is generated to prevent some apps from detecting Kail
    operator fun invoke(classLoader: ClassLoader) {
        val cLocationManagerService = XposedHelpers.findClassIfExists("com.android.server.location.LocationManagerService", classLoader)
        if (cLocationManagerService == null) {
            hookLocationManagerServiceV2(classLoader)
        } else {
            onService(cLocationManagerService)
        }
        ensurePullbackPushStarted()
    }

    fun onService(cILocationManager: Class<*>) {
        // Got instance of ILocationManager.Stub here, you can hook it
        // Not directly Class.forName because of this thing, it can't be reflected, even if I'm system_server?!?!

        if (FakeLoc.enableDebugLog) {
            KailLog.d(null, "Kail_Xposed", "ILocationManager.Stub: class = $cILocationManager")
        }

        if(cILocationManager.hookAllMethods("getLastLocation", afterHook {
                // android 7.0.0 ~ 10.0.0
                // Location getLastLocation(in LocationRequest request, String packageName);
                // android 11.0.0
                // Location getLastLocation(in LocationRequest request, String packageName, String featureId);
                // android 12.0.0 ~ 15.0.0
                // @nullable Location getLastLocation(String provider, in LastLocationRequest request, String packageName, @nullable String attributionTag);
                // Why are there so... I'm really speechless

                // Virtual Coordinate: Instantly update the latest virtual coordinates
                // Roulette Move: Each request moves a certain distance
                // Route Simulation: Move according to a preset route
                //val uid = FqlUtils.getCallerUid()
                // Determine whether it is an app that needs a hook
                if (!FakeLoc.enable) return@afterHook

                // It can't be null, because I'm judging in the previous step
                val location = result as? Location ?: Location("gps")

                result = injectLocation(location)

                if(FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "getLastLocation: injected! $result")
                }
        }).isEmpty()) {
            KailLog.e(null, "Kail_Xposed", "hook getLastLocation failed")
        }

        // android 12 and later remove `requestLocationUpdates`
        cILocationManager.hookAllMethods("requestLocationUpdates", beforeHook {
            // android 7.0.0
            // void requestLocationUpdates(in LocationRequest request, in ILocationListener listener, String packageName);
            //
            // oneway interface ILocationListener
            //{
            //    void onLocationChanged(in Location location);
            //    void onStatusChanged(String provider, int status, in Bundle extras);
            //    void onProviderEnabled(String provider);
            //    void onProviderDisabled(String provider);
            //}
            //
            // android 7.1.1 ~ 9.0.0
            // void requestLocationUpdates(in LocationRequest request, in ILocationListener listener,
            //            in PendingIntent intent, String packageName);
            //
            // oneway interface ILocationListener
            //{
            //    void onLocationChanged(in Location location);
            //    void onStatusChanged(String provider, int status, in Bundle extras);
            //    void onProviderEnabled(String provider);
            //    void onProviderDisabled(String provider);
            //
            // android 10.0.0
            // oneway interface ILocationListener
            //{
            //    @UnsupportedAppUsage
            //    void onLocationChanged(in Location location);
            //    @UnsupportedAppUsage
            //    void onProviderEnabled(String provider);
            //    @UnsupportedAppUsage
            //    void onProviderDisabled(String provider);
            //    // --- deprecated ---
            //    @UnsupportedAppUsage
            //    void onStatusChanged(String provider, int status, in Bundle extras);
            //}
            //
            // android 11.0.0
            // void requestLocationUpdates(in LocationRequest request, in ILocationListener listener,
            //            in PendingIntent intent, String packageName, String featureId, String listenerId);
            //
            // oneway interface ILocationListener
            //{
            //    @UnsupportedAppUsage
            //    void onLocationChanged(in Location location);
            //    @UnsupportedAppUsage
            //    void onProviderEnabled(String provider);
            //    @UnsupportedAppUsage
            //    void onProviderDisabled(String provider);
            //    // called when the listener is removed from the server side; no further callbacks are expected
            //    void onRemoved();
            //}
            // android 12 and later
            // remove this method
            val provider = kotlin.runCatching {
                XposedHelpers.callMethod(args[0], "getProvider") as? String
            }.getOrNull() ?: "gps"

            val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                KailLog.e(null, "Kail_Xposed", "requestLocationUpdates: listener is null: $method")
                return@beforeHook
            }

            if(FakeLoc.enableDebugLog) {
                KailLog.d(null, "Kail_Xposed", "requestLocationUpdates: injected! $listener")
            }

            addLocationListenerInner(provider, listener)

            if (FakeLoc.disableRegisterLocationListener || FakeLoc.enable) {
                result = null
                return@beforeHook
            }

            if (FakeLoc.disableFusedLocation && provider == "fused") {
                result = null
                return@beforeHook
            }
        })
        cILocationManager.hookAllMethods("removeUpdates", afterHook {
            val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                KailLog.e(null, "Kail_Xposed", "removeUpdates: listener is null: $method")
                return@afterHook
            }
            if(FakeLoc.enableDebugLog) {
                KailLog.d(null, "Kail_Xposed", "removeUpdates: injected! $listener")
            }

            removeLocationListenerInner(listener)
        })
        cILocationManager.hookAllMethods("registerLocationListener", beforeHook {
            // android 12 ~ android 15
            // void registerLocationListener(String provider, in LocationRequest request, in ILocationListener listener, String packageName, @nullable String attributionTag, String listenerId);
            //
            // oneway interface ILocationListener
            //{
            //    void onLocationChanged(in List<Location> locations, in @nullable IRemoteCallback onCompleteCallback);
            //    void onProviderEnabledChanged(String provider, boolean enabled);
            //    void onFlushComplete(int requestCode);
            //}
            val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                kotlin.runCatching {
                    XposedHelpers.callMethod(args[1], "getProvider") as? String
                }.getOrNull()
            } else {
                args[0] as? String
            } ?: "gps"
            val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                KailLog.e(null, "Kail_Xposed", "registerLocationListener: listener is null: $method")
                return@beforeHook
            }

            if(FakeLoc.enableDebugLog) {
                KailLog.d(null, "Kail_Xposed", "registerLocationListener: injected! $listener, from ${BinderUtils.getUidPackageNames()}")
            }

            addLocationListenerInner(provider, listener)

            if (FakeLoc.disableRegisterLocationListener) {
                result = null
                return@beforeHook
            }

            if (FakeLoc.disableFusedLocation && provider == "fused") {
                result = null
                return@beforeHook
            }
        })
        cILocationManager.hookAllMethods("unregisterLocationListener", afterHook {
            val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                KailLog.e(null, "Kail_Xposed", "unregisterLocationListener: listener is null: $method")
                return@afterHook
            }
            if(FakeLoc.enableDebugLog) {
                KailLog.d(null, "Kail_Xposed", "unregisterLocationListener: injected! $listener")
            }

            removeLocationListenerInner(listener)
        })

        run {
            cILocationManager.hookAllMethods("addGnssBatchingCallback", beforeHook {
                if (hasThrowable() || args.isEmpty() || args[0] == null) return@beforeHook
                val callback = args[0] ?: return@beforeHook
                val classCallback = callback.javaClass

                if(FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "addGnssBatchingCallback: injected!")
                }

                classCallback.onceHookAllMethod("onLocationBatch", beforeHook onLocationBatch@ {
                    if (args.isEmpty()) return@onLocationBatch

                    if (!FakeLoc.enable) {
                        return@onLocationBatch
                    }

                    if (FakeLoc.enableDebugLog) {
                        KailLog.d(null, "Kail_Xposed", "onLocationBatch: injected!")
                    }

                    val location = (args[0] ?: return@onLocationBatch) as Location
                    args[0] = injectLocation(location)
                })
            })
        }

        cILocationManager.hookAllMethods("requestGeofence", beforeHook {
            if (FakeLoc.disableRequestGeofence && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "requestGeofence: injected!")
                }
                result = null
            }
        })
//        cILocationManager.hookAllMethods("removeGeofence", beforeHook {
//        })

        cILocationManager.hookAllMethods("getFromLocation", beforeHook {
            if (FakeLoc.disableGetFromLocation && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "getFromLocation: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("getFromLocationName", beforeHook {
            if (FakeLoc.disableGetFromLocation && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "getFromLocationName: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("addTestProvider", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "addTestProvider: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("removeTestProvider", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "removeTestProvider: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("setTestProviderLocation", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "setTestProviderLocation: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("setTestProviderEnabled", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "setTestProviderEnabled: injected!")
                }
                result = null
            }
        })

        if(XposedBridge.hookAllMethods(cILocationManager, "registerGnssStatusCallback", object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    if(param == null || param.args.isEmpty() || param.args[0] == null) return

                    val callback = param.args[0] ?: return
                    val cIGnssStatusListener = callback.javaClass

                    if(FakeLoc.enableDebugLog) {
                        KailLog.d(null, "Kail_Xposed", "registerGnssStatusCallback: injected! listener=${callback.javaClass.name}")
                    }

                    if (!FakeLoc.enableMockGnss) {
                        return
                    }

                    // 保存 listener 用于主动推送
                    activeGnssListeners.add(callback)
                    if (!gnssPushStarted) {
                        gnssPushStarted = true
                        gnssPushHandler.post(gnssPushRunnable)
                        if (FakeLoc.enableDebugLog) {
                            KailLog.d(null, "Kail_Xposed", "Started active GNSS push")
                        }
                    }

                    if (cIGnssStatusListener.onceHookAllMethod("onSvStatusChanged", beforeHook {
                        if (!FakeLoc.enableMockGnss) return@beforeHook

                        val mockGps = buildMockGnssData()

                        if (args[0] is Int) {
                            args[0] = mockGps.svCount
                            args[1] = mockGps.svidWithFlags
                            args[2] = mockGps.cn0s
                            args[3] = mockGps.elevations
                            args[4] = mockGps.azimuths
                            if (args.size > 5) {
                                args[5] = mockGps.carrierFreqs
                            }
                            if (args.size > 6) {
                                args[6] = FloatArray(mockGps.svCount) {
                                    mockGps.cn0s[it] - Random.nextFloat(2f, 5f)
                                }
                            }
                            return@beforeHook
                        }

                        if (args[0] != null && args[0].javaClass.name == "android.location.GnssStatus") {
                            val gnssStatus = buildGnssStatusObject(mockGps)
                            if (gnssStatus != null) {
                                args[0] = gnssStatus
                            }
                            return@beforeHook
                        }

                        KailLog.e(null, "Kail_Xposed", "onSvStatusChanged: unsupported version: $method")
                    }).isEmpty()) {
                        KailLog.e(null, "Kail_Xposed", "find onSvStatusChanged failed!")
                    }

                    cIGnssStatusListener.onceHookAllMethod("onNmeaReceived", beforeHook {
                        if (FakeLoc.enableDebugLog) {
                            KailLog.d(null, "Kail_Xposed", "onNmeaReceived")
                        }
                        if (FakeLoc.enableMockGnss) result = null
                    })
                }
            }).isEmpty()) {
            KailLog.e(null, "Kail_Xposed", "hook registerGnssStatusCallback failed")
        }

        cILocationManager.hookAllMethods("unregisterGnssStatusCallback", beforeHook {
            if (FakeLoc.enableMockGnss && args.isNotEmpty() && args[0] != null) {
                activeGnssListeners.remove(args[0])
                if (FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "unregisterGnssStatusCallback: removed listener")
                }
            }
        })

        // android 11+
        // @EnforcePermission("LOCATION_HARDWARE")
        // void startGnssBatch(long periodNanos, in ILocationListener listener, String packageName, @nullable String attributionTag, String listenerId);
        //
        // void startGnssBatch(long periodNanos, in ILocationListener listener, String packageName, @nullable String attributionTag, String listenerId);
        cILocationManager.hookAllMethods("startGnssBatch", beforeHook {
            if(FakeLoc.enableDebugLog) {
                KailLog.d(null, "Kail_Xposed", "startGnssBatch: injected!")
            }

            if (FakeLoc.enable && !FakeLoc.enableAGPS && args.size >= 2) {
                val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                    KailLog.e(null, "Kail_Xposed", "startGnssBatch: listener is null: $method")
                    return@beforeHook
                }

                addLocationListenerInner("GnssBatch", listener)
                hookILocationListener(listener)
            }
        })
        cILocationManager.hookAllMethods("stopGnssBatch", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS && args.size >= 2) {
                if(FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "stopGnssBatch: injected!")
                }
            }
            //locationListeners.removeIf { it.first == "GnssBatch" }
        })

        //  void requestListenerFlush(String provider, in ILocationListener listener, int requestCode);
        cILocationManager.hookAllMethods("requestListenerFlush", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS && args.size >= 2) {
                if(FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "requestListenerFlush: injected!")
                }

                val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                    KailLog.e(null, "Kail_Xposed", "requestListenerFlush: listener is null: $method")
                    return@beforeHook
                }

                addLocationListenerInner("gps", listener)

                if (FakeLoc.disableRegisterLocationListener || FakeLoc.enable) {
                    result = null
                }

                hookILocationListener(listener)
            }
        })

//        cILocationManager.hookAllMethods("getBestProvider", beforeHook {
//            if (FakeLoc.enable) {
//                result = "gps"
//            }
//        })
//
//        cILocationManager.hookAllMethods("getAllProviders", afterHook {
//            if(FakeLoc.enable) {
//                result = if (result is List<*>) {
//                    listOf("gps", "passive")
//                } else if (result is Array<*>) {
//                    arrayOf("gps", "passive")
//                } else {
//                    KailLog.e(null, "Kail_Xposed", "getAllProviders: result is not List or Array")
//                    return@afterHook
//                }
//            }
//        })
//
//        cILocationManager.hookAllMethods("getProviders", afterHook {
//            if(FakeLoc.enable) {
//                result = if (result is List<*>) {
//                    listOf("gps", "passive")
//                } else if (result is Array<*>) {
//                    arrayOf("gps", "passive")
//                } else {
//                    KailLog.e(null, "Kail_Xposed", "getProviders: result is not List or Array")
//                    return@afterHook
//                }
//            }
//        })
//
//        cILocationManager.hookAllMethods("hasProvider", beforeHook {
//            if (FakeLoc.enableDebugLog) {
//                KailLog.d(null, "Kail_Xposed", "hasProvider: ${args[0]}")
//            }
//
//            if(FakeLoc.enable) {
//                if (args[0] == "gps") {
//                    result = true
//                } else if (args[0] == "network") {
//                    result = false
//                } else if (args[0] == "fused" && FakeLoc.disableFusedLocation) {
//                    result = false
//                }
//            }
//        })

        cILocationManager.hookAllMethods("getCurrentLocation", beforeHook {
            if (!FakeLoc.enable) return@beforeHook
            val callback = args.firstOrNull { arg ->
                arg != null && arg.javaClass.methods.any { it.name == "onLocation" && it.parameterTypes.size == 1 }
            } ?: return@beforeHook

            if (FakeLoc.enableDebugLog) {
                KailLog.d(null, "Kail_Xposed", "getCurrentLocation: injected!")
            }

            val fakeLocation = injectLocation((FakeLoc.lastLocation ?: Location("gps")).apply {
                if (time <= 0L) time = System.currentTimeMillis()
                if (elapsedRealtimeNanos <= 0L) elapsedRealtimeNanos = System.nanoTime()
            }, false)

            val fulfilled = kotlin.runCatching {
                val onLocation = callback.javaClass.methods.firstOrNull {
                    it.name == "onLocation" && it.parameterTypes.size == 1
                } ?: return@runCatching false
                onLocation.isAccessible = true
                onLocation.invoke(callback, fakeLocation)
                true
            }.getOrDefault(false)

            if (fulfilled || FakeLoc.disableGetCurrentLocation) {
                result = null
                return@beforeHook
            }

            val classCallback = callback.javaClass
            classCallback.onceHookAllMethod("onLocation", beforeHook onLocation@ {
                val location = args[0] as? Location ?: return@onLocation

                if (FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "onLocation(getCurrentLocation): injected!")
                }

                args[0] = injectLocation(location)
            })
        })

        cILocationManager.hookAllMethods("sendExtraCommand", beforeHook {
            if (args.size < 3) return@beforeHook

            val provider = args[0] as String
            val command = args[1] as String
            val outResult = args[2] as? Bundle

            if (FakeLoc.disableFusedLocation && provider == "fused") {
                result = false
                return@beforeHook
            }

            // If the GPS provider is enabled, the GPS provider is disabled
            if(provider == "gps" && FakeLoc.enable) {
                result = false
                return@beforeHook
            }

            if(provider == "LOCATION_BIG_DATA") {
                result = false
                return@beforeHook
            }

            // Not the provider of kail_location, does not process
            if (provider != "kail") {
                if (FakeLoc.enableDebugLog)
                    KailLog.d(null, "Kail_Xposed", "sendExtraCommand provider: $provider, command: $command, result: $result")
                return@beforeHook
            }
            if (outResult == null) return@beforeHook

            if (KailCommandHandler.handle(provider, command, outResult)) {
                result = true
            }
        })

        if(
        // boolean isProviderEnabledForUser(String provider, int userId); from android 9.0.0
            XposedBridge.hookAllMethods(
                cILocationManager,
                "isProviderEnabledForUser",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param == null || param.args.size < 2 || param.args[0] == null) return
                        val provider = param.args[0] as String
                        var userId = param.args[1] as Int
                        if (provider == "kail") {
                            param.result = FakeLoc.enable
                        } else if(provider == "network") {
                            param.result = !FakeLoc.enable
                        } else if (FakeLoc.disableFusedLocation && provider == "fused") {
                            param.result = false
                            return
                        } else {
                            if (FakeLoc.enableDebugLog) {
                                 KailLog.d(null, "Kail_Xposed", "isProviderEnabledForUser provider: $provider, userId: $userId")
                            }
                        }
                    }
                }).isEmpty()
        ) {
            // boolean isProviderEnabled(String provider);
            XposedBridge.hookAllMethods(
                cILocationManager,
                "isProviderEnabled",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param == null || param.args.isEmpty() || param.args[0] == null) return
                        val provider = param.args[0] as String
                        val userId = BinderUtils.getCallerUid()
                        if (provider == "kail") {
                            param.result = FakeLoc.enable
                        } else if(provider == "network") {
                            param.result = !FakeLoc.enable
                        } else if (FakeLoc.disableFusedLocation && provider == "fused") {
                            param.result = false
                            return
                        }
                    }
                })
        }


        // F**k You! AMAP Service!
        XposedBridge.hookAllMethods(cILocationManager, "setExtraLocationControllerPackageEnabled", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (FakeLoc.enable) {
                    param.args[0] = false
                }
            }
        })

        XposedBridge.hookAllMethods(cILocationManager, "setExtraLocationControllerPackage", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (FakeLoc.enable) {
                    param.result = null
                }
            }
        })

    }

    private fun hookILocationListener(listener: Any) {
        val classListener = listener.javaClass
        if (FakeLoc.enableDebugLog)
            KailLog.d(null, "Kail_Xposed", "will hook ILocationListener: ${classListener.name}")

        if(XposedBridge.hookAllMethods(classListener, "onLocationChanged", object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.isEmpty()) return
                    if (!FakeLoc.enable) return

                    when (param.args[0]) {
                        is Location -> {
                            val location = param.args[0] as? Location ?: run {
                                param.result = null
                                return
                            }
                            param.args[0] = injectLocation(location)
                        }

                        is List<*> -> {
                            val locations = param.args[0] as List<*>
                            param.args[0] = locations.map { injectLocation(it as Location) }
                        }
                        else -> KailLog.e(null, "Kail_Xposed", "onLocationChanged args is not `Location`")
                    }

                    if (FakeLoc.enableDebugLog) {
                        KailLog.d(null, "Kail_Xposed", "${param.method}: injected! ${param.args[0]}")
                    }
                }
            }).isEmpty()) {
            KailLog.e(null, "Kail_Xposed", "hook onLocationChanged failed")
            return // If the hook fails, the listener is not added
        }
    }

//    private fun startDaemon(classLoader: ClassLoader) {
//        //val cIRemoteCallback = XposedHelpers.findClass("android.os.IRemoteCallback", classLoader)
//        thread(
//            name = "LocationUpdater",
//            isDaemon = true,
//            start = true,
//        ) {
//            while (true) {
//                kotlin.runCatching {
//                    if (!FakeLoc.enable) {
//                        Thread.sleep(3000)
//                        return@runCatching
//                    } else {
//                        Thread.sleep(FakeLoc.updateInterval)
//                    }
//
//                    if (!FakeLoc.enable) return@runCatching // Prevent the last loop from being executed
//
//                    if (FakeLoc.enableDebugLog)
//                        KailLog.d(null, "Kail_Xposed", "LocationUpdater: callOnLocationChanged: ${locationListeners.size}")
//
//                    callOnLocationChanged()
//                }.onFailure {
//                    KailLog.e(null, "Kail_Xposed", "LocationUpdater: ${it.message}")
//                }
//            }
//        }
//    }

    private fun ensurePullbackPushStarted() {
        if (pullbackPushStarted) return
        pullbackPushStarted = true
        pullbackPushHandler.post(pullbackPushRunnable)
    }

    private fun addLocationListenerInner(provider: String, listener: IInterface) {
        val binder = listener.asBinder()
        val mDeathRecipient = object: IBinder.DeathRecipient {
            override fun binderDied() {}
            override fun binderDied(who: IBinder) {
                who.unlinkToDeath(this, 0)
                removeLocationListenerByBinder(who)
            }
        }
        kotlin.runCatching { binder.linkToDeath(mDeathRecipient, 0) }
        if (locationListeners.none { it.second.asBinder() == binder }) {
            locationListeners.add(provider to listener)
        }
        hookILocationListener(listener)
        ensurePullbackPushStarted()
        if (FakeLoc.enable) {
            callOnLocationChanged()
        }
    }

    private fun removeLocationListenerInner(listener: IInterface) {
        removeLocationListenerByBinder(listener.asBinder())
    }

    private fun removeLocationListenerByBinder(binder: IBinder) {
        locationListeners.removeIf { it.second.asBinder() == binder }
    }

    fun callOnLocationChanged() {
        if (FakeLoc.enableDebugLog) {
            KailLog.d(null, "Kail_Xposed", "==> callOnLocationChanged: ${locationListeners.size}")
        }
        locationListeners.forEach { listenerWithProvider ->
            val listener = listenerWithProvider.second
            var location = FakeLoc.lastLocation
            if (location == null) {
                location = if (listenerWithProvider.first == "GnssBatch") {
                    Location("gps")
                } else {
                    Location(listenerWithProvider.first)
                }
            }
            location = injectLocation(location)
            var called = false
            var error: Throwable? = null
            kotlin.runCatching {
                val locations = listOf(location)
                val mOnLocationChanged = XposedHelpers.findMethodBestMatch(listener.javaClass, "onLocationChanged", locations, null)
                XposedBridge.invokeOriginalMethod(mOnLocationChanged, listener, arrayOf(locations, null))
                called = true
            }.onFailure {
                if (it is InvocationTargetException && it.targetException is DeadObjectException) {
                    return@forEach
                }
                error = it
            }

            if (!called) runCatching {
                val mOnLocationChanged = XposedHelpers.findMethodBestMatch(listener.javaClass, "onLocationChanged", location)
                XposedBridge.invokeOriginalMethod(mOnLocationChanged, listener, arrayOf(location))
                called = true
            }.onFailure {
                if (it is InvocationTargetException && it.targetException is DeadObjectException) {
                    return@forEach
                }
                error = it
            }

            if (!called) {
                KailLog.e(null, "Kail_Xposed", "callOnLocationChanged failed: " + error?.stackTraceToString())
                KailLog.e(null, "Kail_Xposed", "The listener all methods: " + listener.javaClass.declaredMethods.joinToString { it.name })
            }
        }

        if (FakeLoc.enableDebugLog) {
            KailLog.d(null, "Kail_Xposed", "==> callOnLocationChanged: end")
        }
    }

    private fun hookLocationManagerServiceV2(classLoader: ClassLoader) {
        // As a system_server, the hook can get all the location information here
        kotlin.runCatching {
            XposedHelpers.findClass("android.location.ILocationManager\$Stub", classLoader)
        }.onSuccess {
            fun hookOnTransactForServiceInstance(m: Method) {
                val isHooked = AtomicBoolean(false)
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param?.thisObject == null || param.args.size < 4) return

                        val thisObject = param.thisObject
                        val code = param.args[0] as? Int ?: return
                        val data = param.args[1] as? Parcel ?: return
                        val reply = param.args[2] as? Parcel ?: return
                        val flags = param.args[3] as? Int ?: return

                        if (isHooked.compareAndSet(false, true)) {
                            onService(thisObject.javaClass)
                        }

                        if (!FakeLoc.enable) {
                            return
                        }

                        if (FakeLoc.enable && code == 43) {
                            param.result = true
                        }

                        if (FakeLoc.enableDebugLog) {
                            KailLog.d(null, "Kail_Xposed", "ILocationManager.Stub: onTransact(code=$code)")
                        }
                    }
                })
            }

            it.declaredMethods.forEach {
                if (it.name == "onTransact") {
                    hookOnTransactForServiceInstance(it)

                    // Hey, hey, you've found onTransact, what else are you looking for
                    // It's time to end the cycle! BaKa!
                    return@forEach
                }
            }
        }.onFailure {
            KailLog.e(null, "Kail_Xposed", "ILocationManager.Stub not found: ${it.message}")
        }

//        // This is the intrusive hook
//        kotlin.runCatching {
//            XposedHelpers.findClass("android.location.ILocationManager\$Stub\$Proxy", cLocationManager.classLoader)
//        }.onSuccess {
//            it.declaredMethods.forEach {
//                XposedBridge.hookMethod(it, object : XC_MethodHook() {
//                    override fun beforeHookedMethod(param: MethodHookParam?) {
//                        if (param == null) return
//
//                        XposedBridge.log("[Kail] ILocationManager.Stub.Proxy: c = ${param.thisObject?.javaClass}, m = ${param.method}")
//                    }
//                })
//            }
//        }
    }

}

private fun Random.nextFloat(min: Float, max: Float): Float {
    return nextFloat() * (max - min) + min
}
