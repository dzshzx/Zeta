package com.dzshzx.zeta.ui

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
import com.dzshzx.zeta.ui.app.UiPrefs
import com.dzshzx.zeta.ui.browser.BrowserActivity
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.CouiTopBarStyle
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.settings.AccountsEntry
import com.dzshzx.zeta.ui.settings.AccountsScreen
import com.dzshzx.zeta.ui.settings.AppearanceScreen
import com.dzshzx.zeta.ui.settings.AutoCompactGroup
import com.dzshzx.zeta.ui.settings.DiagnosticsScreen
import com.dzshzx.zeta.ui.settings.NotifyTriggerEntry
import com.dzshzx.zeta.ui.settings.NotifyTriggerScreen
import com.dzshzx.zeta.ui.settings.PermissionHealth
import com.dzshzx.zeta.ui.settings.PermissionScreen
import com.dzshzx.zeta.ui.settings.PermissionStatus
import com.dzshzx.zeta.ui.settings.ScheduleEntry
import com.dzshzx.zeta.ui.settings.ScheduleScreen
import com.dzshzx.zeta.ui.settings.SettingsBackupDialogs
import com.dzshzx.zeta.ui.settings.SettingsBackupGroup
import com.dzshzx.zeta.ui.settings.SettingsLink
import com.dzshzx.zeta.ui.settings.SettingsSubPage
import com.dzshzx.zeta.ui.settings.SettingsSubPageHost
import com.dzshzx.zeta.ui.settings.StorageEntry
import com.dzshzx.zeta.ui.settings.StorageScreen
import com.dzshzx.zeta.ui.settings.rememberPermissionSnapshot
import com.dzshzx.zeta.ui.settings.rememberSettingsBackup
import com.dzshzx.zeta.ui.setup.ClaudeConfigGroup
import com.dzshzx.zeta.ui.setup.ClaudeConfigPages
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

@Composable
internal fun SettingsScreen(
    hub: ConversationHub,
    onBack: () -> Unit,
    onOpenLinux: () -> Unit,
    onOpenPhone: () -> Unit,
    onRevealSession: (String) -> Unit = {},
    /** 回到聊天页：设置页、侧栏和别的盖在聊天页上的页面一起收起（由 MainActivity 做）。 */
    onShowChat: () -> Unit = onBack,
) {
    // 外观、权限这两页挂在设置页自己身上：盖在设置页上面，返回键先关它们，不用改 MainActivity 的页面栈
    var subPage by rememberSaveable { mutableStateOf<SettingsSubPage?>(null) }
    Box(Modifier.fillMaxSize()) {
        SettingsHome(hub, onBack, onOpenLinux, onOpenPhone, onOpenSubPage = { subPage = it })
        SettingsSubPageHost(subPage == SettingsSubPage.Appearance) { AppearanceScreen(hub = hub, onBack = { subPage = null }) }
        SettingsSubPageHost(subPage == SettingsSubPage.Permissions) {
            PermissionScreen(
                hub = hub,
                onBack = { subPage = null },
                // Linux 环境页由 MainActivity 管，先关掉本页，返回键的顺序才对得上
                onOpenLinux = {
                    subPage = null
                    onOpenLinux()
                },
            )
        }
        ClaudeConfigPages(hub = hub, page = subPage, onClose = { subPage = null })
        SettingsSubPageHost(subPage == SettingsSubPage.Accounts) { AccountsScreen(hub = hub, onBack = { subPage = null }) }
        SettingsSubPageHost(subPage == SettingsSubPage.Schedules) {
            // 打开某次运行的对话：设置页整个关掉，回到聊天页
            ScheduleScreen(hub = hub, onBack = { subPage = null }, onOpenConversation = { subPage = null; onShowChat() })
        }
        SettingsSubPageHost(subPage == SettingsSubPage.NotifyTriggers) {
            NotifyTriggerScreen(hub = hub, onBack = { subPage = null }, onOpenConversation = { subPage = null; onShowChat() })
        }
        SettingsSubPageHost(subPage == SettingsSubPage.Diagnostics) { DiagnosticsScreen(hub = hub, onBack = { subPage = null }) }
        SettingsSubPageHost(subPage == SettingsSubPage.Storage) {
            // 定位到侧栏里的会话：设置页整个关掉，侧栏由 MainActivity 打开
            StorageScreen(hub = hub, onBack = { subPage = null }, onRevealSession = { subPage = null; onRevealSession(it) })
        }
    }
    BackHandler(enabled = subPage != null) { subPage = null }
    // 从通知点进来（比如无障碍被关掉的提醒）：直接打开那一页
    LaunchedEffect(SettingsLink.pending) { SettingsLink.pending?.let { subPage = it; SettingsLink.pending = null } }
}

