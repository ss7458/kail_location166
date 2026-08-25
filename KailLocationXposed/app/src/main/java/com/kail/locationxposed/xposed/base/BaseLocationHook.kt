package com.kail.locationxposed.xposed.base

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import de.robv.android.xposed.XposedHelpers
import com.kail.locationxposed.xposed.utils.FakeLoc
import com.kail.locationxposed.xposed.utils.KailLog
import com.kail.locationxposed.xposed.nmea.NMEA
import com.kail.locationxposed.xposed.nmea.NmeaValue
import kotlin.random.Random

abstract class BaseLocationHook: BaseDivineService() {
    private val injecting = ThreadLocal<Boolean>()

    fun injectLocation(originLocation: Location, realLocation: Boolean = true): Location {
        if (FakeLoc.enableDebugLog) {
            KailLog.d(null, "Kail_Xposed", "=== injectLocation ENTER: FakeLoc.lat=${FakeLoc.latitude}, FakeLoc.lon=${FakeLoc.longitude}")
            KailLog.d(null, "Kail_Xposed", "=== injectLocation origin: ${originLocation.latitude},${originLocation.longitude} provider=${originLocation.provider}")
        }
        if (injecting.get() == true) {
            KailLog.e(null, "Kail_Xposed", "=== injectLocation EXIT: reentrant")
            return originLocation
        }
        injecting.set(true)
        try {
        if (realLocation) {
            if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    originLocation.provider == LocationManager.GPS_PROVIDER && originLocation.isComplete
                } else {
                    originLocation.provider == LocationManager.GPS_PROVIDER
                }
            ) {
                FakeLoc.lastLocation = originLocation
            }
        } else {
            originLocation.altitude = FakeLoc.altitude
        }

        if (!FakeLoc.enable) {
            KailLog.e(null, "Kail_Xposed", "=== injectLocation EXIT: enable=false")
            return originLocation
        }

        originLocation.extras?.let {
            if (it.getBoolean("kail_faked", false)) {
                KailLog.e(null, "Kail_Xposed", "=== injectLocation EXIT: already faked")
                return originLocation
            }
        }

        // [本地化修改] 移除和值判重：浮点和值巧合相等会直接泄漏真实 Location（kail_faked extras 已足够判重）。

        if (FakeLoc.disableNetworkLocation && originLocation.provider == LocationManager.NETWORK_PROVIDER) {
            originLocation.provider = LocationManager.GPS_PROVIDER
        }

        val provider = (originLocation.provider ?: LocationManager.GPS_PROVIDER).let {
            if (it.contains("mock", ignoreCase = true) ||
                it.contains("test", ignoreCase = true) ||
                it.contains("fake", ignoreCase = true)
            ) {
                LocationManager.GPS_PROVIDER
            } else {
                it
            }
        }

        val location = Location(provider)
        location.accuracy = if (FakeLoc.accuracy != 0.0f) FakeLoc.accuracy else originLocation.accuracy
        val jitterLat = FakeLoc.jitterLocation()
        location.latitude = jitterLat.first
        location.longitude = jitterLat.second
        location.altitude = FakeLoc.altitude
        KailLog.i(null, "DEBUG", "=== injectLocation after jitter: lat=${location.latitude}, lon=${location.longitude}")
        // [本地化修改] 速度与位移一致：使用引擎实际步进速度+小幅噪声（原实现复制陈旧真实GPS速度±speedAmplitude）。
        val speedNoise = Random.nextDouble(-0.15, 0.15)
        location.speed = (FakeLoc.speed + speedNoise).toFloat().coerceAtLeast(0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.speedAccuracyMetersPerSecond = 0.5f
        }

        if (location.altitude == 0.0) {
            location.altitude = 80.0
        }

        // [本地化修改] 时间戳新鲜度修复：原实现复制陈旧真实GPS时间戳，
        // 地图SDK按 elapsedRealtimeNanos 判重（delta=0 丢弃）→ 高频推送被去重成约1Hz 且被判过期。
        location.time = System.currentTimeMillis()

