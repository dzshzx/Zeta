package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.DropdownDefaults
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButtonDefaults
import io.github.suqi8.coui.kmp.basic.PreciseClickState
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.menu.OverlayIconDropdownMenu
import io.github.suqi8.coui.kmp.overlay.OverlayCascadingListPopup
import io.github.suqi8.coui.kmp.popup.OverlayDropdownPopup
import io.github.suqi8.coui.kmp.theme.COUITheme

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
 * 挂在旁边元素（胶囊、按钮、标题、设置行）上的 COUI 下拉菜单，点一项就收起。和那个元素放进同一个 Box：
 * `Box { 元素; AnchoredMenu(show, entries, onDismiss) }`；给了 [preciseClickState] 时从手指按下的地方弹出。
 * 有项带二级菜单（DropdownItem 的 children）时用 COUI 的级联弹层，点那一项在旁边弹出下一层（普通弹层不认 children）；
 * 级联弹层不支持从手指按下的地方弹出，那时从锚点弹出。
 */
@Composable
internal fun AnchoredMenu(
    show: Boolean,
    entries: List<DropdownEntry>,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit = {},
    preciseClickState: PreciseClickState? = null,
) {
    if (entries.any { entry -> entry.items.any { !it.children.isNullOrEmpty() } }) {
        OverlayCascadingListPopup(
            show = show,
            entries = entries,
            onDismissRequest = onDismiss,
            onDismissFinished = onDismissFinished,
            dropdownColors = DropdownDefaults.dropdownColors(),
            collapseOnSelection = true,
        )
    } else {
        OverlayDropdownPopup(
            entries = entries,
            show = show,
            onDismiss = onDismiss,
            onDismissFinished = onDismissFinished,
            maxHeight = null,
            dropdownColors = DropdownDefaults.dropdownColors(),
            renderInRootScaffold = true,
            collapseOnSelection = true,
            preciseClickState = preciseClickState,
        )
    }
}

/** 顶栏右边的“更多”：COUI 的图标下拉菜单，按钮和菜单是一体的，[entries] 每组之间有分隔线，点一项就收起。 */
@Composable
internal fun TopBarMoreMenu(entries: List<DropdownEntry>, contentDescription: String = "更多") {
    OverlayIconDropdownMenu(entries = entries, collapseOnSelection = true) {
        Icon(imageVector = COUIIcons.More, contentDescription = contentDescription, tint = COUITheme.colorScheme.labelPrimary)
    }
}

/**
 * 列表行里的“更多”：平的图标按钮，菜单展开时按钮保持按下的样子，点一项就收起。
 *
 * 不用 OverlayIconDropdownMenu：它的按钮是顶栏那种带阴影的材质圆钮，放在列表行里是个实心圆，在对话框的列表里阴影还会被裁掉一截。
 * 这里用平按钮加 [AnchoredMenu]，两者在同一个 Box 里，菜单以按钮为锚。
 */
@Composable
internal fun RowMoreMenu(
    contentDescription: String,
    entries: List<DropdownEntry>,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        FlatIconButton(
            icon = COUIIcons.More,
            contentDescription = contentDescription,
            size = IconButtonDefaults.MinWidth,
            iconSize = iconSize,
            holdDownState = expanded,
            tint = COUITheme.colorScheme.labelSecondary,
            onClick = { expanded = true },
        )
        AnchoredMenu(show = expanded, entries = entries, onDismiss = { expanded = false })
    }
}
