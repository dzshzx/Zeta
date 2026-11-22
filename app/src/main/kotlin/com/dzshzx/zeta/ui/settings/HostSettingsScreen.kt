package com.dzshzx.zeta.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.environment
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.setup.ClaudeAgentsScreen
import com.dzshzx.zeta.ui.setup.ClaudeMcpScreen
import com.dzshzx.zeta.ui.setup.ClaudeMemoryScreen
import com.dzshzx.zeta.ui.setup.ClaudeSkillsScreen
import com.dzshzx.zeta.ui.setup.RemoteHostScreen
import com.dzshzx.zeta.ui.setup.ToolPermissionsScreen
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 主机页往下的一层：这台主机上的默认思考强度、账号、定时任务、通知触发和 Claude Code 自己的配置。 */
private enum class HostPage {
    DefaultEffort,
    Accounts,
    Schedules,
    NotifyTriggers,
    Memory,
    Skills,
    Mcp,
    Agents,
    ToolPermissions,
}

private const val ConfigFooter = "改的是这台设备上 Claude Code 自己的配置（~/.claude），那边终端里的 claude 同样生效。"

private const val LinuxFooter =
    "手机上的 Claude Code 跑在手机里的 Debian 上：安装、体检、可选工具、文件、SSH 服务，" + "以及 Claude Code 的版本和登录都在这里。"

/**
 * 设置 › 一台主机（手机或一台远程主机）。上面三组是这台上的 Claude Code：新对话的默认值（模型、思考强度、权限模式， 存在那台的
 * ~/.claude/settings.json，所以每台各是各的），账号、压缩、标题和在这台上跑的定时任务、通知触发，Claude Code
 * 自己的配置（记忆、Skills、MCP、Subagent、工具权限）； 下面手机是 Linux 环境的入口，远程主机是连接（[RemoteHostScreen]）。读写的都是 [backend]
 * 这台，和侧栏正看着哪台无关。[onShowChat] 收起设置页回到聊天页：打开某次后台运行的对话时用。
 */
@Composable
internal fun HostSettingsScreen(
    hub: ConversationHub,
    backend: String,
    onBack: () -> Unit,
    onOpenLinux: () -> Unit,
    onShowChat: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf<HostPage?>(null) }
    val close = { page = null }
    val ready = hub.usable(backend)
    // 默认值可能在那台的终端里改过（/model、/effort 存为默认）：每次打开都重读
    LaunchedEffect(backend, ready) { if (ready) hub.refreshClaudeDefaults(backend) }
    val claude: LazyListScope.() -> Unit = { claudeItems(hub, backend, ready) { page = it } }
    Box(Modifier.fillMaxSize()) {
        if (backend == Backends.LOCAL) LocalHostScreen(hub, onBack, onOpenLinux, claude)
        else RemoteHostScreen(hub, backend, onBack, claude)
        SlideInPage(page, onBack = close) { shown ->
            when (shown) {
                HostPage.DefaultEffort ->
                    DefaultEffortScreen(hub = hub, backend = backend, onBack = close)
                HostPage.Accounts -> AccountsScreen(hub = hub, backend = backend, onBack = close)
                HostPage.Schedules -> ScheduleScreen(hub, backend, onBack = close, onShowChat)
                HostPage.NotifyTriggers ->
                    NotifyTriggerScreen(hub, backend, onBack = close, onShowChat)
                HostPage.Memory -> ClaudeMemoryScreen(hub = hub, backend = backend, onBack = close)
                HostPage.Skills -> ClaudeSkillsScreen(hub = hub, backend = backend, onBack = close)
                HostPage.Mcp -> ClaudeMcpScreen(hub = hub, backend = backend, onBack = close)
                HostPage.Agents -> ClaudeAgentsScreen(hub = hub, backend = backend, onBack = close)
                HostPage.ToolPermissions ->
                    ToolPermissionsScreen(hub = hub, backend = backend, onBack = close)
            }
        }
    }
}

/** 主机页上面三组：新对话默认、Claude、Claude Code 配置。这台用不了（[ready] 为 false）时配置一组灰着。 */
private fun LazyListScope.claudeItems(
    hub: ConversationHub,
    backend: String,
    ready: Boolean,
    onOpen: (HostPage) -> Unit,
) {
    item(key = "defaults") {
        PreferenceGroup(title = "新对话默认", footer = ClaudeDefaultsFooter) {
            item { DefaultModelPreference(hub, backend) }
            item { DefaultEffortEntry(hub, backend, onOpen = { onOpen(HostPage.DefaultEffort) }) }
            item { DefaultPermissionModePreference(hub, backend) }
            item { CompactWindowPreference(hub, backend) }
        }
    }
    // 每台各登各的账号、各设各的压缩和标题；定时任务、通知触发由手机到点或收到通知时在这台上起对话
    item(key = "claude") {
        PreferenceGroup(title = "Claude") {
            item { AccountsEntry(hub, backend) { onOpen(HostPage.Accounts) } }
            item { AutoCompactSwitch(hub, backend) }
            item { CompactTimingPreference(hub, backend) }
            item { AutoTitleSwitch(hub, backend) }
            item { ScheduleEntry(backend) { onOpen(HostPage.Schedules) } }
            item { NotifyTriggerEntry(backend) { onOpen(HostPage.NotifyTriggers) } }
        }
    }
    item(key = "config") {
        PreferenceGroup(title = "Claude Code 配置", footer = ConfigFooter) {
            fun entry(title: String, page: HostPage) = item {
                ArrowPreference(title = title, enabled = ready, onClick = { onOpen(page) })
            }
            entry("记忆", HostPage.Memory)
            entry("Skills", HostPage.Skills)
            entry("MCP 服务器", HostPage.Mcp)
            entry("Subagent", HostPage.Agents)
            entry("工具权限", HostPage.ToolPermissions)
        }
    }
}

/** 设置 › 手机：Claude Code 的三组，下面是这台手机的自定义设备名和 Linux 环境（手机里的 Debian）的入口。 */
@Composable
private fun LocalHostScreen(
    hub: ConversationHub,
    onBack: () -> Unit,
    onOpenLinux: () -> Unit,
    claude: LazyListScope.() -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    CouiPage(
        title = hub.backendName(Backends.LOCAL),
        onBack = onBack,
        dialogs = {
            DeviceNameDialog(hub, Backends.LOCAL, show = renaming, onDismiss = { renaming = false })
        },
    ) {
        claude()
        item(key = "device") {
            PreferenceGroup(title = "设备", footer = LinuxFooter) {
                item { DeviceNameRow(hub, Backends.LOCAL) { renaming = true } }
                item {
                    val (value, bad) = linuxState(hub)
                    ArrowPreference(
                        title = "Linux 环境",
                        endActions = {
                            PreferenceValue(
                                value,
                                color = if (bad) COUITheme.colorScheme.error else Color.Unspecified,
                            )
                        },
                        onClick = onOpenLinux,
                    )
                }
            }
        }
    }
}

/** 手机的 Debian 现在怎样、要不要红字：就绪了是 Linux 用户名。 */
internal fun linuxState(hub: ConversationHub): Pair<String, Boolean> =
    when (hub.environment.setup) {
        SetupState.Checking -> "检查中" to false
        SetupState.NoRoot -> "没有 root" to true
        SetupState.NoEnvironment -> "未安装" to true
        SetupState.Ready -> hub.settings.env?.let { it.user to false } ?: ("未安装" to true)
    }
