package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.EarlierHistory
import com.dzshzx.zeta.core.EarlierState
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.flow.distinctUntilChanged

/*
 * 长对话往上翻：打开时只载入最近的一段，往上翻到顶附近时自动接上更早的一页。
 *
 * 新的一页插在列表前面时，LazyColumn 自己按第一项的 key 保持位置，正看着的那条不动。所以“更早的记录”那一行不放进列表项：
 * 它要是第一项，用户翻到最顶上时它就是那个被保持的第一项，插进来的内容会把正看着的那条往下推。
 * 这一行画在列表外面，位置跟着列表的第 0 项走（紧贴在它上方，列表的顶部留白里给它让出了地方），第 0 项不在屏上时不画。
 */

/** “更早的记录”那一行的高度，列表顶部为它多留这么多。 */
internal val EarlierRowHeight = 44.dp

/** 往上翻到离顶这么多项以内时载入更早的一页。 */
private const val LoadAhead = 3

/**
 * 列表顶上“更早的记录”那一行：平时是一句可以点的“载入更早的 N 条记录”（往上翻到顶附近时会自己载入，
 * 内容不满一屏、翻不动时点它），载入中是转圈，没读到时说明原因，能重试的点一下重试。几种样子一样高。
 */
@Composable
internal fun EarlierHistoryRow(skipped: Int, state: EarlierState, onLoad: () -> Unit, modifier: Modifier = Modifier) {
    val colors = COUITheme.colorScheme
    val clickable = state == EarlierState.Idle || (state as? EarlierState.Failed)?.retry == true
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(EarlierRowHeight)
            .then(if (clickable) Modifier.clickable(role = Role.Button, onClick = onLoad) else Modifier)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            EarlierState.Idle -> Text(text = "载入更早的 $skipped 条记录", style = COUITheme.textStyles.bodyS, color = colors.primary)
            EarlierState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                InfiniteProgressIndicator(size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(text = "正在载入更早的记录", style = COUITheme.textStyles.bodyS, color = colors.labelSecondary)
            }
            is EarlierState.Failed -> Text(
                text = state.message + if (state.retry) "，点按重试" else "",
                style = COUITheme.textStyles.bodyS,
                color = colors.labelSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 把这一行摆在 [listState] 的第 0 项正上方（和列表在同一个坐标里）；第 0 项不在屏上时不摆，也就不画、点不到。 */
internal fun Modifier.aboveFirstItem(listState: LazyListState): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        val info = listState.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull()?.takeIf { it.index == 0 } ?: return@layout
        placeable.place(0, info.beforeContentPadding + first.offset - placeable.height)
    }
}

/**
 * 用户真往上翻过才载入：数据插进来、重新排版、程序滚动（查找跳转、贴底跟随）都不算（照 Eta）。每载入一次用掉一次。
 * 记下是对哪段没载入的部分翻的：一页刚接上时列表还没按新内容排版，第一项的位置是旧的，不能拿载入前的那次翻动再载一页。
 */
private class ScrollDemand {
    var window by mutableStateOf<EarlierHistory?>(null)
}

/**
 * 往上翻到顶附近时自动载入更早的记录。返回要挂到聊天列表上的嵌套滚动连接（用来认出用户往上翻）。
 * [onLoad] 在开始载入前调（停掉贴底跟随：接上一页后内容变长，不能被拽回底部）。
 */
@Composable
internal fun rememberEarlierPaging(conversation: Conversation, listState: LazyListState, onLoad: () -> Unit): NestedScrollConnection {
    val demand = remember(conversation) { ScrollDemand() }
    val latestOnLoad by rememberUpdatedState(onLoad)
    val connection = remember(demand) {
        object : NestedScrollConnection {
            fun scrolled(y: Float) {
                // 手指往下拖（内容往上翻）时 y 为正
                if (y != 0f) demand.window = if (y > 0f) conversation.earlier else null
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) scrolled(available.y)
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // 松手后的惯性滑动也算
                scrolled(consumed.y)
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(conversation, listState) {
        snapshotFlow {
            val earlier = conversation.earlier
            earlier != null && demand.window == earlier && conversation.earlierState == EarlierState.Idle &&
                listState.layoutInfo.totalItemsCount > 0 && listState.firstVisibleItemIndex <= LoadAhead
        }.distinctUntilChanged().collect { ready ->
            if (ready) {
                demand.window = null
                latestOnLoad()
                conversation.loadEarlier()
            }
        }
    }
    return connection
}
