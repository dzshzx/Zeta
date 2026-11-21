package com.dzshzx.zeta.core.schedule

import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.NoticeItem
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleModelTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun local(text: String, zone: ZoneId = shanghai): Instant =
        LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun next(rule: ScheduleRule, after: String, zone: ZoneId = shanghai): Instant? =
        Schedules.nextTrigger(rule, local(after, zone), zone)

    @Test
    fun dailyRollsOverToTomorrowOnceTimePassed() {
        val rule = ScheduleRule.Daily(LocalTime.of(8, 0))
        assertEquals(local("2026-11-19T08:00"), next(rule, "2026-11-19T07:59:30"))
        // 正好在触发的那一刻：要的是下一次，不能再排到同一刻
        assertEquals(local("2026-11-20T08:00"), next(rule, "2026-11-19T08:00"))
        assertEquals(local("2026-11-20T08:00"), next(rule, "2026-11-19T23:59"))
        // 跨月、跨年
        assertEquals(local("2026-12-01T08:00"), next(rule, "2026-11-30T09:00"))
        assertEquals(local("2027-01-01T08:00"), next(rule, "2026-12-31T08:00:01"))
    }

    @Test
    fun weeklyPicksNextSelectedDay() {
        // 2026-11-19 是周四
        val rule =
            ScheduleRule.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), LocalTime.of(9, 30))
        assertEquals(local("2026-11-19T09:30"), next(rule, "2026-11-19T09:00"))
        assertEquals(local("2026-11-23T09:30"), next(rule, "2026-11-19T09:30"))
        assertEquals(local("2026-11-23T09:30"), next(rule, "2026-11-21T12:00"))
        // 只选了今天这一天、今天的时刻已过：排到下周同一天
        val thursday = ScheduleRule.Weekly(setOf(DayOfWeek.THURSDAY), LocalTime.of(9, 30))
        assertEquals(local("2026-11-26T09:30"), next(thursday, "2026-11-19T10:00"))
        // 周日晚到下周一，跨月
        val monday = ScheduleRule.Weekly(setOf(DayOfWeek.MONDAY), LocalTime.of(0, 5))
        assertEquals(local("2026-11-30T00:05"), next(monday, "2026-11-29T23:59"))
        assertNull(next(ScheduleRule.Weekly(emptySet(), LocalTime.NOON), "2026-11-19T10:00"))
    }

    @Test
    fun onceFiresOnlyBeforeItsTime() {
        val rule = ScheduleRule.Once(LocalDateTime.parse("2026-11-20T08:00"))
        assertEquals(local("2026-11-20T08:00"), next(rule, "2026-11-19T10:00"))
        assertNull(next(rule, "2026-11-20T08:00"))
        assertNull(next(rule, "2026-11-21T00:00"))
    }

    @Test
    fun intervalCountsFromStartAcrossDays() {
        val rule = ScheduleRule.Interval(5, LocalDateTime.parse("2026-11-19T08:00"))
        assertEquals(local("2026-11-19T08:00"), next(rule, "2026-11-19T07:00"))
        assertEquals(local("2026-11-19T13:00"), next(rule, "2026-11-19T08:00"))
        assertEquals(local("2026-11-19T13:00"), next(rule, "2026-11-19T12:59:59"))
        // 23:00 之后是第二天 04:00
        assertEquals(local("2026-11-20T04:00"), next(rule, "2026-11-19T23:00"))
        // 08:00 起每 5 小时，两天后是 10:00（不是每天同一时刻）
        assertEquals(local("2026-11-21T10:00"), next(rule, "2026-11-21T08:30"))
    }

    @Test
    fun localTimesFollowTheZone() {
        val rule = ScheduleRule.Daily(LocalTime.of(8, 0))
        val utc = ZoneId.of("UTC")
        // 同一个瞬间，在上海已过 8 点、在 UTC 还没到
        val instant = local("2026-11-19T09:00")
        assertEquals(local("2026-11-20T08:00"), Schedules.nextTrigger(rule, instant, shanghai))
        assertEquals(local("2026-11-19T08:00", utc), Schedules.nextTrigger(rule, instant, utc))
    }

    @Test
    fun describesRulesInChinese() {
        assertEquals("每天 08:00", Schedules.describe(ScheduleRule.Daily(LocalTime.of(8, 0))))
        assertEquals(
            "工作日 07:30",
            Schedules.describe(
                ScheduleRule.Weekly(DayOfWeek.entries.take(5).toSet(), LocalTime.of(7, 30)),
            ),
        )
        assertEquals(
            "每周一、三 21:05",
            Schedules.describe(
                ScheduleRule.Weekly(
                    setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY),
                    LocalTime.of(21, 5),
                ),
            ),
        )
        assertEquals(
            "仅一次 · 11月20日 08:00",
            Schedules.describe(ScheduleRule.Once(LocalDateTime.parse("2026-11-20T08:00"))),
        )
        assertEquals(
            "每 3 小时 · 从 08:00 起",
            Schedules.describe(ScheduleRule.Interval(3, LocalDateTime.parse("2026-11-19T08:00"))),
        )
    }

    @Test
    fun formatsRelativeDays() {
        val now = local("2026-11-19T10:00")
        assertEquals("今天 18:00", Schedules.formatWhen(local("2026-11-19T18:00"), now, shanghai))
        assertEquals("明天 08:00", Schedules.formatWhen(local("2026-11-20T08:00"), now, shanghai))
        assertEquals(
            "11月22日 周日 08:00",
            Schedules.formatWhen(local("2026-11-22T08:00"), now, shanghai),
        )
        assertEquals(
            "2027年1月4日 周一 08:00",
            Schedules.formatWhen(local("2027-01-04T08:00"), now, shanghai),
        )
    }

    @Test
    fun roundTripsThroughJson() {
        val tasks =
            listOf(
                ScheduledTask(
                    id = "a1",
                    name = "每日简报",
                    prompt = "汇总今天的通知和日程",
                    rule =
                        ScheduleRule.Weekly(
                            setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                            LocalTime.of(8, 0),
                        ),
                    cwd = "/workspace/claude",
                    model = "sonnet",
                    permissionMode = "acceptEdits",
                    newSession = false,
                    sessionId = "s-1",
                    created = 5,
                    lastRun = TaskRun(10, RunOutcome.Done, "s-1", "今天有 3 个日程", manual = true),
                    skips = 2,
                    lastSkip = 9,
                ),
                ScheduledTask(
                    id = "b2",
                    name = "提醒",
                    prompt = "say hi",
                    rule = ScheduleRule.Once(LocalDateTime.parse("2026-11-20T08:00")),
                    enabled = false,
                ),
                ScheduledTask(
                    id = "c3",
                    name = "巡检",
                    prompt = "check",
                    rule = ScheduleRule.Interval(6, LocalDateTime.parse("2026-11-19T00:00")),
                    onlyOnChange = true,
                    lastRun = TaskRun(20, RunOutcome.NoChange, "s-2", "价格还是 2999"),
                ),
                ScheduledTask(
                    id = "d4",
                    name = "早安",
                    prompt = "hi",
                    rule = ScheduleRule.Daily(LocalTime.of(7, 5)),
                ),
            )
        assertEquals(tasks, Schedules.decode(Schedules.encode(tasks)))
    }

    @Test
    fun decodingSkipsBrokenEntriesAndFillsDefaults() {
        assertEquals(emptyList<ScheduledTask>(), Schedules.decode(null))
        assertEquals(emptyList<ScheduledTask>(), Schedules.decode("not json"))
        assertEquals(emptyList<ScheduledTask>(), Schedules.decode("{\"id\":\"x\"}"))
        val text =
            """
            [
              {"id":"ok","prompt":"hello world","rule":{"kind":"daily","time":"8:00"},"extra":{"future":1},"lastRun":{"at":"x"}},
              {"id":"no-rule","prompt":"x"},
              {"id":"bad-time","prompt":"x","rule":{"kind":"daily","time":"25:00"}},
              {"id":"bad-kind","prompt":"x","rule":{"kind":"monthly"}},
              {"id":"no-days","prompt":"x","rule":{"kind":"weekly","days":[],"time":"08:00"}},
              {"prompt":"no id","rule":{"kind":"daily","time":"08:00"}},
              {"id":"ok","prompt":"duplicate id","rule":{"kind":"daily","time":"09:00"}},
              {"id":"days","prompt":"x","rule":{"kind":"weekly","days":["mon",3,"x",9],"time":"0730"},"enabled":"maybe","skips":-4},
              42
            ]
            """
                .trimIndent()
        val tasks = Schedules.decode(text)
        assertEquals(listOf("ok", "days"), tasks.map { it.id })
        val ok = tasks[0]
        assertEquals("hello world", ok.name)
        assertEquals(ScheduleRule.Daily(LocalTime.of(8, 0)), ok.rule)
        assertTrue(ok.enabled && ok.newSession)
        assertNull(ok.lastRun)
        val days = tasks[1]
        assertEquals(
            ScheduleRule.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), LocalTime.of(7, 30)),
            days.rule,
        )
        assertTrue(days.enabled)
        assertEquals(0, days.skips)
    }

    @Test
    fun validatesDrafts() {
        val now = local("2026-11-19T10:00")
        val ok = TaskDraft("简报", "汇总通知", ScheduleRule.Daily(LocalTime.of(8, 0)))
        assertNull(Schedules.problem(ok, now, shanghai))
        assertNotNull(Schedules.problem(ok.copy(name = " "), now, shanghai))
        assertNotNull(Schedules.problem(ok.copy(prompt = "\n"), now, shanghai))
        assertNotNull(Schedules.problem(ok.copy(cwd = "relative/dir"), now, shanghai))
        assertNull(Schedules.problem(ok.copy(cwd = "  "), now, shanghai))
        assertNotNull(
            Schedules.problem(
                ok.copy(rule = ScheduleRule.Once(LocalDateTime.parse("2026-11-19T09:59"))),
                now,
                shanghai,
            ),
        )
        assertNull(
            Schedules.problem(
                ok.copy(rule = ScheduleRule.Once(LocalDateTime.parse("2026-11-19T10:01"))),
                now,
                shanghai,
            ),
        )
        assertNotNull(
            Schedules.problem(
                ok.copy(rule = ScheduleRule.Weekly(emptySet(), LocalTime.NOON)),
                now,
                shanghai,
            ),
        )
        assertNotNull(
            Schedules.problem(
                ok.copy(rule = ScheduleRule.Interval(0, LocalDateTime.parse("2026-11-19T08:00"))),
                now,
                shanghai,
            ),
        )
        // 不看时间：跑过的单次任务关着时也能改名
        val past =
            ok.copy(
                rule = ScheduleRule.Once(LocalDateTime.parse("2026-11-18T08:00")),
                enabled = false,
            )
        assertNotNull(Schedules.problem(past, now, shanghai))
        assertNull(Schedules.problem(past, now, shanghai, checkRule = false))
        assertNotNull(Schedules.problem(past.copy(name = ""), now, shanghai, checkRule = false))
    }

    /** 设置页的编辑器和 update_scheduled_task 共用的开关与校验规则。 */
    @Test
    fun editTurnsOnWhenTheScheduleChanges() {
        val now = local("2026-11-19T10:00")
        val daily =
            ScheduledTask(
                id = "a",
                name = "简报",
                prompt = "汇总通知",
                rule = ScheduleRule.Daily(LocalTime.of(8, 0)),
            )
        fun edit(task: ScheduledTask?, draft: TaskDraft, enabled: Boolean? = null) =
            Schedules.edit(task, draft, enabled, now, shanghai)
        // 新建：总是打开、总要看时间（draft 自己的 enabled 不看）
        val fresh =
            TaskDraft(
                "简报",
                "汇总通知",
                ScheduleRule.Once(LocalDateTime.parse("2026-11-19T09:00")),
                enabled = false,
            )
        assertEquals(Edited(fresh.copy(enabled = true), "这个时间已经过了"), edit(null, fresh))
        // 关着的任务：只改文字保持关着；改了时间就打开，除非明确给了开关
        val off = daily.copy(enabled = false)
        assertEquals(false, edit(off, Schedules.draftOf(off).copy(name = "晨报")).draft.enabled)
        val moved = Schedules.draftOf(off).copy(rule = ScheduleRule.Daily(LocalTime.of(9, 0)))
        assertEquals(true, edit(off, moved).draft.enabled)
        assertEquals(false, edit(off, moved, enabled = false).draft.enabled)
        assertEquals(false, edit(daily, Schedules.draftOf(daily), enabled = false).draft.enabled)
        // 跑过、被自动关掉的单次任务：改名不看时间；改了时间、或要打开它，就要看
        val ran =
            daily.copy(
                rule = ScheduleRule.Once(LocalDateTime.parse("2026-11-18T08:00")),
                enabled = false,
            )
        assertNull(edit(ran, Schedules.draftOf(ran).copy(name = "旧提醒")).problem)
        assertEquals("这个时间已经过了", edit(ran, Schedules.draftOf(ran), enabled = true).problem)
        assertEquals(
            "这个时间已经过了",
            edit(
                    ran,
                    Schedules.draftOf(ran)
                        .copy(rule = ScheduleRule.Once(LocalDateTime.parse("2026-11-19T09:00"))),
                )
                .problem,
        )
        assertEquals("写一个名字", edit(ran, Schedules.draftOf(ran).copy(name = " ")).problem)
    }

    @Test
    fun rulesKnowTheirKindAndTime() {
        val at = LocalDateTime.parse("2026-11-20T07:45")
        val rules =
            listOf(
                ScheduleRule.Once(at),
                ScheduleRule.Daily(LocalTime.of(7, 45)),
                ScheduleRule.Weekly(setOf(DayOfWeek.MONDAY), LocalTime.of(7, 45)),
                ScheduleRule.Interval(3, at),
            )
        assertEquals(RuleKind.entries.toList(), rules.map { it.kind })
        assertEquals(List(4) { LocalTime.of(7, 45) }, rules.map { it.time })
        assertEquals(listOf("once", "daily", "weekly", "interval"), RuleKind.entries.map { it.key })
        assertEquals(RuleKind.Weekly, RuleKind.of("weekly"))
        assertNull(RuleKind.of("monthly"))
    }

    @Test
    fun editingKeepsHistoryButDropsSessionWhenModeChanges() {
        val task =
            ScheduledTask(
                id = "a",
                name = "n",
                prompt = "p",
                rule = ScheduleRule.Daily(LocalTime.NOON),
                newSession = false,
                sessionId = "s",
                lastRun = TaskRun(1, RunOutcome.Done),
            )
        val same =
            Schedules.applyDraft(task, Schedules.draftOf(task).copy(name = " 新名字 ", cwd = " "))
        assertEquals("新名字", same.name)
        assertNull(same.cwd)
        assertEquals("s", same.sessionId)
        assertEquals(task.lastRun, same.lastRun)
        assertNull(
            Schedules.applyDraft(task, Schedules.draftOf(task).copy(newSession = true)).sessionId,
        )
        val quiet = Schedules.applyDraft(task, Schedules.draftOf(task).copy(onlyOnChange = true))
        assertTrue(quiet.onlyOnChange)
        assertEquals(quiet, Schedules.applyDraft(quiet, Schedules.draftOf(quiet)))
    }

    /** 跑完怎么记、发不发通知：只有“只在有变化时提醒”、正常跑完、上报了没有变化、也没等过确认的才不发。 */
    @Test
    fun onlyQuietRunsSkipTheNotification() {
        val noChange = RunReport(changed = false, summary = "价格还是 **2999**")
        val deliver = RunReport(changed = true, summary = "降到 2799 了")
        assertEquals(
            Settled(RunOutcome.NoChange, "价格还是 2999", notify = false),
            Schedules.settle(RunOutcome.Done, "回答", true, noChange, asked = false),
        )
        // 等过确认、没上报、出错、被叫停：照常
        assertEquals(
            Settled(RunOutcome.NoChange, "价格还是 2999", notify = true),
            Schedules.settle(RunOutcome.Done, "回答", true, noChange, asked = true),
        )
        assertEquals(
            Settled(RunOutcome.Done, "回答", notify = true),
            Schedules.settle(RunOutcome.Done, "回答", true, null, asked = false),
        )
        assertEquals(
            Settled(RunOutcome.Failed, "进程退出", notify = true),
            Schedules.settle(RunOutcome.Failed, "进程退出", true, noChange, asked = false),
        )
        assertEquals(
            Settled(RunOutcome.Stopped, "已停止", notify = false),
            Schedules.settle(RunOutcome.Stopped, "已停止", true, noChange, asked = false),
        )
        // 有结果：通知用上报的摘要，没写摘要时用回答
        assertEquals(
            Settled(RunOutcome.Done, "降到 2799 了", notify = true),
            Schedules.settle(RunOutcome.Done, "回答", true, deliver, asked = false),
        )
        assertEquals(
            Settled(RunOutcome.Done, "回答", notify = true),
            Schedules.settle(
                RunOutcome.Done,
                "回答",
                true,
                deliver.copy(summary = " "),
                asked = false,
            ),
        )
        assertEquals(
            "没有变化",
            Schedules.settle(
                    RunOutcome.Done,
                    "回答",
                    true,
                    noChange.copy(summary = ""),
                    asked = false,
                )
                .summary,
        )
        // 不是只在有变化时提醒的任务不看上报
        assertEquals(
            Settled(RunOutcome.Done, "回答", notify = true),
            Schedules.settle(RunOutcome.Done, "回答", false, noChange, asked = false),
        )
    }

    @Test
    fun onlyRunningRunsBecomeInterrupted() {
        val running = TaskRun(1, RunOutcome.Running, "s", "")
        assertEquals(RunOutcome.Interrupted, Schedules.interrupted(running)?.outcome)
        assertEquals("s", Schedules.interrupted(running)?.sessionId)
        val done = TaskRun(1, RunOutcome.Done, "s", "ok")
        assertEquals(done, Schedules.interrupted(done))
        assertNull(Schedules.interrupted(null))
    }

    @Test
    fun parsesToolArguments() {
        assertEquals(LocalTime.of(8, 0), Schedules.parseTime("08:00"))
        assertEquals(LocalTime.of(7, 5), Schedules.parseTime("7:05"))
        assertEquals(LocalTime.of(23, 59), Schedules.parseTime("2359"))
        assertNull(Schedules.parseTime("24:00"))
        assertNull(Schedules.parseTime("8"))
        assertEquals(
            LocalDateTime.parse("2026-11-20T08:00"),
            Schedules.parseDateTime("2026-11-20 08:00:31"),
        )
        assertNull(Schedules.parseDateTime("tomorrow"))
        assertEquals(DayOfWeek.MONDAY, Schedules.parseDay("Monday"))
        assertEquals(DayOfWeek.SUNDAY, Schedules.parseDay("7"))
        assertNull(Schedules.parseDay("0"))
    }

    @Test
    fun runOutcomeLooksOnlyAtThisTurn() {
        val prompt = "汇总通知"
        val earlier = listOf(UserItem("u0", prompt), AnswerItem("a0", "昨天的回答", streaming = false))
        // 接着同一个对话：只看最后一次发出的那条之后；回答取最后一段
        val done =
            earlier +
                listOf(
                    UserItem("u1", prompt),
                    AnswerItem("a1", "我先看看通知。", streaming = false),
                    WorkItem("w1", emptyList()),
                    AnswerItem("a2", "**今天** 有 2 条通知", streaming = false),
                )
        assertEquals(RunOutcome.Done to "今天 有 2 条通知", Schedules.outcomeOf(done, prompt))
        val failed =
            earlier +
                listOf(
                    UserItem("u1", prompt),
                    NoticeItem("n1", "Claude Code 进程已退出（1）", error = true),
                )
        assertEquals(
            RunOutcome.Failed to "Claude Code 进程已退出（1）",
            Schedules.outcomeOf(failed, prompt),
        )
        val stopped =
            earlier + listOf(UserItem("u1", prompt), NoticeItem("n1", "已停止", error = false))
        assertEquals(RunOutcome.Stopped to "已停止", Schedules.outcomeOf(stopped, prompt))
        // 进程没起来，消息根本没进列表：不能把上一次的回答当成这次的
        val unsent = earlier + NoticeItem("n1", "启动失败：no su", error = true)
        assertEquals(RunOutcome.Failed to "启动失败：no su", Schedules.outcomeOf(unsent, prompt))
    }

    @Test
    fun previewStripsMarkdown() {
        val answer =
            """
            ## 今日简报

            - **日程**：10:00 [周会](https://example.com)
            - 通知：`3` 条

            ```
            code
            ```
            """
                .trimIndent()
        assertEquals("今日简报 日程：10:00 周会 通知：3 条 code", Schedules.preview(answer))
        assertEquals("abcde…", Schedules.preview("abcdefgh", limit = 5))
        // 摘要默认的上限；截断不从表情中间切开
        assertEquals(Schedules.MAX_SUMMARY + 1, Schedules.preview("长".repeat(300)).length)
    }
}
