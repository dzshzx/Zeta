package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 下拉选一个的设置行：Miuix 的玻璃下拉行（GlassDropdownPreference，同 Eta 的设置页），右边显示选中项，点开是玻璃弹层。毛玻璃拿页面背景
 * （[LocalPageBackdrop]），所以要在 MiuixPage、MiuixPageScaffold 里。
 */
@Composable
fun DropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
) {
    GlassDropdownPreference(
        onSelectedIndexChange = onSelectedIndexChange,
        items = items,
        selectedIndex = selectedIndex,
        title = title,
        backdrop = LocalPageBackdrop.current,
        modifier = modifier,
        enabled = enabled,
        summary = summary,
        startAction = startAction,
    )
}

/**
 * 右边显示一个值、点开弹出菜单的设置行，值和菜单各是各的。[DropdownPreference] 右边只能显示菜单里选中项的文字；这一行要显示的是别的东西时用它，
 * 比如共享文件夹这一行显示“可读写”，点开是“复制挂载点路径 / 移除”。
 *
 * 外观和点按照 [DropdownPreference]：值是 14sp 的次要文字，后面跟下拉箭头，以整行为锚弹出玻璃菜单（[GlassMenu]）；选了一项就收起（多组菜单也是），
 * 项可以带二级菜单（children）。要在 Miuix 的 Scaffold 里（MiuixPage、MiuixPageScaffold 都是）。
 */
@Composable
fun DropdownValuePreference(
    title: String,
    entries: List<MenuEntry>,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: String? = null,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    // 菜单开着和收起动画放完之前，这一行保持按下的样子
    var holdDown by remember { mutableStateOf(false) }
    val anchor = remember { MenuAnchor() }
    val nonEmpty = remember(entries) { entries.filter { it.items.isNotEmpty() } }
    val actualEnabled = enabled && nonEmpty.isNotEmpty()
    val actionColor =
        if (actualEnabled) MiuixTheme.colorScheme.onSurfaceVariantActions
        else MiuixTheme.colorScheme.disabledOnSecondaryVariant
    BasicComponent(
        modifier = modifier.onPlaced { anchor.coordinates = it },
        title = title,
        summary = summary,
        endActions = {
            if (!value.isNullOrBlank()) {
                Text(
                    text = value,
                    modifier =
                        Modifier.padding(end = 4.dp)
                            .align(Alignment.CenterVertically)
                            .weight(1f, fill = false),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = actionColor,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownArrowEndAction(actionColor = actionColor)
        },
        onClick = {
            expanded = !expanded
            if (expanded) holdDown = true
        },
        role = Role.DropdownList,
        holdDownState = holdDown,
        enabled = actualEnabled,
    )
    GlassMenu(
        show = expanded && actualEnabled,
        entries = nonEmpty,
        anchorBounds = anchor::bounds,
        onDismiss = { expanded = false },
        onDismissFinished = { holdDown = false },
    )
}
