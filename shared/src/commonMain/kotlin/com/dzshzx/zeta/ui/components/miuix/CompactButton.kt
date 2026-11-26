package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
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

/** 顶栏右边的文字按钮（保存、清除已结束）：主题色的字，不垫底色，按下时有 Miuix 的按压反馈；不能点时字变灰。 */
@Composable
fun TopBarTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        minWidth = 0.dp,
        minHeight = 40.dp,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        textStyle = MiuixTheme.textStyles.button,
        colors =
            ButtonDefaults.textButtonColors(
                color = Color.Transparent,
                disabledColor = Color.Transparent,
                textColor = MiuixTheme.colorScheme.primary,
            ),
    )
}