@Composable
private fun SettingsHome(
    hub: ConversationHub,
    onBack: () -> Unit,
    onOpenLinux: () -> Unit,
    onOpenPhone: () -> Unit,
    onOpenSubPage: (SettingsSubPage) -> Unit,
) {
    val env = hub.env
    val context = LocalContext.current
    val prefs = remember(context) { UiPrefs.get(context) }
    val permissionItems = PermissionHealth.items(rememberPermissionSnapshot(), hub)
    val permissionIssues = permissionItems.count { it.needsAttention }
    val permissionChecking = permissionItems.any { it.status == PermissionStatus.Checking }
    val backup = rememberSettingsBackup(hub)
    CouiPage(
        title = "设置",
        onBack = onBack,
        topBarStyle = CouiTopBarStyle.SettingsHome,
        overlays = { SettingsBackupDialogs(hub, backup) },
    ) {
        item(key = "general") {
            PreferenceGroup(title = "通用", footer = "屏幕常亮只在 Zeta 显示在前台时起作用；切到别的应用或回到桌面，屏幕照常按系统设置熄灭。") {
                item {
                    ArrowPreference(
                        title = "外观与主题",
                        endActions = {
                            PreferenceValue(
                                when (hub.darkTheme) {
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
                    SwitchPreference(title = "对话进行中保持屏幕常亮", checked = prefs.keepScreenOn, onCheckedChange = prefs::chooseKeepScreenOn)
                }
            }
        }
        item(key = "chat") {
            PreferenceGroup(title = "聊天", footer = "关着的时候，回答里的网络图片先显示成一个“加载图片”的小条，点一下才去取。") {
                item {
                    SwitchPreference(title = "自动加载网络图片", checked = hub.autoLoadRemoteImages, onCheckedChange = hub::chooseAutoLoadRemoteImages)
                }
                item { AutoTitleSwitch(hub) }
                item {
                    SwitchPreference(
                        title = "回答自动朗读",
                        summary = "一轮回答完了用系统的朗读引擎念出来，锁屏也接着读",
                        checked = hub.autoReadAnswers,
                        onCheckedChange = hub::chooseAutoReadAnswers,
                    )
                }
            }
        }
        item(key = "compact") { AutoCompactGroup(hub) }
        item(key = "abilities") {
            PreferenceGroup(title = "能力") {
                item {
                    ArrowPreference(
                        title = "手机操作",
                        summary = if (hub.phoneTools) "Claude 可以读屏、点按、打开应用、执行 Android 命令" else "已关闭",
                        onClick = onOpenPhone,
                    )
                }
                item { ScheduleEntry { onOpenSubPage(SettingsSubPage.Schedules) } }
                item { NotifyTriggerEntry { onOpenSubPage(SettingsSubPage.NotifyTriggers) } }
                item {
                    ArrowPreference(
                        title = "浏览器",
                        summary = "Claude 打开网页用的内置浏览器：看它当前的页面、接管操作、清除数据",
                        onClick = { BrowserActivity.open(context) },
                    )
                }
            }
        }
        item(key = "account") { AccountsEntry(hub) { onOpenSubPage(SettingsSubPage.Accounts) } }
        item(key = "claude") { ClaudeConfigGroup(hub, onOpenSubPage) }
        item(key = "permissions") {
            PreferenceGroup(title = "权限", footer = "后台运行（电池优化）、通知、无障碍、root 和 Linux 环境的状态都在这里，有 root 时可以一键授予。") {
                item {
                    ArrowPreference(
                        title = "权限健康",
                        endActions = {
                            PreferenceValue(
                                text = when {
                                    permissionIssues > 0 -> "$permissionIssues 项需关注"
                                    permissionChecking -> "检查中"
                                    else -> "正常"
                                },
                                color = if (permissionIssues == 0) Color.Unspecified else COUITheme.colorScheme.error,
                            )
                        },
                        onClick = { onOpenSubPage(SettingsSubPage.Permissions) },
                    )
                }
            }
        }
        item(key = "environment") {
            PreferenceGroup(title = "环境") {
                item {
                    ArrowPreference(
                        title = "Linux 环境",
                        summary = when (hub.setup) {
                            SetupState.Checking -> "检查中"
                            SetupState.NoRoot -> "没有 root 权限"
                            SetupState.NoEnvironment -> "未找到环境，可以在这里安装"
                            SetupState.Ready -> listOfNotNull(env?.user, hub.account?.let { if (it.loggedIn) "已登录" else "未登录 Claude 账号" }).joinToString(" · ")
                        },
                        onClick = onOpenLinux,
                    )
                }
                item { StorageEntry { onOpenSubPage(SettingsSubPage.Storage) } }
            }
        }
        item(key = "backup") { SettingsBackupGroup(backup) }
        item(key = "diagnostics") {
            PreferenceGroup(title = "诊断") {
                item {
                    ArrowPreference(
                        title = "诊断与日志",
                        summary = "Zeta 的运行日志、环境信息、Claude Code 的错误输出；可以导出打过码的诊断包",
                        onClick = { onOpenSubPage(SettingsSubPage.Diagnostics) },
                    )
                }
            }
        }
        item(key = "about") {
            PreferenceGroup(title = "关于", footer = "界面基于 Eta 的 COUI 代码，按 GPL-3.0 授权。") {
                item { BasicComponent(title = "Zeta · Ζ", endActions = { PreferenceValue(BuildConfig.VERSION_NAME) }) }
            }
        }
    }
}

/** 自动标题的开关：在每次要起标题时读，关掉后还没起的也不再起。 */
@Composable
private fun AutoTitleSwitch(hub: ConversationHub) {
    var on by remember { mutableStateOf(hub.settings.autoTitle) }
    SwitchPreference(
        title = "自动生成对话标题",
        summary = "新对话第一轮回答后，由 Claude Code 用它的快速模型（Haiku）起个简短标题；改过名的不动",
        checked = on,
        onCheckedChange = {
            on = it
            hub.settings.autoTitle = it
        },
    )
}
