package com.dzshzx.zeta.core

import android.app.Application
import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.phone.PhoneTools
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.chat.toolTitle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 常驻通知（Robolectric 上用真的 Notification.Builder）：提升时带上实时通知要的字段、系统认它可以提升，不提升时照旧；
 * 胶囊那句当前步骤分出的类别（状态栏小胶囊上的词据此取）。系统是否真的接受提升要上真机看 dumpsys。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LiveNotificationTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() = Fixtures.install(null)

    private fun service() = Robolectric.buildService(AgentService::class.java).create().get()

    private fun live(progress: TodoProgress? = null) =
        LiveStatus("s1", "修 bug", "运行 ls", StepKind.TOOL, startedAt = 1_000L, progress = progress, others = 1)

    @Test
    fun promotedCarriesLiveFields() {
        val n = service().notification(StatusNotice(active = true, busy = true, running = listOf("修 bug", "别的"), live = live()))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertTrue("系统据此决定能不能提升", n.hasPromotableCharacteristics())
        // 对照：不常驻的通知系统不认，上面这条检查不是摆设
        assertFalse(Notification.Builder(app, Notifier.CHANNEL_STATUS).setContentTitle("x").build().hasPromotableCharacteristics())
        assertEquals("执行中", n.shortCriticalText)
        assertEquals("修 bug", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("运行 ls", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("另有 1 个对话在回答", n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertEquals(1_000L, n.`when`)
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        // 点开回到这个对话
        assertEquals("s1", shadowOf(n.contentIntent).savedIntent.getStringExtra("com.dzshzx.zeta.extra.CONVERSATION"))
        assertEquals(Notification.BigTextStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
    }

    @Test
    fun todoListBecomesProgressBar() {
        val n = service().notification(StatusNotice(true, true, listOf("修 bug"), live(progress = TodoProgress(2, 5))))
        assertEquals(Notification.ProgressStyle::class.java.name, n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(2, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(5, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertTrue(n.hasPromotableCharacteristics())
    }

    @Test
    fun notPromotedKeepsOldLook() {
        val n = service().notification(StatusNotice(active = true, busy = true, running = listOf("修 bug"), live = null))
        assertFalse(n.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertNull(n.shortCriticalText)
        assertEquals("Claude 正在工作", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("修 bug", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val idle = service().notification(StatusNotice(active = true, busy = false, running = emptyList(), live = null))
        assertEquals("Claude 会话保持中", idle.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse(idle.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
    }

    @Test
    fun stepKinds() {
        val hub = Fixtures.readyHub(app)
        val conversation = hub.current
        val focus = conversation.overlay

        hub.showChat("t", listOf(AnswerItem("a", "你好", streaming = true)))
        assertEquals(Step("正在回答", StepKind.ANSWERING), focus.stepInfo)
        assertEquals("正在回答", focus.step)

        val bash = ToolStep("t1", "Bash", buildJsonObject { put("command", "ls -la") }, ToolStatus.Running, null)
        hub.showChat("t", listOf(WorkItem("w", listOf(bash))))
        assertEquals(Step(toolTitle("Bash", bash.input), StepKind.TOOL), focus.stepInfo)

        val tap = ToolStep("t2", "mcp__${PhoneTools.SERVER}__tap", buildJsonObject { put("id", 3) }, ToolStatus.Running, null)
        hub.showChat("t", listOf(WorkItem("w", listOf(tap))))
        assertEquals(StepKind.PHONE, focus.stepInfo.kind)

        hub.showChat("t", listOf(WorkItem("w", listOf(bash.copy(status = ToolStatus.Success)))))
        assertEquals(Step("正在思考", StepKind.THINKING), focus.stepInfo)

        conversation.force("status", "正在压缩上下文")
        assertEquals(Step("正在压缩上下文", StepKind.STATUS), focus.stepInfo)

        conversation.force("pending", PermissionRequest("r1", "Bash", JsonObject(emptyMap()), emptyList()))
        assertEquals(Step("需要你确认", StepKind.CONFIRMING), focus.stepInfo)
        conversation.force("pending", PermissionRequest("r2", "AskUserQuestion", JsonObject(emptyMap()), emptyList()))
        assertEquals(Step("需要你回答", StepKind.ASKING), focus.stepInfo)
    }
}
