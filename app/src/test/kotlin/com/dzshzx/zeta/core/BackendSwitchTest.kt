package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.ssh.SshKey
import com.dzshzx.zeta.shots.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    private val remote =
        RemoteHost("r-00000001", "工作站", "dev-box", "/home/user", "/home/user/workspace")

    private fun hub(): ConversationHub =
        Fixtures.readyHub(app).also { it.settings.remoteHosts = listOf(remote) }

    private companion object {
        /** 直连主机的地址：本机一个没人听的端口，连它立刻被拒绝。选中直连主机后 hub 会在后台去连（读默认值、会话列表）， 写成外网的名字，测试就真的往外连，还要等满连接超时。 */
        const val DIRECT_TARGET = "me@127.0.0.1:1"
    }

    @Test
    fun selectingAHostStartsTheNewConversationThere() {
        val hub = hub()
        assertEquals(Backends.LOCAL, hub.backend)
        hub.selectBackend(remote.id)
        assertEquals(remote.id, hub.backend)
        assertEquals(remote.id, hub.current.backend)
        assertEquals("/home/user/workspace", hub.current.cwd)
        // 不认识的编号不动
        hub.selectBackend("r-nope")
        assertEquals(remote.id, hub.backend)
        hub.selectBackend(Backends.LOCAL)
        assertEquals(Backends.LOCAL, hub.current.backend)
    }

    @Test
    fun openingASessionOnAnotherHostMovesTheSidebarThere() {
        val hub = hub()
        val summary =
            SessionSummary(
                "s-remote",
                "/home/user/.claude/projects/-w/s-remote.jsonl",
                "/home/user/workspace/Zeta",
                "远程会话",
                "",
                0L,
                backend = remote.id,
            )
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
        val direct =
            RemoteHost(
                "r-00000002",
                "vps",
                DIRECT_TARGET,
                "/root",
                "/root",
                RemoteHost.Via.Direct,
                RemoteHosts.encodeKey(SshKey.generate().blob),
            )
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
        assertEquals("远程设备已经从设置里删掉了", hub.unavailable("r-gone"))
    }

    /** 换手机的 Debian 环境只收掉要靠 Debian 的主机上的对话，直连主机上的照常留着。 */
    @Test
    fun redetectingDebianKeepsDirectConversations() {
        val hub = hub()
        val direct =
            RemoteHost(
                "r-00000002",
                "vps",
                DIRECT_TARGET,
                "/root",
                "/root",
                RemoteHost.Via.Direct,
                RemoteHosts.encodeKey(SshKey.generate().blob),
            )
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

    /** 加主机时默认的连法：手机 Debian 就绪就经它，没有 Debian 时直连。 */
    @Test
    fun defaultViaFollowsDebian() {
        assertEquals(RemoteHost.Via.Debian, hub().defaultVia)
        assertEquals(RemoteHost.Via.Direct, Fixtures.bareHub(app).defaultVia)
    }

    /** 改连法：那台上有对话在回答时不改；闲着时地址、主机指纹跟着换成新连法的，侧栏还在那台上，别的主机不动。 */
    @Test
    fun changingTheViaWaitsForWorkToFinish() {
        val hub = hub()
        val direct =
            RemoteHost(
                "r-00000002",
                "vps",
                DIRECT_TARGET,
                "/root",
                "/root",
                RemoteHost.Via.Direct,
                RemoteHosts.encodeKey(SshKey.generate().blob),
            )
        hub.settings.remoteHosts = listOf(remote, direct)
        hub.selectBackend(direct.id)
        val target = RemoteHosts.targetFor(direct.target, RemoteHost.Via.Debian)!!
        assertEquals("ssh://$DIRECT_TARGET", target)
        hub.current.core { it.copy(busy = true) }
        assertTrue(hub.hostWorking(direct.id))
        assertFalse(hub.changeVia(direct.id, RemoteHost.Via.Debian, target, null))
        assertEquals(direct, hub.remoteHost(direct.id))
        hub.current.core { it.copy(busy = false) }
        assertTrue(hub.changeVia(direct.id, RemoteHost.Via.Debian, target, "ignored"))
        val changed = hub.remoteHost(direct.id)!!
        assertEquals(RemoteHost.Via.Debian, changed.via)
        assertEquals(target, changed.target)
        // 经 Debian 的主机指纹由那边的 known_hosts 管，不记
        assertNull(changed.hostKey)
        assertTrue(hub.shell(direct.id) is DebianSshShell)
        assertEquals(direct.id, hub.backend)
        assertEquals(remote, hub.remoteHost(remote.id))
    }
}
