package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.VerticalDivider

// Miuix 没有的几种设置行和编辑页的输入项：编辑页里的一个输入框、右边带竖线和开关的行、开关在转圈的行。

/**
 * 编辑页里的一个输入项（照 Eta 角色编辑页的 CharacterTextField）：分类小标题 [title]、直接放在页面上的输入框、下面的说明 [footer]。
 * 输入框自带底色，不再套卡片（卡片里再放输入框就成了框套框）；左右和设置分组的卡片对齐，横向撑满。[field] 里放 [TextField]。
 */
@Composable
fun FieldGroup(
    title: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    field: @Composable () -> Unit,
) {
    GroupFrame(title, footer, modifier, field)
}

/** 点行进编辑页、右边的开关单独管开关的一行：开关前面一条竖线，把「点行」和「拨开关」分开。 */
@Composable
fun SwitchWithDividerPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    summary: String? = null,
) {
    BasicComponent(
        modifier = modifier,
        title = title,
        summary = summary,
        endActions = {
            VerticalDivider(
                Modifier.height(28.dp)
                    .padding(horizontal = 12.dp)
                    .align(Alignment.CenterVertically),
            )
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        onClick = onClick,
    )
}

/** 开关行，[isLoading] 时开关换成转圈、不能拨（等异步的设置生效）。 */
@Composable
fun SwitchLoadingPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    summary: String? = null,
    endActions: @Composable RowScope.() -> Unit = {},
    enabled: Boolean = true,
) {
    BasicComponent(
        modifier = modifier,
        title = title,
        summary = summary,
        endActions = {
            endActions()
            if (isLoading) {
                InfiniteProgressIndicator(Modifier.align(Alignment.CenterVertically))
            } else {
                Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            }
        },
        onClick = if (isLoading || !enabled) null else ({ onCheckedChange(!checked) }),
        enabled = enabled,
    )
}
