package com.dzshzx.zeta.ui.components.coui2

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
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.DropdownArrowEndAction
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.preciseClickAnchor
import io.github.suqi8.coui.kmp.basic.rememberPreciseClickState
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 右边显示一个值、点开弹出菜单的设置行，值和菜单各是各的。COUI 2 的 OverlayDropdownPreference 右边只能显示菜单里选中项的文字，
 * OverlayDropdownMenu 右边什么都不显示；这一行要显示的是别的东西时用它，比如共享文件夹这一行显示“可读写”，点开是“复制挂载点路径 / 移除”。
 *
 * 外观和点按照 OverlayDropdownPreference：值是 13sp 的次要文字，后面跟下拉箭头，菜单从手指按下的地方弹出；选了一项就收起（多组菜单也是）。
 * 项可以带二级菜单（children），这时菜单从行尾弹出（[MenuPopup]）。
 * 菜单是 Overlay 弹窗，要在 COUI 的 Scaffold 里（CouiPage、CouiPageScaffold 都是）。
 */
@Composable
internal fun DropdownValuePreference(
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
    val preciseClickState = rememberPreciseClickState()
    val nonEmpty = entries.filter { it.items.isNotEmpty() }
    val actualEnabled = enabled && nonEmpty.isNotEmpty()
    val colors = COUITheme.colorScheme
    BasicComponent(
        modifier = modifier.preciseClickAnchor(preciseClickState),
        interactionSource = interactionSource,
        title = title,
        summary = summary,
        startAction = startAction,
        endActions = {
            if (!value.isNullOrBlank()) {
                Text(
                    text = value,
                    modifier = Modifier.padding(end = 4.dp).align(Alignment.CenterVertically).weight(1f, fill = false),
                    fontSize = COUITheme.textStyles.bodyS.fontSize,
                    color = if (actualEnabled) colors.labelSecondary else colors.disabledNeutral,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownArrowEndAction(actionColor = if (actualEnabled) colors.labelTertiary else colors.disabledNeutral)
            if (nonEmpty.isNotEmpty()) {
                MenuPopup(
                    entries = nonEmpty,
                    show = expanded,
                    onDismiss = { expanded = false },
                    onDismissFinished = { holdDown = false },
                    preciseClickState = preciseClickState,
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
