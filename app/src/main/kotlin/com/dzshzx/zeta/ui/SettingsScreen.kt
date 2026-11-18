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

@Composable
internal fun SettingsScreen(hub: ConversationHub, onBack: () -> Unit, onOpenLinux: () -> Unit) {
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
