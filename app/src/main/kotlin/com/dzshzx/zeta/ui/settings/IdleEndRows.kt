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
import com.dzshzx.zeta.ui.components.coui2.SettingsHue
import com.dzshzx.zeta.ui.components.coui2.SettingsIcon
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Timer
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「闲置超时」可选的几档（分钟），0 是不自动结束。 */
private val IdleChoices = listOf(5, 10, 30, 60, 120, 0)

internal fun idleLabel(minutes: Int): String =
    when {
        minutes == 0 -> "不自动结束"
        minutes % 60 == 0 -> "${minutes / 60} 小时"
        else -> "$minutes 分钟"
    }

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
        if (ready) {
            minutes =
                withContext(Dispatchers.IO) {
                    runCatching { hub.convLinks.idleMinutes(backend) }.getOrNull()
                }
        }
    }
    val current = minutes
    // 存着的值不在几档里时（比如在别的客户端上设的）也列出来，免得显示成别的
    val choices = IdleChoices + listOfNotNull(current?.takeIf { it !in IdleChoices })
    OverlayDropdownPreference(
        title = "闲置超时",
        startAction = {
            SettingsIcon(COUIIcons.Timer, SettingsHue.Claude, enabled = current != null)
        },
        items = choices.map(::idleLabel),
        selectedIndex = choices.indexOf(current).coerceAtLeast(0),
        enabled = current != null,
        onSelectedIndexChange = { index ->
            val pick = choices[index]
            scope.launch {
                withContext(Dispatchers.IO) {
                        runCatching { hub.convLinks.setIdleMinutes(backend, pick) }
                    }
                    .onSuccess { minutes = it }
                    .onFailure {
                        Toast.makeText(context, "没有改成：${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
        },
    )
}
