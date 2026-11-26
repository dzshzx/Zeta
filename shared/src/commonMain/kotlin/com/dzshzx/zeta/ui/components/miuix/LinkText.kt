package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 一句可点的链接（主题色的字）：对话框、卡片里不值得占一个按钮的次要操作，比如「改用系统的文件选择」「还有 3 处改动」。 */
@Composable
fun LinkText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MiuixTheme.textStyles.body2,
) {
    Text(
        text = text,
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        style = style,
        color = MiuixTheme.colorScheme.primary,
    )
}
