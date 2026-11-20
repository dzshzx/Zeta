package com.dzshzx.zeta.shots

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.RemoteHost
import com.dzshzx.zeta.core.RemoteHosts
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.ssh.SshKey
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.chat.ChatScreen
import com.dzshzx.zeta.ui.setup.RemoteHostsScreen
import org.junit.Test

/**
 * 远程主机：设置里的清单、侧栏顶部的主机切换（和它的菜单）、远程主机上的新对话，
 * 以及手机上没有 Debian 环境时：聊天页让人去连远程主机，直连的主机照常能用。
 * 单元测试里进不了 Linux 环境，读 ~/.ssh/config 读不到，「从手机 Debian 选」一组是空的。
 */
class RemoteHostShots : ShotTest() {
    private val hostKey = RemoteHosts.encodeKey(SshKey.generate().blob)
    private val hosts = listOf(
        RemoteHost("r-00000001", "工作站", "nerv-wsl", "/home/ubuntu", "/home/ubuntu/workspace"),
        RemoteHost("r-00000002", "personal-vps", "root@203.0.113.7:2222", "/root", "/root", RemoteHost.Via.Direct, hostKey),
    )

    private fun hub(selected: String? = "r-00000001"): ConversationHub {
        val hub = Fixtures.readyHub(app)
        hub.settings.remoteHosts = hosts
        // 不经 selectBackend：它会去读那台主机的会话列表，测试里进不了环境，侧栏会多一行“没读到”
        if (selected != null) {
            hub.settings.backend = selected
            hub.startNew()
        }
        return hub
    }

    @Test
    fun settings() {
        val hub = hub()
        shoot("remote-hosts") { RemoteHostsScreen(hub = hub, onBack = {}) }
    }

    @Test
    fun hostPage() {
        val hub = hub()
        shoot("remote-host", prepare = { onNodeWithText("工作站").performClick() }) { RemoteHostsScreen(hub = hub, onBack = {}) }
    }

    /** 直连的主机：连接方式、主机指纹。 */
    @Test
    fun directHostPage() {
        val hub = hub()
        shoot("remote-host-direct", prepare = { onNodeWithText("personal-vps").performClick() }) { RemoteHostsScreen(hub = hub, onBack = {}) }
    }

    /** 点「Zeta 的公钥」：整行公钥，复制了加进那台主机的 authorized_keys。 */
    @Test
    fun publicKey() {
        val hub = hub()
        shoot("remote-public-key", prepare = { onNodeWithText("Zeta 的公钥").performClick() }) { RemoteHostsScreen(hub = hub, onBack = {}) }
    }

    /** 没有 Debian 环境：只能直连，没有「从手机 Debian 选」那一组。 */
    @Test
    fun settingsWithoutDebian() {
        val hub = Fixtures.bareHub(app)
        shoot("remote-hosts-bare") { RemoteHostsScreen(hub = hub, onBack = {}) }
    }

    /** 没有 Debian 环境、还没加远程主机：聊天页给「连接远程主机」。 */
    @Test
    fun chatWithoutDebian() {
        val hub = Fixtures.bareHub(app)
        shoot("remote-bare-chat") { ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {}) }
    }

    /** 没有 root：加过远程主机的，聊天页给主机胶囊换过去。 */
    @Test
    fun chatWithoutRoot() {
        val hub = Fixtures.bareHub(app, SetupState.NoRoot)
        hub.settings.remoteHosts = hosts
        shoot("remote-noroot-chat") { ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {}) }
    }

    /** 没有 Debian 环境，侧栏切到直连的主机：对话照常能开。 */
    @Test
    fun directChatWithoutDebian() {
        val hub = Fixtures.bareHub(app)
        hub.settings.remoteHosts = hosts
        hub.settings.backend = "r-00000002"
        hub.startNew()
        shoot("remote-bare-direct-chat") { ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {}) }
    }

    private val sessions = listOf(
        SessionSummary("w1", "/home/ubuntu/.claude/projects/-w/w1.jsonl", "/home/ubuntu/workspace/Zeta", "Zeta 多后端", "", System.currentTimeMillis(), backend = "r-00000001"),
        SessionSummary("w2", "/home/ubuntu/.claude/projects/-w/w2.jsonl", "/home/ubuntu/workspace/fleet-state", "排查 company-vps 磁盘告警", "", System.currentTimeMillis() - 86_400_000L * 3, backend = "r-00000001"),
    )

    @Test
    fun pane() {
        val hub = hub()
        shoot("remote-pane") { PaneShot(sessions) }
    }

    @Test
    fun paneMenu() {
        val hub = hub()
        shoot("remote-pane-menu", prepare = { onNodeWithText("工作站").performClick() }) { PaneShot(sessions) }
    }

    /** 连不上时侧栏里说原因，不说「还没有对话」。 */
    @Test
    fun paneUnreachable() {
        val hub = hub()
        hub.sessionList.force("problem", "连不上「工作站」：网络不通")
        shoot("remote-pane-unreachable") { PaneShot(emptyList()) }
    }

    /** 远程主机上的新对话：空白页上是主机胶囊和那台上的工作目录。 */
    @Test
    fun newChat() {
        val hub = hub()
        shoot("remote-new-chat") { ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {}) }
    }

    /** 远程主机上的对话：标题下面写着主机。 */
    @Test
    fun conversation() {
        val hub = hub()
        hub.showChat("Zeta 多后端", listOf(UserItem("u1", "看一下 git status")))
        shoot("remote-chat") { ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {}) }
    }
}
