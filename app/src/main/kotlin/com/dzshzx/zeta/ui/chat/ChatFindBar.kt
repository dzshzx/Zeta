package com.dzshzx.zeta.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.EarlierHits
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.ui.components.coui2.FlatIconButton
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.markdown.MarkdownInlineStyle
import com.dzshzx.zeta.ui.markdown.MarkdownTone
import com.dzshzx.zeta.ui.markdown.findSegments
import com.dzshzx.zeta.ui.markdown.rememberMarkdownStyle
import com.dzshzx.zeta.ui.markdown.staticMarkdownDocumentCache
import io.github.suqi8.coui.kmp.basic.InputField
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 聊天列表在根坐标里的范围，查找跳转时据此判断命中处在不在视野里。 */
internal fun Modifier.findViewport(find: ChatFindState): Modifier = onGloballyPositioned { find.viewport = it.boundsInRoot() }

/**
 * 能查的段：提问的正文，和已经写完的回答排版后的文字（[answerText] 先去掉单独显示的图片，和回答块画的是同一份）。
 * 工具步骤和思考折叠着，不查。回答的解析结果和回答块共用缓存，这里先解析好，跳过去时就不用再等。
 */
private fun segmentsOf(items: List<ChatItem>, answerText: (String) -> String, inline: MarkdownInlineStyle): List<FindSegment> {
    val segments = ArrayList<FindSegment>()
    for (item in items) {
        if (item is UserItem && item.text.isNotEmpty()) segments += FindSegment(item.id, 0, item.text)
        if (item is AnswerItem && !item.streaming) {
            val text = answerText(item.text)
            if (text.isBlank()) continue
            val document = staticMarkdownDocumentCache.get(text, inline) ?: staticMarkdownDocumentCache.parse(text, inline)
            for ((block, content) in document.findSegments()) segments += FindSegment(item.id, block, content)
        }
    }
    return segments
}

/** 查找词停下来这么久才去查还没载入的更早记录（要读整份会话记录，不跟着每个字查）。 */
private const val EarlierSearchDelayMs = 400L

/**
 * 查找在聊天列表这边要做的事：内容或查找词变了就重算命中，跳到一处时把它滚进视野。
 * 对话还有没载入的更早记录时，查找词停下来后再请助手在那部分里查（[earlier] 是那部分的条数，[findEarlier] 去查），
 * 跳到那里的一处时先 [loadEarlier] 载入到那一条，载入后换成列表里对应的那处（[itemOf]：第几条消息变成了哪一项）。
 * [earlierIdle] 是现在没有在载入更早的一页。
 * [topInset] 是盖在列表上面的顶栏高度。
 * 跳转前调 [onJump]（停掉贴底跟随，不然新内容一来又被拽回底部）。
 */
