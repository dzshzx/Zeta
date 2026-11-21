package com.dzshzx.zeta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateTest {
    private fun candidate(
        key: String,
        step: Step = Step("正在思考", StepKind.THINKING),
        controlling: Boolean = false,
        engaged: Boolean = false,
        engagedAt: Long = 0L,
        current: Boolean = false,
        startedAt: Long = 0L,
        title: String = "对话 $key",
    ) = LiveCandidate(key, title, step, controlling, engaged, engagedAt, current, startedAt)

    private fun live(
        key: String = "a",
        text: String = "正在思考",
        kind: StepKind = StepKind.THINKING,
        others: Int = 0,
        progress: TodoProgress? = null,
    ) = LiveStatus(key, "对话", text, kind, startedAt = 100L, progress = progress, others = others)

    private fun todo(vararg statuses: TodoStatus) =
        TodoList(
            statuses.mapIndexed { i, status -> TodoEntry("$i", "第 $i 项", "正在做第 $i 项", status) },
        )

    @Test
    fun capsuleChoiceWins() {
        // 胶囊正显示 b：通知也说 b，哪怕 a 是用户正看着的、最近开始的
        val runs =
            listOf(
                candidate("a", current = true, startedAt = 50),
                candidate("b", engaged = true, engagedAt = 10),
            )
        assertEquals("b", LiveUpdate.pick(runs, focusKey = "b")?.key)
        // 胶囊显示的那个不在回答了（停在“已完成”）：照规则在回答的里挑
        assertEquals("a", LiveUpdate.pick(runs.take(1), focusKey = "b")?.key)
    }

    @Test
    fun withoutCapsuleFollowsItsOrder() {
        val plain = candidate("plain", startedAt = 300)
        val shown = candidate("shown", current = true, startedAt = 100)
        val phoneOld = candidate("phoneOld", engaged = true, engagedAt = 10)
        val phoneNew = candidate("phoneNew", engaged = true, engagedAt = 20)
        val takenOver = candidate("takenOver", controlling = true, engaged = true, engagedAt = 1)
        assertEquals(
            "takenOver",
            LiveUpdate.pick(listOf(plain, shown, phoneOld, phoneNew, takenOver), null)?.key,
        )
        assertEquals(
            "phoneNew",
            LiveUpdate.pick(listOf(plain, shown, phoneOld, phoneNew), null)?.key,
        )
        assertEquals("shown", LiveUpdate.pick(listOf(plain, shown), null)?.key)
        assertEquals(
            "plain",
            LiveUpdate.pick(listOf(candidate("older", startedAt = 100), plain), null)?.key,
        )
        assertNull(LiveUpdate.pick(emptyList(), "a"))
    }

    @Test
    fun staleEngagedAtDoesNotCount() {
        // engagedAt 是上一轮留下的，这一轮没用手机：不比它
        val stale = candidate("stale", engaged = false, engagedAt = 999, startedAt = 1)
        val fresh = candidate("fresh", engaged = false, engagedAt = 0, startedAt = 2)
        assertEquals("fresh", LiveUpdate.pick(listOf(stale, fresh), null)?.key)
    }

    @Test
    fun statusText() {
        val chosen =
            candidate("s1", step = Step("运行 ls", StepKind.TOOL), title = "", startedAt = 42)
        val status = LiveUpdate.status(chosen, running = 3, todo = null)
        assertEquals("s1", status.key)
        assertEquals("新对话", status.title)
        assertEquals("运行 ls", status.text)
        assertEquals("执行中", status.short)
        assertEquals(42L, status.startedAt)
        assertEquals("另有 2 个对话在回答", status.note)
        assertNull(LiveUpdate.status(chosen, running = 1, todo = null).note)
    }

    @Test
    fun shortTextsFitTheChip() {
        // 状态栏小胶囊只放得下几个字，每种都要有、都要短
        for (kind in StepKind.entries) assertTrue(
            kind.name,
            kind.short.isNotBlank() && kind.short.length <= 3,
        )
        assertEquals("待确认", StepKind.CONFIRMING.short)
        assertEquals("操作中", StepKind.PHONE.short)
    }

    @Test
    fun progressOnlyForUnfinishedList() {
        val chosen = candidate("a")
        val list = todo(TodoStatus.Completed, TodoStatus.InProgress, TodoStatus.Pending)
        assertEquals(TodoProgress(1, 3), LiveUpdate.status(chosen, 1, list).progress)
        // 全部做完（多半是上一轮的清单）：不画进度条
        assertNull(
            LiveUpdate.status(chosen, 1, todo(TodoStatus.Completed, TodoStatus.Completed)).progress,
        )
        assertNull(LiveUpdate.status(chosen, 1, TodoList(emptyList())).progress)
    }

    @Test
    fun segmentsPerItemUntilTooMany() {
        assertEquals(listOf(1, 1, 1), LiveUpdate.segments(3))
        assertEquals(
            List(LiveUpdate.MAX_SEGMENTS) { 1 },
            LiveUpdate.segments(LiveUpdate.MAX_SEGMENTS),
        )
        assertEquals(listOf(30), LiveUpdate.segments(30))
    }

    @Test
    fun onlyStepChangesAreMinor() {
        val base = live()
        assertTrue(LiveUpdate.minorChange(base, live(text = "运行 ls", kind = StepKind.TOOL)))
        assertTrue(LiveUpdate.minorChange(base, live(progress = TodoProgress(1, 2))))
        // 换了对话、多了一个对话、开始或不再提升：马上更新
        assertFalse(LiveUpdate.minorChange(base, live(key = "b")))
        assertFalse(LiveUpdate.minorChange(base, live(others = 1)))
        assertFalse(LiveUpdate.minorChange(null, base))
        assertFalse(LiveUpdate.minorChange(base, null))
        // 要用户确认、回答、继续：变成它或不再是它都马上更新
        assertFalse(LiveUpdate.minorChange(base, live(text = "需要你确认", kind = StepKind.CONFIRMING)))
        assertFalse(LiveUpdate.minorChange(live(text = "已暂停", kind = StepKind.PAUSED), base))
    }

    @Test
    fun noticeMinorOnlyWhenRestUnchanged() {
        val notice = StatusNotice(active = true, busy = true, running = listOf("a"), live = live())
        assertTrue(notice.copy(live = live(text = "运行 ls")).minorAfter(notice))
        assertFalse(notice.copy(running = listOf("a · 在后台操作计算器")).minorAfter(notice))
        assertFalse(notice.copy(busy = false, live = null).minorAfter(notice))
        assertFalse(notice.copy(active = false).minorAfter(notice))
    }

    @Test
    fun throttleHoldsMinorChangesAndSendsLatest() {
        val throttle =
            Throttle<String>(intervalMs = 2_000) { old, new -> old.first() == new.first() }
        assertEquals("a1", throttle.offer("a1", now = 0))
        // 一样的不再发
        assertNull(throttle.offer("a1", now = 100))
        assertNull(throttle.dueIn(100))
        // 小变化：先记下，到点发最新的那份
        assertNull(throttle.offer("a2", now = 500))
        assertNull(throttle.offer("a3", now = 900))
        assertEquals(1_100L, throttle.dueIn(900))
        assertNull(throttle.poll(now = 1_999))
        assertEquals("a3", throttle.poll(now = 2_000))
        assertEquals("a3", throttle.published)
        assertNull(throttle.poll(now = 3_000))
        // 隔够了就直接发
        assertEquals("a4", throttle.offer("a4", now = 4_000))
    }

    @Test
    fun throttlePassesUrgentChangesAndDropsSuperseded() {
        val throttle =
            Throttle<String>(intervalMs = 2_000) { old, new -> old.first() == new.first() }
        throttle.offer("a1", now = 0)
        assertNull(throttle.offer("a2", now = 100))
        // 不是小变化：马上发，记下的那份作废
        assertEquals("b1", throttle.offer("b1", now = 200))
        assertNull(throttle.dueIn(200))
        assertNull(throttle.poll(now = 5_000))
        // 记下以后又变回已发出的那份：不再发
        assertNull(throttle.offer("b2", now = 300))
        assertNull(throttle.offer("b1", now = 400))
        assertNull(throttle.poll(now = 5_000))
    }
}
