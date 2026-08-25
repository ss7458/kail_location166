package com.kail.locationxposed.xposed.hooks.provider

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.telephony.CellIdentity
import android.telephony.CellInfo
import android.util.ArrayMap
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import com.kail.locationxposed.xposed.hooks.BasicLocationHook.injectLocation
import com.kail.locationxposed.xposed.utils.BlindHookLocation
import com.kail.locationxposed.xposed.utils.FakeLoc
import com.kail.locationxposed.xposed.utils.KailLog
import com.kail.locationxposed.xposed.utils.beforeHook
import com.kail.locationxposed.xposed.utils.diyHook
import com.kail.locationxposed.xposed.utils.hook
import com.kail.locationxposed.xposed.utils.onceHook
import com.kail.locationxposed.xposed.utils.onceHookAllMethod
import com.kail.locationxposed.xposed.utils.onceHookMethodBefore
import java.util.Collections
import kotlin.random.Random

object LocationProviderManagerHook {
    private val hookOnFetchLocationResult = beforeHook {
        if (args.isEmpty() || args.isEmpty()) return@beforeHook
        if (!FakeLoc.enable) return@beforeHook

        if (FakeLoc.enableDebugLog) {
            KailLog.d(null, "Kail_Xposed", "${method}: injected!")
        }

        val locationResult = args[0]
        val mLocationsField = XposedHelpers.findFieldIfExists(locationResult.javaClass, "mLocations")
        if (mLocationsField == null) {
            KailLog.e(null, "Kail_Xposed", "Failed to find mLocations in LocationResult")
            return@beforeHook
        }
        mLocationsField.isAccessible = true
        val mLocations = mLocationsField.get(locationResult) as ArrayList<*>

        val originLocation = mLocations.firstOrNull() as? Location
            ?: Location(LocationManager.GPS_PROVIDER)
        val location = Location(originLocation.provider)

        val jitterLat = FakeLoc.jitterLocation()
        location.latitude = jitterLat.first
        location.longitude = jitterLat.second
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock = false
        }
        location.altitude = FakeLoc.altitude
        location.speed = originLocation.speed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.speedAccuracyMetersPerSecond = 0F
        }

        location.time = originLocation.time
        location.accuracy = originLocation.accuracy
        var modBearing = FakeLoc.bearing % 360.0 + 0.0
        if (modBearing < 0) {
            modBearing += 360.0
        }
        location.bearing = modBearing.toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && originLocation.hasBearingAccuracy()) {
            location.bearingAccuracyDegrees = modBearing.toFloat()
        }
        location.elapsedRealtimeNanos = originLocation.elapsedRealtimeNanos
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            location.elapsedRealtimeUncertaintyNanos = originLocation.elapsedRealtimeUncertaintyNanos
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
        }
        originLocation.extras?.let {
            location.extras = it
        }

        mLocationsField.set(locationResult, arrayListOf(location))
    }

    operator fun invoke(classLoader: ClassLoader) {
        hookLocationProviderManager(classLoader)
        hookDelegateLocationProvider(classLoader)
        hookPassiveLocationProvider(classLoader)
        hookProxyLocationProvider(classLoader)
        hookAbstractLocationProvider(classLoader)
        hookOtherProvider(classLoader)
        hookGeofenceProvider(classLoader)
    }

    private fun hookAbstractLocationProvider(classLoader: ClassLoader) {
        run {
            val cAbstractLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.provider.AbstractLocationProvider", classLoader)
                ?: return@run
            val cLocationResult = XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
                ?: return@run
            val mReportLocation = XposedHelpers.findMethodExactIfExists(cAbstractLocationProvider.javaClass, "reportLocation", cLocationResult)
                ?: return@run

            mReportLocation.onceHook(hookOnFetchLocationResult)
        }

        run {
            val cInternalState = XposedHelpers.findClassIfExists("com.android.server.location.provider.AbstractLocationProvider\$InternalState", classLoader)
                ?: return@run

            XposedBridge.hookAllConstructors(cInternalState, object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val listener = param.args[0] ?: return

                    if (FakeLoc.enableDebugLog) {
                        KailLog.d(null, "Kail_Xposed", "AbstractLocationProvider.InternalState: injected!")
                    }

                    // will hook class AbstractLocationProvider.Listener, Be careful not to repeat the hooker!
                    listener.javaClass.onceHookAllMethod("onReportLocation", hookOnFetchLocationResult)
                }
            })
        }

    }

    private fun hookProxyLocationProvider(classLoader: ClassLoader) {
        val cProxyLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.provider.proxy.ProxyLocationProvider", classLoader)
            ?: return


    }

    private fun hookPassiveLocationProvider(classLoader: ClassLoader) {
        val cPassiveLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.provider.PassiveLocationProvider", classLoader)
            ?: return
        val cLocationResult = XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
            ?: return
        val updateLocation = XposedHelpers.findMethodExactIfExists(cPassiveLocationProvider, "updateLocation", cLocationResult)
            ?: return

        updateLocation.hook(hookOnFetchLocationResult)
    }

    private fun hookDelegateLocationProvider(classLoader: ClassLoader) {
        val cDelegateLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.provider.DelegateLocationProvider", classLoader)
            ?: return

        val waitForInitialization = XposedHelpers.findMethodExactIfExists(cDelegateLocationProvider, "waitForInitialization") ?: return
        waitForInitialization.diyHook(
            hookOnce = true,
            before = {
                if (FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "DelegateLocationProvider.waitForInitialization: injected!")
                }

                val cLocationResult = XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
                    ?: return@diyHook true
                XposedHelpers.findMethodExactIfExists(thisObject.javaClass, "onReportLocation", cLocationResult)?.onceHook(hookOnFetchLocationResult)
                XposedHelpers.findMethodExactIfExists(thisObject.javaClass, "reportLocation", cLocationResult)?.onceHook(hookOnFetchLocationResult)

                return@diyHook true
            }
        )
    }

    private fun hookLocationProviderManager(classLoader: ClassLoader) {
        val cLocationProviderManager = XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager", classLoader)
            ?: return
        BlindHookLocation(cLocationProviderManager, classLoader)

        XposedBridge.hookAllMethods(cLocationProviderManager, "setRealProvider", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val locationProvider = param.args[0]
                if (FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "setRealProvider: $locationProvider")
                }
            }
        })
        XposedBridge.hookAllMethods(cLocationProviderManager, "setMockProvider", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val locationProvider = param.args[0]
                if (FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "setMockProvider: $locationProvider")
                }
            }
        })
        XposedBridge.hookAllMethods(cLocationProviderManager, "sendExtraCommand", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if(param.args.size < 4) return
                val command = param.args[2]

                if (command == "force_xtra_injection" || command == "CMD_SHOW_GPS_TIPS_CONFIG") {
                    param.result = null
                    return
                }

                val extras = param.args[3]
                if (FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "sendExtraCommand: $command, $extras")
                }
            }
        })

        run {
            val hookedListeners = Collections.synchronizedSet(HashSet<String>())
            if(cLocationProviderManager.onceHookAllMethod("getCurrentLocation", object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.size < 4 || param.args[3] == null) return

                    val callback = param.args[3]
                    if (FakeLoc.enableDebugLog) {
                        KailLog.d(null, "Kail_Xposed", "getCurrentLocation injected: $callback")
                    }

                    if(FakeLoc.disableGetCurrentLocation) {
                        param.result = null
                        return
                    }

                    val classCallback = callback.javaClass
                    if (hookedListeners.contains(classCallback.name)) return // Prevent repeated hooking
                    if (XposedBridge.hookAllMethods(classCallback, "onLocation", object: XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam?) {
                            if (param == null || param.args.isEmpty()) return
                            val location = (param.args[0] ?: return) as Location

                            if (FakeLoc.enableDebugLog)
                                KailLog.d(null, "Kail_Xposed", "onLocation(LocationProviderManager.getCurrentLocation): injected!")
                            param.args[0] = injectLocation(location)
                        }
                    }).isEmpty()) {
                        KailLog.e(null, "Kail_Xposed", "hook onLocation(LocationProviderManager.getCurrentLocation) failed")
                    }

                    hookedListeners.add(classCallback.name)
                }
            }).isEmpty()) {
                KailLog.e(null, "Kail_Xposed", "hook LocationProviderManager.getCurrentLocation failed")
            }
        }

        cLocationProviderManager.onceHookMethodBefore("onReportLocation") {
            val fieldMRegistrations = XposedHelpers.findFieldIfExists(cLocationProviderManager, "mRegistrations")
            if (fieldMRegistrations == null) {
                KailLog.e(null, "Kail_Xposed", "Failed to find mRegistrations in LocationProviderManager")
                return@onceHookMethodBefore
            }
            if (!fieldMRegistrations.isAccessible)
                fieldMRegistrations.isAccessible = true

            if (!FakeLoc.enable) {
                return@onceHookMethodBefore
            }

            val registrations = fieldMRegistrations.get(thisObject) as ArrayMap<*, *>
            // [本地化修改] 安全修复：不再用空 ArrayMap 替换 mRegistrations 字段（原实现会导致
            // 系统监听注册表永久丢失，且无锁替换与系统并发写竞争可抛 CME 直接崩 system_server）。
            // 改为对快照迭代；单条监听器处理失败只跳过该条，不中断其余、不逃逸异常。
            // [本地化修改] 快照必须在系统锁内获取：mRegistrations 受 synchronized 保护，
            // 无锁 toList 与 register/unregister 并发会抛 CME 崩溃 system_server。
            val snapshot = synchronized(registrations) { registrations.entries.toList() }
            snapshot.forEach { entry ->
                runCatching {
                    val value = entry.value ?: return@runCatching
                    val locationResult = args[0]

                    val mLocationsField = XposedHelpers.findFieldIfExists(locationResult.javaClass, "mLocations")
                    if (mLocationsField == null) {
                        KailLog.e(null, "Kail_Xposed", "Failed to find mLocations in LocationResult")
                        return@runCatching
                    }
                    mLocationsField.isAccessible = true
                    val mLocations = mLocationsField.get(locationResult) as ArrayList<*>

                    val originLocation = mLocations.firstOrNull() as? Location
                        ?: Location(LocationManager.GPS_PROVIDER)
                    val location = Location(originLocation.provider)

                    val jitterLat = FakeLoc.jitterLocation()
                    location.latitude = jitterLat.first
                    location.longitude = jitterLat.second
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        location.isMock = false
                    }
                    location.altitude = FakeLoc.altitude
                    // [本地化修改] 速度用引擎实际值（origin 为陈旧真实GPS）。
                    location.speed = (FakeLoc.speed + kotlin.random.Random.nextDouble(-0.15, 0.15)).toFloat().coerceAtLeast(0f)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        location.speedAccuracyMetersPerSecond = 0.5f
                    }

                    // [本地化修改] 时间戳新鲜度（原复制陈旧真实GPS时间导致SDK判重丢弃→时走时停）。
                    location.time = System.currentTimeMillis()
                    location.accuracy = originLocation.accuracy
                    var modBearing = FakeLoc.bearing % 360.0 + 0.0
                    if (modBearing < 0) {
                        modBearing += 360.0
                    }
                    // [本地化修改] 叠加 ±1.5° 高斯噪声。
                    location.bearing = ((modBearing + kotlin.random.Random.nextDouble(-1.5, 1.5) + 360.0) % 360.0).toFloat()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && originLocation.hasBearingAccuracy()) {
                        location.bearingAccuracyDegrees = modBearing.toFloat()
                    }
                    location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        location.elapsedRealtimeUncertaintyNanos = 0.0
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
                    }
                    originLocation.extras?.let {
                        location.extras = it
                    }

                    mLocationsField.set(locationResult, arrayListOf(location))

                    val operation = XposedHelpers.callMethod(value, "acceptLocationChange", locationResult)
                    XposedHelpers.callMethod(value, "executeOperation", operation)
                }.onFailure {
                    KailLog.e(null, "Kail_Xposed", "onReportLocation: listener dispatch failed: ${it.message}")
                }
            }

            if (FakeLoc.enableDebugLog) {
                KailLog.d(null, "Kail_Xposed", "onReportLocation: injected!")
            }

            // [本地化修改] 抑制原生分发：伪造位置已自行派发给全部监听器，
            // 若放行原生 onReportLocation 会再派发一帧真实位置（真假交替泄漏/拉扯）。
            result = null
        }
    }

    private fun hookGeofenceProvider(classLoader: ClassLoader) {
        val cGeofenceManager = XposedHelpers.findClassIfExists("com.android.server.geofence.GeofenceManager", classLoader)
            ?: return
        BlindHookLocation(cGeofenceManager, classLoader)
    }

    private fun hookOtherProvider(classLoader: ClassLoader) {
        kotlin.runCatching {
            val cGnssLocationProvider = XposedHelpers.findClassIfExists("com.android.location.provider.LocationProviderBase", classLoader)
                ?: return@runCatching
            if(BlindHookLocation(cGnssLocationProvider, classLoader) == 0) {
                cGnssLocationProvider.onceHookMethodBefore("reportLocation", Location::class.java) {
                    if (!FakeLoc.enable) return@onceHookMethodBefore
                    if (FakeLoc.enableDebugLog) {
                        KailLog.d(null, "Kail_Xposed", "LocationProviderBase.reportLocation: injected!")
                    }
                    args[0] = injectLocation(args[0] as Location)
                }
            }
            cGnssLocationProvider.onceHookMethodBefore("reportLocations", List::class.java) {
                if (!FakeLoc.enable) return@onceHookMethodBefore
                if (FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "LocationProviderBase.reportLocations: injected!")
                }
                args[0] = (args[0] as List<*>).map {
                    injectLocation(it as Location)
                }
            }
        }.onFailure {
            KailLog.w(null, "Kail_Xposed", "Failed to hook LocationProviderBase: ${it.message}")
        }

        kotlin.runCatching {
            val cGnssLocationProvider = XposedHelpers.findClass("com.android.server.location.gnss.GnssLocationProvider", classLoader)
            cGnssLocationProvider.onceHookMethodBefore("onReportLocation", Boolean::class.java, Location::class.java) {
                if (!FakeLoc.enable) return@onceHookMethodBefore

                args[1] = injectLocation(args[1] as Location)

                if (FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "GnssLocationProvider.onReportLocation: injected! ${args[1]}")
                }
            }

            cGnssLocationProvider.onceHookMethodBefore("onReportLocations", Boolean::class.java, Array<Location>::class.java) {
                if (!FakeLoc.enable) return@onceHookMethodBefore

                args[0] = (args[0] as Array<*>).map {
                    injectLocation(it as Location)
                }.toTypedArray()

                if (FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "GnssLocationProvider.onReportLocations: injected! ${args[0]}")
                }
            }

            cGnssLocationProvider.onceHookMethodBefore("getCellType", CellInfo::class.java) {
                if (!FakeLoc.enable) return@onceHookMethodBefore
                if (FakeLoc.enableDebugLog) {
                    KailLog.d(null, "Kail_Xposed", "GnssLocationProvider.getCellType: injected!")
                }

                result = 0
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cGnssLocationProvider.onceHookMethodBefore("getCidFromCellIdentity", CellIdentity::class.java) {
                    if (!FakeLoc.enable) return@onceHookMethodBefore
                    if (FakeLoc.enableDebugLog) {
                        KailLog.d(null, "Kail_Xposed", "GnssLocationProvider.getCidFromCellIdentity: injected!")
                    }

                    result = -1L
                }

                cGnssLocationProvider.onceHookMethodBefore("setRefLocation", Int::class.java, CellIdentity::class.java) {
                    if (!FakeLoc.enable) return@onceHookMethodBefore
                    if (FakeLoc.enableDebugLog) {
                        KailLog.d(null, "Kail_Xposed", "GnssLocationProvider.setRefLocation: injected!")
                    }

                    args[0] = 114514 // disable AGPS
                }
            }
        }.onFailure {
            KailLog.w(null, "Kail_Xposed", "Failed to hook GnssLocationProvider: ${it.message}")
        }
    }
}