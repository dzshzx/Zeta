package com.dzshzx.zeta.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.ui.components.coui.CouiInputDialog
import com.dzshzx.zeta.ui.components.coui.CouiPreference
import com.dzshzx.zeta.ui.components.coui.CouiScaffoldPage
import com.dzshzx.zeta.ui.components.coui.CouiSwitchPreference
import com.itos.coui.layout.Group

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

    CouiScaffoldPage(title = "SSH 服务", onBack = onBack) {
        Group(
            title = "服务",
            footer = "sshd 跑在 Debian 环境里，和 Zeta 开没开着无关。停止只是不再接受新的连接，已经连上的会话不受影响。",
        ) {
            CouiPreference(
                title = "状态",
                value = when {
                    status == null -> "检查中"
                    !status.installed -> "未安装"
                    status.running -> "运行中"
                    else -> "未运行"
                },
            )
            if (status != null && !status.installed) {
                CouiPreference(
                    title = "安装并配置",
                    summary = "安装 openssh-server；只在本机的 8022 端口监听，只认公钥",
                    enabled = !busy,
                    onClick = hub::setupSsh,
                )
            } else if (status != null) {
                CouiPreference(title = "监听地址", value = status.listen.ifBlank { "端口 " + status.port })
                CouiPreference(
                    title = if (status.running) "停止" else "启动",
                    enabled = !busy,
                    showChevron = false,
                    onClick = if (status.running) hub::stopSsh else hub::startSsh,
                )
                CouiSwitchPreference(
                    title = "开机自动启动",
                    checked = status.autostart,
                    enabled = !busy,
                    onCheckedChange = hub::setSshAutostart,
                )
            }
        }
        if (status?.installed == true) {
            Group(
                title = "登录",
                footer = "在这台手机的终端应用里连接：ssh -p ${status.port.ifBlank { "8022" }} ${env?.user ?: "用户名"}@127.0.0.1。" +
                    "从别的设备连接时，经 Tailscale 之类把流量转到这个端口。" +
                    if (status.passwordLogin) "注意：当前的配置允许用密码登录。" else "密码登录是关着的，只认公钥。",
            ) {
                CouiPreference(title = "已授权的公钥", value = "${status.keys} 条")
                CouiPreference(
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
        if (maintenance != null) {
            Group(title = maintenance.title) {
                CouiPreference(
                    title = when {
                        maintenance.running -> "进行中…"
                        maintenance.ok -> "已完成"
                        else -> "没有成功"
                    },
                    summary = maintenance.detail.ifBlank { null },
                    onClick = if (maintenance.running) null else hub::dismissMaintenance,
                    showChevron = false,
                )
            }
        }
    }

    CouiInputDialog(
        visible = adding,
        title = "添加公钥",
        message = "形如 ssh-ed25519 AAAA… 的一整行",
        value = keyDraft,
        onValueChange = { keyDraft = it },
        placeholder = "ssh-ed25519 AAAA…",
        confirmText = "添加",
        confirmEnabled = Regex("(ssh-|ecdsa-|sk-)\\S+\\s+\\S+.*").matches(keyDraft.trim()),
        onConfirm = {
            hub.addSshKey(keyDraft)
            adding = false
        },
        onDismissRequest = { adding = false },
    )
}
