package com.dzshzx.zeta.ui.settings

import androidx.compose.runtime.Composable
import com.dzshzx.zeta.core.AutoCompact
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.ui.components.coui2.SettingsHue
import com.dzshzx.zeta.ui.components.coui2.SettingsIcon
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Backup
import io.github.suqi8.coui.kmp.icon.extended.Tune
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference

internal fun compactPercentLabel(percent: Int?): String =
    if (percent == null) "默认（快满时）" else "用到 $percent% 时"

/**
 * 设置首页「自动压缩上下文」。只影响 Zeta 起的 Claude Code 进程（起进程时带环境变量），不改 Claude Code 自己的配置； 已经在跑的对话不受影响，下次起进程时生效。
 */
@Composable
internal fun AutoCompactSwitch(hub: ConversationHub) {
    SwitchPreference(
        title = "自动压缩上下文",
        startAction = { SettingsIcon(COUIIcons.Backup, SettingsHue.Claude) },
        checked = hub.settings.autoCompact,
        onCheckedChange = { hub.settings.autoCompact = it },
    )
}

/** 设置首页「压缩时机」：用到窗口的百分之几时压缩（百分比按窗口扣掉回答预留后算）；关掉自动压缩时不能选。 */
@Composable
internal fun CompactTimingPreference(hub: ConversationHub) {
    val percent = hub.settings.autoCompactPercent
    // 菜单第一项是“默认”（null）；存着的值不在可选的几档里时也列出来，免得显示成别的
    val choices =
        listOf<Int?>(null) +
            AutoCompact.Percents +
            listOfNotNull(percent?.takeIf { it !in AutoCompact.Percents })
    OverlayDropdownPreference(
        title = "压缩时机",
        startAction = {
            SettingsIcon(COUIIcons.Tune, SettingsHue.Claude, enabled = hub.settings.autoCompact)
        },
        items = choices.map(::compactPercentLabel),
        selectedIndex = choices.indexOf(percent),
        enabled = hub.settings.autoCompact,
        onSelectedIndexChange = { index ->
            hub.settings.autoCompactPercent = choices.getOrNull(index)
        },
    )
}
