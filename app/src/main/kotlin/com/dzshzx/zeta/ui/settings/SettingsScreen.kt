package com.dzshzx.zeta.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.dzshzx.zeta.BuildConfig
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.ui.browser.BrowserActivity
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.CouiTopBarStyle
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.SettingsHue
import com.dzshzx.zeta.ui.components.coui2.SettingsIcon
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.setup.ClaudeConfigPages
import com.dzshzx.zeta.ui.setup.claudeConfigItems
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Image
import io.github.suqi8.coui.kmp.icon.extended.Info
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
    // 子页面挂在设置页自己身上：盖在设置页上面，返回键先关它们。处理器在子页之前注册，子页里再往下的一层先收到返回键
    var subPage by rememberSaveable { mutableStateOf<SettingsSubPage?>(null) }
    BackHandler(enabled = subPage != null) { subPage = null }
    Box(Modifier.fillMaxSize()) {
        SettingsHome(hub, onBack, onOpenLinux, onOpenSubPage = { subPage = it })
        SlideInPage(subPage == SettingsSubPage.Appearance) { AppearanceScreen(hub = hub, onBack = { subPage = null }) }
        SlideInPage(subPage == SettingsSubPage.PermissionHealth) { PermissionHealthScreen(hub = hub, onBack = { subPage = null }, onOpenLinux = onOpenLinux) }
        SlideInPage(subPage == SettingsSubPage.Phone) { PhoneScreen(hub = hub, onBack = { subPage = null }) }
        ClaudeConfigPages(hub = hub, page = subPage, onClose = { subPage = null })
        SlideInPage(subPage == SettingsSubPage.Accounts) { AccountsScreen(hub = hub, onBack = { subPage = null }) }
        SlideInPage(subPage == SettingsSubPage.Schedules) {
            // 打开某次运行的对话：设置页整个关掉，回到聊天页
            ScheduleScreen(hub = hub, onBack = { subPage = null }, onOpenConversation = { subPage = null; onShowChat() })
        }
        SlideInPage(subPage == SettingsSubPage.NotifyTriggers) {
            NotifyTriggerScreen(hub = hub, onBack = { subPage = null }, onOpenConversation = { subPage = null; onShowChat() })
        }
        SlideInPage(subPage == SettingsSubPage.Diagnostics) { DiagnosticsScreen(hub = hub, onBack = { subPage = null }) }
        SlideInPage(subPage == SettingsSubPage.Assistant) { AssistantSettingsScreen(hub = hub, onBack = { subPage = null }) }
        SlideInPage(subPage == SettingsSubPage.Backup) { SettingsBackupScreen(hub = hub, onBack = { subPage = null }) }
        SlideInPage(subPage == SettingsSubPage.Storage) {
            // 定位到侧栏里的会话：设置页整个关掉，侧栏由 MainActivity 打开
            StorageScreen(hub = hub, onBack = { subPage = null }, onRevealSession = { subPage = null; onRevealSession(it) })
        }
    }
    // 从通知点进来（比如无障碍被关掉的提醒）：直接打开那一页
    LaunchedEffect(SettingsLink.pending) { SettingsLink.pending?.let { subPage = it; SettingsLink.pending = null } }
}

