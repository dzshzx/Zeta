package com.dzshzx.zeta.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.Harnesses
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.environment
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.miuix.DropdownPreference
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.PreferenceValue
import com.dzshzx.zeta.ui.components.miuix.SegmentedTabs
import com.dzshzx.zeta.ui.setup.ClaudeAgentsScreen
import com.dzshzx.zeta.ui.setup.ClaudeMcpScreen
import com.dzshzx.zeta.ui.setup.ClaudeMemoryScreen
import com.dzshzx.zeta.ui.setup.ClaudeSkillsScreen
import com.dzshzx.zeta.ui.setup.RemoteHostScreen
import com.dzshzx.zeta.ui.setup.ToolPermissionsScreen
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 主机页往下的一层：这台主机上的默认思考强度、账号、定时任务、通知触发和各工具自己的配置。 */
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

/**
 * 设置 › 一台主机（手机或一台远程主机）。支持选择这台设备上新建对话的默认工具（Claude Code / Antigravity）， 并可切换管理 Claude Code 与
 * Antigravity 的新对话默认值、账号与额度、CLI 版本更新以及各自的配置（规则/记忆、Skills、MCP、Subagent、工具权限）。
 */
@Composable
internal fun HostSettingsScreen(
    hub: ConversationHub,
    backend: String,
    onBack: () -> Unit,
    onOpenLinux: () -> Unit,
    onShowChat: () -> Unit,
    initialHarness: String = hub.settings.defaultHarness(backend),
) {
    var page by rememberSaveable { mutableStateOf<HostPage?>(null) }
    var harness by
        rememberSaveable(backend) {
            mutableStateOf(Harnesses.normalize(initialHarness))
        }
    val close = { page = null }
    val ready = hub.usable(backend)
    // 默认值可能在那台的终端里改过（/model、/effort 存为默认）：每次打开或切换工具都重读
    LaunchedEffect(backend, harness, ready) {
        if (ready) hub.refreshClaudeDefaults(backend, harness)
    }
    val items: LazyListScope.() -> Unit = {
        hostCliItems(
            hub = hub,
            backend = backend,
            harness = harness,
            ready = ready,
            onSelectHarness = { harness = it },
            onOpen = { page = it },
        )
    }
    Box(Modifier.fillMaxSize()) {
        if (backend == Backends.LOCAL) LocalHostScreen(hub, onBack, onOpenLinux, items)
        else RemoteHostScreen(hub, backend, onBack, items)
        SlideInPage(page, onBack = close) { shown ->
            HostSubPage(hub, backend, harness, shown, close, onShowChat)
        }
    }
}

@Composable
private fun HostSubPage(
    hub: ConversationHub,
    backend: String,
    harness: String,
    shown: HostPage,
    close: () -> Unit,
    onShowChat: () -> Unit,
) {
    when (shown) {
        HostPage.DefaultEffort ->
            DefaultEffortScreen(hub = hub, backend = backend, onBack = close, harness = harness)
        HostPage.Accounts ->
            AccountsScreen(hub = hub, backend = backend, onBack = close, harness = harness)
        HostPage.Schedules -> ScheduleScreen(hub, backend, onBack = close, onShowChat)
        HostPage.NotifyTriggers -> NotifyTriggerScreen(hub, backend, onBack = close, onShowChat)
        HostPage.Memory ->
            ClaudeMemoryScreen(hub = hub, backend = backend, onBack = close, harness = harness)
        HostPage.Skills ->
            ClaudeSkillsScreen(hub = hub, backend = backend, onBack = close, harness = harness)
        HostPage.Mcp ->
            ClaudeMcpScreen(hub = hub, backend = backend, onBack = close, harness = harness)
        HostPage.Agents ->
            ClaudeAgentsScreen(hub = hub, backend = backend, onBack = close, harness = harness)
        HostPage.ToolPermissions ->
            ToolPermissionsScreen(hub = hub, backend = backend, onBack = close, harness = harness)
    }
}

@Composable
private fun DefaultHarnessPreference(
    hub: ConversationHub,
    backend: String,
    onSelected: (String) -> Unit,
) {
    val current = hub.settings.defaultHarness(backend)
    DropdownPreference(
        title = Strings.settingsHostDefaultHarness(),
        summary = Strings.settingsHostDefaultHarnessSummary(),
        items = Harnesses.ALL.map(Harnesses::shortTitle),
        selectedIndex = Harnesses.ALL.indexOf(current).coerceAtLeast(0),
        onSelectedIndexChange = { index ->
            val next = Harnesses.ALL.getOrNull(index) ?: return@DropdownPreference
            hub.settings.setDefaultHarness(backend, next)
            onSelected(next)
            val conv = hub.current
            if (conv.backend == backend && conv.canSwitchHarness) conv.setHarness(next)
        },
    )
}

