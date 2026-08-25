package com.kail.location.utils.service

import android.os.SystemClock
import kotlin.math.sqrt
import java.util.concurrent.ThreadLocalRandom

/**
 * [本地化修改] 让模拟定位更接近真实 GPS 的统计特征（调研结论落地）：
 *
 * - 位置漂移：Ornstein-Uhlenbeck 过程（高斯增量 + 均值回归），时间相关、
 *   非逐点独立，规避对坐标序列的 FFT/统计检测；量级与真实 GPS 相当（米级）。
 * - accuracy：围绕基准值缓慢高斯漂移（真实手机 3~15m 且持续波动），
 *   替换"恒定 1.0m"这一强检测特征。
 * - speed / bearing：叠加小幅高斯噪声（真实芯片带 S.Acc/B.Acc 误差）。
 *
 * 由"自然 GPS 抖动"设置项作为总开关（关闭时各服务不调用本类）。
 */
object GeoRealism {

    // ---- OU 位置漂移状态（单位：度）----
    @Volatile private var driftLat = 0.0
    @Volatile private var driftLng = 0.0
    @Volatile private var lastDriftAtMs = 0L

    // 步强 ≈0.28m/√s；均值回归 5%/s；钳制 ≈±4m 防瞬移。
    private const val SIGMA_DEG = 0.0000025
    private const val ALPHA_PER_SEC = 0.05
    private const val CLAMP_DEG = 0.000036

    // ---- accuracy 漂移状态（单位：米）----
    // [本地化修改][回归修正] 原钳制 [4,25] 会游走到地图 SDK 的精度拒收门限（约15~20m）以上，
    // 导致定位点被目标间歇性丢弃（表现为时走时停）。收紧到 [4,12]，贴近真实手机 3~10m 特征。
    @Volatile private var accMeters = 7.0
    @Volatile private var lastAccAtMs = 0L

    /**
     * 对基础坐标施加 OU 时间相关漂移。
     * 内部按真实流逝时间积分，调用频率变化不影响统计特性。
     */
    @Synchronized
    fun drifted(lat: Double, lng: Double): Pair<Double, Double> {
        val now = SystemClock.elapsedRealtime()
        val dtSec = if (lastDriftAtMs == 0L) 0.2 else ((now - lastDriftAtMs) / 1000.0).coerceIn(0.05, 30.0)
        lastDriftAtMs = now
        val rnd = ThreadLocalRandom.current()
        val step = SIGMA_DEG * sqrt(dtSec)
        driftLat += step * rnd.nextGaussian() - ALPHA_PER_SEC * driftLat * dtSec
        driftLng += step * rnd.nextGaussian() - ALPHA_PER_SEC * driftLng * dtSec
        driftLat = driftLat.coerceIn(-CLAMP_DEG, CLAMP_DEG)
        driftLng = driftLng.coerceIn(-CLAMP_DEG, CLAMP_DEG)
        return (lat + driftLat) to (lng + driftLng)
    }

    /**
     * 真实感 accuracy：围绕基准（默认 8m）缓慢高斯游走，钳制 [4, 25] 米。
     */
    @Synchronized
    fun driftedAccuracy(baseMeters: Double = 7.0): Float {
        val now = SystemClock.elapsedRealtime()
        val dtSec = if (lastAccAtMs == 0L) 1.0 else ((now - lastAccAtMs) / 1000.0).coerceIn(0.05, 10.0)
        lastAccAtMs = now
        val rnd = ThreadLocalRandom.current()
        accMeters += 0.35 * sqrt(dtSec) * rnd.nextGaussian() - 0.1 * (accMeters - baseMeters) * dtSec
        accMeters = accMeters.coerceIn(4.0, 12.0)
        return accMeters.toFloat()
    }

    /** speed 叠加 ±0.4 m/s 高斯噪声，保持非负。 */
    fun noisySpeed(speedMps: Double): Float =
        (speedMps + ThreadLocalRandom.current().nextGaussian() * 0.4).coerceAtLeast(0.0).toFloat()

    /** bearing 叠加 ±4° 高斯噪声，环绕到 [0,360)。 */
    fun noisyBearing(bearingDeg: Float): Float {
        val rnd = ThreadLocalRandom.current()
        var b = (bearingDeg + rnd.nextGaussian() * 4.0).toDouble() % 360.0
        if (b < 0) b += 360.0
        return b.toFloat()
    }
}
