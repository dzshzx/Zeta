package com.dzshzx.zeta.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SshStatus
import com.dzshzx.zeta.core.environment
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.TextInputDialog
import com.dzshzx.zeta.ui.settings.ValueRow
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.CardListGroupScope
import io.github.suqi8.coui.kmp.preference.SwitchPreference

/** “SSH 服务”：Debian 里的 sshd，用来从终端应用或别的设备连进这个环境。 */
@Composable
internal fun SshScreen(hub: ConversationHub, onBack: () -> Unit) {
    val env = hub.settings.env
    val status = hub.environment.ssh
    val maintenance = hub.environment.maintenance
    val busy = hub.environment.sshWorking || maintenance?.running == true
    var adding by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }
    LaunchedEffect(env?.rootfs) { hub.environment.refreshSsh() }

    CouiPage(
        title = Strings.setupSshTitle(),
        onBack = onBack,
        dialogs = {
            TextInputDialog(
                show = adding,
                title = Strings.setupSshAddKey(),
                summary = Strings.setupSshAddKeyHint(),
                value = keyDraft,
                onValueChange = { keyDraft = it },
                label = "ssh-ed25519 AAAA…",
                confirmText = Strings.setupAdd(),
                confirmEnabled = Regex("(ssh-|ecdsa-|sk-)\\S+\\s+\\S+.*").matches(keyDraft.trim()),
                onConfirm = {
                    hub.environment.addSshKey(keyDraft)
                    adding = false
                },
                onDismissRequest = { adding = false },
            )
        },
    ) {
        // sshd 跑在 Debian 环境里，和 Zeta 开没开着无关
        item(key = "service") { SshServiceGroup(hub, status, busy) }
        if (status?.installed == true) {
            item(key = "login") {
                SshLoginGroup(
                    status = status,
                    user = env?.user,
                    busy = busy,
                    onAddKey = {
                        keyDraft = ""
                        adding = true
                    },
                )
            }
        }
        if (maintenance != null) {
            item(key = "maintenance") {
                MaintenanceGroup(maintenance, onDismiss = hub.environment::dismissMaintenance)
            }
        }
    }
}

/** 「服务」一组：状态；没装时是安装，装了是监听地址、启停和开机自启。 */
@Composable
private fun SshServiceGroup(hub: ConversationHub, status: SshStatus?, busy: Boolean) {
    PreferenceGroup(title = Strings.setupSshService(), footer = Strings.setupSshServiceFooter()) {
        item {
            ValueRow(
                Strings.setupStatus(),
                when {
                    status == null -> Strings.setupStateChecking()
                    !status.installed -> Strings.setupNotInstalled()
                    status.running -> Strings.setupSshRunning()
                    else -> Strings.setupSshNotRunning()
                },
            )
        }
        if (status != null && !status.installed) {
            item {
                BasicComponent(
                    title = Strings.setupSshInstall(),
                    // 安装 openssh-server
                    summary = Strings.setupSshInstallSummary(),
                    enabled = !busy,
                    onClick = hub.environment::setupSsh,
                )
            }
        } else if (status != null) {
            installedRows(hub, status, busy)
        }
    }
}

/** 装好以后的几行：监听地址、启停、开机自启。 */
private fun CardListGroupScope.installedRows(
    hub: ConversationHub,
    status: SshStatus,
    busy: Boolean,
) {
    item {
        ValueRow(
            Strings.setupSshListen(),
            status.listen.ifBlank { Strings.setupSshPort(status.port) },
        )
    }
    item {
        BasicComponent(
            title = if (status.running) Strings.setupSshStop() else Strings.setupSshStart(),
            enabled = !busy,
            onClick = if (status.running) hub.environment::stopSsh else hub.environment::startSsh,
        )
    }
    item {
        SwitchPreference(
            checked = status.autostart,
            onCheckedChange = hub.environment::setSshAutostart,
            title = Strings.setupSshAutostart(),
            enabled = !busy,
        )
    }
}

/** 「登录」一组：怎么连、已授权的公钥条数、添加公钥。 */
@Composable
private fun SshLoginGroup(status: SshStatus, user: String?, busy: Boolean, onAddKey: () -> Unit) {
    PreferenceGroup(
        title = Strings.setupSshLogin(),
        footer = sshLoginFooter(status, user),
    ) {
        item {
            ValueRow(Strings.setupSshAuthorizedKeys(), Strings.setupSshKeyCount(status.keys))
        }
        item {
            BasicComponent(
                title = Strings.setupSshAddKey(),
                // 加进 ~/.ssh/authorized_keys
                summary = Strings.setupSshPasteKey(),
                enabled = !busy,
                onClick = onAddKey,
            )
        }
    }
}

/** 「登录」一组下面的说明：本机怎么连、认不认密码。 */
private fun sshLoginFooter(status: SshStatus, user: String?): String {
    val port = status.port.ifBlank { "8022" }
    val name = user ?: Strings.setupSshUserPlaceholder()
    return if (status.passwordLogin) Strings.setupSshLoginFooterPassword(port, name)
    else Strings.setupSshLoginFooterKeys(port, name)
}
