package com.dzshzx.zeta.ui.components.miuix

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
import com.dzshzx.zeta.i18n.Strings
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButtonDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.overlay.OverlayCascadingListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 菜单里带图标的一项，带图标的菜单项都从这里建：图标取和文字同一份颜色（[DropdownDefaults.dropdownColors]），不可用的变灰。[alert]
 * 的（删除）图标是红色；Miuix 的菜单项不能单独给文字上色，文字仍是普通颜色。
 */
fun menuItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    alert: Boolean = false,
): DropdownItem =
    DropdownItem(
        text = text,
        enabled = enabled,
        onClick = onClick,
        icon = { modifier ->
            val colors = MiuixTheme.colorScheme
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = modifier,
                tint =
                    when {
                        !enabled -> colors.disabledOnSecondaryVariant
                        alert -> colors.error
                        else -> DropdownDefaults.dropdownColors().contentColor
                    },
            )
        },
    )

/**
 * 挂在旁边元素（胶囊、按钮、标题、设置行）上的下拉菜单，点一项就收起。和那个元素放进同一个 Box：`Box { 元素; AnchoredMenu(show, entries,
 * onDismiss) }`，菜单从那个 Box 的边上弹出。项可以带二级菜单（DropdownItem 的 children），点那一项在旁边弹出下一层。
 */
@Composable
fun AnchoredMenu(
    show: Boolean,
    entries: List<DropdownEntry>,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit = {},
) {
    OverlayCascadingListPopup(
        show = show,
        entries = entries,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
        collapseOnSelection = true,
    )
}

/** 顶栏右边的“更多”：在玻璃顶栏里是玻璃圆钮，[entries] 每组之间有分隔线，点一项就收起。 */
@Composable
fun TopBarMoreMenu(
    entries: List<DropdownEntry>,
    contentDescription: String = Strings.componentsMore(),
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconActionButton(
            icon = MiuixIcons.More,
            contentDescription = contentDescription,
            holdDownState = expanded,
            onClick = { expanded = true },
        )
        AnchoredMenu(show = expanded, entries = entries, onDismiss = { expanded = false })
    }
}

/** 列表行里的“更多”：平的图标按钮，菜单展开时按钮保持按下的样子，点一项就收起；按钮和菜单在同一个 Box 里，菜单以按钮为锚。 */
@Composable
fun RowMoreMenu(
    contentDescription: String,
    entries: List<DropdownEntry>,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        FlatIconButton(
            icon = MiuixIcons.More,
            contentDescription = contentDescription,
            size = IconButtonDefaults.MinWidth,
            iconSize = iconSize,
            holdDownState = expanded,
            tint = MiuixTheme.colorScheme.onBackgroundVariant,
            onClick = { expanded = true },
        )
        AnchoredMenu(show = expanded, entries = entries, onDismiss = { expanded = false })
    }
}
