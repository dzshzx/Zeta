package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.IconButtonDefaults
import io.github.suqi8.coui.kmp.effect.LocalCOUIMaterialEffectEnabled
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 只放一个图标的按钮（顶栏的新建、刷新，查找栏、消息下方的小按钮）。COUI 2 的 IconButton 不能点时图标颜色不变，
 * 这里换成禁用色；图标按钮都用它，不在页面里各自写一份。按钮参数照 COUI 的 IconButton，[tint] 是能点时图标的颜色。
 */
@Composable
internal fun IconActionButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    material: Boolean = LocalCOUIMaterialEffectEnabled.current,
    minWidth: Dp = IconButtonDefaults.MinWidth,
    minHeight: Dp = IconButtonDefaults.MinHeight,
    cornerRadius: Dp = IconButtonDefaults.CornerRadius,
    iconModifier: Modifier = Modifier,
    tint: Color = COUITheme.colorScheme.labelPrimary,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        material = material,
        cornerRadius = cornerRadius,
        minHeight = minHeight,
        minWidth = minWidth,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = iconModifier,
            tint = if (enabled) tint else COUITheme.colorScheme.disabledNeutral,
        )
    }
}
