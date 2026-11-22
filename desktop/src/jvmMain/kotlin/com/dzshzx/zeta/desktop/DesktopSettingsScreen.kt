package com.dzshzx.zeta.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.RemoteHosts
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.setup.RemoteHostScreen
import io.github.suqi8.coui.kmp.preference.ArrowPreference

/**
 * 桌面版的设置：加过的远程设备（点进去是它的连接、默认目录、改名和删除，见 [RemoteHostScreen]）和「添加远程设备」。 手机版设置里的手机、Debian、提醒这些桌面上没有。
 */
@Composable
fun DesktopSettingsScreen(hub: ConversationHub, onBack: () -> Unit, onAddHost: () -> Unit) {
    var host by rememberSaveable { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        CouiPage(title = "设置", onBack = onBack) {
            item(key = "hosts") {
                PreferenceGroup(
                    title = "远程设备",
                    footer = "Zeta 桌面版经 SSH 用这些设备上的 Claude Code。",
                ) {
                    hub.settings.remoteHosts.forEach { remote ->
                        item {
                            ArrowPreference(
                                title = hub.backendName(remote.id),
                                summary = RemoteHosts.displayTarget(remote.target),
                                onClick = { host = remote.id },
                            )
                        }
                    }
                    item { ArrowPreference(title = "添加远程设备", onClick = onAddHost) }
                }
            }
        }
        SlideInPage(host, onBack = { host = null }) { backend ->
            RemoteHostScreen(hub, backend, onBack = { host = null }) {}
        }
    }
}
