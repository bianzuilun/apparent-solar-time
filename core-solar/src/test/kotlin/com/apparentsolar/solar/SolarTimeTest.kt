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
    fun shichen_allTwelvePeriodsAndTheirBoundaries() {
        val labels = listOf("丑时", "寅时", "卯时", "辰时", "巳时", "午时",
            "未时", "申时", "酉时", "戌时", "亥时", "子时")
        labels.forEachIndexed { index, label ->
            val start = (1 + index * 2) * 3_600_000L
            assertEquals(labels[(index + 11) % 12], SolarTime.shichenLabel(start - 1))
            assertEquals(label, SolarTime.shichenLabel(start))
            assertEquals(label, SolarTime.shichenLabel(start + 7_200_000L - 1))
        }
    }

    @Test
    fun shichen_ziPeriodContinuesAcrossMidnight() {
        for (millis in listOf(0L, 3_599_999L, 82_800_000L, 86_399_999L, 86_400_000L)) {
            assertEquals("子时", SolarTime.shichenLabel(millis))
        }
    }

    @Test
    fun nextSolarHour_doesNotFireBeforeBoundaryAcrossSeasonsAndLongitudes() {
        val hourMillis = 3_600_000L
        for (month in 1..12) {
            for (longitude in listOf(-179.9, -75.0, 0.0, 118.18, 179.9)) {
                for (hour in listOf("00", "06", "12", "23")) {
                    val utc = epochMs("2026-${month.toString().padStart(2, '0')}-15T$hour:37:42Z")
                    val next = SolarTime.nextSolarHourUtcMillis(utc, longitude)
                    assertTrue(next > utc && next - utc <= hourMillis + 1_500)
                    val before = SolarTime.snapshot(next - 1, longitude, 0).apparentSolarMillisOfDay % hourMillis
                    val after = SolarTime.snapshot(next, longitude, 0).apparentSolarMillisOfDay % hourMillis
                    assertTrue(before in hourMillis - 2 until hourMillis, "提前触发：$month / $longitude / $hour")
                    assertTrue(after in 0L..1L, "错过整点：$month / $longitude / $hour")
                }
            }
        }
    }

    @Test
    fun nextSolarHour_chainResetsClockAtTwoConsecutiveMidnights() {
        val utc = epochMs("2026-10-14T12:00:00Z")
        val eotMillis = SolarTime.equationOfTimeSeconds(SolarTime.julianDay(utc)) * 1000.0
        // 构造当前真太阳时 23:59:55，随后模拟 26 次整点闹钟。
        val longitude = (43_195_000.0 - eotMillis) / SolarTime.MILLIS_PER_DEGREE
        var current = utc
        repeat(26) { index ->
            current = SolarTime.nextSolarHourUtcMillis(current, longitude) + 250
            val snap = SolarTime.snapshot(current, longitude, 0)
            assertEquals((index % 24).toLong(), snap.apparentSolarMillisOfDay / 3_600_000L)
            if (index % 24 == 0) {
                assertTrue(snap.apparentSolarMillisOfDay in 250L..251L)
                assertEquals("子时", SolarTime.shichenLabel(snap.apparentSolarMillisOfDay))
            }
        }
    }

    @Test
    fun nextSolarHour_updatesShichenOnTheCorrectSideOfBoundary() {
        val utc = epochMs("2026-10-14T12:00:00Z")
        val eotMillis = SolarTime.equationOfTimeSeconds(SolarTime.julianDay(utc)) * 1000.0
        // 真太阳时 00:59:55，下一整点必须进入丑时。
        val longitude = (-39_605_000.0 - eotMillis) / SolarTime.MILLIS_PER_DEGREE
        val next = SolarTime.nextSolarHourUtcMillis(utc, longitude)
        assertEquals("子时", SolarTime.shichenLabel(SolarTime.snapshot(next - 1, longitude, 0).apparentSolarMillisOfDay))
        assertEquals("丑时", SolarTime.shichenLabel(SolarTime.snapshot(next + 250, longitude, 0).apparentSolarMillisOfDay))
    }

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
