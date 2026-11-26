package com.dzshzx.zeta.ui.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.notify.NotifyRules
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.DropdownPreference
import com.dzshzx.zeta.ui.components.miuix.SettingsIcon
import com.dzshzx.zeta.ui.setup.problem
import com.dzshzx.zeta.ui.setup.runConfig
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Timer

/** 「闲置超时」可选的几档（分钟），0 是不自动结束。 */
private val IdleChoices = listOf(5, 10, 30, 60, 120, 0)

internal fun idleLabel(minutes: Int): String =
    if (minutes == 0) Strings.settingsIdleNever() else NotifyRules.cooldownText(minutes)

/**
 * 设备页「闲置超时」：主机 [backend] 上没有客户端看着、又闲着（不在回答、不在等确认、没有后台任务）的 Claude Code 进程，
 * 过这么久由那台的托管脚本结束，腾出内存；会话内容留着，下次发消息时接上。值存在那台主机上（docs/core.md「主机」的
 * system.idle），手机和电脑连同一台看到的是同一个，改了马上对已经在跑的进程生效。读不到（这台用不了、主机上的 Zeta 服务比这个旧）时灰着。
 */
@Composable
internal fun IdleEndPreference(hub: ConversationHub, backend: String, ready: Boolean) {
    var minutes by remember(backend) { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(backend, ready) {
        if (ready) runConfig({ hub.convLinks.idleMinutes(backend) }) { minutes = it.getOrNull() }
    }
    val current = minutes
    // 存着的值不在几档里时（比如在别的客户端上设的）也列出来，免得显示成别的
    val choices = IdleChoices + listOfNotNull(current?.takeIf { it !in IdleChoices })
    DropdownPreference(
        title = Strings.settingsIdleTimeout(),
        startAction = {
            SettingsIcon(MiuixIcons.Timer, enabled = current != null)
        },
        items = choices.map(::idleLabel),
        selectedIndex = choices.indexOf(current).coerceAtLeast(0),
        enabled = current != null,
        onSelectedIndexChange = { index ->
            val pick = choices[index]
            scope.runConfig({ hub.convLinks.setIdleMinutes(backend, pick) }) { result ->
                result
                    .onSuccess { minutes = it }
                    .onFailure {
                        Toast.makeText(
                                context,
                                Strings.settingsDefaultsChangeFailed(it.problem()),
                                Toast.LENGTH_LONG,
                            )
                            .show()
                    }
            }
        },
    )
}
