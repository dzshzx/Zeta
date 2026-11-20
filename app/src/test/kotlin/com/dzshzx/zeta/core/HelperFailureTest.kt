package com.dzshzx.zeta.core

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * zeta-helper 没跑成不等于输出为空（[HelperResult]）。单元测试里的 su 总是失败（app/src/test/no-root/su），
 * 和手机上 Linux 环境出错一样：会话列表留着原来的，接回后台对话时不删用户的权限模式。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class HelperFailureTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun reset() = Fixtures.install(null)

    /** 等名字在 [names] 里的后台线程都结束，再把它们投回主线程的消息放完。 */
    private fun settleThreads(vararg names: String) {
        Thread.getAllStackTraces().keys.filter { it.name in names }.forEach { it.join(30_000) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun onlyASuccessfulRunHasOutput() {
        assertEquals("[]", HelperResult.of(CommandResult(0, "[]", "")).outputOrNull)
        assertEquals("进不了 Linux 环境", (HelperResult.of(null) as HelperResult.Failed).reason)
        val timedOut = HelperResult.of(CommandResult(-1, """{"partial":1}""", CommandResult.TIMEOUT))
        assertEquals("Linux 环境没有响应（超时）", (timedOut as HelperResult.Failed).reason)
        val failed = HelperResult.of(CommandResult(1, """{"type":"zeta_x"}""", "open /x: no such file\n"))
        assertNull("非 0 退出时不给输出", failed.outputOrNull)
        assertEquals("open /x: no such file", (failed as HelperResult.Failed).reason)
        assertEquals("zeta-helper 出错（退出码 97）", (HelperResult.of(CommandResult(97, "", "  ")) as HelperResult.Failed).reason)
    }

    @Test
    fun failedRefreshKeepsTheSessionList() {
        val list = Fixtures.readyHub(app).sessionList
        val kept = SessionSummary("a", "/home/dzshzx/.claude/projects/-w/a.jsonl", "/workspace/claude", "留着的", "", 0)
        list.listed(listOf(kept))
        list.refresh()
        settleThreads("zeta-sessions")
        assertEquals(listOf(kept), list.sessions)
    }

    @Test
    fun failedReattachKeepsPermissionModes() {
        val hub = Fixtures.readyHub(app)
        hub.settings.hostModes = mapOf("k-running" to "plan")
        hub.onEnvironmentReady()
        settleThreads("zeta-hosts", "zeta-sessions", "zeta-defaults")
        assertEquals(mapOf("k-running" to "plan"), hub.settings.hostModes)
    }
}