@Composable
internal fun ChatFindDriver(
    find: ChatFindState,
    items: List<ChatItem>,
    answerText: (String) -> String,
    listState: LazyListState,
    topInset: Dp,
    onJump: () -> Unit,
    earlier: Int = 0,
    earlierIdle: Boolean = true,
    itemOf: (Int) -> String? = { null },
    findEarlier: suspend (String) -> List<EarlierHits>? = { emptyList() },
    loadEarlier: (Int) -> Unit = {},
) {
    val inline = rememberMarkdownStyle(MarkdownTone.Answer).inline
    val latestItems by rememberUpdatedState(items)
    val latestText by rememberUpdatedState(answerText)
    val latestEarlier by rememberUpdatedState(earlier)
    val latestIdle by rememberUpdatedState(earlierIdle)
    val latestItemOf by rememberUpdatedState(itemOf)
    val latestFindEarlier by rememberUpdatedState(findEarlier)
    val latestLoadEarlier by rememberUpdatedState(loadEarlier)
    // 打字和流式输出时节流而不是防抖：一直有新内容时也能定时算一遍
    LaunchedEffect(find, find.active, inline) {
        if (!find.active) return@LaunchedEffect
        // 列表和没载入的条数一起读：往前接上一页时两样在同一次改动里变
        snapshotFlow { Triple(ChatFind.normalize(find.input), latestItems, latestEarlier) }.conflate().collect { (query, list, skipped) ->
            val found = if (query.isEmpty()) emptyList() else withContext(Dispatchers.Default) { ChatFind.matches(segmentsOf(list, latestText, inline), query) }
            find.update(query, found, list.withIndex().associate { it.value.id to it.index }, skipped, latestItemOf)
            delay(120)
        }
    }
    // 更早的记录：查找词定下来后查一次（有更早的部分时）
    LaunchedEffect(find, find.active, find.query) {
        val query = find.query
        if (!find.active || query.isEmpty() || latestEarlier == 0) return@LaunchedEffect
        delay(EarlierSearchDelayMs)
        find.earlierSearch(query)
        find.earlierFound(query, latestFindEarlier(query), latestEarlier)
    }
    // 停在更早的那处、上一页刚载完还没载到它：接着载
    LaunchedEffect(find) {
        snapshotFlow { Triple(find.currentEarlier, latestIdle, latestEarlier) }.collect { (hit, idle, skipped) ->
            if (hit != null && idle && hit.event < skipped) latestLoadEarlier(hit.event)
        }
    }
    val density = LocalDensity.current
    val latestOnJump by rememberUpdatedState(onJump)
    LaunchedEffect(find, find.jumps) {
        if (find.jumps == 0) return@LaunchedEffect
        find.currentEarlier?.let { hit ->
            // 先把那段载入；载入后这一处换成列表里的那处，会再跳一次
            latestOnJump()
            latestLoadEarlier(hit.event)
            return@LaunchedEffect
        }
        val match = find.current ?: return@LaunchedEffect
        val index = latestItems.indexOfFirst { it.id == match.itemId }
        if (index < 0) return@LaunchedEffect
        latestOnJump()
        // 不在屏上的先滚到那条消息，等它排好版报上位置，再把命中处挪到视野上部三分之一处
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
        val bounds = withTimeoutOrNull(1_000) { snapshotFlow { find.placedFor(match) }.filterNotNull().first() } ?: return@LaunchedEffect
        val viewport = find.viewport ?: return@LaunchedEffect
        val top = viewport.top + with(density) { topInset.toPx() }
        val margin = with(density) { 24.dp.toPx() }
        if (bounds.top >= top + margin && bounds.bottom <= viewport.bottom - margin) return@LaunchedEffect
        listState.animateScrollBy(bounds.top - (top + (viewport.bottom - top) * 0.3f))
    }
}

/**
 * 查找栏：查找时顶替输入栏。COUI 的搜索输入胶囊，后面是命中计数和上一个、下一个、关闭；下面一行说明查的范围和进度。
 * 计数含还没载入的更早记录里的命中。[loadingEarlier] 是正在载入更早的一页，[earlierProblem] 是载不了时的说明。
 */
@Composable
internal fun ChatFindBar(find: ChatFindState, loadingEarlier: Boolean, earlierProblem: String?, modifier: Modifier = Modifier) {
    val colors = COUITheme.colorScheme
    BackHandler(onBack = find::close)
    val count = find.count
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 一直是展开（编辑）状态：出现时就拿到焦点，搜索键跳到下一个
            InputField(
                query = find.input,
                onQueryChange = { find.input = it },
                onSearch = { find.next() },
                expanded = true,
                onExpandedChange = {},
                modifier = Modifier.weight(1f),
                label = "在对话中查找",
                material = false,
            )
            if (find.query.isNotEmpty()) {
                Text(
                    text = if (count > 0) "${find.index + 1}/$count" else if (find.searchingEarlier) "查找中" else "无结果",
                    style = COUITheme.textStyles.bodyS,
                    color = if (count == 0) colors.labelTertiary else colors.labelSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp, end = 2.dp),
                )
            }
            BarButton(ZetaIcons.ChevronUp, "上一个", enabled = count > 0, onClick = find::previous)
            BarButton(ZetaIcons.ChevronDown, "下一个", enabled = count > 0, onClick = find::next)
            BarButton(COUIIcons.Close, "关闭查找", enabled = true, onClick = find::close)
        }
        val progress = when {
            find.currentEarlier != null && earlierProblem != null -> earlierProblem
            find.currentEarlier != null && loadingEarlier -> "正在载入更早的记录…"
            find.searchingEarlier -> "正在查更早的记录…"
            else -> find.earlierProblem
        }
        Text(
            text = "查提问和回答，不含工具步骤和思考" + progress?.let { "；$it" }.orEmpty(),
            style = COUITheme.textStyles.bodyXS,
            color = colors.labelSecondary,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp),
        )
    }
}

/** 查找栏的小按钮：36dp 的平按钮，20dp 图标。 */
@Composable
private fun BarButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) =
    FlatIconButton(icon, description, size = 36.dp, iconSize = 20.dp, enabled = enabled, onClick = onClick)
