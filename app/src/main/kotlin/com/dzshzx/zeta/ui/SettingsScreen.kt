package com.dzshzx.zeta.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.itos.coui.layout.Group
import com.dzshzx.zeta.ui.app.UiPrefs
import com.dzshzx.zeta.ui.components.StatusError
import com.dzshzx.zeta.ui.settings.AppearanceScreen
import com.dzshzx.zeta.ui.settings.PermissionHealth
import com.dzshzx.zeta.ui.settings.PermissionScreen
import com.dzshzx.zeta.ui.settings.PermissionStatus
import com.dzshzx.zeta.ui.settings.SettingsSubPage
import com.dzshzx.zeta.ui.settings.SettingsSubPageHost
import com.dzshzx.zeta.ui.settings.rememberPermissionSnapshot
import com.dzshzx.zeta.BuildConfig
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.ui.components.coui.CouiPreference
import com.dzshzx.zeta.ui.components.coui.CouiScaffoldPage
import com.dzshzx.zeta.ui.components.coui.CouiSwitchPreference

@Composable
internal fun SettingsScreen(hub: ConversationHub, onBack: () -> Unit, onOpenLinux: () -> Unit, onOpenPhone: () -> Unit) {
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
    }
    BackHandler(enabled = subPage != null) { subPage = null }
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
    CouiScaffoldPage(title = "设置", onBack = onBack) {
        Group(title = "通用", footer = "屏幕常亮只在 Zeta 显示在前台时起作用；切到别的应用或回到桌面，屏幕照常按系统设置熄灭。") {
            CouiPreference(
                title = "外观与主题",
                value = when (hub.darkTheme) {
                    null -> "跟随系统"
                    false -> "浅色"
                    true -> "深色"
                },
                onClick = { onOpenSubPage(SettingsSubPage.Appearance) },
            )
            CouiSwitchPreference(title = "对话进行中保持屏幕常亮", checked = prefs.keepScreenOn, onCheckedChange = prefs::chooseKeepScreenOn)
        }
        Group(title = "聊天", footer = "关着的时候，回答里的网络图片先显示成一个“加载图片”的小条，点一下才去取。") {
            CouiSwitchPreference(title = "自动加载网络图片", checked = hub.autoLoadRemoteImages, onCheckedChange = hub::chooseAutoLoadRemoteImages)
        }
        Group(title = "能力") {
            CouiPreference(
                title = "手机操作",
                summary = if (hub.phoneTools) "Claude 可以读屏、点按、打开应用、执行 Android 命令" else "已关闭",
                onClick = onOpenPhone,
            )
        }
        Group(title = "权限", footer = "后台运行（电池优化）、通知、无障碍、root 和 Linux 环境的状态都在这里，有 root 时可以一键授予。") {
            CouiPreference(
                title = "权限健康",
                value = when {
                    permissionIssues > 0 -> "$permissionIssues 项需关注"
                    permissionChecking -> "检查中"
                    else -> "正常"
                },
                valueColor = if (permissionIssues == 0) Color.Unspecified else StatusError,
                onClick = { onOpenSubPage(SettingsSubPage.Permissions) },
            )
        }
        Group(title = "环境") {
            CouiPreference(
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
        Group(title = "关于", footer = "界面基于 Eta 的 COUI 代码，按 GPL-3.0 授权。") {
            CouiPreference(title = "Zeta · Ζ", value = BuildConfig.VERSION_NAME)
        }
    }
}
