package com.dzshzx.zeta.ui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.dzshzx.zeta.phone.OverlayPhase
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.Colors

/*
 * 胶囊、展开画面和主屏边缘光共用的阶段配色与说法。
 * 暂停是“Claude 停下等你”，接管是“你在操作”：颜色、指示和文字都分开，一眼能看出是哪种。
 * 颜色取 COUI 的色板：运行是主题色，暂停是橙，接管是紫（和运行、暂停、完成都分得开），完成是绿。
 */

@Composable
internal fun phaseAccent(phase: OverlayPhase): Color = phaseAccent(phase, COUITheme.colorScheme)

internal fun phaseAccent(phase: OverlayPhase, colors: Colors): Color =
    when (phase) {
        OverlayPhase.RUNNING -> colors.primary
        OverlayPhase.PAUSED -> colors.orange
        OverlayPhase.CONTROLLING -> colors.purple
        OverlayPhase.FINISHED -> colors.green
    }

/** 胶囊上没有具体步骤时的那句。 */
internal fun phaseLabel(phase: OverlayPhase) =
    when (phase) {
        OverlayPhase.RUNNING -> "运行中"
        OverlayPhase.PAUSED -> "已暂停"
        OverlayPhase.CONTROLLING -> "你在接管"
        OverlayPhase.FINISHED -> "已完成"
    }

/** 展开画面下方那一行的模式：后台画布 / 已暂停，等你继续 / 手动接管中。 */
internal fun stageModeLabel(phase: OverlayPhase) =
    when (phase) {
        OverlayPhase.PAUSED -> "已暂停，等你继续"
        OverlayPhase.CONTROLLING -> "手动接管中"
        OverlayPhase.RUNNING,
        OverlayPhase.FINISHED -> "后台画布"
    }
