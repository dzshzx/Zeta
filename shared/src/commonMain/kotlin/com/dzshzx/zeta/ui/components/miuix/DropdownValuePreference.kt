package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 右边显示一个值、点开弹出菜单的设置行，值和菜单各是各的。Miuix 的 OverlayDropdownPreference 右边只能显示菜单里选中项的文字，
 * OverlayDropdownMenu 右边什么都不显示；这一行要显示的是别的东西时用它，比如共享文件夹这一行显示“可读写”，点开是“复制挂载点路径 / 移除”。
 *
 * 外观和点按照 OverlayDropdownPreference：值是 14sp 的次要文字，后面跟下拉箭头，菜单从行尾弹出（[AnchoredMenu]）；选了一项就收起（多组菜单也是），
 * 项可以带二级菜单（children）。菜单是 Overlay 弹窗，要在 Miuix 的 Scaffold 里（MiuixPage、MiuixPageScaffold 都是）。
 */
@Composable
fun DropdownValuePreference(
    title: String,
    entries: List<DropdownEntry>,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var expanded by remember { mutableStateOf(false) }
    // 菜单开着和收起动画放完之前，这一行保持按下的样子
    var holdDown by remember { mutableStateOf(false) }
    val nonEmpty = entries.filter { it.items.isNotEmpty() }
    val actualEnabled = enabled && nonEmpty.isNotEmpty()
    val colors = MiuixTheme.colorScheme
    BasicComponent(
        modifier = modifier,
        interactionSource = interactionSource,
        title = title,
        summary = summary,
        startAction = startAction,
        endActions = {
            if (!value.isNullOrBlank()) {
                Text(
                    text = value,
                    modifier =
                        Modifier.padding(end = 4.dp)
                            .align(Alignment.CenterVertically)
                            .weight(1f, fill = false),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color =
                        if (actualEnabled) colors.onSurfaceVariantActions
                        else colors.disabledOnSecondaryVariant,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownArrowEndAction(
                actionColor =
                    if (actualEnabled) colors.onSurfaceVariantActions
                    else colors.disabledOnSecondaryVariant,
            )
            if (nonEmpty.isNotEmpty()) {
                AnchoredMenu(
                    show = expanded,
                    entries = nonEmpty,
                    onDismiss = { expanded = false },
                    onDismissFinished = { holdDown = false },
                )
            }
        },
        onClick = {
            expanded = !expanded
            if (expanded) holdDown = true
        },
        role = Role.DropdownList,
        holdDownState = holdDown,
        enabled = actualEnabled,
    )
}
