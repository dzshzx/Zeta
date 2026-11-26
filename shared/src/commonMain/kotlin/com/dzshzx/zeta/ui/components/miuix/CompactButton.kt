package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.glass.glassShadow
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 一行里和文字并排的小按钮：比 Miuix 的 TextButton 默认的高度压低，左右留白收窄。 */
@Composable
fun CompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        minWidth = ButtonDefaults.MinWidth,
        minHeight = 36.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        textStyle = MiuixTheme.textStyles.button,
        colors =
            if (primary) ButtonDefaults.textButtonColorsPrimary()
            else ButtonDefaults.textButtonColors(),
    )
}

/**
 * 顶栏右边的文字按钮（保存、清除已结束）：主题色的字，按下时有 Miuix
 * 的按压反馈；不能点时字变灰。在玻璃顶栏里（[LocalInGlassTopBar]）垫卡片底色的胶囊、带返回钮那档玻璃阴影，一直显出按钮的样子，不像玻璃圆钮那样页面滚动后才显出底色；否则不垫底色（侧栏多选顶部那一行，和旁边的平圆钮一致）。
 */
@Composable
fun TopBarTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val pill = LocalInGlassTopBar.current
    val colors = MiuixTheme.colorScheme
    val fill = if (pill) colors.surfaceContainer else Color.Transparent
    TextButton(
        text = text,
        onClick = onClick,
        modifier = if (pill) modifier.glassShadow(PillShape) else modifier,
        enabled = enabled,
        cornerRadius = ButtonDefaults.MinHeight / 2,
        minWidth = 0.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        textStyle = MiuixTheme.textStyles.button,
        colors =
            ButtonDefaults.textButtonColors(
                color = fill,
                disabledColor = fill,
                textColor = colors.primary,
            ),
    )
}

/** 胶囊的阴影形状，和 Miuix 文字按钮默认的 40dp 高度对应。 */
private val PillShape = GlassShape(ButtonDefaults.MinHeight / 2)
