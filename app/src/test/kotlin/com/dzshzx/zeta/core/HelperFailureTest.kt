package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * zeta-helper 没跑成不等于输出为空（[HelperResult]）。单元测试里的 su 总是失败（app/src/test/no-root/su）， 和手机上 Linux
 * 环境出错一样：会话列表留着原来的；改名、归档没写进会话记录时，界面上先改了的改回去，并说一声。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class HelperFailureTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun session(id: String, title: String, archived: Boolean = false) =
        SessionSummary(
            id,
            "/home/dzshzx/.claude/projects/-w/$id.jsonl",
            "/workspace/claude",
            title,
            "",
            0,
            archived = archived,
        )

    @Test
    fun onlyASuccessfulRunHasOutput() {
        assertEquals("[]", HelperResult.of(CommandResult(0, "[]", "")).outputOrNull)
        assertEquals("无法进入 Linux 环境", (HelperResult.of(null) as HelperResult.Failed).reason)
        val timedOut =
            HelperResult.of(CommandResult(-1, """{"partial":1}""", CommandResult.TIMEOUT))
        assertEquals("Linux 环境没有响应（超时）", (timedOut as HelperResult.Failed).reason)
        val failed =
            HelperResult.of(CommandResult(1, """{"type":"zeta_x"}""", "open /x: no such file\n"))
        assertNull("非 0 退出时不给输出", failed.outputOrNull)
        assertEquals("open /x: no such file", (failed as HelperResult.Failed).reason)
        assertEquals(
            "zeta-helper 出错（退出码 97）",
            (HelperResult.of(CommandResult(97, "", "  ")) as HelperResult.Failed).reason,
        )
    }

    @Test
    fun failedRefreshKeepsTheSessionList() {
        val list = Fixtures.readyHub(app).sessionList
        val kept = session("a", "留着的")
        list.listed(listOf(kept))
        list.refresh()
        awaitMain { list.problem != null }
        assertEquals(listOf(kept), list.sessions)
    }

    @Test
    fun failedRenameIsUndone() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = true
        val list = hub.sessionList
        list.listed(listOf(session("a", "原来的")))
        list.rename(list.sessions.single(), "新标题")
        assertEquals("界面先改", "新标题", list.sessions.single().title)
        // 没写成就改回去
        awaitMain { list.sessions.single().title == "原来的" }
        assertTrue(
            ShadowToast.getTextOfLatestToast(),
            ShadowToast.getTextOfLatestToast().startsWith("重命名失败："),
        )
    }

    @Test
    fun failedArchiveIsUndone() {
        val list = Fixtures.readyHub(app).sessionList
        list.listed(listOf(session("a", "留着的"), session("b", "已归档的", archived = true)))
        list.archive(list.sessions.filter { !it.archived }, archived = true)
        assertTrue("界面先改", list.sessions.all { it.archived })
        // 没写成就改回去，原来归档的不动
        awaitMain { list.sessions.map { it.archived } == listOf(false, true) }
    }
}
