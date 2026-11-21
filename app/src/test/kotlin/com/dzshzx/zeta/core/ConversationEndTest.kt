package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.Fixtures.force
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
        running.force("busy", true)
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
        hub.opened("s-left").force("busy", true)
        hub.startNew()
        assertTrue(hub.findConversation("s-left")?.busy == true)

        val ending = hub.opened("s-ended")
        ending.force("busy", true)
        hub.endCurrent()
        assertNull(hub.current.sessionId)
        assertNull(hub.findConversation("s-ended"))
        assertFalse(ending in hub.liveConversations)
        // 别的在后台跑的不受影响
        assertTrue(hub.findConversation("s-left")?.busy == true)
    }
}
