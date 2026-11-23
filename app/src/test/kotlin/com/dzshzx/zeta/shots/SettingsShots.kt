package com.dzshzx.zeta.shots

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.FakeCore
import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.core.notify.NotifyRuleStore
import com.dzshzx.zeta.core.schedule.ScheduleStore
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.i18n.UiLanguage
import com.dzshzx.zeta.ui.settings.DefaultEffortScreen
import com.dzshzx.zeta.ui.settings.HostSettingsScreen
import com.dzshzx.zeta.ui.settings.SettingsBackupScreen
import com.dzshzx.zeta.ui.settings.SettingsScreen
import org.junit.Test

class SettingsShots : ShotTest() {
    private val models = Fixtures.models
    private val workstation = Fixtures.workstation

    /** 手机和一台远程主机，各有各的默认模型和思考强度。 */
    private fun hostsHub(): ConversationHub {
        val hub = Fixtures.readyHub(app)
        hub.settings.remoteHosts = listOf(workstation)
        hub.models = mapOf(Backends.LOCAL to models, workstation.id to models)
        hub.updateClaudeDefaults(
            Backends.LOCAL,
            ClaudeDefaults(
                model = "sonnet",
                permissionMode = "auto",
                efforts = mapOf("claude-sonnet-5-5" to "medium"),
                effort = null,
            ),
        )
        hub.updateClaudeDefaults(
            workstation.id,
            ClaudeDefaults(
                model = null,
                permissionMode = "auto",
                efforts = mapOf("claude-opus-5-5" to "xhigh"),
                effort = null,
            ),
        )
        return hub
    }

    /** 设置首页最上面：各台主机（右边是它的默认模型和思考强度）、添加远程主机，下面是 Claude 一组和手机与工具的开头。 */
    @Test
    fun settingsHome() {
        val hub = hostsHub()
        // 定时任务、通知触发的清单存在进程级的单例里，别的截图测试先跑会留下规则：清空，入口的说明才每次一样
        ScheduleStore.update(app) { emptyList() }
        NotifyRuleStore.update(app) { emptyList() }
        shoot("settings") {
            SettingsScreen(hub = hub, onBack = {}, onOpenLinux = {}, onShowChat = {})
        }
    }

    /** 手机上没装 Debian：手机那一行红字「未安装」，经 Debian 连的主机「Debian 未就绪」。 */
    @Test
    fun settingsWithoutDebian() {
        val hub = Fixtures.bareHub(app)
        hub.settings.remoteHosts = listOf(workstation)
        ScheduleStore.update(app) { emptyList() }
        NotifyRuleStore.update(app) { emptyList() }
        shoot("settings-bare") {
            SettingsScreen(hub = hub, onBack = {}, onOpenLinux = {}, onShowChat = {})
        }
    }

    /** 设置 › 手机 › 自定义设备名：留空就用系统里的设备名。 */
    @Test
    fun deviceName() {
        val hub = hostsHub()
        shoot(
            "settings-device-name",
            prepare = { scrollListTo("自定义设备名").performClick() },
        ) {
            HostSettingsScreen(
                hub = hub,
                backend = Backends.LOCAL,
                onBack = {},
                onOpenLinux = {},
                onShowChat = {},
            )
        }
    }

    /** 设置 › 手机：新对话默认、Claude Code 配置、设备（自定义设备名、Linux 环境）。 */
    @Test
    fun phoneHost() {
        val hub = hostsHub()
        shoot("settings-host-phone") {
            HostSettingsScreen(
                hub = hub,
                backend = Backends.LOCAL,
                onBack = {},
                onOpenLinux = {},
                onShowChat = {},
            )
        }
    }

    /** 设置 › 手机 ›「闲置超时」：值从这台主机上的核心读（system.idle），这里是假的核心答 30 分钟。 */
    @Test
    fun idleEnd() {
        val hub = hostsHub()
        val core = FakeCore().install(hub)
        core.respond = { call ->
            if (call.method == "system.idle") """{"minutes":30}""" else core.defaultReply(call)
        }
        shoot("settings-host-idle", prepare = { scrollListTo("闲置超时") }) {
            HostSettingsScreen(
                hub = hub,
                backend = Backends.LOCAL,
                onBack = {},
                onOpenLinux = {},
                onShowChat = {},
            )
        }
    }

    /** 设置 › 远程主机 ›「Claude Code」：版本从那台主机上的核心读（system.claude），点按在那台上跑 claude update。 */
    @Test
    fun remoteClaudeCode() {
        val hub = hostsHub()
        val core = FakeCore().install(hub)
        core.respond = { call ->
            if (call.method == "system.claude") """{"version":"2.1.330"}"""
            else core.defaultReply(call)
        }
        shoot("settings-host-remote-claude", prepare = { scrollListTo("Claude Code") }) {
            HostSettingsScreen(
                hub = hub,
                backend = workstation.id,
                onBack = {},
                onOpenLinux = {},
                onShowChat = {},
            )
        }
    }

    /** 设置首页最下面：手机与工具、通用、关于。 */
    @Test
    fun settingsBottom() {
        val hub = Fixtures.readyHub(app)
        shoot(
            "settings-bottom",
            prepare = { repeat(6) { onRoot().performTouchInput { swipeUp() } } },
        ) {
            SettingsScreen(hub = hub, onBack = {}, onOpenLinux = {}, onShowChat = {})
        }
    }

    /** 设置首页「通用」一组最上面的「语言」：简体中文和英文界面各一张（英文时这一行和核心的字换成英文，别的字还没进文字表）。 */
    @Test fun languageChinese() = shootLanguage(UiLanguage.SOURCE)

    @Test fun languageEnglish() = shootLanguage(UiLanguage.ENGLISH)

    private fun shootLanguage(lang: String) {
        val hub = Fixtures.readyHub(app)
        UiLanguage.current = lang
        shoot(
            "settings-language-$lang",
            prepare = { onNodeWithText(Strings.settingsLanguage()).scrollIntoView() },
        ) {
            SettingsScreen(hub = hub, onBack = {}, onOpenLinux = {}, onShowChat = {})
        }
    }

    /** 设置 › 手机 › 默认思考强度：Claude Code 清单里的模型各一行，Opus 设过、Fable 没设（跟随），还有一个清单里没有的旧模型。 */
    @Test
    fun defaultEffort() {
        val hub = Fixtures.readyHub(app)
        hub.models = mapOf(Backends.LOCAL to models)
        hub.updateClaudeDefaults(
            Backends.LOCAL,
            ClaudeDefaults(
                model = "opus",
                permissionMode = null,
                efforts = mapOf("claude-opus-5-5" to "xhigh", "claude-fable-5-1" to "high"),
                effort = null,
            ),
        )
        shoot("settings-default-effort") {
            DefaultEffortScreen(hub = hub, backend = Backends.LOCAL, onBack = {})
        }
    }

    /** 设置 › 备份与恢复。 */
    @Test
    fun backup() {
        val hub = Fixtures.readyHub(app)
        shoot("settings-backup") { SettingsBackupScreen(hub = hub, onBack = {}) }
    }
}
