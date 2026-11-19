package com.dzshzx.zeta.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.TextInputDialog
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.preference.SwitchPreference

/** “SSH 服务”：Debian 里的 sshd，用来从终端应用或别的设备连进这个环境。 */
@Composable
internal fun SshScreen(hub: ConversationHub, onBack: () -> Unit) {
    val env = hub.env
    val status = hub.ssh
    val maintenance = hub.maintenance
    val busy = hub.sshWorking || maintenance?.running == true
    var adding by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }
    LaunchedEffect(env?.rootfs) { hub.refreshSsh() }

    CouiPage(
        title = "SSH 服务",
        onBack = onBack,
        overlays = {
            TextInputDialog(
                show = adding,
                title = "添加公钥",
                summary = "形如 ssh-ed25519 AAAA… 的一整行",
                value = keyDraft,
                onValueChange = { keyDraft = it },
                label = "ssh-ed25519 AAAA…",
                confirmText = "添加",
                confirmEnabled = Regex("(ssh-|ecdsa-|sk-)\\S+\\s+\\S+.*").matches(keyDraft.trim()),
                onConfirm = {
                    hub.addSshKey(keyDraft)
                    adding = false
                },
                onDismissRequest = { adding = false },
            )
        },
    ) {
        item(key = "service") {
            PreferenceGroup(
                title = "服务",
                footer = "sshd 跑在 Debian 环境里，和 Zeta 开没开着无关。停止只是不再接受新的连接，已经连上的会话不受影响。",
            ) {
                item {
                    BasicComponent(
                        title = "状态",
                        endActions = {
                            PreferenceValue(
                                when {
                                    status == null -> "检查中"
                                    !status.installed -> "未安装"
                                    status.running -> "运行中"
                                    else -> "未运行"
                                },
                            )
                        },
                    )
                }
                if (status != null && !status.installed) {
                    item {
                        BasicComponent(
                            title = "安装并配置",
                            summary = "安装 openssh-server；只在本机的 8022 端口监听，只认公钥",
                            enabled = !busy,
                            onClick = hub::setupSsh,
                        )
                    }
                } else if (status != null) {
                    item { BasicComponent(title = "监听地址", endActions = { PreferenceValue(status.listen.ifBlank { "端口 " + status.port }) }) }
                    item {
                        BasicComponent(
                            title = if (status.running) "停止" else "启动",
                            enabled = !busy,
                            onClick = if (status.running) hub::stopSsh else hub::startSsh,
                        )
                    }
                    item {
                        SwitchPreference(
                            checked = status.autostart,
                            onCheckedChange = hub::setSshAutostart,
                            title = "开机自动启动",
                            enabled = !busy,
                        )
                    }
                }
            }
        }
        if (status?.installed == true) {
            item(key = "login") {
                PreferenceGroup(
                    title = "登录",
                    footer = "在这台手机的终端应用里连接：ssh -p ${status.port.ifBlank { "8022" }} ${env?.user ?: "用户名"}@127.0.0.1。" +
                        "从别的设备连接时，经 Tailscale 之类把流量转到这个端口。" +
                        if (status.passwordLogin) "注意：当前的配置允许用密码登录。" else "密码登录是关着的，只认公钥。",
                ) {
                    item { BasicComponent(title = "已授权的公钥", endActions = { PreferenceValue("${status.keys} 条") }) }
                    item {
                        BasicComponent(
                            title = "添加公钥",
                            summary = "粘贴一条公钥，加进 ~/.ssh/authorized_keys",
                            enabled = !busy,
                            onClick = {
                                keyDraft = ""
                                adding = true
                            },
                        )
                    }
                }
            }
        }
        if (maintenance != null) {
            item(key = "maintenance") { MaintenanceGroup(maintenance, onDismiss = hub::dismissMaintenance) }
        }
    }
}
