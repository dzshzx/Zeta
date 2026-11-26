package com.dzshzx.zeta.ui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.phone.OverlayPhase
import com.dzshzx.zeta.ui.components.accentOrange
import com.dzshzx.zeta.ui.components.accentPurple
import com.dzshzx.zeta.ui.components.success
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 胶囊、展开画面和主屏边缘光共用的阶段配色与说法。
 * 暂停是“Claude 停下等你”，接管是“你在操作”：颜色、指示和文字都分开，一眼能看出是哪种。
 * 颜色：运行是主题色，暂停是橙，接管是紫（和运行、暂停、完成都分得开），完成是绿。
 */

@Composable
internal fun phaseAccent(phase: OverlayPhase): Color = phaseAccent(phase, MiuixTheme.colorScheme)

internal fun phaseAccent(phase: OverlayPhase, colors: Colors): Color =
    when (phase) {
        OverlayPhase.RUNNING -> colors.primary
        OverlayPhase.PAUSED -> colors.accentOrange
        OverlayPhase.CONTROLLING -> colors.accentPurple
        OverlayPhase.FINISHED -> colors.success
    }

/** 胶囊上没有具体步骤时的那句。 */
internal fun phaseLabel(phase: OverlayPhase) =
    when (phase) {
        OverlayPhase.RUNNING -> Strings.overlayPhaseRunning()
        OverlayPhase.PAUSED -> Strings.overlayPhasePaused()
        OverlayPhase.CONTROLLING -> Strings.overlayPhaseControlling()
        OverlayPhase.FINISHED -> Strings.overlayPhaseFinished()
    }

/** 展开画面下方那一行的模式：后台画布 / 已暂停，等你继续 / 手动接管中。 */
internal fun stageModeLabel(phase: OverlayPhase) =
    when (phase) {
        OverlayPhase.PAUSED -> Strings.overlayStagePaused()
        OverlayPhase.CONTROLLING -> Strings.overlayStageControlling()
        OverlayPhase.RUNNING,
        OverlayPhase.FINISHED -> Strings.overlayStageCanvas()
    }
