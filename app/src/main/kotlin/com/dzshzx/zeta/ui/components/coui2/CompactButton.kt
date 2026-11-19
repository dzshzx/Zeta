package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 一行里和文字并排的小按钮：COUI 2 的 TextButton 默认至少 132dp 宽，这里收窄、压低。 */
@Composable
internal fun CompactButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        minWidth = ButtonDefaults.MinWidthSmall,
        minHeight = 36.dp,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        textStyle = COUITheme.textStyles.buttonM,
        colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
    )
}