        // final addition of zero is to remove -0 results. while these are technically within the
        // range [0, 360) according to IEEE semantics, this eliminates possible user confusion.
        var modBearing = FakeLoc.bearing % 360.0 + 0.0
        if (modBearing < 0) {
            modBearing += 360.0
        }
        // [本地化修改] 叠加 ±1.5° 高斯噪声（真实 GPS 航向误差特征）。
        modBearing = (modBearing + Random.nextDouble(-1.5, 1.5) + 360.0) % 360.0
        location.bearing = modBearing.toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.bearingAccuracyDegrees = modBearing.toFloat()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (location.hasBearingAccuracy() && location.bearingAccuracyDegrees == 0.0f) {
                location.bearingAccuracyDegrees = 1.0f
            }
        }

        // [本地化修改] 移除强制 1.2f hack：停止时速度就应为 0。

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
        if (location.extras == null) {
            location.extras = Bundle()
        }
        cleanMockExtras(location.extras)
        location.extras?.putDouble("latlon", location.latitude + location.longitude)
        location.extras?.putBoolean("kail_faked", true)
        location.extras?.putInt("satellites", Random.nextInt(8, 45))
        location.extras?.putInt("maxCn0", Random.nextInt(30, 50))
        location.extras?.putInt("meanCn0", Random.nextInt(20, 30))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (originLocation.hasMslAltitude()) {
                location.mslAltitudeMeters = FakeLoc.altitude
            }
            if (originLocation.hasVerticalAccuracy()) {
                location.mslAltitudeAccuracyMeters = FakeLoc.altitude.toFloat()
            }
        }
        if (FakeLoc.hideMock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                location.isMock = false
            }
            cleanMockFields(location)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                location.isMock = true
            }
            location.extras?.putBoolean("kail.enable", true)
            location.extras?.putBoolean("is_mock", true)
        }

        kotlin.runCatching {
            XposedHelpers.callMethod(location, "makeComplete")
        }.onFailure {
            KailLog.e(null, "Kail_Xposed", "makeComplete failed: ${it.message}")
        }

        if (FakeLoc.enableDebugLog) {
            KailLog.d(null, "Kail_Xposed", "injectLocation success! $location")
        }

        return location
        } finally {
            injecting.set(false)
        }
    }

    private fun cleanMockExtras(extras: Bundle?) {
        extras ?: return
        extras.remove("mockLocation")
        extras.remove("isMock")
        extras.remove("is_mock")
        extras.remove("mock")
    }

    private fun cleanMockFields(location: Location) {
        kotlin.runCatching {
            XposedHelpers.setBooleanField(location, "mMock", false)
        }
        kotlin.runCatching {
            XposedHelpers.setBooleanField(location, "mIsFromMockProvider", false)
        }
    }

    fun injectNMEA(nmeaStr: String): String? {
        if (!FakeLoc.enable) {
            return null
        }

        kotlin.runCatching {
            val nmea = NMEA.valueOf(nmeaStr)
            when(val value = nmea.value) {
                is NmeaValue.DTM -> {
                    return null
                }
                is NmeaValue.GGA -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.fixQuality == 0) {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    // [本地化修改] 南/西半球修复：度分换算必须用绝对值，符号由半球字母(N/S,E/W)表达；
                    // 原实现对负坐标产出形如 -030.300000+S 的非法报文，下游解析崩溃或坐标错乱。
                    val latAbs = kotlin.math.abs(FakeLoc.latitude)
                    var degree = latAbs.toInt()
                    var minute = (latAbs - degree) * 60
                    value.latitude = degree + minute / 100

                    val lngAbs = kotlin.math.abs(FakeLoc.longitude)
                    degree = lngAbs.toInt()
                    minute = (lngAbs - degree) * 60
                    value.longitude = degree + minute / 100

                    return NMEA(nmea.talkerId, value).toNmeaString()
                }
                is NmeaValue.GNS -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.mode == "N") {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    // [本地化修改] 南/西半球修复：度分换算必须用绝对值，符号由半球字母(N/S,E/W)表达；
                    // 原实现对负坐标产出形如 -030.300000+S 的非法报文，下游解析崩溃或坐标错乱。
                    val latAbs = kotlin.math.abs(FakeLoc.latitude)
                    var degree = latAbs.toInt()
                    var minute = (latAbs - degree) * 60
                    value.latitude = degree + minute / 100

                    val lngAbs = kotlin.math.abs(FakeLoc.longitude)
                    degree = lngAbs.toInt()
                    minute = (lngAbs - degree) * 60
                    value.longitude = degree + minute / 100

                    return NMEA(nmea.talkerId, value).toNmeaString()
                }
                is NmeaValue.GSA -> {
                    // [本地化修改] 伪造 GSA：原 return null 会回落透传真实 NMEA（真实坐标泄漏 + 与假 GPS 矛盾）。
                    val prn = List(12) { i -> if (i < 8) 1 + (i * 7 % 32) else null }
                    return NMEA(nmea.talkerId, NmeaValue.GSA(
                        mode = "A",
                        fixStatus = 3,
                        prn = prn,
                        pdop = 1.2,
                        hdop = 0.9,
                        vdop = 0.8,
                        systemId = value.systemId
                    )).toNmeaString()
                }
                is NmeaValue.GSV -> {
                    // [本地化修改] 伪造 GSV：生成与假 GPS 自洽的卫星视图；字段按 4 秒步进缓慢变化，
                    // 避免目标 app 因卫星数据高频突变而识破（借鉴 LocationSpoofer）。
                    // [本地化修改][规范修正] 单句最多 4 颗（NMEA 上限）且 PRN 唯一；原 10 颗单页含重复 PRN 会被严校验解析器拒收。
                    val step = (android.os.SystemClock.elapsedRealtime() / 4000L).toInt()
                    val sats = (0 until 4).map { i ->
                        NmeaValue.GSV.Satellite(
                            prn = 1 + (i * 13 + step) % 32,
                            elevation = 20 + (i * 17 + step * 11) % 55,
                            azimuth = (i * 90 + step * 3) % 360,
                            snr = 26 + (i * 7 + step * 5) % 12
                        )
                    }
                    return NMEA(nmea.talkerId, NmeaValue.GSV(
                        totalMessages = 1,
                        messageNumber = 1,
                        totalSatellitesInView = 4,
                        satellites = sats,
                        infoId = value.infoId
                    )).toNmeaString()
                }
                is NmeaValue.RMC -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.status == "V") {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    // [本地化修改] 南/西半球修复：度分换算必须用绝对值，符号由半球字母(N/S,E/W)表达；
                    // 原实现对负坐标产出形如 -030.300000+S 的非法报文，下游解析崩溃或坐标错乱。
                    val latAbs = kotlin.math.abs(FakeLoc.latitude)
                    var degree = latAbs.toInt()
                    var minute = (latAbs - degree) * 60
                    value.latitude = degree + minute / 100

                    val lngAbs = kotlin.math.abs(FakeLoc.longitude)
                    degree = lngAbs.toInt()
                    minute = (lngAbs - degree) * 60
                    value.longitude = degree + minute / 100

                    return NMEA(nmea.talkerId, value).toNmeaString()
                }
                is NmeaValue.VTG -> {
                    // [本地化修改] 伪造 VTG：真实报文含真实速度/航向（泄漏+矛盾），改为从假状态生成。
                    val track = (FakeLoc.bearing % 360.0 + 360.0) % 360.0
                    val knots = FakeLoc.speed * 1.943844
                    val kph = FakeLoc.speed * 3.6
                    return NMEA(nmea.talkerId, NmeaValue.VTG(
                        trueTrack = track,
                        magneticTrack = null,
                        groundSpeedKnots = knots,
                        groundSpeedUnit = "N",
                        groundSpeedKph = kph,
                        groundSpeedKphUnit = "K",
                        trueTrackMode = "T",
                        magneticTrackMode = "M",
                        mode = "A"
                    )).toNmeaString()
                }
            }
        }.onFailure {
            KailLog.e(null, "Kail_Xposed", "NMEA parse failed: ${it.message}, source = $nmeaStr")
            return null
        }
        return null
    }
}
