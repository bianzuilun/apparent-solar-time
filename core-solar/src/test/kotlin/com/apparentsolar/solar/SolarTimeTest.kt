package com.apparentsolar.solar

import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 均时差参考值来自 NOAA 全球监测实验室太阳计算器（2026 年），
 * 容差 ±90 秒：足以捕获符号、量级与结构性错误（真正的实现错误通常达分钟级）。
 */
class SolarTimeTest {

    private fun epochMs(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun equationOfTime_februaryMinimum() {
        val eot = SolarTime.equationOfTimeSeconds(SolarTime.julianDay(epochMs("2026-02-11T12:00:00Z")))
        assertEquals(-14.2 * 60, eot, 90.0)
    }

    @Test
    fun equationOfTime_novemberMaximum() {
        val eot = SolarTime.equationOfTimeSeconds(SolarTime.julianDay(epochMs("2026-11-03T12:00:00Z")))
        assertEquals(16.4 * 60, eot, 90.0)
    }

    @Test
    fun equationOfTime_aprilZeroCrossing() {
        val eot = SolarTime.equationOfTimeSeconds(SolarTime.julianDay(epochMs("2026-04-15T12:00:00Z")))
        assertTrue(abs(eot) < 90.0, "4 月中旬均时差应接近 0，实际 ${"%.1f".format(eot / 60)} 分")
    }

    @Test
    fun equationOfTime_julyDip() {
        val eot = SolarTime.equationOfTimeSeconds(SolarTime.julianDay(epochMs("2026-07-26T12:00:00Z")))
        assertEquals(-6.5 * 60, eot, 90.0)
    }

    @Test
    fun equationOfTime_mayPositive() {
        val eot = SolarTime.equationOfTimeSeconds(SolarTime.julianDay(epochMs("2026-05-14T12:00:00Z")))
        assertEquals(3.6 * 60, eot, 90.0)
    }

    @Test
    fun beijing_offsetAndLongitudeCorrection() {
        // 北京 116.4074°E、UTC+8：经度修正 = (116.4074 − 120) × 4min = −14.37min
        val s = SolarTime.snapshot(
            utcMillis = epochMs("2026-11-03T12:00:00Z"),
            longitudeDegrees = 116.4074,
            zoneOffsetSeconds = 8 * 3600,
        )
        assertEquals(-14.37 * 60, s.longitudeCorrectionSeconds, 1.0)
        // 均时差 +16.4min + 经度修正 −14.37min ≈ +2.0min
        assertEquals(2.0 * 60, s.offsetFromStandardSeconds, 120.0)
    }

    @Test
    fun snapshot_millisOfDayStaysWithinOneDay() {
        val s = SolarTime.snapshot(
            utcMillis = epochMs("2026-11-03T12:00:00Z"),
            longitudeDegrees = 116.4074,
            zoneOffsetSeconds = 8 * 3600,
        )
        assertTrue(s.apparentSolarMillisOfDay in 0 until 86_400_000L)
        assertEquals(s.solarDayStartUtcMillis + 86_400_000L, s.nextSolarMidnightUtcMillis)
    }

    @Test
    fun snapshot_solarTimeAtOwnMeridianMatchesEoT() {
        // 站在时区中央经线上：真太阳时与标准时间的偏差就是均时差本身
        val s = SolarTime.snapshot(
            utcMillis = epochMs("2026-11-03T12:00:00Z"),
            longitudeDegrees = 120.0,
            zoneOffsetSeconds = 8 * 3600,
        )
        assertEquals(s.equationOfTimeSeconds, s.offsetFromStandardSeconds, 0.001)
    }
}
