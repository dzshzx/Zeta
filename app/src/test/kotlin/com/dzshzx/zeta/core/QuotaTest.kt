package com.dzshzx.zeta.core

import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val now = 1_795_000_000_000L // 2026-11-18T... UTC

    /** get_usage 的应答，形状照 2.1.328 在真账号上的实测（数改过，多余的键删了一些）。 */
    private val serverRows = obj(
        """
        {"session":{"total_cost_usd":0},"subscription_type":"max","rate_limits_available":true,
         "rate_limits":{"five_hour":null,
           "seven_day":{"utilization":86,"resets_at":"2026-11-21T17:00:00.685462+00:00","limit_dollars":null},
           "seven_day_opus":null,"seven_day_sonnet":null,
           "extra_usage":{"is_enabled":false,"monthly_limit":null,"used_credits":null,"utilization":null,"currency":null},
           "limits":[
             {"kind":"weekly_all","group":"weekly","percent":86,"severity":"warning","resets_at":"2026-11-21T17:00:00.685462+00:00","scope":null,"is_active":true},
             {"kind":"weekly_scoped","group":"weekly","percent":78,"severity":"warning","resets_at":"2026-11-21T16:59:59.685664+00:00",
              "scope":{"model":{"id":null,"display_name":"Fable"},"surface":null},"is_active":false}
           ],
           "model_scoped":[{"display_name":"Fable","utilization":78,"resets_at":"2026-11-21T16:59:59.685664+00:00"}]},
         "behaviors":null}
        """,
    )

    @Test
    fun serverRowsAreShownAsSent() {
        val s = Quota.parseUsage(serverRows, now)!!
        assertEquals(listOf("weekly_all", "weekly_scoped"), s.windows.map { it.kind })
        assertEquals(listOf("每周 · 全部模型", "每周 · Fable"), s.windows.map { it.label })
        assertEquals(0.86, s.windows[0].used, 1e-9)
        assertEquals(QuotaSeverity.Warning, s.windows[1].severity)
        assertEquals(java.time.OffsetDateTime.parse("2026-11-21T17:00:00.685462+00:00").toInstant().toEpochMilli(), s.windows[0].resetsAt)
        assertEquals("max", s.subscription)
        assertFalse(s.extra!!.enabled)
        // 服务器挑的那一行是头条；86% 是“要注意”
        assertEquals("weekly_all", s.headline!!.kind)
        assertEquals("weekly_all", s.alarming!!.kind)
        assertEquals("本周", s.headline!!.spokenName)
    }

    @Test
    fun fixedKeysWhenNoServerRows() {
        // Claude Code 只拿到响应头里的数时（契约测试里就是这样），只有 five_hour、seven_day
        val s = Quota.parseUsage(
            obj(
                """{"subscription_type":"max","rate_limits_available":true,"rate_limits":{
                   "five_hour":{"utilization":25,"resets_at":"2026-11-19T03:05:10.000Z"},
                   "seven_day":{"utilization":40,"resets_at":"2026-11-22T02:05:10.000Z"},
                   "seven_day_sonnet":{"utilization":5,"resets_at":"2026-11-22T02:05:10.000Z"},
                   "model_scoped":[{"display_name":"Fable","utilization":10,"resets_at":null}]}}""",
            ),
            now,
        )!!
        assertEquals(listOf("5 小时", "每周 · 全部模型", "每周 · Sonnet", "每周 · Fable"), s.windows.map { it.label })
        assertEquals(0.25, s.windows[0].used, 1e-9)
        assertNull(s.windows[3].resetsAt)
        assertNull(s.alarming)
        // 没有服务器挑的：最满的那个
        assertEquals("weekly_all", s.headline!!.kind)
    }

    @Test
    fun notAUsageAnswerOrNoPlan() {
        assertNull(Quota.parseUsage(obj("""{"models":[]}"""), now))
        val none = Quota.parseUsage(obj("""{"subscription_type":null,"rate_limits_available":false,"rate_limits":null}"""), now)!!
        assertFalse(none.available)
        assertTrue(none.windows.isEmpty())
        val empty = Quota.parseUsage(obj("""{"subscription_type":"max","rate_limits_available":true,"rate_limits":null}"""), now)!!
        assertTrue(empty.available && empty.windows.isEmpty())
    }

    /** rate_limit_event 的 rate_limit_info，照 2.1.328 实测的形状。 */
    private fun event(five: Double, status: String = "allowed", fiveStatus: String = "allowed") = obj(
        """{"status":"$status","resetsAt":1795057510,"rateLimitType":"five_hour","isUsingOverage":false,
            "unifiedWindows":{"five_hour":{"utilization":$five,"resetsAt":1795057510,"status":"$fiveStatus"},
                              "seven_day":{"utilization":0.4,"resetsAt":1795313110,"status":"allowed"}}}""",
    )

    @Test
    fun eventsUpdateTheirWindowsAndKeepTheRest() {
        val fromEvent = Quota.applyEvent(null, event(0.25), now)!!
        assertEquals(listOf("session", "weekly_all"), fromEvent.windows.map { it.kind })
        assertEquals(1_795_057_510_000L, fromEvent.windows[0].resetsAt)
        assertFalse(fromEvent.blocked)

        val server = Quota.parseUsage(serverRows, now)!!
        val merged = Quota.applyEvent(server, event(0.9, fiveStatus = "allowed_warning"), now + 1)!!
        // 5 小时窗口是新出现的，放在最前；每周的被事件的数替换，服务器挑的头条标记留着；按模型分的那行不动
        assertEquals(listOf("session", "weekly_all", "weekly_scoped"), merged.windows.map { it.kind })
        assertEquals(QuotaSeverity.Warning, merged.windows[0].severity)
        assertEquals(0.4, merged.windows[1].used, 1e-9)
        assertTrue(merged.windows[1].active)
        assertEquals(0.78, merged.windows[2].used, 1e-9)

        val rejected = Quota.applyEvent(merged, event(1.0, status = "rejected", fiveStatus = "rejected"), now + 2)!!
        assertTrue(rejected.blocked)
        assertEquals(1_795_057_510_000L, rejected.blockedUntil)
        assertEquals("session", rejected.alarming!!.kind)
        // 过了解除时刻：不再算被拒，5 小时窗口从零开始
        val later = rejected.current(1_795_057_510_001L)
        assertFalse(later.blocked)
        assertEquals(0.0, later.windows[0].used, 1e-9)
        assertNull(later.windows[0].resetsAt)
    }

    @Test
    fun resetAndAgeTexts() {
        val zone = ZoneId.of("Asia/Shanghai")
        assertEquals("45 分钟后重置", Quota.resetText(now + 45 * 60_000 - 10, now, zone))
        assertEquals("2 小时 5 分钟后重置", Quota.resetText(now + 125 * 60_000, now, zone))
        assertEquals("3 小时后重置", Quota.resetText(now + 180 * 60_000, now, zone))
        val weekly = java.time.OffsetDateTime.parse("2026-11-22T01:00:00+08:00").toInstant().toEpochMilli()
        assertEquals("周日 01:00 重置", Quota.resetText(weekly, now, zone))
        assertEquals("已重置", Quota.resetText(now - 1, now, zone))
        assertNull(Quota.resetText(null, now, zone))
        assertEquals("刚刚", Quota.ageText(now - 30_000, now))
        assertEquals("5 分钟前", Quota.ageText(now - 5 * 60_000, now))
        assertEquals("$12.34", Quota.money(1234.0, "USD"))
    }

    @Test
    fun savedSnapshotRoundTrips() {
        val s = Quota.applyEvent(Quota.parseUsage(serverRows, now), event(1.0, status = "rejected"), now)!!
        val back = Quota.decode(Quota.encode(s))
        assertNotNull(back)
        assertEquals(s, back)
        assertNull(Quota.decode("not json"))
    }
}
