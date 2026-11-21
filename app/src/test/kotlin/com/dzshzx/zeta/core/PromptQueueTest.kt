package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 等用户回应的请求按到达顺序排队：主对话和后台子代理可能同时各有一个在等（ClaudeInteractionContractTest 钉住）， 答一个弹下一个，Claude Code
 * 撤回的去掉，停止时全部答掉；接回进程时工具确认以 Claude Code 报的为准。 还有输入栏的 `!` 命令：没有 result，靠 command_lifecycle
 * 收尾。进程是真起的、只是 su 总是失败，做法同 QueuedMessagesTest。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PromptQueueTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun ask(id: String, tool: String = "Bash", agent: String? = null) =
        """{"type":"control_request","request_id":"$id","request":{"subtype":"can_use_tool","tool_name":"$tool","input":{},"tool_use_id":"t-$id"${agent?.let { ",\"agent_id\":\"$it\"" } ?: ""}}}"""

    private val elicit =
        """{"type":"control_request","request_id":"e1","request":{"subtype":"elicitation","mcp_server_name":"eli","message":"填一下","mode":"form","requested_schema":{"type":"object","properties":{}}}}"""

    private fun busyChat(): Conversation {
        val chat =
            Conversation(
                Fixtures.readyHub(app),
                "/workspace/claude",
                sessionId = null,
                title = "",
                historyFile = null,
            )
        chat.send("做点事")
        return chat
    }

    @Test
    fun promptsWaitInArrivalOrder() {
        val chat = busyChat()
        chat.receive(ask("main"))
        chat.receive(ask("sub", agent = "a1"))
        chat.receive(elicit)
        chat.receive(ask("main"))
        assertEquals("同一个号只排一次", listOf("main", "sub", "e1"), chat.prompts.map { it.requestId })
        assertEquals("main", chat.pending?.requestId)

        chat.reply(chat.pending as PermissionRequest, PermissionReply.Allow)
        assertEquals("sub", chat.pending?.requestId)
        assertEquals("a1", (chat.pending as PermissionRequest).agentId)

        chat.receive("""{"type":"control_cancel_request","request_id":"sub"}""")
        assertTrue(chat.pending is ElicitationRequest)
        chat.answerElicitation(chat.pending as ElicitationRequest, "decline")
        assertNull(chat.pending)
    }

    /** 不写理由的拒绝：和终端里按 Esc 一样这一轮停下，算“已停止”，不当成出错；事件顺序照真 Claude Code（ClaudeInteractionContractTest）。 */
    @Test
    fun denyWithoutReasonStopsTheTurn() {
        val chat = busyChat()
        val uuid = chat.items.filterIsInstance<UserItem>().single().uuid!!
        chat.receive(
            """{"type":"command_lifecycle","command_uuid":"$uuid","state":"started","session_id":"s1"}""",
        )
        chat.receive(ask("r1"))
        chat.reply(chat.pending as PermissionRequest, PermissionReply.Deny(null))
        chat.receive(
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"STOP","is_error":true,"tool_use_id":"t-r1"}]},"session_id":"s1"}""",
        )
        chat.receive(
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user for tool use]"}]},"session_id":"s1"}""",
        )
        chat.receive(
            """{"type":"result","subtype":"error_during_execution","is_error":true,"terminal_reason":"aborted_tools","stop_reason":"tool_use","errors":["[ede_diagnostic] x"],"session_id":"s1"}""",
        )
        chat.receive(
            """{"type":"command_lifecycle","command_uuid":"$uuid","state":"cancelled","session_id":"s1"}""",
        )
        assertFalse(chat.busy)
        val notice = chat.items.last() as NoticeItem
        assertEquals("已停止", notice.text)
        assertFalse(notice.error)
    }

    @Test
    fun stoppingAnswersEverythingThatWaits() {
        val chat = busyChat()
        chat.receive(ask("main"))
        chat.receive(elicit)
        chat.interrupt()
        assertEquals(emptyList<UserPrompt>(), chat.prompts)
    }

    /** 接回进程：日志里读到的确认，Claude Code 说已经不在等的去掉（答了以后命令还在跑），它报的补上；MCP 表单不在它的清单里，留着。 */
    @Test
    fun reattachTrustsClaudeCodesPendingList() {
        val hub = Fixtures.readyHub(app)
        val fromLog = listOf(ask("stale"), elicit).map { Json.parseToJsonElement(it) as JsonObject }
        val host =
            HostInfo(
                "k1",
                "/workspace/claude",
                "旧对话",
                sessionId = "s1",
                historyFile = null,
                lines = 0,
                busy = true,
                pending = fromLog,
            )
        val chat =
            Conversation(hub, host.cwd, host.sessionId, host.title, host.historyFile, attach = host)
        assertEquals(listOf("stale", "e1"), chat.prompts.map { it.requestId })
        chat.answer(
            "initialize",
            "{}",
            extra = ""","pending_permission_requests":[${ask("live", agent = "a2")}]""",
        )
        assertEquals(listOf("e1", "live"), chat.prompts.map { it.requestId })
    }

    /** `!` 命令：发 bash_command，回放的两行画成一个命令步骤，它的 command_lifecycle completed 就是结束。 */
    @Test
    fun shellCommandRunsWithoutATurn() {
        val chat =
            Conversation(
                Fixtures.readyHub(app),
                "/workspace/claude",
                sessionId = null,
                title = "",
                historyFile = null,
            )
        chat.send("!ls -la")
        assertTrue(chat.busy)
        assertTrue("不当成发给模型的消息", chat.items.none { it is UserItem })
        @Suppress("UNCHECKED_CAST")
        val uuid =
            (Conversation::class
                    .java
                    .getDeclaredField("shellCommands")
                    .apply { isAccessible = true }
                    .get(chat) as Set<String>)
                .single()
        chat.receive(
            """{"type":"command_lifecycle","command_uuid":"$uuid","state":"started","session_id":"s1"}""",
        )
        chat.receive(
            """{"type":"user","isReplay":true,"message":{"role":"user","content":"<bash-input>ls -la</bash-input>"},"session_id":"s1"}""",
        )
        chat.receive(
            """{"type":"user","isReplay":true,"message":{"role":"user","content":"<bash-stdout>a\nb</bash-stdout><bash-stderr>warn</bash-stderr><bash-exit-code>2</bash-exit-code>"},"session_id":"s1"}""",
        )
        chat.receive(
            """{"type":"command_lifecycle","command_uuid":"$uuid","state":"completed","session_id":"s1"}""",
        )
        assertFalse(chat.busy)
        val step = (chat.items.single() as WorkItem).steps.single() as ToolStep
        assertEquals("Bash", step.name)
        assertEquals(ToolStatus.Failed, step.status)
        assertEquals("a\nb\nwarn\n退出码 2", step.result)

        // 回答进行中不收，原样放回输入框
        chat.send("改一下 README")
        chat.send("!pwd")
        assertTrue(chat.items.last() is NoticeItem)
    }

    /** 历史里的 `!` 命令（zeta-helper 原样交来的两行）和实时的画法一样。 */
    @Test
    fun shellLinesInHistory() {
        val reducer = TranscriptReducer("h")
        fun user(text: String) =
            Json.parseToJsonElement(
                """{"content":[{"type":"text","text":${Json.encodeToString(String.serializer(), text)}}]}""",
            ) as JsonObject
        reducer.applyUser(user("<bash-input>pwd</bash-input>"), replay = true)
        reducer.applyUser(
            user("<bash-stdout>/w</bash-stdout><bash-stderr></bash-stderr>"),
            replay = true,
        )
        val step = (reducer.items.single() as WorkItem).steps.single() as ToolStep
        assertEquals(ToolStatus.Success, step.status)
        assertEquals("/w", step.result)
        assertEquals(
            "! pwd",
            (step.input?.get("description") as kotlinx.serialization.json.JsonPrimitive).content,
        )
    }
}
