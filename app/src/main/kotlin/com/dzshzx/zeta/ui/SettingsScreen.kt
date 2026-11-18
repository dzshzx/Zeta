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
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.ui.components.coui.CouiInputDialog
import com.dzshzx.zeta.ui.components.coui.CouiPreference
import com.dzshzx.zeta.ui.components.coui.CouiScaffoldPage

private enum class EditTarget { Rootfs, Cwd }

@Composable
internal fun SettingsScreen(hub: ConversationHub, onBack: () -> Unit) {
    val env = hub.env
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var lastEditing by remember { mutableStateOf(EditTarget.Rootfs) }
    var draft by remember { mutableStateOf("") }
    fun edit(target: EditTarget, initial: String) {
        draft = initial
        lastEditing = target
        editing = target
    }
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
        Group(
            title = "Linux 环境",
            footer = "Zeta 经 root 进入这个 Debian 环境，以其中的普通用户身份运行 Claude Code，不经过任何网络中转。",
        ) {
            CouiPreference(
                title = "状态",
                value = when (hub.setup) {
                    SetupState.Checking -> "检查中"
                    SetupState.NoRoot -> "没有 root 权限"
                    SetupState.NoEnvironment -> "未找到环境"
                    SetupState.Ready -> "可用"
                },
            )
            CouiPreference(
                title = "rootfs 路径",
                summary = env?.rootfs ?: hub.rootfsHint,
                onClick = { edit(EditTarget.Rootfs, env?.rootfs ?: hub.rootfsHint) },
            )
            if (env != null) {
                CouiPreference(title = "运行用户", value = "${env.user} (${env.uid})")
                CouiPreference(
                    title = "默认工作目录",
                    summary = env.defaultCwd,
                    onClick = { edit(EditTarget.Cwd, env.defaultCwd) },
                )
            }
            CouiPreference(title = "重新检测", summary = "重新检查 root 权限并识别环境里的用户", onClick = { hub.redetect(env?.rootfs ?: hub.rootfsHint) })
        }
        Group(title = "关于", footer = "界面基于 Eta 的 COUI 代码，按 GPL-3.0 授权。") {
            CouiPreference(title = "Zeta · Ζ", value = BuildConfig.VERSION_NAME)
        }
    }
    CouiInputDialog(
        visible = editing != null,
        title = if (lastEditing == EditTarget.Rootfs) "rootfs 路径" else "默认工作目录",
        message = if (lastEditing == EditTarget.Rootfs) "Android 侧看到的 Debian 根目录" else "新对话默认在这个目录下开始",
        value = draft,
        onValueChange = { draft = it },
        placeholder = if (lastEditing == EditTarget.Rootfs) LinuxEnv.DEFAULT_ROOTFS else "/workspace",
        confirmText = "确定",
        confirmEnabled = draft.trim().startsWith("/"),
        onConfirm = {
            val value = draft.trim()
            when (editing) {
                EditTarget.Rootfs -> hub.redetect(value)
                EditTarget.Cwd -> hub.setDefaultCwd(value)
                null -> Unit
            }
            editing = null
        },
        onDismissRequest = { editing = null },
    )
}
