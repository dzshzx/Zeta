package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 远程主机上「Zeta」tmux 会话里的窗口什么时候开（[DeskWindows]）：新会话拿到会话号时开一次；已有会话号的对话每次起进程都再开一次（helper
 * 那边已经有窗口就不动），tmux 服务器没了丢掉的窗口这样补回来；手机本机上的对话不开。进程是真起的，单元测试的 su 总是失败（同 ControlResponsesTest），这里只看发出去的
 * helper 命令。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class DeskWindowsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val remote =
        RemoteHost("r-00000001", "工作站", "dev-box", "/home/user", "/home/user/workspace")
    private val sent = mutableListOf<List<String>>()
    private val original = DeskWindows.run

    @Before
    fun record() {
        DeskWindows.run = { _, backend, command, arguments ->
            sent += listOf(backend, command) + arguments
        }
    }

    @After
    fun restore() {
        DeskWindows.run = original
    }

    private fun chat(sessionId: String?, backend: String = remote.id): Conversation {
        val hub = Fixtures.readyHub(app)
        hub.updateRemoteHosts(listOf(remote))
        return Conversation(
            hub,
            "/home/user/workspace",
            sessionId = sessionId,
            title = "",
            historyFile = null,
            backend = backend,
        )
    }

    @Test
    fun startingAKnownSessionReopensItsWindow() {
        chat("s-old").prepare()
        assertEquals(
            listOf(listOf(remote.id, "desk-open", "s-old", "/home/user/workspace")),
            sent,
        )
    }

    @Test
    fun newSessionOpensOnceWhenItGetsItsId() {
        val chat = chat(sessionId = null)
        chat.prepare()
        assertEquals(emptyList<List<String>>(), sent)
        chat.receive("""{"type":"system","subtype":"init","session_id":"s-new"}""")
        assertEquals(
            listOf(listOf(remote.id, "desk-open", "s-new", "/home/user/workspace")),
            sent,
        )
    }

    @Test
    fun phoneConversationsOpenNoWindow() {
        chat("s-local", backend = Backends.LOCAL).prepare()
        assertEquals(emptyList<List<String>>(), sent)
    }
}
