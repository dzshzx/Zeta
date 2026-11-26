package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 带外框的分段标签，几个选项里选一个。
 *
 * Miuix 的 TabRowWithContour 没有 `enabled`，而且总是先横向撑满，调用方给的宽度不起作用。 这里在外面套一层 [Box] 承接 [modifier]，要撑满就传
 * `Modifier.fillMaxWidth()`。 [enabled] 为 false 时忽略点击，文字和选中块换成 Miuix 不可用的颜色。
 */
@Composable
fun SegmentedTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors =
        if (enabled) {
            TabRowDefaults.tabRowColors()
        } else {
            val scheme = MiuixTheme.colorScheme
            TabRowDefaults.tabRowColors(
                contentColor = scheme.disabledOnSecondaryVariant,
                selectedBackgroundColor = scheme.disabledSecondaryVariant,
                selectedContentColor = scheme.disabledOnSecondaryVariant,
            )
        }
    Box(modifier) {
        TabRowWithContour(
            tabs = tabs,
            selectedTabIndex = selectedIndex,
            onTabSelected = { index -> if (enabled) onSelect(index) },
            colors = colors,
        )
    }
}
