package com.dzshzx.zeta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateTest {
    private fun live(
        key: String = "a",
        text: String = "正在思考",
        kind: StepKind = StepKind.THINKING,
        progress: TodoProgress? = null,
    ) = LiveStatus(key, "对话", text, kind, startedAt = 100L, progress = progress)

    private fun todo(vararg statuses: TodoStatus) =
        TodoList(
            statuses.mapIndexed { i, status -> TodoEntry("$i", "第 $i 项", "正在做第 $i 项", status) },
        )

    @Test
    fun statusText() {
        val status = LiveUpdate.status("s1", "", Step("运行 ls", StepKind.TOOL), 42, todo = null)
        assertEquals("s1", status.key)
        assertEquals("新对话", status.title)
        assertEquals("运行 ls", status.text)
        assertEquals("执行中", status.short)
        assertEquals(42L, status.startedAt)
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
        fun status(todo: TodoList) =
            LiveUpdate.status("a", "对话", Step("正在思考", StepKind.THINKING), 0L, todo)
        val list = todo(TodoStatus.Completed, TodoStatus.InProgress, TodoStatus.Pending)
        assertEquals(TodoProgress(1, 3), status(list).progress)
        // 全部做完（多半是上一轮的清单）：不画进度条
        assertNull(status(todo(TodoStatus.Completed, TodoStatus.Completed)).progress)
        assertNull(status(TodoList(emptyList())).progress)
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
        // 换了对话：马上更新
        assertFalse(LiveUpdate.minorChange(base, live(key = "b")))
        assertFalse(LiveUpdate.minorChange(base, base.copy(startedAt = 200L)))
        // 要用户确认、回答、继续：变成它或不再是它都马上更新
        assertFalse(LiveUpdate.minorChange(base, live(text = "需要你确认", kind = StepKind.CONFIRMING)))
        assertFalse(LiveUpdate.minorChange(live(text = "已暂停", kind = StepKind.PAUSED), base))
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
