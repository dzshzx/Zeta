package com.dzshzx.zeta.ui.pane

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.SessionSort
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.IconMenuButton
import com.dzshzx.zeta.ui.components.miuix.MenuEntry
import com.dzshzx.zeta.ui.components.miuix.MenuItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Sort

/** 排序按钮见方的边长：比分组标题那一行矮，放进去不撑高标题。 */
internal val SortButtonSize: Dp = 28.dp

/** 排序按钮里图标的边长；按钮比图标每边多出 (28 - 18) / 2 = 5dp，排版时用它把图标对齐到会话行的文字。 */
internal val SortIconSize: Dp = 18.dp

/** 排法 [sort] 在界面上的名字（排序菜单、备份确认清单）。 */
fun sessionSortLabel(sort: SessionSort): String =
    when (sort) {
        SessionSort.Replied -> Strings.sidebarSortReplied()
        SessionSort.Created -> Strings.sidebarSortCreated()
    }

/** 侧栏列表第一组标题右边的排序按钮（手机上没有悬停，常显）：点开玻璃菜单列两种排法，当前的 [sort] 打勾，选了交给 [onSort]。 读屏念「排序」和当前的排法。 */
@Composable
internal fun SessionSortButton(sort: SessionSort, onSort: (SessionSort) -> Unit) {
    val entries =
        listOf(
            MenuEntry(
                SessionSort.entries.map { option ->
                    MenuItem(
                        text = sessionSortLabel(option),
                        selected = option == sort,
                        onClick = { onSort(option) },
                    )
                },
            ),
        )
    IconMenuButton(
        icon = MiuixIcons.Sort,
        contentDescription = Strings.sidebarSort(),
        entries = entries,
        size = SortButtonSize,
        iconSize = SortIconSize,
        stateDescription = sessionSortLabel(sort),
    )
}
