package com.dzshzx.zeta.ui.settings

import androidx.compose.runtime.Composable
import com.dzshzx.zeta.core.AutoCompact
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.SettingsIcon
import com.dzshzx.zeta.ui.components.miuix.ZetaIcons
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

internal fun compactPercentLabel(percent: Int?): String =
    if (percent == null) Strings.settingsCompactDefault()
    else Strings.settingsCompactAtPercent(percent)

/**
 * 设备页「自动压缩上下文」：主机 [backend] 上的。只影响 Zeta 在那台上起的 Claude Code 进程（起进程时带环境变量），不改 Claude Code 自己的配置；
 * 已经在跑的对话不受影响，下次起进程时生效。
 */
@Composable
internal fun AutoCompactSwitch(hub: ConversationHub, backend: String) {
    val claude = hub.settings.claude(backend)
    SwitchPreference(
        title = Strings.settingsCompactAuto(),
        startAction = { SettingsIcon(MiuixIcons.Backup) },
        checked = claude.autoCompact,
        onCheckedChange = { claude.autoCompact = it },
    )
}

/** 自动标题的开关：主机 [backend] 上的新对话第一轮回答后，由 Claude Code 用它的快速模型（Haiku）起个简短标题；改过名的不动。 */
@Composable
internal fun AutoTitleSwitch(hub: ConversationHub, backend: String) {
    val claude = hub.settings.claude(backend)
    SwitchPreference(
        title = Strings.settingsCompactAutoTitle(),
        startAction = { SettingsIcon(ZetaIcons.Sparkle) },
        checked = claude.autoTitle,
        onCheckedChange = { claude.autoTitle = it },
    )
}

/** 设备页「压缩时机」（主机 [backend] 上的）：用到窗口的百分之几时压缩（百分比按窗口扣掉回答预留后算）；关掉自动压缩时不能选。 */
@Composable
internal fun CompactTimingPreference(hub: ConversationHub, backend: String) {
    val claude = hub.settings.claude(backend)
    val percent = claude.autoCompactPercent
    // 菜单第一项是“默认”（null）；存着的值不在可选的几档里时也列出来，免得显示成别的
    val choices =
        listOf<Int?>(null) +
            AutoCompact.Percents +
            listOfNotNull(percent?.takeIf { it !in AutoCompact.Percents })
    OverlayDropdownPreference(
        title = Strings.settingsCompactTiming(),
        startAction = {
            SettingsIcon(MiuixIcons.Tune, enabled = claude.autoCompact)
        },
        items = choices.map(::compactPercentLabel),
        selectedIndex = choices.indexOf(percent),
        enabled = claude.autoCompact,
        onSelectedIndexChange = { index ->
            claude.autoCompactPercent = choices.getOrNull(index)
        },
    )
}
