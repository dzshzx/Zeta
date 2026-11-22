package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.ui.components.coui2.AnchoredMenu
import io.github.suqi8.coui.kmp.basic.ChipColors
import io.github.suqi8.coui.kmp.basic.ChipDefaults
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.IconChip
import io.github.suqi8.coui.kmp.basic.Tooltip
import io.github.suqi8.coui.kmp.basic.TooltipBox
import io.github.suqi8.coui.kmp.basic.TooltipMode
import io.github.suqi8.coui.kmp.basic.rememberTooltipState
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 输入栏底栏的按钮：COUI 的 IconChip（28dp 定宽，16dp 图标），只放图标，底栏上不写字。 [description]
 * 同时是读屏说明和长按弹出的提示，档位名、百分比都写在这句话里；图标自己的读屏说明给 null。
 */
@Composable
fun BarButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ChipColors = barChipColors(null),
    icon: @Composable () -> Unit,
) {
    TooltipBox(
        state = rememberTooltipState(isPersistent = false),
        tooltip = { Tooltip(text = description, mode = TooltipMode.Info) },
        modifier = modifier,
        enableUserInput = true,
    ) {
        IconChip(
            onClick = onClick,
            modifier = Modifier.semantics { contentDescription = description },
            enabled = enabled,
            colors = colors,
            icon = icon,
        )
    }
}

/** IconChip 给图标的位置（COUI 定的 16dp，超出的裁掉）：底栏上自己画的图形（上下文圆环、负载数字）按它算大小。 */
val BarIconSize = 16.dp

/** 点开是菜单的底栏按钮：自己管展开状态，菜单以按钮为锚。 */
@Composable
fun BarMenuButton(
    description: String,
    entries: List<DropdownEntry>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ChipColors = barChipColors(null),
    icon: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        BarButton(
            description,
            onClick = { expanded = true },
            enabled = enabled,
            colors = colors,
            icon = icon,
        )
        AnchoredMenu(show = expanded, entries = entries, onDismiss = { expanded = false })
    }
}

/**
 * 底栏按钮的配色：[tone] 为 null 是中性的灰；有颜色时是它的淡底加同色图标， 透明度照 COUI 半透明主题底的比例（浅色 15%，深色 25%）。主题色的用
 * [barChipThemeColors]。
 */
@Composable
fun barChipColors(tone: Color?): ChipColors {
    val colors = COUITheme.colorScheme
    return if (tone == null) {
        ChipDefaults.chipColors(contentColor = colors.labelSecondary)
    } else {
        ChipDefaults.chipColors(
            containerColor = tone.copy(alpha = if (colors.isDark) 0.25f else 0.15f),
            contentColor = tone,
        )
    }
}

/** 主题色的底栏按钮：COUI 的半透明主题底加主题色图标。 */
@Composable
fun barChipThemeColors(): ChipColors =
    ChipDefaults.chipColors(
        containerColor = COUITheme.colorScheme.containerThemeHalftone,
        contentColor = COUITheme.colorScheme.primaryText,
    )
