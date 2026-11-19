package com.dzshzx.zeta.phone.tools

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.schedule.RunReport
import com.dzshzx.zeta.core.schedule.ScheduleRule
import com.dzshzx.zeta.core.schedule.ScheduleStore
import com.dzshzx.zeta.core.schedule.ScheduledTask
import com.dzshzx.zeta.core.schedule.UnattendedRun
import com.dzshzx.zeta.core.str
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 定时任务的手机工具：修改只动传了的字段、时间参数怎么并进原来的规则；后台运行时加给 Claude Code 的权限规则；
 * 上报记进这次运行。经 [ScheduleStore] 读写的那几样在 Robolectric 上真走一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ScheduleToolsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val now: Instant = LocalDateTime.parse("2026-11-19T10:00").atZone(shanghai).toInstant()

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun text(result: JsonObject): String =
        ((result["content"] as JsonArray)[0] as JsonObject).str("text").orEmpty()

    private fun failed(result: JsonObject) = result.bool("isError") == true

    private val daily = ScheduledTask(
        id = "t1", name = "早报", prompt = "汇总通知", rule = ScheduleRule.Daily(LocalTime.of(8, 0)),
        newSession = false, sessionId = "s-1", created = 1,
    )

    private fun update(task: ScheduledTask, json: String) = ScheduleTools.updated(task, args(json), now, shanghai)

    @Before
    fun clear() {
        ScheduleStore.update(app) { emptyList() }
    }

    @Test
    fun unattendedRulesDenyOnlyZetasManagingTools() {
        // 工具清单不随后台运行变：上报一直列着
        assertEquals(
            listOf("create_scheduled_task", "list_scheduled_tasks", "update_scheduled_task", "delete_scheduled_task", "run_scheduled_task", "report_scheduled_run"),
            ScheduleTools.tools.map { (it as JsonObject).str("name") },
        )
        assertEquals(
            """{"deny":["mcp__phone__create_scheduled_task","mcp__phone__list_scheduled_tasks","mcp__phone__update_scheduled_task",""" +
                """"mcp__phone__delete_scheduled_task","mcp__phone__run_scheduled_task"],"allow":["mcp__phone__report_scheduled_run"]}""",
            ScheduleTools.unattendedRules.toString(),
        )
    }

    @Test
    fun reportIsRecordedOnTheRun() {
        val run = UnattendedRun(onlyOnChange = true)
        val quiet = ScheduleTools.report(args("""{"outcome":"no_change","summary":"价格还是 2999"}"""), run)
        assertFalse(failed(quiet))
        assertEquals(RunReport(changed = false, summary = "价格还是 2999"), run.report)
        assertTrue(failed(ScheduleTools.report(args("""{"outcome":"deliver","summary":" "}"""), run)))
        assertTrue(failed(ScheduleTools.report(args("""{"outcome":"maybe","summary":"x"}"""), run)))
        ScheduleTools.report(args("""{"outcome":"deliver","summary":"降到 2799 了"}"""), run)
        assertEquals(RunReport(changed = true, summary = "降到 2799 了"), run.report)
        // 平常的对话、不是只在有变化时提醒的运行：回一句不用上报，不算出错
        val idle = ScheduleTools.report(args("""{"outcome":"no_change","summary":"x"}"""), null)
        assertTrue(!failed(idle) && "不用上报" in text(idle))
        val always = UnattendedRun(onlyOnChange = false)
        assertTrue("不用上报" in text(ScheduleTools.report(args("""{"outcome":"no_change","summary":"x"}"""), always)))
        assertNull(always.report)
    }

    @Test
    fun updateChangesOnlyWhatIsGiven() {
        val renamed = update(daily, """{"id":"t1","name":"晨报","only_on_change":true}""")
        assertEquals(daily.copy(name = "晨报", onlyOnChange = true), renamed)
        // 只给 time：还是每天；换了会话方式，原来接着的会话不要了
        assertEquals(daily.copy(rule = ScheduleRule.Daily(LocalTime.of(9, 30))), update(daily, """{"id":"t1","time":"09:30"}"""))
        assertNull(update(daily, """{"id":"t1","new_session":true}""").sessionId)
        // 空字符串回到默认
        val custom = daily.copy(cwd = "/workspace/a", model = "opus", permissionMode = "plan")
        assertEquals(daily, update(custom, """{"id":"t1","cwd":"","model":"","permission_mode":""}"""))
        assertEquals(custom, update(custom, """{"id":"t1","cwd":null}"""))
        assertEquals(
            "permission_mode 只能是 default、acceptEdits、plan、auto、bypassPermissions",
            runCatching { update(daily, """{"id":"t1","permission_mode":"yolo"}""") }.exceptionOrNull()?.message,
        )
    }

    @Test
    fun scheduleFieldsMergeIntoTheOldRule() {
        val weekly = daily.copy(rule = ScheduleRule.Weekly(setOf(DayOfWeek.MONDAY), LocalTime.of(8, 0)))
        assertEquals(
            ScheduleRule.Weekly(setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY), LocalTime.of(8, 0)),
            update(weekly, """{"id":"t1","days":["tue","fri"]}""").rule,
        )
        // 换成每周：沿用原来的时刻，但要给星期
        assertEquals(
            ScheduleRule.Weekly(setOf(DayOfWeek.SATURDAY), LocalTime.of(8, 0)),
            update(daily, """{"id":"t1","repeat":"weekly","days":["sat"]}""").rule,
        )
        assertTrue(runCatching { update(daily, """{"id":"t1","repeat":"weekly"}""") }.exceptionOrNull() is BadInput)
        val interval = daily.copy(rule = ScheduleRule.Interval(3, LocalDateTime.parse("2026-11-19T06:00")))
        assertEquals(ScheduleRule.Interval(6, LocalDateTime.parse("2026-11-19T06:00")), update(interval, """{"id":"t1","every_hours":6}""").rule)
        // 单次任务只改时刻：同一天
        val once = daily.copy(rule = ScheduleRule.Once(LocalDateTime.parse("2026-11-20T08:00")))
        assertEquals(ScheduleRule.Once(LocalDateTime.parse("2026-11-20T21:15")), update(once, """{"id":"t1","time":"21:15"}""").rule)
        assertTrue(runCatching { update(daily, """{"id":"t1","time":"9点"}""") }.exceptionOrNull() is BadInput)
    }

    @Test
    fun changingTheScheduleTurnsATaskBackOn() {
        // 跑过的单次任务自动关了：改名不用给新时间，改时间就重新打开
        val ran = daily.copy(rule = ScheduleRule.Once(LocalDateTime.parse("2026-11-18T08:00")), enabled = false)
        assertEquals(ran.copy(name = "旧提醒"), update(ran, """{"id":"t1","name":"旧提醒"}"""))
        val again = update(ran, """{"id":"t1","at":"2026-11-21T08:00"}""")
        assertTrue(again.enabled)
        assertEquals(ScheduleRule.Once(LocalDateTime.parse("2026-11-21T08:00")), again.rule)
        assertFalse(update(ran, """{"id":"t1","at":"2026-11-21T08:00","enabled":false}""").enabled)
        // 时间已过：报错里带上手机本地的现在
        val past = runCatching { update(ran, """{"id":"t1","at":"2026-11-19T09:00"}""") }.exceptionOrNull()?.message.orEmpty()
        assertTrue(past, past.startsWith("这个时间已经过了") && "2026-11-19T10:00" in past)
        assertFalse(update(daily, """{"id":"t1","enabled":false}""").enabled)
    }

    @Test
    fun toolsReadAndWriteTheStore() {
        val created = ScheduleTools.call(
            app, "create_scheduled_task",
            args("""{"name":"价格监控","prompt":"看看降价没有","repeat":"interval","every_hours":3,"only_on_change":true}"""),
        )
        assertFalse(text(created), failed(created))
        val task = ScheduleStore.load(app).single()
        assertTrue(task.onlyOnChange)
        val updated = ScheduleTools.call(app, "update_scheduled_task", args("""{"id":"${task.id}","every_hours":6,"only_on_change":false}"""))
        assertFalse(text(updated), failed(updated))
        assertTrue(text(updated), "每 6 小时" in text(updated))
        val saved = ScheduleStore.load(app).single()
        assertEquals(6, (saved.rule as ScheduleRule.Interval).hours)
        assertFalse(saved.onlyOnChange)
        assertTrue("没有要改的" in text(ScheduleTools.call(app, "update_scheduled_task", args("""{"id":"${task.id}"}"""))))
        assertTrue(failed(ScheduleTools.call(app, "update_scheduled_task", args("""{"id":"nope","name":"x"}"""))))
        assertTrue(failed(ScheduleTools.call(app, "run_scheduled_task", args("""{"id":"nope"}"""))))
        assertTrue("只在有变化时提醒" !in text(ScheduleTools.call(app, "list_scheduled_tasks", args("{}"))))
    }
}
