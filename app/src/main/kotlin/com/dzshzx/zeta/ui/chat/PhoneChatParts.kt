package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.alerts
import com.dzshzx.zeta.core.environment
import com.dzshzx.zeta.core.overlay
import com.dzshzx.zeta.core.phone
import com.dzshzx.zeta.i18n.Strings
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton

// 手机上聊天页的两块：手机本机的 Debian 用不了时的说明，输入栏上方的后台屏幕条。经 AndroidChatPlatform 交给共用的聊天页。

/** 手机本机的 Debian 用不了：在检查、没有 root、还没装。 */
@Composable
internal fun PhoneUnavailable(
    hub: ConversationHub,
    padding: PaddingValues,
    onOpenInstall: () -> Unit,
    onOpenRemoteHosts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    when (hub.environment.setup) {
        SetupState.Checking -> SetupMessage(padding, Strings.chatCheckingEnv(), null)
        SetupState.NoRoot -> NoRootMessage(hub, padding, onOpenRemoteHosts)
        else -> NoEnvironmentMessage(hub, padding, onOpenInstall, onOpenRemoteHosts, onOpenSettings)
    }
}

/** 后台屏幕条：顶部胶囊正显示这个对话时，后台屏幕在胶囊里看和接管，这里不再重复一条。 */
@Composable
internal fun AgentDisplayRow(hub: ConversationHub, conversation: Conversation, modifier: Modifier) {
    var viewingScreen by remember { mutableStateOf(false) }
    if (hub.alerts.overlayFocus !== conversation.overlay) {
        AgentDisplayStrip(
            phone = conversation.phone,
            onOpen = { viewingScreen = true },
            modifier = modifier,
        )
    }
    if (viewingScreen) AgentDisplayViewer(conversation.phone) { viewingScreen = false }
}

/** 没有 root 时的说明：去授权后重试，或连远程设备。 */
@Composable
private fun NoRootMessage(
    hub: ConversationHub,
    padding: PaddingValues,
    onOpenRemoteHosts: () -> Unit,
) {
    SetupMessage(
        padding,
        Strings.chatNeedRoot(),
        Strings.chatNeedRootBody(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RemoteChoice(hub, onOpenRemoteHosts)
            TextButton(text = Strings.chatRetry(), onClick = hub.environment::checkSetup)
        }
    }
}

/** 还没有 Linux 环境时的说明：装一个、指向已有的，或连远程设备。 */
@Composable
private fun NoEnvironmentMessage(
    hub: ConversationHub,
    padding: PaddingValues,
    onOpenInstall: () -> Unit,
    onOpenRemoteHosts: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    SetupMessage(
        padding,
        Strings.chatNoLinuxEnv(),
        Strings.chatNoLinuxEnvBody(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text =
                    if (hub.environment.ownStatus?.base == true) Strings.chatContinueInstallDebian()
                    else Strings.chatInstallDebian(),
                onClick = onOpenInstall,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            RemoteChoice(hub, onOpenRemoteHosts)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = Strings.chatOpenSettings(), onClick = onOpenSettings)
                TextButton(text = Strings.chatRetry(), onClick = hub.environment::checkSetup)
            }
        }
    }
}
