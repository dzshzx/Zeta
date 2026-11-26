package com.dzshzx.zeta.core

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.schedule.RunReport
import com.dzshzx.zeta.core.schedule.UnattendedRun
import com.dzshzx.zeta.shots.Fixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * [TurnAlerts]：完成和确认提醒只在用户没看着这个对话时发，每个对话一条，等的请求没了就撤，用户叫停的不提醒；没人用手机时胶囊不显示；回答中的对话那条通知照小爱在原地变成等你、已完成。
 * 结果卡片弹不弹、留不留由 ResultCardShots 走一轮结束的真实路径验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TurnAlertsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun grantNotifications() =
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

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

    /** Claude Code 要批准一次 Bash（核心推来的样子）。 */
    private fun bash(id: String) =
        PermissionRequest(
            requestId = id,
            toolName = "Bash",
            input =
                buildJsonObject {
                    put("command", "ls")
                    put("description", "列出文件")
                },
            notice = "Claude 想用 Bash",
        )

    /** Claude Code 问一道单选题。 */
    private fun running(i: Int) =
        BackgroundTask("t$i", "local_agent", TaskKind.Agent, "", null, null, null, startedAt = 0)

    private fun question(id: String) =
        PermissionRequest(
            requestId = id,
            toolName = "AskUserQuestion",
            input = JsonObject(emptyMap()),
            questions =
                listOf(
                    Question(
                        "先改哪个？",
                        "顺序",
                        listOf(QuestionOption("界面", ""), QuestionOption("文档", "")),
                        multiSelect = false,
                    ),
                ),
            notice = "Claude 有问题要问你",
            asksInput = true,
        )

    /** 核心推来：多了一个等用户回应的请求。 */
    private fun Conversation.ask(prompt: UserPrompt) = core {
        it.copy(prompts = it.prompts + prompt)
    }

    /** 核心推来：请求 [id] 没了（Claude Code 撤回了）。 */
    private fun Conversation.withdraw(id: String) = core { s ->
        s.copy(prompts = s.prompts.filterNot { it.requestId == id })
    }

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

    /** 一轮答完、后台任务还在跑：不响铃、不说已完成；后台都做完、最后一轮答完时才响铃说已完成。 */
    @Test
    fun backgroundTasksStillRunningIsNotDone() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = false
        val chat = hub.current
        chat.core { it.copy(tasks = List(3) { i -> running(i) }) }
        hub.alerts.turnFinished(chat, stopped = false)
        assertEquals("答完了，后台还有 3 个任务在跑", text(chat.openKey))
        assertEquals("后台还在跑，不响铃", "silent", notification(chat.openKey)!!.group)

        chat.core { it.copy(tasks = emptyList()) }
        hub.alerts.turnFinished(chat, stopped = false)
        assertEquals("已完成", text(chat.openKey))
        assertNotEquals("都做完了才响铃", "silent", notification(chat.openKey)!!.group)
    }

    @Test
    fun attentionAlertOnlyWhenNotWatching() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = true
        hub.current.ask(bash("r1"))
        assertEquals(emptyList<String>(), alerts())
        hub.current.withdraw("r1")
        hub.foreground = false
        hub.current.ask(bash("r2"))
        assertEquals(listOf("需要你确认：列出文件"), alerts())
    }

    /** 请求在用户看着时来、用户随后离开：照样提醒，第一次响铃；回来看过再离开只更新、不响铃（进不响铃的那一组）。 */
    @Test
    fun leavingAWaitingConversationAlerts() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = true
        hub.current.ask(bash("r1"))
        assertEquals(emptyList<String>(), alerts())
        hub.foreground = false
        assertEquals(listOf("需要你确认：列出文件"), alerts())
        assertNull("第一次响铃", notification(hub.current.openKey)!!.group)
        hub.foreground = true
        assertEquals(emptyList<String>(), alerts())
        hub.foreground = false
        assertEquals("silent", notification(hub.current.openKey)!!.group)
        assertEquals(
            listOf("允许", "拒绝"),
            notification(hub.current.openKey)!!.actions.map { it.title.toString() },
        )
    }

    /** 每个对话一条提醒（tag 是会话的 openKey），点开的 PendingIntent 各对话不同，不会互相顶掉。 */
    @Test
    fun oneAlertPerConversation() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = false
        val other = conversation(hub, "别的")
        hub.current.ask(bash("r1"))
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
        hub.current.ask(bash("r1"))
        hub.current.withdraw("r1")
        assertNull(text(hub.current.openKey))
        assertEquals(listOf("已完成"), alerts())
        // 在这里回答了：核心收走它，推来的请求里没有了
        hub.current.ask(bash("r2"))
        assertEquals("需要你确认：列出文件", text(hub.current.openKey))
        hub.current.withdraw("r2")
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
        val core = FakeCore().install(hub)
        hub.foreground = false
        val conversation = hub.current
        conversation.ask(bash("r1"))
        val actions = notification(conversation.openKey)!!.actions.toList()
        assertEquals(listOf("允许", "拒绝"), actions.map { it.title.toString() })
        assertEquals(listOf(true, false), actions.map { it.isAuthenticationRequired })
        conversation.withdraw("r1")
        conversation.ask(bash("r2"))
        hub.alerts.reply(conversation.openKey, "r1", 0)
        assertEquals("过时的按钮不动新的请求", "r2", conversation.pending?.requestId)
        hub.alerts.reply(conversation.openKey, "r2", 1)
        val answered = core.next("conv.answer")
        assertEquals("r2", answered.str("request_id"))
        assertEquals("deny", answered.params.obj("reply")?.str("kind"))
        awaitCore { conversation.pending == null }
        assertNull(text(conversation.openKey))
    }

    /** 只有一道单选题时，选项就是按钮；按下就按这个选项回答。 */
    @Test
    fun singleChoiceQuestionHasOptionButtons() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        hub.foreground = false
        val conversation = hub.current
        conversation.ask(question("q1"))
        val alert = notification(conversation.openKey)!!
        assertEquals("需要你回答：先改哪个？", alert.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(listOf("界面", "文档"), alert.actions.map { it.title.toString() })
        hub.alerts.reply(conversation.openKey, "q1", 1)
        val answered = core.next("conv.answer")
        assertEquals(
            """{"kind":"answer","answers":{"先改哪个？":"文档"}}""",
            answered.params.obj("reply").toString(),
        )
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
        hub.current.ask(bash("r1"))
        assertEquals(emptyList<String>(), alerts())
        hub.current.withdraw("r1")
        // 跑完摘掉标记，就是普通对话了
        hub.current.unattended = null
        assertNull(hub.current.phone.unattended)
        hub.current.ask(bash("r2"))
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
        assertNull(hub.alerts.resultCard)
    }

    /** 照小爱，一个对话只有一条通知：进行中、等你确认、又进行中、已完成都在这一条上更新；看着它时、叫停时撤掉。 */
    @Test
    fun oneNotificationFollowsTheTurn() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = false
        val chat = hub.current
        val key = chat.openKey
        chat.core { it.copy(busy = true) }
        hub.alerts.sync()
        val progress = notification(key)!!
        assertEquals(chat.overlay.stepInfo.text, text(key))
        assertEquals("进行中不响", "silent", progress.group)
        assertTrue(progress.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(progress.extras.containsKey(FocusParam.EXTRA))
        assertEquals(1, alerts().size)

        chat.ask(bash("one-r1"))
        hub.alerts.sync()
        val waiting = notification(key)!!
        assertEquals("需要你确认：列出文件", text(key))
        assertEquals(listOf("允许", "拒绝"), waiting.actions.map { it.title.toString() })
        // 超级岛上的按钮对得上通知上的那批
        val buttons = waiting.extras.getBundle(FocusParam.ACTIONS)!!
        assertEquals(2, buttons.size())
        assertEquals(1, alerts().size)

        chat.withdraw("one-r1")
        hub.alerts.sync()
        assertEquals("请求没了，接着显示进行中", chat.overlay.stepInfo.text, text(key))

        chat.core { it.copy(busy = false) }
        hub.alerts.turnFinished(chat, stopped = false)
        hub.alerts.sync()
        assertEquals(listOf("已完成"), alerts())

        // 叫停：进行中的那条撤掉
        clear()
        chat.core { it.copy(busy = true) }
        hub.alerts.sync()
        assertEquals(1, alerts().size)
        chat.core { it.copy(busy = false) }
        hub.alerts.turnFinished(chat, stopped = true)
        hub.alerts.sync()
        assertEquals(emptyList<String>(), alerts())

        // 看着这个对话时不发，回到前台看着它时撤掉
        chat.core { it.copy(busy = true) }
        hub.alerts.sync()
        assertEquals(1, alerts().size)
        hub.foreground = true
        hub.alerts.sync()
        assertEquals(emptyList<String>(), alerts())
    }
}
