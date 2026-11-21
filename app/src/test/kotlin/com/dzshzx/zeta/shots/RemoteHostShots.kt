package com.dzshzx.zeta.shots

import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.ModelOption
import com.dzshzx.zeta.core.RemoteHost
import com.dzshzx.zeta.core.RemoteHosts
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.ssh.SshKey
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.chat.ChatScreen
import com.dzshzx.zeta.ui.settings.HostSettingsScreen
import com.dzshzx.zeta.ui.setup.RemoteHostsScreen
import org.junit.Test

/**
 * 远程主机：添加远程主机（输入地址时选连接方式、从 ~/.ssh/config 选）、主机页（新对话默认、改连接方式）、
 * 侧栏顶部的主机切换（和它的菜单）、远程主机上的新对话，
 * 以及手机上没有 Debian 环境时：只能直连，聊天页让人去连远程主机，直连的主机照常能用。
 * 单元测试里进不了 Linux 环境，「从 ~/.ssh/config 选」那页显示读不到和原因。
 */
class RemoteHostShots : ShotTest() {
    private val hostKey = RemoteHosts.encodeKey(SshKey.generate().blob)
    private val hosts = listOf(
        Fixtures.workstation,
        RemoteHost("r-00000002", "personal-vps", "root@203.0.113.7:2222", "/root", "/root", RemoteHost.Via.Direct, hostKey),
    )

    /** 主机页滚到连接那一组：它还没组合进 LazyColumn，不能 scrollIntoView，按条目的键滚。 */
    private fun AndroidComposeTestRule<*, *>.toBottom() = onNode(hasScrollToKeyAction()).performScrollToKey("host")

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

    /** 添加远程主机：两种加法和 Zeta 直连用的公钥。加好的主机列在设置首页。 */
    @Test
    fun settings() {
        val hub = hub()
        shoot("remote-hosts") { RemoteHostsScreen(hub = hub, onBack = {}) }
    }

    /** 一台远程主机的页面：上面是那台上新对话的默认值和 Claude Code 的配置。 */
    @Test
    fun hostPage() {
        val hub = hub()
        hub.models = mapOf("r-00000001" to Fixtures.models)
        hub.updateClaudeDefaults("r-00000001", ClaudeDefaults("sonnet", "acceptEdits", mapOf("claude-sonnet-5-5" to "medium"), null))
        shoot("remote-host") { HostSettingsScreen(hub = hub, backend = "r-00000001", onBack = {}, onOpenLinux = {}) }
    }

    /** 远程主机页往下滑：连接（名字、地址、连接方式、默认工作目录）、检查连接、删掉。 */
    @Test
    fun hostConnection() {
        val hub = hub()
        shoot("remote-host-connection", prepare = { toBottom() }) {
            HostSettingsScreen(hub = hub, backend = "r-00000001", onBack = {}, onOpenLinux = {})
        }
    }

    /** 直连的主机：连接方式、主机指纹。 */
    @Test
    fun directHostPage() {
        val hub = hub()
        shoot("remote-host-direct", prepare = { toBottom() }) {
            HostSettingsScreen(hub = hub, backend = "r-00000002", onBack = {}, onOpenLinux = {})
        }
    }

    /** 主机页点「连接方式」：两种连法，选另一种先按它连一次，连上了才改。 */
    @Test
    fun viaMenu() {
        val hub = hub()
        shoot(
            "remote-host-via-menu",
            prepare = {
                toBottom()
                onNodeWithText("连接方式").performClick()
            },
        ) { HostSettingsScreen(hub = hub, backend = "r-00000002", onBack = {}, onOpenLinux = {}) }
    }

    /** 点「Zeta 的公钥」：整行公钥，复制了加进那台主机的 authorized_keys。 */
    @Test
    fun publicKey() {
        val hub = hub()
        shoot("remote-public-key", prepare = { onNodeWithText("Zeta 的公钥").scrollIntoView().performClick() }) { RemoteHostsScreen(hub = hub, onBack = {}) }
    }

    /** 输入地址：有 Debian 时默认经 Debian，上面能换成直连。 */
    @Test
    fun addressDialog() {
        val hub = hub()
        shoot("remote-address", prepare = { onNodeWithText("输入地址").performClick() }) { RemoteHostsScreen(hub = hub, onBack = {}) }
    }

    /** 从 ~/.ssh/config 选：单元测试里进不了 Debian，显示读不到和原因。 */
    @Test
    fun sshConfigPage() {
        val hub = hub()
        shoot("remote-ssh-config", prepare = { onNodeWithText("从 ~/.ssh/config 选").performClick() }) { RemoteHostsScreen(hub = hub, onBack = {}) }
    }

    /** 没有 Debian 环境：只能直连，没有「从 ~/.ssh/config 选」。 */
    @Test
    fun settingsWithoutDebian() {
        val hub = Fixtures.bareHub(app)
        shoot("remote-hosts-bare") { RemoteHostsScreen(hub = hub, onBack = {}) }
    }

    /** 没有 Debian 环境时输入地址：只有直连，不给选。 */
    @Test
    fun addressDialogWithoutDebian() {
        val hub = Fixtures.bareHub(app)
        shoot("remote-address-bare", prepare = { onNodeWithText("输入地址").performClick() }) { RemoteHostsScreen(hub = hub, onBack = {}) }
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
