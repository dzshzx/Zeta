package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 聊天页离开一个会话的几条路：结束对话（终端里的 /exit）把进行中的也停掉，归档和“新对话”一样把进行中的留在后台； 归档的是聊天页正开着的会话时，聊天页换成空的新对话，不再显示已归档的会话。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ConversationEndTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val cwd = Fixtures.env.defaultCwd

    private fun summary(id: String) =
        SessionSummary(id, "/home/dzshzx/.claude/projects/-w/$id.jsonl", cwd, "会话 $id", "", 0)

    private fun ConversationHub.opened(id: String): Conversation {
        val s = summary(id)
        sessionList.listed(sessionList.sessions + s)
        open(s)
        assertEquals(id, current.sessionId)
        return current
    }

    @Test
    fun archivingTheOpenSessionLeavesIt() {
        val hub = Fixtures.readyHub(app)
        val other = summary("s-other")
        hub.sessionList.listed(listOf(other))
        hub.opened("s-open")

        // 归档别的会话、取消归档：聊天页不动
        hub.sessionList.archive(other, true)
        assertEquals("s-open", hub.current.sessionId)
        hub.sessionList.archive(summary("s-open"), false)
        assertEquals("s-open", hub.current.sessionId)

        // 归档正开着的：换成空的新对话
        hub.sessionList.archive(listOf(other, summary("s-open")), true)
        assertNull(hub.current.sessionId)
        assertTrue(hub.current.items.isEmpty())
        assertTrue(hub.sessionList.sessions.single { it.id == "s-open" }.archived)
    }

    @Test
    fun archivingARunningSessionLetsItFinishInTheBackground() {
        val hub = Fixtures.readyHub(app)
        val running = hub.opened("s-running")
        running.core { it.copy(busy = true) }
        hub.sessionList.archive(summary("s-running"), true)
        assertNull(hub.current.sessionId)
        assertSame(running, hub.findConversation("s-running"))
    }

    @Test
    fun endingSwitchesToABlankConversation() {
        val hub = Fixtures.readyHub(app)
        hub.opened("s-done")
        hub.endCurrent()
        assertNull(hub.current.sessionId)
        assertNull(hub.findConversation("s-done"))
        // 会话还在列表里，没有归档
        assertFalse(hub.sessionList.sessions.single { it.id == "s-done" }.archived)
    }

    /** 和“新对话”的差别：进行中的对话不留在后台，一并停掉。 */
    @Test
    fun endingStopsARunningConversation() {
        val hub = Fixtures.readyHub(app)
        hub.opened("s-left").core { it.copy(busy = true) }
        hub.startNew()
        assertTrue(hub.findConversation("s-left")?.busy == true)

        val ending = hub.opened("s-ended")
        ending.core { it.copy(busy = true) }
        hub.endCurrent()
        assertNull(hub.current.sessionId)
        assertNull(hub.findConversation("s-ended"))
        assertFalse(ending in hub.liveConversations)
        // 别的在后台跑的不受影响
        assertTrue(hub.findConversation("s-left")?.busy == true)
    }

    /**
     * 闲着放开过的对话（Zeta 切到后台、切走几秒后 [Conversation.leave]，核心随后也放开进程）再结束：手上已没有核心里的那份，
     * 照样请核心结束这个会话的进程（sessions.end）。不然什么也不发，进程留在那台主机上等闲置超时（可设到一小时），侧栏一直是运行中。
     */
    @Test
    fun endingAReleasedConversationEndsItsSessionOnTheHost() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        val chat = hub.opened("s-idle")
        awaitCore { chat.view.connected }
        chat.leave()
        core.next("conv.close")

        hub.endCurrent()
        assertEquals("s-idle", core.next("sessions.end").str("session"))
        assertNull(hub.current.sessionId)
    }

    /** 结束进程后接着做的事（引用文件夹前换核心）：排在 conv.end 答了之后；没打开过的对话也照做。 */
    @Test
    fun shutdownRunsTheNextStepAfterTheEnd() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        val order = CopyOnWriteArrayList<String>()
        core.respond = { call ->
            order += call.method
            core.defaultReply(call)
        }
        val chat = Conversation(hub, cwd, "s-restart", "", historyFile = null)
        awaitCore { chat.view.connected }
        chat.shutdown { order += "after" }
        assertTrue(waitUntil { "after" in order })
        assertEquals(
            listOf("conv.end", "conv.close", "after"),
            order.dropWhile { it != "conv.end" },
        )

        Conversation(hub, cwd, null, "", historyFile = null).shutdown { order += "never opened" }
        assertTrue(waitUntil { "never opened" in order })
    }
}
