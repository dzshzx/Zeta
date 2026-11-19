package com.dzshzx.zeta.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 后台任务清单的归约。事件照 Claude Code 2.1.328 的真实输出删减（真跑一遍的见 ClaudeTaskContractTest）。 */
class TaskBoardTest {
    private fun e(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val bashCall = e(
        """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_0","name":"Bash",
        "input":{"command":"echo hello-bg; sleep 300","description":"ticker in background","run_in_background":true}}]},"parent_tool_use_id":null}""",
    )
    private val bashListed = e(
        """{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"bvsjk0cv8","task_type":"local_bash","description":"ticker in background"}]}""",
    )
    private val bashStarted = e(
        """{"type":"system","subtype":"task_started","task_id":"bvsjk0cv8","tool_use_id":"toolu_0","description":"ticker in background","is_backgrounded":true,"task_type":"local_bash"}""",
    )
    private val bashKilled = e("""{"type":"system","subtype":"task_updated","task_id":"bvsjk0cv8","patch":{"status":"killed","end_time":5000}}""")
    private val bashStopped = e(
        """{"type":"system","subtype":"task_notification","task_id":"bvsjk0cv8","tool_use_id":"toolu_0","status":"stopped","output_file":"/tmp/x","summary":"ticker in background"}""",
    )
    private val empty = e("""{"type":"system","subtype":"background_tasks_changed","tasks":[]}""")

    @Test
    fun backgroundCommandStartsAndIsStopped() {
        val board = TaskBoard()
        assertFalse(board.observe(bashCall, 1000))
        // 清单先到，task_started 随后补上发起它的调用
        assertTrue(board.observe(bashListed, 1000))
        assertTrue(board.observe(bashStarted, 1001))
        val task = board.tasks.single()
        assertEquals("bvsjk0cv8", task.id)
        assertEquals(TaskKind.Command, task.kind)
        assertEquals("echo hello-bg; sleep 300", task.command)
        assertEquals("ticker in background", task.description)
        assertEquals("toolu_0", task.toolUseId)
        assertTrue(task.running && task.readable)
        assertEquals(4000, task.elapsedMs(5000))

        assertTrue(board.markStopping("bvsjk0cv8"))
        assertTrue(board.tasks.single().stopping)
        board.observe(bashKilled, 5001)
        board.observe(bashStopped, 5002)
        board.observe(empty, 5003)
        val stopped = board.tasks.single()
        assertEquals(TaskState.Stopped, stopped.state)
        assertFalse(stopped.stopping)
        assertEquals(5000L, stopped.endedAt)
        // 停止的通知里 summary 就是说明，不重复留
        assertNull(stopped.summary)
        assertEquals(4000, stopped.elapsedMs(99_999))
    }

    @Test
    fun startedBeforeListedIsKeptUntilItGoesToTheBackground() {
        val board = TaskBoard()
        board.observe(bashCall, 1000)
        // 前台的任务只有 task_started，不进清单
        assertFalse(board.observe(bashStarted, 1000))
        assertTrue(board.tasks.isEmpty())
        // 后来转到后台（Ctrl+B、超时自动转）：清单里出现时用当初记下的开始时间和命令
        assertTrue(board.observe(bashListed, 3000))
        val task = board.tasks.single()
        assertEquals(1000L, task.startedAt)
        assertEquals("echo hello-bg; sleep 300", task.command)
    }

    @Test
    fun subagentProgressAndReport() {
        val board = TaskBoard()
        board.observe(e("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_0","name":"Agent","input":{"description":"look around","prompt":"look at the repo","subagent_type":"general-purpose","run_in_background":true}}]}}"""), 0)
        board.observe(e("""{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"aa26421d62638ceff","task_type":"local_agent","subagent_type":"general-purpose","description":"look around"}]}"""), 10)
        board.observe(e("""{"type":"system","subtype":"task_started","task_id":"aa26421d62638ceff","tool_use_id":"toolu_0","description":"look around","subagent_type":"general-purpose","is_backgrounded":true,"task_type":"local_agent","prompt":"look at the repo"}"""), 10)
        // 子代理自己的消息带 parent_tool_use_id，它的工具调用不算主对话的
        board.observe(e("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_s0","name":"Bash","input":{"command":"echo from-sub"}}]},"parent_tool_use_id":"toolu_0"}"""), 20)
        assertTrue(board.observe(e("""{"type":"system","subtype":"task_progress","task_id":"aa26421d62638ceff","description":"Running sub cmd","usage":{"total_tokens":23,"tool_uses":1,"duration_ms":3157},"last_tool_name":"Bash"}"""), 3000))
        var task = board.tasks.single()
        assertEquals(TaskKind.Agent, task.kind)
        assertEquals("general-purpose", task.agentType)
        assertEquals("look at the repo", task.command)
        assertEquals("Running sub cmd", task.progress)
        assertEquals(1, task.toolUses)
        assertFalse(task.readable)

        board.observe(e("""{"type":"system","subtype":"task_updated","task_id":"aa26421d62638ceff","patch":{"status":"completed","end_time":6400}}"""), 6400)
        board.observe(e("""{"type":"system","subtype":"task_notification","task_id":"aa26421d62638ceff","status":"completed","summary":"sub done REPORT","summary_is_report":true}"""), 6401)
        board.observe(empty, 6402)
        task = board.tasks.single()
        assertEquals(TaskState.Completed, task.state)
        assertEquals("sub done REPORT", task.summary)
    }

    @Test
    fun monitorIsALocalBashTaskStartedByMonitor() {
        val board = TaskBoard()
        board.observe(e("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_1","name":"Monitor","input":{"description":"watch events","timeout_ms":60000,"command":"tail -f log"}}]}}"""), 0)
        board.observe(e("""{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"bmql5uhd1","task_type":"local_bash","description":"watch events"}]}"""), 0)
        board.observe(e("""{"type":"system","subtype":"task_started","task_id":"bmql5uhd1","tool_use_id":"toolu_1","description":"watch events","is_backgrounded":true,"task_type":"local_bash"}"""), 0)
        val task = board.tasks.single()
        assertEquals(TaskKind.Monitor, task.kind)
        assertEquals("tail -f log", task.command)
        assertTrue(task.readable)
    }

    @Test
    fun droppedWithoutAnOutcomeOrProcessGoneEndsIt() {
        val board = TaskBoard()
        board.observe(bashListed, 0)
        assertTrue(board.observe(empty, 700))
        assertEquals(TaskState.Ended, board.tasks.single().state)
        assertEquals(700L, board.tasks.single().endedAt)

        val other = TaskBoard()
        other.observe(bashListed, 0)
        assertTrue(other.processEnded(900))
        assertEquals(TaskState.Ended, other.tasks.single().state)
        assertFalse(other.processEnded(1000))
    }

    @Test
    fun failedCommandKeepsItsExitSummary() {
        val board = TaskBoard()
        board.observe(bashListed, 0)
        board.observe(e("""{"type":"system","subtype":"task_notification","task_id":"bvsjk0cv8","status":"failed","summary":"Background command \"ticker in background\" failed with exit code 1"}"""), 50)
        val task = board.tasks.single()
        assertEquals(TaskState.Failed, task.state)
        assertEquals("Background command \"ticker in background\" failed with exit code 1", task.summary)
        assertEquals(50L, task.endedAt)
    }

    @Test
    fun replayFromTheHelperCarriesTheCallAndStartTime() {
        // 接回后台进程：助手脚本给的 task_started 带着发起它的调用（zeta_tool）和当时的时间（zeta_at）
        val board = TaskBoard()
        board.observe(e("""{"type":"system","subtype":"task_started","task_id":"bvsjk0cv8","tool_use_id":"toolu_0","description":"ticker in background","task_type":"local_bash","zeta_at":1234,"zeta_tool":{"name":"Bash","input":{"command":"make test"}}}"""), 9999)
        board.observe(bashListed, 9999)
        val task = board.tasks.single()
        assertEquals(1234L, task.startedAt)
        assertEquals("make test", task.command)
    }

    @Test
    fun resumedTaskRunsAgainAndOldFinishedOnesAreTrimmed() {
        val board = TaskBoard(keepFinished = 2)
        board.observe(bashListed, 0)
        board.observe(bashStopped, 10)
        // 续跑：同一个任务又回到清单
        board.observe(bashListed, 20)
        assertEquals(TaskState.Running, board.tasks.single().state)
        assertNull(board.tasks.single().endedAt)

        for (i in 1..4) {
            board.observe(e("""{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"b$i","task_type":"local_bash","description":"t$i"}]}"""), 100L * i)
        }
        board.observe(empty, 1000)
        // 在跑的没有了，结束的只留最近 2 个
        assertEquals(listOf("b4", "b3"), board.tasks.map { it.id })
        assertTrue(board.clearFinished())
        assertTrue(board.tasks.isEmpty())
    }

    @Test
    fun outputResponse() {
        val ok = parseTaskOutput(e("""{"subtype":"success","request_id":"c2","response":{"output":"hello-bg\nsecond-line\n","total_bytes":21,"truncated":false}}"""))
        assertEquals(TaskOutput("hello-bg\nsecond-line\n", 21, false), ok)
        val error = parseTaskOutput(e("""{"subtype":"error","request_id":"c2","error":"get_task_output: no shell or Monitor task with that task_id in this session"}"""))
        assertEquals("get_task_output: no shell or Monitor task with that task_id in this session", error.error)
    }

    @Test
    fun terminalTextAndElapsed() {
        assertEquals("red plain", plainTerminalText("\u001B[31mred\u001B[0m plain"))
        assertEquals("100%\ndone", plainTerminalText("10%\r50%\r100%\r\ndone"))
        assertEquals("3 秒", formatElapsed(3_400))
        assertEquals("2 分 05 秒", formatElapsed(125_000))
        assertEquals("1 小时 03 分", formatElapsed(3_780_000))
    }
}
