package com.dzshzx.zeta.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.itos.coui.layout.Group
import com.itos.coui.menu.CouiMenuItem
import com.dzshzx.zeta.ui.components.coui.CouiPopupPreference
import com.dzshzx.zeta.BuildConfig
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.ui.components.coui.CouiPreference
import com.dzshzx.zeta.ui.components.coui.CouiScaffoldPage
import com.dzshzx.zeta.ui.components.coui.CouiSwitchPreference

@Composable
internal fun SettingsScreen(hub: ConversationHub, onBack: () -> Unit, onOpenLinux: () -> Unit, onOpenPhone: () -> Unit) {
    val env = hub.env
    var themeMenu by remember { mutableStateOf(false) }
    val themeItems = listOf<Pair<Boolean?, String>>(null to "跟随系统", false to "浅色", true to "深色")
    CouiScaffoldPage(title = "设置", onBack = onBack) {
        Group(title = "外观") {
            CouiPopupPreference(
                title = "主题",
                value = themeItems.first { it.first == hub.darkTheme }.second,
                expanded = themeMenu,
                onExpandedChange = { themeMenu = it },
                items = themeItems.map { CouiMenuItem(value = it.first, text = it.second, selected = it.first == hub.darkTheme) },
                onItemClick = {
                    themeMenu = false
                    hub.chooseTheme(it.value)
                },
            )
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
