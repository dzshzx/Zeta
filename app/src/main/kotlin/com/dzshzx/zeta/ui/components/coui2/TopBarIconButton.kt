package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 顶栏右边的图标按钮（新建、刷新、清空），放进页面骨架的 actions。COUI 2 的 IconButton 不能点时图标颜色不变，这里换成禁用色。 */
@Composable
internal fun TopBarIconButton(icon: ImageVector, contentDescription: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) COUITheme.colorScheme.labelPrimary else COUITheme.colorScheme.disabledNeutral,
        )
    }
}
