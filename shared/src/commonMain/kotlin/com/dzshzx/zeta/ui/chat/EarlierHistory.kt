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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.EarlierState
import com.dzshzx.zeta.i18n.Strings
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 长对话往上翻：打开时只载入最近的一段，往上翻到顶附近时自动接上更早的一页。“更早的记录”那一行是列表的第一项。
 *
 * 新的一页插在列表前面时由 [KeepReadingPosition] 让正看着的那条留在原处。LazyColumn 自己也按第一项的 key 保持位置，
 * 但只在第一项附近（约 130 项）里找那个 key，一页有上千条时找不到，视野会跳；翻到最顶上时第一项又是“更早的记录”那行，
 * 它留在原处的话插进来的内容会把正看着的那条往下推。
 */

/** 列表里“更早的记录”那一行的 key。 */
const val EarlierRowKey = "earlier"

private val EarlierRowHeight = 44.dp

/** 往上翻到离顶这么多项以内时载入更早的一页。 */
private const val LoadAhead = 3

/**
 * 列表顶上“更早的记录”那一行：平时是一句可以点的“载入更早的 N 条记录”（往上翻到顶附近时会自己载入，
 * 内容不满一屏、翻不动时点它），载入中是转圈，没读到时说明原因，能重试的点一下重试。几种样子一样高。
 */
@Composable
fun EarlierHistoryRow(
    skipped: Int,
    state: EarlierState,
    onLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val clickable = state == EarlierState.Idle || (state as? EarlierState.Failed)?.retry == true
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(EarlierRowHeight)
                .then(
                    if (clickable) Modifier.clickable(role = Role.Button, onClick = onLoad)
                    else Modifier,
                )
                .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            EarlierState.Idle ->
                Text(
                    text = Strings.chatLoadEarlier(skipped),
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.primary,
                )
            EarlierState.Loading ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    InfiniteProgressIndicator(size = 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = Strings.chatLoadingEarlier(),
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onBackgroundVariant,
                    )
                }
            is EarlierState.Failed ->
                Text(
                    text =
                        if (state.retry) Strings.chatTapToRetry(state.message) else state.message,
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onBackgroundVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
        }
    }
}

/** 上次排版时列表里第一条消息的 key；只在排版前比一下，不用触发重组。 */
private class SeenFirstKey {
    var key: Any? = null
}

/**
 * 往前接上一页（列表前面多了内容）时，让上次排版时屏上第一条消息停在原来的位置：在新列表排版之前（同一帧）找到它的新下标， requestScrollToItem
 * 到那里、偏移照旧。“更早的记录”那行不算（翻到最顶上时它是第一项）。[firstKey] 是列表里第一条消息的 key， 它变了才找；[indexOf] 给出 key 在新列表里的下标（找不到是
 * -1，比如开头那几轮被删了，这时照 LazyColumn 自己的）。
 */
@Composable
fun KeepReadingPosition(listState: LazyListState, firstKey: Any?, indexOf: (Any) -> Int) {
    val seen = remember(listState) { SeenFirstKey() }
    SideEffect {
        val before = seen.key
        seen.key = firstKey
        if (before == null || before == firstKey) return@SideEffect
        val anchor =
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key != EarlierRowKey }
                ?: return@SideEffect
        val index = indexOf(anchor.key)
        if (index >= 0 && index != anchor.index)
            listState.requestScrollToItem(index, -anchor.offset)
    }
}

/** 往上翻到顶附近时自动载入更早的记录。返回要挂到聊天列表上的嵌套滚动连接（用来认出用户往上翻）。 [onLoad] 在开始载入前调（停掉贴底跟随：接上一页后内容变长，不能被拽回底部）。 */
@Composable
fun rememberEarlierPaging(
    conversation: Conversation,
    listState: LazyListState,
    onLoad: () -> Unit,
): NestedScrollConnection {
    // 用户真往上翻过才载入：数据插进来、重新排版、程序滚动（查找跳转、贴底跟随）都不算（照 Eta）。每载入一次用掉一次。
    // 记下是对哪段没载入的部分翻的：一页刚接上时，不能拿载入前的那次翻动再载一页
    val demand = remember(conversation) { mutableStateOf<Int?>(null) }
    val latestOnLoad by rememberUpdatedState(onLoad)
    val connection =
        remember(demand) {
            object : NestedScrollConnection {
                fun scrolled(y: Float) {
                    // 手指往下拖（内容往上翻）时 y 为正
                    if (y != 0f) demand.value = if (y > 0f) conversation.earlier else null
                }

                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput) scrolled(available.y)
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // 松手后的惯性滑动也算
                    scrolled(consumed.y)
                    return Offset.Zero
                }
            }
        }
    LaunchedEffect(conversation, listState) {
        snapshotFlow {
            val earlier = conversation.earlier
            earlier != null &&
                demand.value == earlier &&
                conversation.earlierState == EarlierState.Idle &&
                listState.layoutInfo.totalItemsCount > 0 &&
                listState.firstVisibleItemIndex <= LoadAhead
        }
            .distinctUntilChanged()
            .collect { ready ->
                if (ready) {
                    demand.value = null
                    latestOnLoad()
                    conversation.loadEarlier()
                }
            }
    }
    return connection
}
