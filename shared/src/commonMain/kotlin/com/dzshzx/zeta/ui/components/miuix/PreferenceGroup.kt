package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** [PreferenceGroup] 花括号里的写法：每一行写进一个 `item { }`，[key] 让前面的行增减时这一行留住自己的状态。 */
interface PreferenceGroupScope {
    fun item(key: Any? = null, content: @Composable () -> Unit)
}

private class PreferenceGroupScopeImpl : PreferenceGroupScope {
    val rows = mutableListOf<Pair<Any?, @Composable () -> Unit>>()

    override fun item(key: Any?, content: @Composable () -> Unit) {
        rows += key to content
    }
}

/**
 * 设置页的一组：分类标题、圆角卡片里的一组设置行、卡片下面的说明文字。行用 Miuix 的设置项（SwitchPreference、ArrowPreference、
 * OverlayDropdownPreference、BasicComponent……）写在 `item { }` 里；HyperOS 的卡片里行与行之间不画分隔线。
 */
@Composable
fun PreferenceGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: PreferenceGroupScope.() -> Unit,
) {
    val rows = PreferenceGroupScopeImpl().apply(content).rows
    Column(modifier) {
        if (title != null) SmallTitle(text = title)
        // 卡片离屏幕边 12dp、离下一张卡片 12dp（照 Miuix 的示例和 Eta 的设置页）
        Card(
            Modifier.padding(horizontal = 12.dp)
                .padding(bottom = if (footer == null) 12.dp else 0.dp),
        ) {
            rows.forEachIndexed { index, (rowKey, row) -> key(rowKey ?: index) { row() } }
        }
        if (footer != null) FooterHint(summary = footer, modifier = Modifier.padding(top = 8.dp))
    }
}

/** 卡片下面、列表末尾的一段说明（照 Eta 的 FooterHint）；出错时给 [summaryColor]。 */
@Composable
fun FooterHint(
    summary: String,
    modifier: Modifier = Modifier,
    summaryColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
) {
    Text(
        text = summary,
        style = MiuixTheme.textStyles.body2,
        color = summaryColor,
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp),
    )
}

/**
 * 设置行右侧显示的当前值，放进行的 endActions：`endActions = { PreferenceValue("跟随系统") }`。要按状态上色（出错）时给 [color]。
 * [color] 为 Unspecified 时按这一行能不能点上色：能点进去或点了能调整的行用 `onSurfaceVariantActions`（和 Miuix 下拉行右侧的值同色）；
 * 点不了的行（只读或禁用）用禁用色 `disabledOnSecondaryVariant`。标题加值的一行用 `ValueRow`，它按行的 enabled 和 onClick 自己传
 * [clickable]；自己拼的行按行的可点条件传。
 */
@Composable
fun PreferenceValue(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    clickable: Boolean = true,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MiuixTheme.textStyles.body2,
        color =
            color.takeOrElse {
                if (clickable) MiuixTheme.colorScheme.onSurfaceVariantActions
                else MiuixTheme.colorScheme.disabledOnSecondaryVariant
            },
        maxLines = 1,
        // 放不下时（译文比中文长）显示省略号，不在词边界上悄悄截掉后半
        overflow = TextOverflow.Ellipsis,
    )
}
