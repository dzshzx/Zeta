package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.ui.components.fill8
import com.dzshzx.zeta.ui.components.isDark
import com.dzshzx.zeta.ui.components.miuix.AnchoredMenu
import com.dzshzx.zeta.ui.components.miuix.MenuEntry
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 底栏按钮的底色和图标色，由 [barChipColors]、[barChipThemeColors] 给。 */
@Immutable data class BarChipColors(val containerColor: Color, val contentColor: Color)

/** 底栏按钮的大小：28dp 见方的圆。 */
private val BarButtonSize = 28.dp

/** 底栏按钮里图标的位置（16dp，超出的裁掉）：底栏上自己画的图形（上下文圆环、负载数字）按它算大小。 */
val BarIconSize = 16.dp

/**
 * 输入栏底栏的按钮：28dp 的圆，16dp 图标，只放图标，底栏上不写字。[description] 同时是读屏说明和长按弹出的说明，档位名、百分比都写在这句话里； 图标自己的读屏说明给
 * null。图标的颜色经 LocalContentColor 给（[BarChipColors.contentColor]）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BarButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: BarChipColors = barChipColors(null),
    icon: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val content =
        if (enabled) colors.contentColor else MiuixTheme.colorScheme.disabledOnSecondaryVariant
    // 长按时 Miuix 的 TooltipBox 在按钮上方弹出说明，过一会儿自己收起，并吞掉松手那一下，不再算一次点击；按钮自己的长按只管震动
    TooltipBox(
        text = description,
        modifier = modifier,
        enabled = enabled,
        positioning = TooltipAnchorPosition.Above,
    ) {
        Box(
            Modifier.size(BarButtonSize)
                .clip(CircleShape)
                .background(colors.containerColor)
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onClick = onClick,
                )
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(BarIconSize), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides content) { icon() }
            }
        }
    }
}

/** 点开是菜单的底栏按钮：自己管展开状态，菜单以按钮为锚。 */
@Composable
fun BarMenuButton(
    description: String,
    entries: List<MenuEntry>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: BarChipColors = barChipColors(null),
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

/** 底栏按钮的配色：[tone] 为 null 是中性的灰底灰图标；有颜色时是它的淡底加同色图标（浅色 15%，深色 25%）。主题色的用 [barChipThemeColors]。 */
@Composable
fun barChipColors(tone: Color?): BarChipColors {
    val colors = MiuixTheme.colorScheme
    return if (tone == null) {
        BarChipColors(containerColor = colors.fill8, contentColor = colors.onBackgroundVariant)
    } else {
        BarChipColors(
            containerColor = tone.copy(alpha = if (colors.isDark) 0.25f else 0.15f),
            contentColor = tone,
        )
    }
}

/** 主题色的底栏按钮：主题色的淡底加主题色图标。 */
@Composable
fun barChipThemeColors(): BarChipColors =
    BarChipColors(
        containerColor = MiuixTheme.colorScheme.tertiaryContainer,
        contentColor = MiuixTheme.colorScheme.primary,
    )