/** 主机页上面几组：默认工具选择、工具切换栏、新对话默认、账号与运行、工具自身配置。 */
private fun LazyListScope.hostCliItems(
    hub: ConversationHub,
    backend: String,
    harness: String,
    ready: Boolean,
    onSelectHarness: (String) -> Unit,
    onOpen: (HostPage) -> Unit,
) {
    val isAgy = Harnesses.normalize(harness) == Harnesses.AGY
    item(key = "default-harness") {
        PreferenceGroup {
            item { DefaultHarnessPreference(hub, backend, onSelectHarness) }
        }
    }
    item(key = "harness-tabs") {
        SegmentedTabs(
            tabs = Harnesses.ALL.map(Harnesses::shortTitle),
            selectedIndex = Harnesses.ALL.indexOf(Harnesses.normalize(harness)).coerceAtLeast(0),
            onSelect = { index -> Harnesses.ALL.getOrNull(index)?.let(onSelectHarness) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
    item(key = "defaults-$harness") { hostDefaultsGroup(hub, backend, harness, isAgy, onOpen) }
    item(key = "runtime-$harness") { hostRuntimeGroup(hub, backend, harness, isAgy, ready, onOpen) }
    item(key = "config-$harness") { hostConfigGroup(isAgy, ready, onOpen) }
}

@Composable
private fun hostDefaultsGroup(
    hub: ConversationHub,
    backend: String,
    harness: String,
    isAgy: Boolean,
    onOpen: (HostPage) -> Unit,
) {
    PreferenceGroup(
        title = Strings.settingsHostDefaults(),
        footer = if (isAgy) Strings.settingsDefaultsAgyFooter() else ClaudeDefaultsFooter,
    ) {
        item { DefaultModelPreference(hub, backend, harness) }
        item {
            DefaultEffortEntry(
                hub,
                backend,
                harness,
                onOpen = { onOpen(HostPage.DefaultEffort) },
            )
        }
        item { DefaultPermissionModePreference(hub, backend, harness) }
        if (!isAgy) item { CompactWindowPreference(hub, backend, harness) }
    }
}

@Composable
private fun hostRuntimeGroup(
    hub: ConversationHub,
    backend: String,
    harness: String,
    isAgy: Boolean,
    ready: Boolean,
    onOpen: (HostPage) -> Unit,
) {
    PreferenceGroup(title = Harnesses.shortTitle(harness)) {
        item { AccountsEntry(hub, backend, harness) { onOpen(HostPage.Accounts) } }
        if (isAgy) {
            item { AgyUpdateRow(hub, backend, ready) }
        } else {
            item { AutoCompactSwitch(hub, backend) }
            item { CompactTimingPreference(hub, backend) }
            if (backend != Backends.LOCAL) item { ClaudeCodeUpdateRow(hub, backend, ready) }
        }
        item { AutoTitleSwitch(hub, backend) }
        item { IdleEndPreference(hub, backend, ready) }
        if (!isAgy) {
            item { ScheduleEntry(backend) { onOpen(HostPage.Schedules) } }
            item { NotifyTriggerEntry(backend) { onOpen(HostPage.NotifyTriggers) } }
        }
    }
}

@Composable
private fun hostConfigGroup(isAgy: Boolean, ready: Boolean, onOpen: (HostPage) -> Unit) {
    PreferenceGroup(
        title = if (isAgy) Strings.settingsHostAgyConfig() else Strings.settingsHostClaudeConfig(),
        footer =
            if (isAgy) Strings.settingsHostAgyConfigFooter()
            else Strings.settingsHostConfigFooter(),
    ) {
        fun entry(title: String, page: HostPage) = item {
            ArrowPreference(title = title, enabled = ready, onClick = { onOpen(page) })
        }
        entry(
            if (isAgy) Strings.settingsHostRules() else Strings.settingsHostMemory(),
            HostPage.Memory,
        )
        entry("Skills", HostPage.Skills)
        entry(Strings.settingsHostMcpServers(), HostPage.Mcp)
        entry(Strings.settingsHostSubagents(), HostPage.Agents)
        entry(Strings.settingsHostToolPermissions(), HostPage.ToolPermissions)
    }
}

/** 设置 › 手机：工具配置，下面是这台手机的自定义设备名和 Linux 环境（手机里的 Debian）的入口。 */
@Composable
private fun LocalHostScreen(
    hub: ConversationHub,
    onBack: () -> Unit,
    onOpenLinux: () -> Unit,
    claude: LazyListScope.() -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    MiuixPage(
        title = hub.backendName(Backends.LOCAL),
        onBack = onBack,
        dialogs = {
            DeviceNameDialog(hub, Backends.LOCAL, show = renaming, onDismiss = { renaming = false })
        },
    ) {
        claude()
        item(key = "device") {
            PreferenceGroup(
                title = Strings.settingsHostDevice(),
                footer = Strings.settingsHostLinuxFooter(),
            ) {
                item { DeviceNameRow(hub, Backends.LOCAL) { renaming = true } }
                item {
                    val (value, bad) = linuxState(hub)
                    ArrowPreference(
                        title = Strings.settingsTitleLinux(),
                        endActions = {
                            PreferenceValue(
                                value,
                                color =
                                    if (bad) MiuixTheme.colorScheme.error else Color.Unspecified,
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
        SetupState.Checking -> Strings.commonChecking() to false
        SetupState.NoRoot -> Strings.settingsHostNoRoot() to true
        SetupState.NoEnvironment -> Strings.commonNotInstalled() to true
        SetupState.Ready ->
            hub.settings.env?.let { it.user to false } ?: (Strings.commonNotInstalled() to true)
    }
