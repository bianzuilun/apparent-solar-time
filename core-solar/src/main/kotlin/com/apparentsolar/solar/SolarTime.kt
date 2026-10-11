package com.apparentsolar.solar

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 真太阳时（视太阳时，Apparent Solar Time）计算核心。
 *
 * 纯 Kotlin 实现，只依赖 kotlin.math，不包含任何平台 API：
 *   - Android / JVM 桌面：直接编译
 *   - iOS / macOS / Windows：把插件换成 Kotlin Multiplatform 后原样复用，
 *     或按本文件平移为 Swift / C# / Dart（约 100 行）
 *
 * 定义：
 *   真太阳时 = 平太阳时 + 均时差(EoT)
 *   平太阳时 = UTC + 经度 × 4 分钟/度
 *   均时差   = 真太阳时 − 平太阳时（一年中约 -14 分 ~ +16 分）
 *
 * 算法：Meeus《Astronomical Algorithms》第 25、28 章，
 * 均时差精度优于 ±1 秒（1900–2100 年）。
 */
object SolarTime {

    /** 地球自转 1° 经度对应 4 分钟。 */
    const val MILLIS_PER_DEGREE: Double = 240_000.0

    /** ΔT ≈ TT − UTC（2020 年代约 69 秒）。偏差 10 秒对结果影响 < 0.1 秒，无需逐年修正。 */
    private const val DELTA_T_SECONDS = 69.0

    /**
     * 某一时刻真太阳时的完整快照。
     */
    data class Snapshot(
        /** 输入的 UTC 时刻（Unix 毫秒）。 */
        val utcMillis: Long,
        /** 均时差（秒）：真太阳时 − 平太阳时，11 月初约 +16.4 分钟。 */
        val equationOfTimeSeconds: Double,
        /** 经度修正（秒）：所在经度相对时区中央经线的偏移。 */
        val longitudeCorrectionSeconds: Double,
        /** 真太阳时 − 设备标准时间（秒）= 上两者之和。 */
        val offsetFromStandardSeconds: Double,
        /** 真太阳时当日时刻（毫秒），0 = 真太阳午夜。 */
        val apparentSolarMillisOfDay: Long,
        /** 当前真太阳日开始处的 UTC 时刻（Unix 毫秒）。 */
        val solarDayStartUtcMillis: Long,
        /** 下一个真太阳午夜（UTC 毫秒），用于安排锚点重校。 */
        val nextSolarMidnightUtcMillis: Long,
    )

    /** 时区中央经线（东经为正），如 UTC+8 → 120°。 */
    fun zoneMeridianDegrees(zoneOffsetSeconds: Int): Double =
        zoneOffsetSeconds / 3600.0 * 15.0

    /** Unix 毫秒 → 儒略日（UTC 口径）。 */
    fun julianDay(utcMillis: Long): Double = utcMillis / 86_400_000.0 + 2_440_587.5

    /**
     * 均时差（秒）= 真太阳时 − 平太阳时。
     * 输入为 UTC 儒略日；内部用 TT（≈UTC+69s）驱动太阳历表，对结果影响可忽略。
     */
    fun equationOfTimeSeconds(julianDayUtc: Double): Double {
        val t = (julianDayUtc + DELTA_T_SECONDS / 86_400.0 - 2_451_545.0) / 36_525.0
        val rad = PI / 180.0

        // 太阳平黄经、平近点角、中心差（Meeus 25）
        val l0 = norm360(280.46646 + 36_000.76983 * t + 0.0003032 * t * t)
        val m = normRad(357.52911 + 35_999.05029 * t - 0.0001537 * t * t)
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(m) +
                (0.019993 - 0.000101 * t) * sin(2 * m) +
                0.000289 * sin(3 * m)

        // 月球升交点平黄经（章动与交角章动公用）
        val omega = normRad(125.04 - 1934.136 * t)

        // 视黄经（含光行差 -0.00569° 与黄经章动主项）
        val lambdaApp = (l0 + c - 0.00569 - 0.00478 * sin(omega)) * rad

        // 黄赤交角（Meeus 22.2 + 交角章动主项）
        val epsilon = (23.43929111 - 0.01300417 * t - 1.639e-7 * t * t +
                5.036e-7 * t * t * t + 0.00256 * cos(omega)) * rad

        // 黄经章动，4 项主式（精度 ~0.5″，对应时间 ~0.01 秒）
        val lMoon = 218.3165 + 481_267.8813 * t
        val dPsiArcsec = -17.20 * sin(omega) -
                1.32 * sin(2 * normRad(l0)) -
                0.23 * sin(2 * normRad(lMoon)) +
                0.21 * sin(2 * omega)

        // 视赤经
        val alpha = norm360(atan2(cos(epsilon) * sin(lambdaApp), cos(lambdaApp)) / rad)

        // Meeus 28.1：E = L0 − 0.0057183 − α + Δψ·cos ε
        val eDegrees = normSigned(l0 - 0.0057183 - alpha + dPsiArcsec / 3600.0 * cos(epsilon))
        return eDegrees * 240.0 // 1° = 4 分 = 240 秒
    }

    /**
     * 计算真太阳时快照。
     *
     * @param utcMillis          UTC 时刻（Unix 毫秒）
     * @param longitudeDegrees   经度（东经为正）
     * @param zoneOffsetSeconds  设备时区偏移（秒），用于给出相对设备标准时间的偏差
     */
    fun snapshot(utcMillis: Long, longitudeDegrees: Double, zoneOffsetSeconds: Int): Snapshot {
        val eot = equationOfTimeSeconds(julianDay(utcMillis))
        val longitudeCorrection = longitudeDegrees * 240.0 - zoneMeridianDegrees(zoneOffsetSeconds) * 240.0

        // 真太阳时映射到 UTC 时间轴上的绝对位置，再取当日余数
        val apparentAbsoluteMillis =
            utcMillis + longitudeDegrees * MILLIS_PER_DEGREE + eot * 1000.0
        val dayStartMillis = floor(apparentAbsoluteMillis / 86_400_000.0) * 86_400_000.0
        val millisOfDay = (apparentAbsoluteMillis - dayStartMillis).toLong()

        return Snapshot(
            utcMillis = utcMillis,
            equationOfTimeSeconds = eot,
            longitudeCorrectionSeconds = longitudeCorrection,
            offsetFromStandardSeconds = eot + longitudeCorrection,
            apparentSolarMillisOfDay = millisOfDay,
            solarDayStartUtcMillis = dayStartMillis.toLong(),
            nextSolarMidnightUtcMillis = dayStartMillis.toLong() + 86_400_000L,
        )
    }

    private fun norm360(degrees: Double): Double {
        var d = degrees % 360.0
        if (d < 0) d += 360.0
        return d
    }

    private fun normRad(degrees: Double): Double = norm360(degrees) * PI / 180.0

    private fun normSigned(degrees: Double): Double {
        var d = degrees % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }
}
