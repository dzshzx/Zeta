package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.ssh.SshKey
import com.dzshzx.zeta.shots.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 侧栏所在的主机（[ConversationHub.backend]）和当前对话所在的主机一起动：换主机时新对话开在那台上；
 * 打开别的主机上的会话（通知、查找），侧栏跟过去；删掉正在用的主机，回到手机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class BackendSwitchTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val remote = RemoteHost("r-00000001", "工作站", "nerv-wsl", "/home/ubuntu", "/home/ubuntu/workspace")

    private fun hub(): ConversationHub = Fixtures.readyHub(app).also { it.settings.remoteHosts = listOf(remote) }

    @Test
    fun selectingAHostStartsTheNewConversationThere() {
        val hub = hub()
        assertEquals(Backends.LOCAL, hub.backend)
        hub.selectBackend(remote.id)
        assertEquals(remote.id, hub.backend)
        assertEquals(remote.id, hub.current.backend)
        assertEquals("/home/ubuntu/workspace", hub.current.cwd)
        // 不认识的编号不动
        hub.selectBackend("r-nope")
        assertEquals(remote.id, hub.backend)
        hub.selectBackend(Backends.LOCAL)
        assertEquals(Backends.LOCAL, hub.current.backend)
    }

    @Test
    fun openingASessionOnAnotherHostMovesTheSidebarThere() {
        val hub = hub()
        val summary = SessionSummary("s-remote", "/home/ubuntu/.claude/projects/-w/s-remote.jsonl", "/home/ubuntu/workspace/Zeta", "远程会话", "", 0L, backend = remote.id)
        hub.open(summary)
        assertEquals(remote.id, hub.current.backend)
        assertEquals(remote.id, hub.backend)
        // 接着开的新对话也在那台上
        val opened = hub.current
        hub.startNew()
        assertNotSame(opened, hub.current)
        assertEquals(remote.id, hub.current.backend)
    }

    /** 手机上没有 Debian：直连的主机照常能用，经 Debian 连的和手机本机都说明为什么用不了。 */
    @Test
    fun directHostsDoNotNeedDebian() {
        val hub = Fixtures.bareHub(app)
        val direct = RemoteHost("r-00000002", "vps", "me@example.com:2222", "/root", "/root", RemoteHost.Via.Direct, RemoteHosts.encodeKey(SshKey.generate().blob))
        hub.settings.remoteHosts = listOf(remote, direct)
        assertNull(hub.shell(Backends.LOCAL))
        assertEquals("还没有配置 Linux 环境，请到设置里检查", hub.unavailable(Backends.LOCAL))
        assertNull(hub.shell(remote.id))
        assertEquals("「工作站」经手机的 Debian 连，Debian 环境还没就绪", hub.unavailable(remote.id))
        assertTrue(hub.shell(direct.id) is DirectSshShell)
        assertNull(hub.unavailable(direct.id))
        hub.selectBackend(direct.id)
        assertEquals(direct.id, hub.current.backend)
        assertEquals("/root", hub.current.cwd)
        assertEquals("远程主机已经从设置里删掉了", hub.unavailable("r-gone"))
    }

    /** 换手机的 Debian 环境只收掉要靠 Debian 的主机上的对话，直连主机上的照常留着。 */
    @Test
    fun redetectingDebianKeepsDirectConversations() {
        val hub = hub()
        val direct = RemoteHost("r-00000002", "vps", "me@example.com:2222", "/root", "/root", RemoteHost.Via.Direct, RemoteHosts.encodeKey(SshKey.generate().blob))
        hub.settings.remoteHosts = listOf(remote, direct)
        hub.selectBackend(direct.id)
        val onDirect = hub.current
        hub.dropConversations()
        assertSame(onDirect, hub.current)
        assertEquals(direct.id, hub.backend)
        hub.selectBackend(remote.id)
        val viaDebian = hub.current
        hub.dropConversations()
        assertNotSame(viaDebian, hub.current)
    }

    @Test
    fun removingTheHostInUseFallsBackToThePhone() {
        val hub = hub()
        hub.selectBackend(remote.id)
        val onRemote = hub.current
        hub.updateRemoteHosts(emptyList())
        assertEquals(Backends.LOCAL, hub.backend)
        // 存着的也改回来（列表、默认值跟着换成手机的）
        assertEquals(Backends.LOCAL, hub.settings.backend)
        assertEquals(Backends.LOCAL, hub.current.backend)
        assertNotSame(onRemote, hub.current)
        // 换回手机后再选一次手机：当前对话不换
        val local = hub.current
        hub.selectBackend(Backends.LOCAL)
        assertSame(local, hub.current)
    }
}