@Composable
private fun SettingsHome(
    hub: ConversationHub,
    onBack: () -> Unit,
    onOpenLinux: () -> Unit,
    onOpenSubPage: (SettingsSubPage) -> Unit,
) {
    val context = LocalContext.current
    val ready = hub.environment.setup == SetupState.Ready && hub.env != null
    // 默认值可能在终端里改过（/model、/effort 存为默认）：每次打开都重读
    LaunchedEffect(ready) { if (ready) hub.refreshClaudeDefaults() }
    CouiPage(title = "设置", onBack = onBack, topBarStyle = CouiTopBarStyle.SettingsHome) {
        item(key = "claude") {
            PreferenceGroup(title = "Claude", footer = ClaudeDefaultsFooter) {
                item { AccountsEntry(hub) { onOpenSubPage(SettingsSubPage.Accounts) } }
                item { DefaultModelPreference(hub) { SettingsIcon(ZetaIcons.Chip, SettingsHue.Claude) } }
                item { DefaultPermissionModePreference(hub) { SettingsIcon(ZetaIcons.ShieldA, SettingsHue.Claude) } }
                item { DefaultEffortSummary(hub) { SettingsIcon(ZetaIcons.Lightbulb, SettingsHue.Claude) } }
            }
        }
        item(key = "context") {
            PreferenceGroup(title = "上下文与扩展") {
                item { AutoCompactSwitch(hub) }
                item { CompactTimingPreference(hub) }
                item { AutoTitleSwitch(hub) }
                claudeConfigItems(ready, onOpenSubPage)
                item { ScheduleEntry { onOpenSubPage(SettingsSubPage.Schedules) } }
                item { NotifyTriggerEntry { onOpenSubPage(SettingsSubPage.NotifyTriggers) } }
            }
        }
        item(key = "phone") {
            PreferenceGroup(title = "手机与工具") {
                item {
                    ArrowPreference(
                        title = "手机操作",
                        startAction = { SettingsIcon(ZetaIcons.Phone, SettingsHue.System) },
                        endActions = { PreferenceValue(if (hub.settings.phoneTools) "已开启" else "已关闭") },
                        onClick = { onOpenSubPage(SettingsSubPage.Phone) },
                    )
                }
                item { AssistantEntry { onOpenSubPage(SettingsSubPage.Assistant) } }
                item {
                    ArrowPreference(
                        title = "浏览器",
                        startAction = { SettingsIcon(ZetaIcons.Globe, SettingsHue.System) },
                        onClick = { BrowserActivity.open(context) },
                    )
                }
                item { LinuxEntry(hub, onOpenLinux) }
            }
        }
        item(key = "general") {
            PreferenceGroup(title = "通用") {
                item {
                    ArrowPreference(
                        title = "外观与主题",
                        startAction = { SettingsIcon(COUIIcons.Theme, SettingsHue.System) },
                        endActions = {
                            PreferenceValue(
                                when (hub.settings.darkTheme) {
                                    null -> "跟随系统"
                                    false -> "浅色"
                                    true -> "深色"
                                },
                            )
                        },
                        onClick = { onOpenSubPage(SettingsSubPage.Appearance) },
                    )
                }
                item {
                    // 只在 Zeta 显示在前台时起作用；切到别的应用或回到桌面，屏幕照常按系统设置熄灭
                    SwitchPreference(
                        title = "对话中保持屏幕常亮",
                        startAction = { SettingsIcon(ZetaIcons.Sun, SettingsHue.System) },
                        checked = hub.settings.keepScreenOn,
                        onCheckedChange = { hub.settings.keepScreenOn = it },
                    )
                }
                item {
                    // 关着的时候，回答里的网络图片先显示成一个“加载图片”的小条，点一下才去取
                    SwitchPreference(
                        title = "自动加载网络图片",
                        startAction = { SettingsIcon(COUIIcons.Image, SettingsHue.Claude) },
                        checked = hub.settings.autoLoadRemoteImages,
                        onCheckedChange = { hub.settings.autoLoadRemoteImages = it },
                    )
                }
                item {
                    // 一轮回答完了用系统的朗读引擎念出来，锁屏也接着读
                    SwitchPreference(
                        title = "回答自动朗读",
                        startAction = { SettingsIcon(COUIIcons.VolumeUp, SettingsHue.Claude) },
                        checked = hub.settings.autoReadAnswers,
                        onCheckedChange = { hub.settings.autoReadAnswers = it },
                    )
                }
                item { StorageEntry { onOpenSubPage(SettingsSubPage.Storage) } }
                item {
                    ArrowPreference(
                        title = "备份与恢复",
                        startAction = { SettingsIcon(ZetaIcons.Archive, SettingsHue.Files) },
                        onClick = { onOpenSubPage(SettingsSubPage.Backup) },
                    )
                }
            }
        }
        item(key = "permissions") {
            PreferenceGroup(title = "权限") {
                item { PermissionHealthEntry(hub) { onOpenSubPage(SettingsSubPage.PermissionHealth) } }
            }
        }
        item(key = "about") {
            PreferenceGroup(title = "关于", footer = "Markdown 渲染、侧栏和主题的代码来自 Eta，按 GPL-3.0 授权。") {
                item {
                    BasicComponent(
                        title = "版本",
                        summary = BuildConfig.VERSION_NAME,
                        startAction = { SettingsIcon(COUIIcons.Info, SettingsHue.System) },
                    )
                }
                item {
                    ArrowPreference(
                        title = "诊断与日志",
                        startAction = { SettingsIcon(ZetaIcons.Pulse, SettingsHue.Files) },
                        onClick = { onOpenSubPage(SettingsSubPage.Diagnostics) },
                    )
                }
            }
        }
    }
}

/** 「Linux 环境」：右边是 Linux 用户名，没 root、没装环境时红字。 */
@Composable
private fun LinuxEntry(hub: ConversationHub, onOpen: () -> Unit) {
    val (value, bad) = when (hub.environment.setup) {
        SetupState.Checking -> "检查中" to false
        SetupState.NoRoot -> "没有 root" to true
        SetupState.NoEnvironment -> "未安装" to true
        SetupState.Ready -> (hub.env?.user ?: "已就绪") to false
    }
    ArrowPreference(
        title = "Linux 环境",
        startAction = { SettingsIcon(ZetaIcons.Terminal, SettingsHue.Files) },
        endActions = { PreferenceValue(value, color = if (bad) COUITheme.colorScheme.error else Color.Unspecified) },
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
        title = "权限健康",
        startAction = { SettingsIcon(ZetaIcons.ShieldCheck, SettingsHue.System) },
        endActions = {
            PreferenceValue(
                text = when {
                    issues > 0 -> "$issues 项需关注"
                    checking -> "检查中"
                    else -> "正常"
                },
                color = if (issues == 0) Color.Unspecified else COUITheme.colorScheme.error,
            )
        },
        onClick = onOpen,
    )
}

/** 自动标题的开关：新对话第一轮回答后，由 Claude Code 用它的快速模型（Haiku）起个简短标题；改过名的不动。 */
@Composable
private fun AutoTitleSwitch(hub: ConversationHub) {
    SwitchPreference(
        title = "自动生成对话标题",
        startAction = { SettingsIcon(ZetaIcons.Sparkle, SettingsHue.Claude) },
        checked = hub.settings.autoTitle,
        onCheckedChange = { hub.settings.autoTitle = it },
    )
}
