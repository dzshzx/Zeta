package com.dzshzx.zeta.shots

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.EnvHealth
import com.dzshzx.zeta.core.LoginState
import com.dzshzx.zeta.core.SshStatus
import com.dzshzx.zeta.core.environment
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.ui.setup.EnvInstallScreen
import com.dzshzx.zeta.ui.setup.LinuxScreen
import com.dzshzx.zeta.ui.setup.LoginScreen
import com.dzshzx.zeta.ui.setup.SshScreen
import org.junit.Test

/** Linux 环境页、安装向导、登录页、SSH 服务页。体检和 SSH 的状态要 root 才查得到，这里直接摆上去。 */
class SetupShots : ShotTest() {
    private val health =
        EnvHealth(
            missing = emptyList(),
            workspaceWritable = true,
            network = true,
            freeBytes = 48L * 1024 * 1024 * 1024,
            debian = "13.1",
            claude = "2.1.330 (Claude Code)",
            go = "go1.25.4",
            node = "v22.11.0",
            zetaOwned = setOf("go"),
        )

    private val ssh =
        SshStatus(
            installed = true,
            running = true,
            autostart = true,
            port = "8022",
            listen = "127.0.0.1:8022",
            keys = 2,
            passwordLogin = false,
        )

    private fun readyWithStatus(): ConversationHub =
        Fixtures.readyHub(app).also { hub ->
            hub.environment.health = health
            hub.environment.ssh = ssh
        }

    @Test
    fun linux() {
        val hub = readyWithStatus()
        shoot("linux") {
            LinuxScreen(
                hub = hub,
                onBack = {},
                onOpenInstall = {},
                onOpenLogin = {},
                onOpenSsh = {},
            )
        }
    }

    /** 点“位置”弹出的环境菜单。 */
    @Test
    fun linuxLocationMenu() {
        val hub = readyWithStatus()
        shoot("linux-location", prepare = { onNodeWithText("位置").performClick() }) {
            LinuxScreen(
                hub = hub,
                onBack = {},
                onOpenInstall = {},
                onOpenLogin = {},
                onOpenSsh = {},
            )
        }
    }

    /** 点“默认工作目录”弹出的输入框。 */
    @Test
    fun linuxCwdDialog() {
        val hub = readyWithStatus()
        shoot("linux-cwd", prepare = { onNodeWithText("默认工作目录").performClick() }) {
            LinuxScreen(
                hub = hub,
                onBack = {},
                onOpenInstall = {},
                onOpenLogin = {},
                onOpenSsh = {},
            )
        }
    }

    @Test
    fun install() {
        val hub = Fixtures.readyHub(app)
        shoot("env-install") { EnvInstallScreen(hub = hub, onBack = {}, onOpenLogin = {}) }
    }

    @Test
    fun login() {
        val hub = Fixtures.readyHub(app)
        shoot("login") { LoginScreen(hub = hub, backend = Backends.LOCAL, onBack = {}) }
    }

    /** 开始登录以后：打开登录页、贴授权码那一步。 */
    @Test
    fun loginCode() {
        val hub = Fixtures.readyHub(app)
        hub.accounts(Backends.LOCAL)
            .login
            .force(
                "state",
                LoginState.WaitingCode(
                    "https://claude.ai/oauth/authorize?code=true",
                    notice = "授权码不完整，请重新复制",
                ),
            )
        shoot("login-code") { LoginScreen(hub = hub, backend = Backends.LOCAL, onBack = {}) }
    }

    /** SSH 页每次打开都会重新查一遍（没有 root，查出来是空的），查完再把状态摆上去。 */
    @Test
    fun sshService() {
        val hub = Fixtures.readyHub(app)
        shoot("ssh", prepare = { hub.environment.ssh = ssh }) { SshScreen(hub = hub, onBack = {}) }
    }
}
