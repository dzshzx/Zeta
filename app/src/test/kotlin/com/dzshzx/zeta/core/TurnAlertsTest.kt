package com.dzshzx.zeta.core

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.schedule.RunReport
import com.dzshzx.zeta.core.schedule.UnattendedRun
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.Fixtures.force
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * [TurnAlerts]：完成和确认提醒只在用户没看着这个对话时发，用户叫停的不提醒；没人用手机时胶囊不显示、实时通知没有内容。
 * 结果卡片弹不弹、留不留由 ResultCardShots 走一轮结束的真实路径验。
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
        shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
            .map { it.extras.getCharSequence(Notification.EXTRA_TEXT).toString() }

    private fun clear() = app.getSystemService(NotificationManager::class.java).cancelAll()

    @Test
    fun completionAlertOnlyWhenNotWatching() {
        val hub = Fixtures.readyHub(app)
        hub.force("foreground", true)
        hub.alerts.turnFinished(hub.current, stopped = false)
        assertEquals(emptyList<String>(), alerts())
        // Zeta 切到后台了
        hub.force("foreground", false)
        hub.alerts.turnFinished(hub.current, stopped = true)
        assertEquals("用户叫停的不提醒", emptyList<String>(), alerts())
        hub.alerts.turnFinished(hub.current, stopped = false)
        assertEquals(listOf("已完成"), alerts())
        clear()
        // 前台显示的是别的对话
        hub.force("foreground", true)
        val other = Conversation(hub, "/workspace/claude", sessionId = null, title = "别的", historyFile = null)
        hub.alerts.turnFinished(other, stopped = false)
        assertEquals(listOf("已完成"), alerts())
    }

    @Test
    fun attentionAlertOnlyWhenNotWatching() {
        val hub = Fixtures.readyHub(app)
        hub.force("foreground", true)
        hub.alerts.attentionNeeded(hub.current)
        assertEquals(emptyList<String>(), alerts())
        hub.force("foreground", false)
        hub.alerts.attentionNeeded(hub.current)
        assertEquals(listOf("需要你确认"), alerts())
    }

    /** 后台运行跑着的对话（挂着 [UnattendedRun]）：完成和确认由 ScheduleRunner 带任务名提醒，通用的这两条不发。 */
    @Test
    fun unattendedRunsAlertThemselves() {
        val hub = Fixtures.readyHub(app)
        hub.force("foreground", false)
        val run = UnattendedRun(onlyOnChange = false)
        hub.current.unattended = run
        assertSame("手机工具上同步挂着", run, hub.current.phone.unattended)
        hub.alerts.turnFinished(hub.current, stopped = false)
        hub.alerts.attentionNeeded(hub.current)
        assertEquals(emptyList<String>(), alerts())
        // 跑完摘掉标记，就是普通对话了
        hub.current.unattended = null
        assertNull(hub.current.phone.unattended)
        hub.alerts.attentionNeeded(hub.current)
        assertEquals(listOf("需要你确认"), alerts())
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
        assertFalse("每次都提醒的不会安静", UnattendedRun(onlyOnChange = false).apply { report = RunReport(false, "") }.quiet)
    }

    @Test
    fun nothingToShowWhenNobodyUsesThePhone() {
        val hub = Fixtures.readyHub(app)
        assertNull(hub.alerts.overlayFocus)
        assertNull(hub.alerts.liveStatus(emptyList()))
        assertNull(hub.alerts.resultCard)
    }
}
