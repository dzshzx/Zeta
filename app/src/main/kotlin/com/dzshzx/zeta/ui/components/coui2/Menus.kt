package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.suqi8.coui.kmp.basic.DropdownDefaults
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.popup.OverlayDropdownPopup

/**
 * 菜单里带图标的一项。COUI 2 的菜单行给文字上色却不给图标上色，这里让图标取和文字同一份颜色
 * （[DropdownDefaults.dropdownColors]）：[alert] 的（删除）是红色，不可用的变灰。带图标的菜单项都从这里建。
 */
internal fun menuItem(text: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true, alert: Boolean = false): DropdownItem =
    DropdownItem(
        text = text,
        enabled = enabled,
        onClick = onClick,
        alert = alert,
        icon = { modifier ->
            val colors = DropdownDefaults.dropdownColors()
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = modifier,
                tint = when {
                    !enabled -> colors.disabledContentColor
                    alert -> colors.alertContentColor
                    else -> colors.contentColor
                },
            )
        },
    )

/**
 * 挂在旁边元素（胶囊、按钮、标题）上的 COUI 下拉菜单，点一项就收起。和那个元素放进同一个 Box：
 * `Box { 元素; AnchoredMenu(show, entries, onDismiss) }`。
 */
@Composable
internal fun AnchoredMenu(show: Boolean, entries: List<DropdownEntry>, onDismiss: () -> Unit) {
    OverlayDropdownPopup(
        entries = entries,
        show = show,
        onDismiss = onDismiss,
        onDismissFinished = {},
        maxHeight = null,
        dropdownColors = DropdownDefaults.dropdownColors(),
        renderInRootScaffold = true,
        collapseOnSelection = true,
    )
}
