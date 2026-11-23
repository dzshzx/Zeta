package com.dzshzx.zeta.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.RemoteHosts
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.environment
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.browser.BrowserActivity
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.CouiTopBarStyle
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.SettingsHue
import com.dzshzx.zeta.ui.components.coui2.SettingsIcon
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.pane.hostIcon
import com.dzshzx.zeta.ui.setup.RemoteHostsScreen
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.Image
import io.github.suqi8.coui.kmp.icon.extended.Theme
import io.github.suqi8.coui.kmp.icon.extended.VolumeUp
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

@Composable
internal fun SettingsScreen(
    hub: ConversationHub,
    onBack: () -> Unit,
    onOpenLinux: () -> Unit,
    onRevealSession: (String) -> Unit = {},
    /** 回到聊天页：设置页、侧栏和别的盖在聊天页上的页面一起收起（由 MainActivity 做）。 */
    onShowChat: () -> Unit = onBack,
) {
    // 子页面挂在设置页自己身上：盖在设置页上面，返回键先关它们（子页里再往下的一层先收到返回键）。
    // 只有一层：一台主机的页面（[host]）或别的子页（[subPage]），同时只开一个。从通知深链在子页之间直接切换时，
    // 内容原地换掉，不放滑入滑出动画
    var subPage by rememberSaveable { mutableStateOf<SettingsSubPage?>(null) }
    val close = { subPage = null }
    // 正打开着的主机页（[Backends] 的编号）
    var host by rememberSaveable { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        SettingsHome(
            hub,
            onOpenHost = { host = it },
            onBack = onBack,
            onOpenSubPage = { subPage = it },
        )
        SlideInPage(host, onBack = { host = null }) { id ->
            HostSettingsScreen(
                hub = hub,
                backend = id,
                onBack = { host = null },
                onOpenLinux = onOpenLinux,
                // 打开某次运行的对话：设置页整个关掉，回到聊天页
                onShowChat = {
                    host = null
                    onShowChat()
                },
            )
        }
        SlideInPage(subPage, onBack = close) { page ->
            when (page) {
                SettingsSubPage.Appearance -> AppearanceScreen(hub = hub, onBack = close)
                SettingsSubPage.PermissionHealth ->
                    PermissionHealthScreen(hub = hub, onBack = close, onOpenLinux = onOpenLinux)
                SettingsSubPage.Phone -> PhoneScreen(hub = hub, onBack = close)
                SettingsSubPage.RemoteHosts -> RemoteHostsScreen(hub = hub, onBack = close)
                SettingsSubPage.Diagnostics -> DiagnosticsScreen(hub = hub, onBack = close)
                SettingsSubPage.License -> LicenseScreen(onBack = close)
                SettingsSubPage.OpenSource -> OpenSourceScreen(onBack = close)
                SettingsSubPage.Credits -> CreditsScreen(onBack = close)
                SettingsSubPage.Update -> UpdateScreen(onBack = close)
                SettingsSubPage.Assistant -> AssistantSettingsScreen(hub = hub, onBack = close)
                SettingsSubPage.Backup -> SettingsBackupScreen(hub = hub, onBack = close)
                // 定位到侧栏里的会话：设置页整个关掉，侧栏由 MainActivity 打开
                SettingsSubPage.Storage ->
                    StorageScreen(
                        hub = hub,
                        onBack = close,
                        onRevealSession = {
                            subPage = null
                            onRevealSession(it)
                        },
                    )
            }
        }
    }
    // 从通知点进来（比如无障碍被关掉的提醒）：直接打开那一页
    LaunchedEffect(SettingsLink.pending) {
        SettingsLink.pending?.let {
            host = null
            subPage = it
            SettingsLink.pending = null
        }
    }
}

/**
 * 设置首页：最上面是各台主机（手机和远程主机），点进去是那台上新对话的默认值、Claude Code 的配置和连接（[HostSettingsScreen]）； 下面几组是 Zeta
 * 自己的设置，哪台主机都一样。
 */
