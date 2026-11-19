package com.dzshzx.zeta.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.AutoCompact
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference

internal fun compactPercentLabel(percent: Int?): String = if (percent == null) "默认（快满时）" else "用到 $percent% 时"

/**
 * 设置页“上下文”：Zeta 起的对话要不要自动压缩、在哪儿压缩。只影响 Zeta 起的 Claude Code 进程（起进程时带环境变量），
 * 不改 Claude Code 自己的配置；已经在跑的对话不受影响，下次起进程时生效。
 */
@Composable
internal fun AutoCompactGroup(hub: ConversationHub) {
    var on by remember { mutableStateOf(hub.settings.autoCompact) }
    var percent by remember { mutableStateOf(hub.settings.autoCompactPercent) }
    // 菜单第一项是“默认”（null）；存着的值不在可选的几档里时也列出来，免得显示成别的
    val choices = listOf<Int?>(null) + AutoCompact.Percents + listOfNotNull(percent?.takeIf { it !in AutoCompact.Percents })
    PreferenceGroup(
        title = "上下文",
        footer = "上下文快满时，Claude Code 把前面的对话总结成摘要再接着做。这两项只管 Zeta 开的对话，不改 Claude Code 自己的配置，" +
            "对话下次启动时生效。百分比按窗口扣掉回答预留后算；点输入框旁的上下文圆环能看到准确的压缩点。",
    ) {
        item {
            SwitchPreference(
                title = "自动压缩上下文",
                summary = if (on) "快满时自动压缩" else "只在你手动压缩时压缩，上下文满了就发不出去",
                checked = on,
                onCheckedChange = {
                    on = it
                    hub.settings.autoCompact = it
                },
            )
        }
        item {
            OverlayDropdownPreference(
                title = "压缩时机",
                summary = "压缩得早，之后每一轮更省；摘要会丢掉一些细节",
                items = choices.map(::compactPercentLabel),
                selectedIndex = choices.indexOf(percent),
                enabled = on,
                onSelectedIndexChange = { index ->
                    val chosen = choices.getOrNull(index)
                    percent = chosen
                    hub.settings.autoCompactPercent = chosen
                },
            )
        }
    }
}
