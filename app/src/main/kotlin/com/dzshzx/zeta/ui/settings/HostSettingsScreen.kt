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
import com.dzshzx.zeta.core.RemoteHosts
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.TextInputDialog
import com.dzshzx.zeta.ui.setup.ClaudeAgentsScreen
import com.dzshzx.zeta.ui.setup.ClaudeMcpScreen
import com.dzshzx.zeta.ui.setup.ClaudeMemoryScreen
import com.dzshzx.zeta.ui.setup.ClaudeSkillsScreen
import com.dzshzx.zeta.ui.setup.RemoteHostScreen
import com.dzshzx.zeta.ui.setup.ToolPermissionsScreen
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 主机页往下的一层：这台主机上的默认思考强度和 Claude Code 自己的配置。 */
private enum class HostPage {
    DefaultEffort,
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
 * 设置 › 一台主机（手机或一台远程主机）。上面两组是这台上的 Claude Code：新对话的默认值（模型、思考强度、权限模式， 存在那台的
 * ~/.claude/settings.json，所以每台各是各的），和 Claude Code 自己的配置（记忆、Skills、MCP、Subagent、工具权限）； 下面手机是 Linux
 * 环境的入口，远程主机是连接（[RemoteHostScreen]）。读写的都是 [backend] 这台，和侧栏正看着哪台无关。
 */
@Composable
internal fun HostSettingsScreen(
    hub: ConversationHub,
    backend: String,
    onBack: () -> Unit,
    onOpenLinux: () -> Unit,
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

/** 主机页上面两组：新对话默认、Claude Code 配置。这台用不了（[ready] 为 false）时灰着。 */
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

/** 设置 › 手机：Claude Code 的两组，下面是这台手机的自定义设备名和 Linux 环境（手机里的 Debian）的入口。 */
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

/** 「自定义设备名」一行：右边是设备现在的名字（没起名字时是设备自己的）。点了改名（[DeviceNameDialog]）。 */
@Composable
internal fun DeviceNameRow(hub: ConversationHub, backend: String, onClick: () -> Unit) {
    ArrowPreference(
        title = "自定义设备名",
        endActions = { PreferenceValue(hub.backendName(backend)) },
        onClick = onClick,
    )
}

/** 给设备 [backend] 起名字：留空是不起，显示设备自己的名字；和别的设备重名、太长时不让确定。放在页面的 dialogs 里。 */
@Composable
internal fun DeviceNameDialog(
    hub: ConversationHub,
    backend: String,
    show: Boolean,
    onDismiss: () -> Unit,
) {
    var draft by remember(show) { mutableStateOf(hub.customName(backend).orEmpty()) }
    val original = hub.originalName(backend)
    val problem =
        RemoteHosts.nameProblem(draft, hub.backends.filter { it != backend }.map(hub::backendName))
    TextInputDialog(
        show = show,
        title = "自定义设备名",
        summary = problem ?: "留空就用设备自己的名字「$original」",
        value = draft,
        onValueChange = { draft = it },
        label = original,
        confirmText = "确定",
        confirmEnabled = problem == null,
        onConfirm = {
            hub.rename(backend, draft)
            onDismiss()
        },
        onDismissRequest = onDismiss,
    )
}

/** 手机的 Debian 现在怎样、要不要红字：就绪了是 Linux 用户名。 */
internal fun linuxState(hub: ConversationHub): Pair<String, Boolean> =
    when (hub.environment.setup) {
        SetupState.Checking -> "检查中" to false
        SetupState.NoRoot -> "没有 root" to true
        SetupState.NoEnvironment -> "未安装" to true
        SetupState.Ready -> hub.settings.env?.let { it.user to false } ?: ("未安装" to true)
    }
