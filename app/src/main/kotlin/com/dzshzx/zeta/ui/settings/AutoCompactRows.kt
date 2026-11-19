package com.dzshzx.zeta.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.AutoCompact
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.ui.components.coui2.SettingsHue
import com.dzshzx.zeta.ui.components.coui2.SettingsIcon
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Backup
import io.github.suqi8.coui.kmp.icon.extended.Tune
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference

internal fun compactPercentLabel(percent: Int?): String = if (percent == null) "默认（快满时）" else "用到 $percent% 时"

/**
 * Zeta 起的对话要不要自动压缩、在哪儿压缩。只影响 Zeta 起的 Claude Code 进程（起进程时带环境变量），
 * 不改 Claude Code 自己的配置；已经在跑的对话不受影响，下次起进程时生效。两行共用这一份状态：关掉自动压缩时压缩时机不能选。
 */
@Stable
internal class AutoCompactState(private val hub: ConversationHub) {
    var on by mutableStateOf(hub.settings.autoCompact)
        private set
    var percent by mutableStateOf(hub.settings.autoCompactPercent)
        private set

    fun chooseOn(value: Boolean) {
        on = value
        hub.settings.autoCompact = value
    }

    fun choosePercent(value: Int?) {
        percent = value
        hub.settings.autoCompactPercent = value
    }
}

@Composable
internal fun rememberAutoCompact(hub: ConversationHub): AutoCompactState = remember(hub) { AutoCompactState(hub) }

/** 设置首页「自动压缩上下文」。 */
@Composable
internal fun AutoCompactSwitch(state: AutoCompactState) {
    SwitchPreference(
        title = "自动压缩上下文",
        startAction = { SettingsIcon(COUIIcons.Backup, SettingsHue.Claude) },
        checked = state.on,
        onCheckedChange = state::chooseOn,
    )
}

/** 设置首页「压缩时机」：用到窗口的百分之几时压缩（百分比按窗口扣掉回答预留后算）。 */
@Composable
internal fun CompactTimingPreference(state: AutoCompactState) {
    // 菜单第一项是“默认”（null）；存着的值不在可选的几档里时也列出来，免得显示成别的
    val choices = listOf<Int?>(null) + AutoCompact.Percents + listOfNotNull(state.percent?.takeIf { it !in AutoCompact.Percents })
    OverlayDropdownPreference(
        title = "压缩时机",
        startAction = { SettingsIcon(COUIIcons.Tune, SettingsHue.Claude, enabled = state.on) },
        items = choices.map(::compactPercentLabel),
        selectedIndex = choices.indexOf(state.percent),
        enabled = state.on,
        onSelectedIndexChange = { index -> state.choosePercent(choices.getOrNull(index)) },
    )
}
