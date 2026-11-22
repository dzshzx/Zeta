package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回答进行中发的消息先进排队条，Claude Code 取走它（command_lifecycle started）才进聊天记录；排队的都取走了，result 才算这一轮结束。
 * 进程是真起的，只是单元测试的 su 总是失败：进程对象在、发出去的落空，它退出的回调排在主线程上，测试不让主线程空转，所以不会被处理。 Claude Code 那一侧（插话各自有
 * started、并进同一轮只出一个 result）由 ClaudeCodeContractTest 钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class QueuedMessagesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val Conversation.users
        get() = items.filterIsInstance<UserItem>().map { it.text }

    private fun Conversation.started(uuid: String) =
        receive(
            """{"type":"command_lifecycle","command_uuid":"$uuid","state":"started","uuid":"e-$uuid","session_id":"s1"}""",
        )

    private fun Conversation.result() =
        receive(
            """{"type":"result","subtype":"success","is_error":false,"result":"好了","session_id":"s1"}""",
        )

    /** 接回一个正在回答的进程，还没收到过 command_lifecycle：这时发的也要排队。 */
    @Test
    fun messageToAReattachedBusyProcessWaitsInTheQueue() {
        val hub = Fixtures.readyHub(app)
        val host =
            HostInfo(
                "k1",
                "/workspace/claude",
                "旧对话",
                sessionId = "s1",
                historyFile = null,
                lines = 0,
                busy = true,
            )
        val chat =
            Conversation(hub, host.cwd, host.sessionId, host.title, host.historyFile, attach = host)
        assertTrue(chat.busy)

        chat.send("顺便看看日志")
        assertEquals(listOf("顺便看看日志"), chat.queued.map { it.text })
        assertEquals("Claude Code 取走之前不进聊天记录", emptyList<String>(), chat.users)

        chat.started(chat.queued.single().uuid)
        assertEquals(emptyList<QueuedMessage>(), chat.queued)
        assertEquals(listOf("顺便看看日志"), chat.users)
        chat.result()
        assertFalse(chat.busy)
    }

    /** 插话并进当前这一轮：两条消息只出一个 result，它就是这一轮的结束。 */
    @Test
    fun mergedFollowUpEndsWithTheSingleResult() {
        val chat =
            Conversation(
                Fixtures.readyHub(app),
                "/workspace/claude",
                sessionId = null,
                title = "",
                historyFile = null,
            )
        chat.send("改一下 README")
        assertEquals(listOf("改一下 README"), chat.users)
        chat.send("顺便改 CLAUDE.md")
        val followUp = chat.queued.single().uuid
        chat.started(chat.items.filterIsInstance<UserItem>().single().uuid!!)
        chat.started(followUp)
        assertEquals(listOf("改一下 README", "顺便改 CLAUDE.md"), chat.users)
        chat.result()
        assertFalse(chat.busy)
    }

    /** 插话没赶上这一轮：这一轮的 result 来时它还在排队，Claude Code 接着另起一轮，那一轮的 result 才是结束。 */
    @Test
    fun stillQueuedMessageKeepsTheTurnGoing() {
        val chat =
            Conversation(
                Fixtures.readyHub(app),
                "/workspace/claude",
                sessionId = null,
                title = "",
                historyFile = null,
            )
        chat.send("改一下 README")
        chat.started(chat.items.filterIsInstance<UserItem>().single().uuid!!)
        chat.send("顺便改 CLAUDE.md")
        chat.result()
        assertTrue("还有排队的，这一轮没完", chat.busy)
        chat.started(chat.queued.single().uuid)
        chat.result()
        assertFalse(chat.busy)
    }

    /** 闲着时 Claude Code 自己起的一轮（后台任务做完）：没有 Zeta 发的消息，以 result 结束。 */
    @Test
    fun turnClaudeCodeStartsItselfEndsWithResult() {
        val chat =
            Conversation(
                Fixtures.readyHub(app),
                "/workspace/claude",
                sessionId = null,
                title = "",
                historyFile = null,
            )
        chat.receive(
            """{"type":"system","subtype":"init","session_id":"s1","model":"claude-opus-5-5"}""",
        )
        assertTrue(chat.busy)
        chat.result()
        assertFalse(chat.busy)
    }

    /** 回答中的对话，排着一条插话；[capabilities] 是 init 报的能力。 */
    private fun steeringChat(vararg capabilities: String): Conversation {
        val chat =
            Conversation(
                Fixtures.readyHub(app),
                "/workspace/claude",
                sessionId = null,
                title = "",
                historyFile = null,
            )
        chat.send("改一下 README")
        val list = capabilities.joinToString(",") { "\"$it\"" }
        chat.receive(
            """{"type":"system","subtype":"init","session_id":"s1","model":"claude-opus-5-5","capabilities":[$list]}""",
        )
        chat.started(chat.items.filterIsInstance<UserItem>().single().uuid!!)
        chat.send("顺便改 CLAUDE.md")
        return chat
    }

    private val Conversation.process
        get() =
            Conversation::class
                .java
                .getDeclaredField("process")
                .apply { isAccessible = true }
                .get(this) as ClaudeProcess

    /** Claude Code 回 ⚡ 发出的 send now 请求。 */
    private fun Conversation.sendNowAnswered(outcome: String) {
        val id = process.waitingFor("interrupt")!!
        receive(
            """{"type":"control_response","response":{"subtype":"success","request_id":"$id","response":{"still_queued":[],"send_now":"$outcome"}}}""",
        )
    }

    private val Conversation.notices
        get() = items.filterIsInstance<NoticeItem>().map { it.text }

    /** ⚡ 同终端的 send now：工具挪到后台、插话并进这一轮时，这一轮照常结束，不算打断（真 Claude Code 由 ClaudeCodeContractTest 钉住）。 */
    @Test
    fun sendNowDeliveredKeepsTheTurn() {
        val chat = steeringChat("interrupt_send_now_v1")
        chat.steerNow()
        assertTrue(chat.steering)
        chat.sendNowAnswered("delivering")
        chat.started(chat.queued.single().uuid)
        assertFalse(chat.steering)
        chat.result()
        assertFalse(chat.busy)
        assertEquals(emptyList<String>(), chat.notices)
    }

    /** 工具挪不到后台：Claude Code 停下这一轮，插话接着开始，这时才提示已打断。 */
    @Test
    fun sendNowThatStopsTheTurnSaysSo() {
        val chat = steeringChat("interrupt_send_now_v1")
        chat.steerNow()
        chat.sendNowAnswered("stopped")
        chat.result()
        assertEquals(listOf("已打断，先处理插话"), chat.notices)
        assertTrue("插话还没开始，这一轮没完", chat.busy)
        chat.started(chat.queued.single().uuid)
        chat.result()
        assertFalse(chat.busy)
    }

    /** 插话在应答前已被取走：什么都不用做，⚡ 恢复。 */
    @Test
    fun sendNowWithNothingWaiting() {
        val chat = steeringChat("interrupt_send_now_v1")
        chat.send("再改一下 docs")
        chat.steerNow()
        chat.sendNowAnswered("nothing_waiting")
        assertFalse(chat.steering)
        assertEquals(2, chat.queued.size)
    }

    /** 不认 send_now 的 Claude Code：只能发普通的 interrupt，这一轮停下。 */
    @Test
    fun withoutSendNowSteeringInterrupts() {
        val chat = steeringChat()
        chat.steerNow()
        assertEquals("普通的 interrupt 不等应答", null, chat.process.waitingFor("interrupt"))
        chat.result()
        assertEquals(listOf("已打断，先处理插话"), chat.notices)
    }
}
