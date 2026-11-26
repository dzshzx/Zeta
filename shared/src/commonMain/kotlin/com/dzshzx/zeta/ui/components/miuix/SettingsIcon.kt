package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置首页一行的行首图标：放进行的 `startAction`，`startAction = { SettingsIcon(ZetaIcons.Phone) }`。颜色一律是全局强调色
 * （`MiuixTheme.colorScheme.primary`，和开关、按钮同一种）。只用在设置首页这类入口列表上；子页里的设置行不放图标。 行不能用时给 [enabled] =
 * false，图标跟着标题变灰。
 */
@Composable
fun SettingsIcon(icon: ImageVector, enabled: Boolean = true) {
    val colors = MiuixTheme.colorScheme
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = if (enabled) colors.primary else colors.disabledOnSurface,
    )
}
