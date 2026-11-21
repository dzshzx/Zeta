package com.dzshzx.zeta.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 子代理跟踪（[SubagentTracker]）：输出流里的事件怎么对上查看页的子代理。记录的读法见 SubagentLogTest。 */
class SubagentTrackerTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun started(use: String, id: String, type: String = "local_agent") =
        json(
            """{"type":"system","subtype":"task_started","task_id":"$id","tool_use_id":"$use","task_type":"$type"}""",
        )

    private fun notified(id: String, status: String) =
        json(
            """{"type":"system","subtype":"task_notification","task_id":"$id","status":"$status"}""",
        )

    @Test
    fun outcomeFromTheStreamReachesTheWatchedLog() {
        val tracker = SubagentTracker()
        val log = tracker.log("toolu_1")
        assertSame(log, tracker.log("toolu_1"))
        tracker.observe(started("toolu_1", "a1"), parent = null)
        // 后台命令的 task_started 不是子代理
        tracker.observe(started("toolu_2", "b1", type = "local_bash"), parent = null)
        assertEquals("a1", tracker.agentId(log))
        assertNull(tracker.agentId(tracker.log("toolu_2")))

        // 没开查看页：结局记下，但不用刷新
        assertEquals(
            emptyList<SubagentLog>(),
            tracker.observe(notified("a1", "completed"), parent = null),
        )
        log.watchers = 1
        assertEquals(listOf(log), tracker.observe(notified("a1", "failed"), parent = null))
        assertEquals(listOf(log), tracker.watched)
        tracker.publish(log, active = true)
        assertEquals(TaskState.Failed, log.outcome)
        assertFalse("有结局就不在跑", log.running)
        // 不认识的状态不算结局
        assertEquals(
            emptyList<SubagentLog>(),
            tracker.observe(notified("a1", "running"), parent = null),
        )
    }

    @Test
    fun withoutAnOutcomeItRunsWhileTheConversationIsActive() {
        val tracker = SubagentTracker()
        val log = tracker.log("toolu_1")
        tracker.publish(log, active = true)
        assertTrue(log.running)
        assertNull(log.outcome)
        tracker.publish(log, active = false)
        assertFalse(log.running)
    }

    @Test
    fun agentToolResultCarriesTheId() {
        val tracker = SubagentTracker()
        tracker.observe(
            json(
                """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_9","content":"ok"}]},"tool_use_result":{"agentId":"a9"}}""",
            ),
            parent = null,
        )
        assertEquals("a9", tracker.agentId(tracker.log("toolu_9")))
    }

    @Test
    fun keepsOnlyRecentLogsAndPastSessions() {
        val tracker = SubagentTracker()
        val first = tracker.log("toolu_0")
        repeat(8) { tracker.log("toolu_${it + 1}") }
        // 最早看的那个被挤掉了，再要是新的一份
        assertFalse(first === tracker.log("toolu_0"))

        tracker.sessionCleared("s1")
        tracker.sessionCleared("s2")
        assertEquals(listOf("s3", "src", "s1", "s2"), tracker.sessions(listOf("s3", "src")))
        assertEquals(listOf("s1", "s2"), tracker.sessions(listOf("s1")))
    }
}
