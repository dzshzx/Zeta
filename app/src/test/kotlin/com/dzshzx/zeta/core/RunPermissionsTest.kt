package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.phone.tools.ScheduleTools
import com.dzshzx.zeta.shots.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 每条消息按发的人的权限模式跑：后台运行的模式只管它发的那几条，用户自己发下一条时进程切回用户的；
 * 输入栏显示的、用户的设置都不被后台运行改。这里不起进程（没配 Linux 环境），看的是要起进程时带的模式（[Conversation.processMode]）；
 * 已经开着的进程上切模式、Claude Code 那一侧由 UnattendedRulesContractTest 和设备脚本 mode-per-sender.sh 钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class RunPermissionsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val rules = ScheduleTools.unattendedRules

    private fun hub(): ConversationHub {
        val hub = Fixtures.readyHub(app)
        hub.settings.env = null
        hub.reloadSettings()
        return hub
    }

    /** 新对话；[mode] 给了就是用户在输入栏选过的。 */
    private fun conversation(hub: ConversationHub, mode: String? = null) =
        Conversation(hub, "/workspace/claude", sessionId = null, title = "任务", historyFile = null).also { chat ->
            mode?.let(chat::choosePermissionMode)
        }

    private fun status(mode: String) = """{"type":"system","subtype":"status","status":null,"permissionMode":"$mode"}"""

    @Test
    fun runModeLastsUntilTheUserSends() {
        val chat = conversation(hub(), "acceptEdits")
        chat.sendUnattended("跑一次", "bypassPermissions", rules)
        assertEquals("bypassPermissions", chat.processMode)
        // 输入栏照旧是用户的：用户接下来发的按它跑
        assertEquals("acceptEdits", chat.permissionMode)
        // 后台运行这一轮里 Claude Code 报来的是运行的模式，不改用户的
        chat.receive(status("bypassPermissions"))
        assertEquals("acceptEdits", chat.permissionMode)
        chat.sendUnattended("再跑一次", "auto", rules)
        assertEquals("auto", chat.processMode)
        chat.send("我自己问")
        assertEquals("acceptEdits", chat.processMode)
        assertEquals("acceptEdits", chat.launchArgument("--permission-mode"))
        // 用户的模式上 Claude Code 自己换了（计划获批后退出计划模式）：用户的跟着变
        chat.receive(status("default"))
        assertEquals("default", chat.permissionMode)
    }

    @Test
    fun choosingDuringARunKeepsTheChoice() {
        val chat = conversation(hub(), "acceptEdits")
        chat.sendUnattended("跑一次", "bypassPermissions", rules)
        chat.choosePermissionMode("plan")
        chat.send("我自己问")
        assertEquals("plan", chat.processMode)
    }

    @Test
    fun unknownUserModeFallsBackToAsking() {
        val hub = hub()
        val chat = conversation(hub)
        assertNull(chat.permissionMode)
        assertNull(chat.launchArgument("--permission-mode"))
        chat.sendUnattended("跑一次", "auto", rules)
        // 起进程时指定了模式，init 报的是后台运行的，不当成用户的
        chat.receive("""{"type":"system","subtype":"init","session_id":"s1","permissionMode":"auto"}""")
        assertNull(chat.permissionMode)
        // Claude Code 的默认还没读到：用户的消息用逐项确认
        assertNull(hub.defaultPermissionMode)
        chat.send("我自己问")
        assertEquals("default", chat.processMode)
        // 没指定模式起的进程报来的是 Claude Code 照设置定的：这个对话从此用它，全局的默认只认 settings.json
        val fresh = conversation(hub)
        fresh.receive("""{"type":"system","subtype":"init","session_id":"s2","permissionMode":"acceptEdits"}""")
        assertEquals("acceptEdits", fresh.permissionMode)
        assertNull(hub.defaultPermissionMode)
    }

    /** 用户没选过、Claude Code 的默认已知（settings.json 里设的）：后台运行之后用户的消息切回那个默认，输入栏一直显示它。 */
    @Test
    fun userWithoutChoiceGetsClaudeCodeDefaultAfterARun() {
        val hub = hub()
        hub.updateClaudeDefaults(ClaudeDefaults(model = null, permissionMode = "auto", efforts = emptyMap(), effort = null))
        val chat = conversation(hub)
        assertEquals("auto", chat.shownPermissionMode)
        chat.sendUnattended("跑一次", "bypassPermissions", rules)
        assertEquals("auto", chat.shownPermissionMode)
        chat.send("我自己问")
        assertEquals("auto", chat.processMode)
        assertNull(chat.permissionMode)
    }

    /**
     * 应用被回收后接回还在跑的进程：用户在这个对话里定的模式照进程上记下的恢复，接着按它跑；
     * 没记下的（比如后台运行起的进程）不当成用户的，用户的消息切到 Claude Code 的默认。
     */
    @Test
    fun reattachRestoresTheUserMode() {
        val hub = hub()
        fun host(key: String, mode: String) =
            HostInfo(key, "/workspace/claude", "", sessionId = "s-$key", historyFile = null, lines = 0, busy = true, pending = null, permissionMode = mode)
        fun attached(host: HostInfo) = Conversation(hub, host.cwd, host.sessionId, host.title, host.historyFile, attach = host)
        hub.rememberHostMode("mine", "plan")
        val mine = attached(host("mine", "plan"))
        assertEquals("plan", mine.permissionMode)
        assertEquals("plan", mine.shownPermissionMode)
        mine.send("接着问")
        assertEquals("plan", mine.processMode)

        val run = attached(host("run", "bypassPermissions"))
        assertNull(run.permissionMode)
        run.send("我自己问")
        assertEquals("default", run.processMode)
    }

    @Test
    fun launchAllowsSwitchingIntoBypassLater() {
        val chat = conversation(hub(), "default")
        assertTrue("--allow-dangerously-skip-permissions" in chat.launchArguments())
        assertFalse("--dangerously-skip-permissions" in chat.launchArguments())
    }
}
