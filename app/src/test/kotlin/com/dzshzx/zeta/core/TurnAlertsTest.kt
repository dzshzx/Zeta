package com.dzshzx.zeta.core

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.schedule.RunReport
import com.dzshzx.zeta.core.schedule.UnattendedRun
import com.dzshzx.zeta.shots.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [TurnAlerts]：完成和确认提醒只在用户没看着这个对话时发，每个对话一条，等的请求没了就撤，用户叫停的不提醒；没人用手机时胶囊不显示、实时通知没有内容。 结果卡片弹不弹、留不留由
 * ResultCardShots 走一轮结束的真实路径验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TurnAlertsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun reset() {
        Fixtures.install(null)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun alerts(): List<String> =
        shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.map {
            it.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        }

    private fun clear() = app.getSystemService(NotificationManager::class.java).cancelAll()

    private fun notification(key: String): Notification? =
        shadowOf(app.getSystemService(NotificationManager::class.java))
            .getNotification(key, Notifier.ID_ALERT)

    private fun text(key: String): String? =
        notification(key)?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun conversation(hub: ConversationHub, title: String) =
        Conversation(hub, "/workspace/claude", sessionId = null, title = title, historyFile = null)

    /** Claude Code 要批准一次 Bash。 */
    private fun bash(id: String) =
        """{"type":"control_request","request_id":"$id","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"ls","description":"列出文件"}}}"""

    /** Claude Code 问一道单选题。 */
    private fun question(id: String) =
        """{"type":"control_request","request_id":"$id","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","input":{"questions":[{"question":"先改哪个？","header":"顺序","multiSelect":false,"options":[{"label":"界面","description":""},{"label":"文档","description":""}]}]}}}"""

    /** Claude Code 撤回请求 [id]。 */
    private fun cancel(id: String) = """{"type":"control_cancel_request","request_id":"$id"}"""

    @Test
    fun completionAlertOnlyWhenNotWatching() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = true
        hub.alerts.turnFinished(hub.current, stopped = false)
        assertEquals(emptyList<String>(), alerts())
        // Zeta 切到后台了
        hub.foreground = false
        hub.alerts.turnFinished(hub.current, stopped = true)
        assertEquals("用户叫停的不提醒", emptyList<String>(), alerts())
        hub.alerts.turnFinished(hub.current, stopped = false)
        assertEquals(listOf("已完成"), alerts())
        clear()
        // 前台显示的是别的对话
        hub.foreground = true
        val other =
            Conversation(
                hub,
                "/workspace/claude",
                sessionId = null,
                title = "别的",
                historyFile = null,
            )
        hub.alerts.turnFinished(other, stopped = false)
        assertEquals(listOf("已完成"), alerts())
    }

    @Test
    fun attentionAlertOnlyWhenNotWatching() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = true
        hub.current.receive(bash("r1"))
        assertEquals(emptyList<String>(), alerts())
        hub.current.receive(cancel("r1"))
        hub.foreground = false
        hub.current.receive(bash("r2"))
        assertEquals(listOf("需要你确认：列出文件"), alerts())
    }

    /** 每个对话一条提醒（tag 是会话的 openKey），点开的 PendingIntent 各对话不同，不会互相顶掉。 */
    @Test
    fun oneAlertPerConversation() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = false
        val other = conversation(hub, "别的")
        hub.current.receive(bash("r1"))
        hub.alerts.turnFinished(other, stopped = false, text = "出错了")
        assertEquals(setOf("需要你确认：列出文件", "出错了"), alerts().toSet())
        assertEquals("出错了", text(other.openKey))
        assertNotEquals(
            Notifier.conversationOpen(app, hub.current.openKey),
            Notifier.conversationOpen(app, other.openKey),
        )
    }

    /** 等的请求没了（Claude Code 撤回、用户在别处回答），这个对话的提醒随即撤掉，别的对话的留着。 */
    @Test
    fun alertGoesAwayWithTheRequest() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = false
        val other = conversation(hub, "别的")
        hub.alerts.turnFinished(other, stopped = false)
        hub.current.receive(bash("r1"))
        hub.current.receive(cancel("r1"))
        assertNull(text(hub.current.openKey))
        assertEquals(listOf("已完成"), alerts())
        hub.current.receive(bash("r2"))
        hub.current.pending?.let { hub.current.respond(it, allow = false) }
        assertNull(text(hub.current.openKey))
    }

    /** 回到前台、看着的对话的提醒撤掉；切走的对话的留着，切过去再撤。 */
    @Test
    fun watchingClearsOnlyThatConversation() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = false
        val other = conversation(hub, "别的")
        hub.alerts.turnFinished(hub.current, stopped = false)
        hub.alerts.turnFinished(other, stopped = false)
        assertEquals(2, alerts().size)
        hub.foreground = true
        assertNull(text(hub.current.openKey))
        assertEquals("已完成", text(other.openKey))
    }

    /** 提醒上的按钮：一般工具“允许”“拒绝”，只有允许要先解锁；按下就回答那个请求，提醒撤掉；过时的按钮不回答新的请求。 */
    @Test
    fun replyFromTheAlert() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = false
        val conversation = hub.current
        conversation.receive(bash("r1"))
        val actions = notification(conversation.openKey)!!.actions.toList()
        assertEquals(listOf("允许", "拒绝"), actions.map { it.title.toString() })
        assertEquals(listOf(true, false), actions.map { it.isAuthenticationRequired })
        conversation.receive(cancel("r1"))
        conversation.receive(bash("r2"))
        hub.alerts.reply(conversation.openKey, "r1", 0)
        assertEquals("过时的按钮不动新的请求", "r2", conversation.pending?.requestId)
        hub.alerts.reply(conversation.openKey, "r2", 1)
        assertNull(conversation.pending)
        assertNull(text(conversation.openKey))
    }

    /** 只有一道单选题时，选项就是按钮；按下就按这个选项回答。 */
    @Test
    fun singleChoiceQuestionHasOptionButtons() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = false
        val conversation = hub.current
        conversation.receive(question("q1"))
        val alert = notification(conversation.openKey)!!
        assertEquals("需要你回答：先改哪个？", alert.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(listOf("界面", "文档"), alert.actions.map { it.title.toString() })
        hub.alerts.reply(conversation.openKey, "q1", 1)
        assertNull(conversation.pending)
    }

    /** 后台运行跑着的对话（挂着 [UnattendedRun]）：完成和确认由 ScheduleRunner 带任务名提醒，通用的这两条不发。 */
    @Test
    fun unattendedRunsAlertThemselves() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = false
        val run = UnattendedRun(onlyOnChange = false)
        hub.current.unattended = run
        assertSame("手机工具上同步挂着", run, hub.current.phone.unattended)
        hub.alerts.turnFinished(hub.current, stopped = false)
        hub.current.receive(bash("r1"))
        assertEquals(emptyList<String>(), alerts())
        hub.current.receive(cancel("r1"))
        // 跑完摘掉标记，就是普通对话了
        hub.current.unattended = null
        assertNull(hub.current.phone.unattended)
        hub.current.receive(bash("r2"))
        assertEquals(listOf("需要你确认：列出文件"), alerts())
    }

    /** 只在有变化时提醒的后台运行：Claude 上报没有变化、也没停下来等过确认，这一轮跑完不提醒、不弹结果卡片。 */
    @Test
    fun unattendedRunIsQuietOnlyWithoutChange() {
        val run = UnattendedRun(onlyOnChange = true)
        assertFalse("没上报：照常提醒", run.quiet)
        run.report = RunReport(changed = false, summary = "没有新邮件")
        assertTrue(run.quiet)
        run.notifiedRequest = "r1"
        assertTrue(run.asked)
        assertFalse("等过确认：照常提醒", run.quiet)
        assertFalse(
            "每次都提醒的不会安静",
            UnattendedRun(onlyOnChange = false).apply { report = RunReport(false, "") }.quiet,
        )
    }

    @Test
    fun nothingToShowWhenNobodyUsesThePhone() {
        val hub = Fixtures.readyHub(app)
        assertNull(hub.alerts.overlayFocus)
        assertNull(hub.alerts.liveStatus(emptyList()))
        assertNull(hub.alerts.resultCard)
    }
}
