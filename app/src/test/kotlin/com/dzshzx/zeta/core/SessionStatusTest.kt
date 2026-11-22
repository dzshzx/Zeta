package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.SessionStatus.Activity
import com.dzshzx.zeta.core.conv.SessionActivity
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.Fixtures.force
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 会话列表里各会话的状态：几样同时成立时报最要紧的；不在眼前时答完的记成有新回答，点开就不再记。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SessionStatusTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun reset() = Fixtures.install(null)

    private fun parked(hub: ConversationHub, id: String) =
        Conversation(hub, "/workspace/claude", sessionId = id, title = "", historyFile = null)
            .also(hub::park)

    private fun summary(id: String) =
        SessionSummary(id, "/home/u/.claude/projects/-w/$id.jsonl", "/workspace/claude", "", "", 0)

    @Test
    fun mostUrgentWins() {
        fun of(waiting: Boolean, busy: Boolean, tasks: Int, unread: Boolean, alive: Boolean) =
            SessionStatus.of(waiting, busy, tasks, unread, alive)?.activity
        assertEquals(Activity.Waiting, of(true, true, 2, true, true))
        assertEquals(Activity.Answering, of(false, true, 2, true, true))
        assertEquals(Activity.Background, of(false, false, 2, true, true))
        assertEquals(Activity.Unread, of(false, false, 0, true, true))
        assertEquals(Activity.Idle, of(false, false, 0, false, true))
        assertNull(of(false, false, 0, false, false))
        assertEquals(2, SessionStatus.of(false, true, 2, false, true)?.tasks)
    }

    @Test
    fun turnFinishedOutOfSightIsUnreadUntilOpened() {
        val hub = Fixtures.readyHub(app)
        val chat = parked(hub, "s-bg")
        chat.force("busy", true)
        assertEquals(Activity.Answering, hub.sessionStatus("s-bg")?.activity)

        chat.force("busy", false)
        hub.onTurnFinished(chat)
        assertEquals(Activity.Unread, hub.sessionStatus("s-bg")?.activity)

        hub.open(summary("s-bg"))
        assertNull("点开看过就不再记", hub.sessionStatus("s-bg"))
    }

    @Test
    fun stoppedOrWatchedTurnIsNotUnread() {
        val hub = Fixtures.readyHub(app)
        val chat = parked(hub, "s-stopped")
        hub.onTurnFinished(chat, stopped = true)
        assertNull("自己叫停的不算新回答", hub.sessionStatus("s-stopped"))

        hub.open(summary("s-seen"))
        hub.onTurnFinished(hub.current)
        assertNull("正看着的不算新回答", hub.sessionStatus("s-seen"))
    }

    @Test
    fun unreadFollowsTheSessionIdAndGoesWithTheSession() {
        val hub = Fixtures.readyHub(app)
        val chat = parked(hub, "s-old")
        hub.onTurnFinished(chat)
        hub.sessionChanged(chat, "s-old", "s-new")
        assertNull(hub.sessionStatus("s-old"))
        assertEquals(Activity.Unread, hub.sessionStatus("s-new")?.activity)

        hub.closeSessions(setOf("s-new"))
        assertNull("删掉的会话不再记", hub.sessionStatus("s-new"))
    }

    @Test
    fun coreReportsSessionsOpenedElsewhere() {
        val hub = Fixtures.readyHub(app)
        fun core(vararg sessions: Pair<String, SessionActivity>) =
            hub.sessionList.activityReceived("local", mapOf(*sessions))
        core(
            "s-a" to SessionActivity(Activity.Answering, 0, 100),
            "s-b" to SessionActivity(Activity.Waiting, 1, 0),
        )
        assertEquals(Activity.Answering, hub.sessionStatus("s-a")?.activity)
        assertEquals(Activity.Waiting, hub.sessionStatus("s-b")?.activity)
        assertEquals(true, hub.isActive("s-a"))

        hub.open(summary("s-b"))
        core(
            "s-a" to SessionActivity(Activity.Idle, 0, 200),
            "s-b" to SessionActivity(Activity.Idle, 0, 300),
        )
        assertEquals("别处开的对话答完了，不在眼前的记成有新回答", Activity.Unread, hub.sessionStatus("s-a")?.activity)
        assertEquals("正看着的不算新回答", Activity.Idle, hub.sessionStatus("s-b")?.activity)
        assertEquals(false, hub.isActive("s-a"))

        core("s-c" to SessionActivity(Activity.Idle, 0, 400))
        assertEquals("第一次见到的会话不算刚答完", Activity.Idle, hub.sessionStatus("s-c")?.activity)
    }

    @Test
    fun localAndCoreCombine() {
        val hub = Fixtures.readyHub(app)
        val chat = parked(hub, "s-both")
        chat.force("busy", true)
        hub.sessionList.activityReceived(
            "local",
            mapOf("s-both" to SessionActivity(Activity.Background, 3, 0)),
        )
        val status = hub.sessionStatus("s-both")
        assertEquals(Activity.Answering, status?.activity)
        assertEquals(3, status?.tasks)
    }
}