@Composable
private fun SettingsHome(
    hub: ConversationHub,
    onOpenHost: (String) -> Unit,
    onBack: () -> Unit,
    onOpenSubPage: (SettingsSubPage) -> Unit,
) {
    val context = LocalContext.current
    CouiPage(
        title = Strings.settingsHomeTitle(),
        onBack = onBack,
        topBarStyle = CouiTopBarStyle.SettingsHome,
    ) {
        item(key = "hosts") {
            PreferenceGroup(title = Strings.settingsHomeDevices()) {
                hub.backends.forEach { backend ->
                    item { HostEntry(hub, backend) { onOpenHost(backend) } }
                }
                item {
                    ArrowPreference(
                        title = Strings.settingsHomeAddRemote(),
                        startAction = { SettingsIcon(COUIIcons.Add, SettingsHue.Files) },
                        onClick = { onOpenSubPage(SettingsSubPage.RemoteHosts) },
                    )
                }
            }
        }
        item(key = "phone") {
            PreferenceGroup(title = Strings.settingsHomePhoneAndTools()) {
                item {
                    ArrowPreference(
                        title = Strings.settingsTitlePhone(),
                        startAction = { SettingsIcon(ZetaIcons.Phone, SettingsHue.System) },
                        endActions = {
                            PreferenceValue(
                                if (hub.settings.phoneTools) Strings.commonOn()
                                else Strings.settingsHomeOff(),
                            )
                        },
                        onClick = { onOpenSubPage(SettingsSubPage.Phone) },
                    )
                }
                item { AssistantEntry { onOpenSubPage(SettingsSubPage.Assistant) } }
                item {
                    ArrowPreference(
                        title = Strings.settingsHomeBrowser(),
                        startAction = { SettingsIcon(ZetaIcons.Globe, SettingsHue.System) },
                        onClick = { BrowserActivity.open(context) },
                    )
                }
                item {
                    PermissionHealthEntry(hub) { onOpenSubPage(SettingsSubPage.PermissionHealth) }
                }
            }
        }
        item(key = "general") {
            PreferenceGroup(title = Strings.settingsHomeGeneral()) {
                item { LanguageRow() }
                item {
                    ArrowPreference(
                        title = Strings.settingsTitleAppearance(),
                        startAction = { SettingsIcon(COUIIcons.Theme, SettingsHue.System) },
                        endActions = {
                            PreferenceValue(
                                when (hub.settings.darkTheme) {
                                    null -> Strings.commonFollowSystem()
                                    false -> Strings.commonThemeLight()
                                    true -> Strings.commonThemeDark()
                                },
                            )
                        },
                        onClick = { onOpenSubPage(SettingsSubPage.Appearance) },
                    )
                }
                item {
                    // 只在 Zeta 显示在前台时起作用；切到别的应用或回到桌面，屏幕照常按系统设置熄灭
                    SwitchPreference(
                        title = Strings.settingsHomeKeepScreenOn(),
                        startAction = { SettingsIcon(ZetaIcons.Sun, SettingsHue.System) },
                        checked = hub.settings.keepScreenOn,
                        onCheckedChange = { hub.settings.keepScreenOn = it },
                    )
                }
                item {
                    // 关着的时候，回答里的网络图片先显示成一个“加载图片”的小条，点一下才去取
                    SwitchPreference(
                        title = Strings.settingsHomeAutoLoadImages(),
                        startAction = { SettingsIcon(COUIIcons.Image, SettingsHue.Claude) },
                        checked = hub.settings.autoLoadRemoteImages,
                        onCheckedChange = { hub.settings.autoLoadRemoteImages = it },
                    )
                }
                item {
                    // 一轮回答完了用系统的朗读引擎念出来，锁屏也接着读
                    SwitchPreference(
                        title = Strings.settingsHomeAutoRead(),
                        startAction = { SettingsIcon(COUIIcons.VolumeUp, SettingsHue.Claude) },
                        checked = hub.settings.autoReadAnswers,
                        onCheckedChange = { hub.settings.autoReadAnswers = it },
                    )
                }
                item { StorageEntry { onOpenSubPage(SettingsSubPage.Storage) } }
                item {
                    ArrowPreference(
                        title = Strings.settingsBackupTitle(),
                        startAction = { SettingsIcon(ZetaIcons.Archive, SettingsHue.Files) },
                        onClick = { onOpenSubPage(SettingsSubPage.Backup) },
                    )
                }
            }
        }
        item(key = "about") { AboutGroup(onOpenSubPage) }
    }
}

/**
 * 首页的一台主机：名字，下面一行是它在哪（手机里的 Debian，或远程主机的地址和连法），右边是它新对话的默认模型和思考强度；
 * 还没读过的、能用了就读一次（每次都重读要连一遍所有远程主机，点进主机页时才重读那台）。手机的 Debian 没就绪时右边写状态 （出问题红字），经 Debian 连的远程主机写「Debian
 * 未就绪」，「连接」关着的远程主机写「已断开」。
 */
@Composable
private fun HostEntry(hub: ConversationHub, backend: String, onOpen: () -> Unit) {
    val host = hub.remoteHost(backend)
    val usable = hub.usable(backend)
    val defaults = hub.claudeDefaults(backend)
    LaunchedEffect(backend, usable) {
        if (usable && hub.claudeDefaults(backend) == null) hub.refreshClaudeDefaults(backend)
    }
    val problem =
        when {
            usable -> null
            host == null -> linuxState(hub)
            !host.connected -> Strings.settingsHomeDisconnected() to false
            hub.environment.setup == SetupState.Checking -> Strings.commonChecking() to false
            else -> Strings.settingsHomeDebianNotReady() to true
        }
    ArrowPreference(
        title = hub.backendName(backend),
        summary =
            host?.let { RemoteHosts.displayTarget(it.target) + " · " + it.via.label }
                ?: RemoteHosts.LOCAL_SUMMARY,
        startAction = { SettingsIcon(hostIcon(backend), SettingsHue.Files) },
        endActions = {
            when {
                problem != null ->
                    PreferenceValue(
                        problem.first,
                        color =
                            if (problem.second) COUITheme.colorScheme.error else Color.Unspecified,
                    )
                defaults != null ->
                    PreferenceValue(hostDefaultsSummary(defaults, hub.modelOptions(backend)))
            }
        },
        onClick = onOpen,
    )
}

/** 「权限健康」：有几项要处理，有 root 时进去可以一键授予。 */
@Composable
private fun PermissionHealthEntry(hub: ConversationHub, onOpen: () -> Unit) {
    val items = PermissionHealth.items(rememberPermissionSnapshot(), hub)
    val issues = items.count { it.needsAttention }
    val checking = items.any { it.status == PermissionStatus.Checking }
    ArrowPreference(
        title = Strings.settingsPermTitle(),
        startAction = { SettingsIcon(ZetaIcons.ShieldCheck, SettingsHue.System) },
        endActions = {
            PreferenceValue(
                text =
                    when {
                        issues > 0 -> Strings.settingsHomeIssues(issues)
                        checking -> Strings.commonChecking()
                        else -> Strings.settingsHomeHealthy()
                    },
                color = if (issues == 0) Color.Unspecified else COUITheme.colorScheme.error,
            )
        },
        onClick = onOpen,
    )
}
