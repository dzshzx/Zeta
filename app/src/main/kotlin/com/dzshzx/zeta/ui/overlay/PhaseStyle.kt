package com.dzshzx.zeta.ui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.dzshzx.zeta.phone.OverlayPhase
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 胶囊、展开画面和主屏边缘光共用的阶段配色与说法。
 * 暂停是“Claude 停下等你”，接管是“你在操作”：颜色、指示和文字都分开，一眼能看出是哪种。
 */

/** 暂停：照 Eta 的橙色。 */
internal val PausedAccent = Color(0xFFFF9F0A)

/** 接管：用户自己在操作，和运行（主题强调色）、暂停（橙）、完成（绿）都分得开的紫色。 */
internal val ControllingAccent = Color(0xFFAF52DE)

private val FinishedAccent = Color(0xFF34C759)

@Composable
internal fun phaseAccent(phase: OverlayPhase): Color = when (phase) {
    OverlayPhase.RUNNING -> MiuixTheme.colorScheme.primary
    OverlayPhase.PAUSED -> PausedAccent
    OverlayPhase.CONTROLLING -> ControllingAccent
    OverlayPhase.FINISHED -> FinishedAccent
}

/** 胶囊上没有具体步骤时的那句。 */
internal fun phaseLabel(phase: OverlayPhase) = when (phase) {
    OverlayPhase.RUNNING -> "运行中"
    OverlayPhase.PAUSED -> "已暂停"
    OverlayPhase.CONTROLLING -> "你在接管"
    OverlayPhase.FINISHED -> "已完成"
}

/** 展开画面下方那一行的模式：后台画布 / 已暂停，等你继续 / 手动接管中。 */
internal fun stageModeLabel(phase: OverlayPhase) = when (phase) {
    OverlayPhase.PAUSED -> "已暂停，等你继续"
    OverlayPhase.CONTROLLING -> "手动接管中"
    OverlayPhase.RUNNING, OverlayPhase.FINISHED -> "后台画布"
}
