package com.dzshzx.zeta.core

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.SessionStatus.Activity
import com.dzshzx.zeta.core.conv.SessionActivity
import com.dzshzx.zeta.shots.Fixtures
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 核心推来的忙闲（conv.activity）里在进行、这边还没打开的会话，由 [ConversationHub.openRunning] 按会话号打开放在后台：应用被回收重开后
 * 核心接着跑的对话，提醒照常跟得上。闲着的不打开，同一个会话只打开一次，当前对话不换。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class OpenRunningTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun reset() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun alerts(): List<String> =
        shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.map {
            it.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        }

    private fun pushActivity(hub: ConversationHub, vararg sessions: Pair<String, SessionActivity>) =
        hub.sessionList.activityReceived(Backends.LOCAL, mapOf(*sessions))

    /** 还有没有排着的 conv.open（等 [ms] 毫秒）。 */
    private fun FakeCore.opensWithin(ms: Long): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return false
            val call = calls.poll(left, TimeUnit.MILLISECONDS) ?: return false
            if (call.method == "conv.open") return true
        }
    }

    @Test
    fun runningSessionIsOpenedInTheBackgroundOnce() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        val current = hub.current
        val answering = "s-answering" to SessionActivity(Activity.Answering, 0, null)
        val idle = "s-idle" to SessionActivity(Activity.Idle, 0, 100)

        pushActivity(hub, answering, idle)
        val open = core.next("conv.open")
        assertEquals("s-answering", open.str("session"))
        val chat = checkNotNull(hub.findConversation("s-answering")) { "在回答的会话打开了" }
        assertSame("当前对话不换", current, hub.current)
        assertNotSame(hub.current, chat)
        assertEquals(listOf(chat), hub.parkedConversations)
        assertNull("闲着的不打开", hub.findConversation("s-idle"))

        // 同一个会话再推来（核心每次变了都推整份）：不再打开
        pushActivity(hub, answering, idle)
        pushActivity(hub, "s-answering" to SessionActivity(Activity.Waiting, 1, null), idle)
        assertFalse("同一个会话只打开一次", core.opensWithin(300))
        assertEquals(listOf(chat), hub.parkedConversations)

        // 这一轮答完：不在眼前，照常提醒
        awaitMain { chat.view.state.key.isNotEmpty() }
        core.push("""{"type":"conv.turn","conv":"${open.conv}","phase":"started"}""")
        awaitMain { chat.busy }
        core.state(open.conv, """"busy":false""")
        core.push(
            """{"type":"conv.turn","conv":"${open.conv}","phase":"finished","outcome":"done","text":"已完成"}""",
        )
        awaitMain { !chat.busy }
        awaitMain { "已完成" in alerts() }
    }

    @Test
    fun ownNewConversationWithoutSessionIdIsNotOpenedTwice() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        // 自己刚发出第一条的新对话：核心已经在回答，会话号还没推到这边
        val mine =
            Conversation(hub, "/workspace/claude", sessionId = null, title = "", historyFile = null)
                .also(hub::park)
        mine.core { it.copy(busy = true) }
        val answering = "s-mine" to SessionActivity(Activity.Answering, 0, null)

        pushActivity(hub, answering)
        assertNull("核心报的可能就是它，这次先不打开", hub.findConversation("s-mine"))
        assertFalse(core.opensWithin(300))

        // 会话号到了：找得到的就是它，不另开一个
        mine.core { it.copy(session = "s-mine") }
        pushActivity(hub, answering)
        assertSame(mine, hub.findConversation("s-mine"))
        assertFalse(core.opensWithin(300))
    }
}
