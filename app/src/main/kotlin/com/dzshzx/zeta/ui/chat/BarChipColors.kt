package com.dzshzx.zeta.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.suqi8.coui.kmp.basic.ChipColors
import io.github.suqi8.coui.kmp.basic.ChipDefaults
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 输入栏底栏图标按钮（COUI 的 IconChip）的配色，底栏的按钮都从这里取：[tone] 为 null 是中性的灰；
 * 有颜色时是它的淡底加同色图标，透明度照 COUI 半透明主题底的比例（浅色 15%，深色 25%）。主题色用 [barChipThemeColors]。
 */
@Composable
internal fun barChipColors(tone: Color?): ChipColors {
    val colors = COUITheme.colorScheme
    return if (tone == null) {
        ChipDefaults.chipColors(contentColor = colors.labelSecondary)
    } else {
        ChipDefaults.chipColors(containerColor = tone.copy(alpha = if (colors.isDark) 0.25f else 0.15f), contentColor = tone)
    }
}

/** 主题色的底栏按钮：COUI 的半透明主题底加主题色图标，跟着强调色变。 */
@Composable
internal fun barChipThemeColors(): ChipColors =
    ChipDefaults.chipColors(containerColor = COUITheme.colorScheme.containerThemeHalftone, contentColor = COUITheme.colorScheme.primaryText)
