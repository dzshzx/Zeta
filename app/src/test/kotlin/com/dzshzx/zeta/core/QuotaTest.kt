package com.dzshzx.zeta.core

import com.dzshzx.zeta.i18n.UiLanguage
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun millis(text: String) = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    private val now = 1_795_000_000_000L // 2026-11-18T... UTC

    /**
     * 核心推来的 quota.snapshot 里的 snapshot（helper/accounts/quota.go 的 Snapshot 照 encoding/json 写出的样子）。
     */
    private val core =
        obj(
            """
        {"windows":[
           {"kind":"session","group":"session","used":0.31,"resetsAt":"2026-11-18T13:05:10Z","severity":"normal","label":"5 小时","labelCode":"quotaSession"},
           {"kind":"weekly_all","group":"weekly","used":0.86,"resetsAt":"2026-11-21T17:00:00.685462Z","severity":"warning","active":true,"label":"每周 · 全部模型"},
           {"kind":"weekly_scoped","group":"weekly","scope":"Fable","used":0.78,"severity":"critical","label":"每周 · Fable","labelCode":"quotaWeeklyScoped"}],
         "extra":{"enabled":true,"usedMinor":1234,"limitMinor":5000,"currency":"USD","used":0.25},
         "subscription":"max","blocked":true,"blockedUntil":"2026-11-18T20:05:10+08:00",
         "updatedAt":"2026-11-18T07:00:00.123456789+08:00"}
        """,
        )

    @Test
    fun coreSnapshotIsShownAsSent() {
        val s = Quota.fromCore(core)!!
        assertEquals(listOf("session", "weekly_all", "weekly_scoped"), s.windows.map { it.kind })
        // 名字、紧张程度都是核心给的
        assertEquals(listOf("5 小时", "每周 · 全部模型", "每周 · Fable"), s.windows.map { it.label })
        // 有种类码的按界面语言出字，没有的照核心给的名字
        UiLanguage.current = UiLanguage.ENGLISH
        try {
            assertEquals(
                listOf("5-hour", "每周 · 全部模型", "Weekly · Fable"),
                s.windows.map { it.labelSaid() },
            )
        } finally {
            UiLanguage.current = UiLanguage.SOURCE
        }
        assertEquals(
            listOf(QuotaSeverity.Normal, QuotaSeverity.Warning, QuotaSeverity.Critical),
            s.windows.map { it.severity },
        )
        assertEquals(listOf(null, null, "Fable"), s.windows.map { it.scope })
        assertEquals(0.86, s.windows[1].used, 1e-9)
        assertEquals(86, s.windows[1].percent)
        assertEquals(millis("2026-11-21T17:00:00.685462Z"), s.windows[1].resetsAt)
        assertNull(s.windows[2].resetsAt)
        assertEquals(listOf(false, true, false), s.windows.map { it.active })
        assertEquals(ExtraUsage(true, 1234.0, 5000.0, "USD", 0.25), s.extra)
        assertEquals("max", s.subscription)
        assertTrue(s.available)
        assertTrue(s.blocked)
        assertEquals(millis("2026-11-18T20:05:10+08:00"), s.blockedUntil)
        assertEquals(millis("2026-11-18T07:00:00.123456789+08:00"), s.updatedAt)
    }

    @Test
    fun noPlanNoDataOrNotASnapshot() {
        val none =
            Quota.fromCore(
                obj("""{"windows":null,"unavailable":true,"updatedAt":"2026-11-18T07:00:00Z"}"""),
            )!!
        assertFalse(none.available)
        assertTrue(none.windows.isEmpty())
        assertNull(none.subscription)
        assertNull(none.extra)
        // 核心说还没有数
        assertNull(Quota.fromCore(null))
        // 不是核心给的样子：没有收到的时刻，或者时刻是毫秒数（早先应用自己存的那种）
        assertNull(Quota.fromCore(obj("""{"windows":[]}""")))
        assertNull(Quota.fromSaved("""{"windows":[],"updatedAt":1795000000000}"""))
        assertNull(Quota.fromSaved("not json"))
        assertNull(Quota.fromSaved(null))
        assertEquals(Quota.fromCore(core), Quota.fromSaved(core.toString()))
    }

    @Test
    fun headlineAndCurrent() {
        val s = Quota.fromCore(core)!!
        // 被拒时是最满的那个；不然是服务器挑的，没挑时是最满的
        assertEquals("weekly_all", s.headline!!.kind)
        val open = s.copy(blocked = false, blockedUntil = null)
        assertEquals("weekly_all", open.headline!!.kind)
        val unpicked = open.copy(windows = open.windows.map { it.copy(active = false) })
        assertEquals("weekly_all", unpicked.headline!!.kind)
        // 过了重置和解除时刻：不再算被拒，5 小时窗口从零开始，别的不动
        val later = s.current(millis("2026-11-18T13:05:10Z") + 1)
        assertFalse(later.blocked)
        assertNull(later.blockedUntil)
        assertEquals(0.0, later.windows[0].used, 1e-9)
        assertNull(later.windows[0].resetsAt)
        assertEquals(QuotaSeverity.Normal, later.windows[0].severity)
        assertEquals(0.86, later.windows[1].used, 1e-9)
        assertSame(s, s.current(now))
    }

    @Test
    fun resetAndAgeTexts() {
        val zone = ZoneId.of("Asia/Shanghai")
        assertEquals("45 分钟后重置", Quota.resetText(now + 45 * 60_000 - 10, now, zone))
        assertEquals("2 小时 5 分钟后重置", Quota.resetText(now + 125 * 60_000, now, zone))
        assertEquals("3 小时后重置", Quota.resetText(now + 180 * 60_000, now, zone))
        val weekly = millis("2026-11-22T01:00:00+08:00")
        assertEquals("周日 01:00 重置", Quota.resetText(weekly, now, zone))
        assertEquals("已重置", Quota.resetText(now - 1, now, zone))
        assertNull(Quota.resetText(null, now, zone))
        assertEquals("刚刚", Quota.ageText(now - 30_000, now))
        assertEquals("5 分钟前", Quota.ageText(now - 5 * 60_000, now))
        assertEquals("$12.34", Quota.money(1234.0, "USD"))
    }
}
