package com.dzshzx.zeta.phone.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 数值都是编造的。 */
class HealthSummaryTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-11-19 10:30 北京时间。 */
    private val now = ZonedDateTime.of(2026, 11, 19, 10, 30, 0, 0, zone).toInstant().toEpochMilli()

    private fun ms(text: String) = PersonalQueries.parseTime(text, zone)!!

    private val start = ms("2026-11-13")

    private fun format(data: HealthData) = HealthSummary.format(data, start, now, zone, now).lines()

    @Test
    fun defaultWindowIsSevenDaysIncludingToday() {
        assertEquals(ms("2026-11-13"), HealthSummary.defaultStart(now, zone))
        val request = PersonalQueries.searchRequest(kotlinx.serialization.json.buildJsonObject { put("source", kotlinx.serialization.json.JsonPrimitive("health")) }, now, zone)
        assertEquals(ms("2026-11-13"), request.start)
        assertEquals(now, request.end)
    }

    @Test
    fun permissionsMatchKindsPlusBackgroundAndHistory() {
        assertEquals(HealthKind.entries.size + 2, HealthSummary.PERMISSIONS.size)
        assertTrue(HealthSummary.PERMISSIONS.all { it.startsWith("android.permission.health.READ_") })
    }

    @Test
    fun formatsEveryKind() {
        val data = HealthData(
            stepsTotal = 30_000,
            stepsByDay = listOf(LocalDate.of(2026, 11, 17) to 12_000L, LocalDate.of(2026, 11, 18) to 18_000L, LocalDate.of(2026, 11, 19) to 0L),
            sleep = listOf(
                HealthSession(ms("2026-11-17T23:30"), ms("2026-11-18T07:00")),
                HealthSession(ms("2026-11-18T23:00"), ms("2026-11-19T06:30")),
                HealthSession(ms("2026-11-19T13:00"), ms("2026-11-19T13:30")),
            ),
            exercise = listOf(HealthSession(ms("2026-11-18T18:30"), ms("2026-11-18T19:02"), 56, "夜跑")),
            heart = HeartStats(72, 51, 148, 4_321),
            restingHeart = HeartStats(58, 55, 61, null),
            weight = listOf(HealthReading(ms("2026-11-18T07:30"), 68.2), HealthReading(ms("2026-11-14T07:30"), 68.45)),
            oxygen = listOf(HealthReading(ms("2026-11-19T06:00"), 97.0)),
        )
        val lines = format(data)
        assertEquals("健康数据 2026-11-13 00:00 到 2026-11-19 10:30（时间为 UTC+08:00；来自健康连接，由小米运动健康等应用写入）", lines[0])
        assertEquals("步数：合计 30000，日均 15000（按有记录的 2 天）；每天 11-17 12000 · 11-18 18000", lines[1])
        assertEquals(
            "睡眠：3 段，合计 15h30m，每晚平均 7h45m（2 晚）；最近 11-19 13:00–13:30 30m · 11-18 23:00–06:30 7h30m · 11-17 23:30–07:00 7h30m",
            lines[2],
        )
        assertEquals("运动：1 次，合计 32m；最近 11-18 18:30 跑步 32m「夜跑」", lines[3])
        assertEquals("心率：平均 72，最低 51，最高 148 次/分（4321 个读数）；静息心率平均 58（55–61）", lines[4])
        assertEquals("体重：最近 68.2 kg（11-18 07:30）；这段时间 2 次，68.2–68.5 kg，平均 68.3 kg", lines[5])
        assertEquals("血氧：最近 97%（11-19 06:00）", lines[6])
        assertEquals(7, lines.size)
    }

    @Test
    fun saysWhatIsMissingDeniedOrFailed() {
        val lines = format(
            HealthData(
                denied = setOf(HealthKind.Weight, HealthKind.Oxygen),
                failed = mapOf(HealthKind.HeartRate to "健康连接拒绝读取：x"),
            ),
        )
        assertEquals("步数：这段时间没有记录", lines[1])
        assertEquals("睡眠：这段时间没有记录", lines[2])
        assertEquals("运动：这段时间没有记录", lines[3])
        // 心率没读成、也没有静息心率：不列“没有记录”，只说没读到
        assertEquals("心率没读到：健康连接拒绝读取：x", lines[4])
        assertTrue(lines[5].startsWith("没有读权限：体重、血氧。"))
        assertTrue(lines[5].contains("权限健康"))
        assertEquals(6, lines.size)
        val withResting = format(HealthData(restingHeart = HeartStats(58, 55, 61, null), failed = mapOf(HealthKind.HeartRate to "x")))
        assertEquals("心率：静息心率平均 58（55–61）", withResting[4])
        assertFalse(lines.any { it.startsWith("体重：") || it.startsWith("血氧：") })
    }

    @Test
    fun listsAreCapped() {
        val sleep = (0 until 20).map { HealthSession(start + it * PersonalQueries.DAY, start + it * PersonalQueries.DAY + PersonalQueries.HOUR) }
        val line = format(HealthData(sleep = sleep))[2]
        assertEquals(HealthSummary.LIST_MAX, line.substringAfter("最近 ").split(" · ").size)
        assertTrue(line.endsWith(" 等"))
    }

    @Test
    fun namesExerciseTypes() {
        assertEquals("跑步", HealthSummary.exerciseName(56))
        assertEquals("运动（类型 999）", HealthSummary.exerciseName(999))
        assertEquals("1h05m", HealthSummary.hm(65 * PersonalQueries.MINUTE))
        assertEquals("0m", HealthSummary.hm(10_000))
    }
}
