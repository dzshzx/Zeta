package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [SessionList]：列表在界面上马上变，开着的对话跟着改名、删除时先收掉。读写会话记录要 root，这里不验。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SessionListTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun summary(id: String, title: String = "会话 $id") = SessionSummary(id, "/home/dzshzx/.claude/projects/-w/$id.jsonl", "/workspace/claude", title, "", 0)

    /** 自动起的标题先记着：在它写进会话记录之前读到的列表用记着的，读到写进去的以后就不再替它补。 */
    @Test
    fun freshTitleSurvivesAStaleList() {
        val list = Fixtures.readyHub(app).sessionList
        list.listed(listOf(summary("a", "你好")))
        list.titled("a", "起好的标题")
        assertEquals("起好的标题", list.sessions.single().title)
        list.listed(listOf(summary("a", "你好")))
        assertEquals("起好的标题", list.sessions.single().title)
        list.listed(listOf(summary("a", "起好的标题")))
        // 列表里已经是新标题了，以后用户在别处改名也照读到的显示
        list.listed(listOf(summary("a", "用户改的")))
        assertEquals("用户改的", list.sessions.single().title)
    }

    @Test
    fun renameAlsoRetitlesTheOpenConversation() {
        val hub = Fixtures.readyHub(app)
        val list = hub.sessionList
        list.listed(listOf(summary("a"), summary("b")))
        hub.open(list.sessions.first())
        list.rename(list.sessions.first(), "  新的   名字 ")
        assertEquals(listOf("新的 名字", "会话 b"), list.sessions.map { it.title })
        assertEquals("新的 名字", hub.current.title)
    }

    @Test
    fun archiveOnlyFlagsTheChosen() {
        val list = Fixtures.readyHub(app).sessionList
        list.listed(listOf(summary("a"), summary("b"), summary("c")))
        list.archive(list.sessions.take(2), true)
        assertEquals(listOf(true, true, false), list.sessions.map { it.archived })
        list.archive(list.sessions.first(), false)
        assertEquals(listOf(false, true, false), list.sessions.map { it.archived })
    }

    /** 删除：从列表去掉，开着的对话换成空的新对话，置顶标记一并去掉。 */
    @Test
    fun deleteClosesTheOpenConversation() {
        val hub = Fixtures.readyHub(app)
        val list = hub.sessionList
        list.listed(listOf(summary("a"), summary("b")))
        hub.settings.pinnedSessions = setOf("a", "b")
        hub.open(list.sessions.first())
        val opened = hub.current
        list.delete(list.sessions.first())
        assertEquals(listOf("b"), list.sessions.map { it.id })
        assertEquals(setOf("b"), hub.settings.pinnedSessions)
        assertNotSame(opened, hub.current)
        assertTrue(hub.current.sessionId == null && hub.current.items.isEmpty())
    }
}
