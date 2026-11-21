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
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.ui.components.coui2.FlatIconButton
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.markdown.MarkdownInlineStyle
import com.dzshzx.zeta.ui.markdown.MarkdownTone
import com.dzshzx.zeta.ui.markdown.StreamingGfmParserSession
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
internal fun Modifier.findViewport(find: ChatFindState): Modifier = onGloballyPositioned {
    find.viewport = it.boundsInRoot()
}

/**
 * 能查的段：提问的正文，和已经写完的回答排版后的文字（[answerText] 先去掉单独显示的图片，和回答块画的是同一份）。
 * 工具步骤和思考折叠着，不查。写完的回答不再变，它的段按消息记下来，重算时只解析新来的（或改过的）回答。
 * 解析结果不放进回答显示共用的缓存：那里只放得下几十份，整个对话解析一遍会把屏上回答的挤掉，滚动时又要重新解析。 只在一个线程上用（查找的那个协程）。
 */
internal class FindSegments(private val inline: MarkdownInlineStyle) {
    /** 回答 id → 算段时的原文和段。 */
    private var answers = HashMap<String, Pair<String, List<FindSegment>>>()

    fun of(items: List<ChatItem>, answerText: (String) -> String): List<FindSegment> {
        val kept = HashMap<String, Pair<String, List<FindSegment>>>()
        val segments = ArrayList<FindSegment>()
        for (item in items) {
            if (item is UserItem && item.text.isNotEmpty())
                segments += FindSegment(item.id, 0, item.text)
            if (item is AnswerItem && !item.streaming) {
                val entry =
                    answers[item.id]?.takeIf { it.first == item.text }
                        ?: (item.text to parse(item.id, answerText(item.text)))
                kept[item.id] = entry
                segments += entry.second
            }
        }
        // 不在列表里的（删掉、剪掉的）跟着丢掉
        answers = kept
        return segments
    }

    private fun parse(id: String, text: String): List<FindSegment> {
        if (text.isBlank()) return emptyList()
        val document =
            staticMarkdownDocumentCache.get(text, inline)
                ?: StreamingGfmParserSession()
                    .parse(text, isComplete = true, style = inline)
                    .document
        return document.findSegments().map { (block, content) -> FindSegment(id, block, content) }
    }
}

/**
 * 查找在聊天列表这边要做的事：内容或查找词变了就重算命中，跳到一处时把它滚进视野。 [leading] 是列表里排在消息前面的项数（“更早的记录”那行），[topInset]
 * 是盖在列表上面的顶栏高度。 跳转前调 [onJump]（停掉贴底跟随，不然新内容一来又被拽回底部）。
 */
@Composable
internal fun ChatFindDriver(
    find: ChatFindState,
    items: List<ChatItem>,
    answerText: (String) -> String,
    listState: LazyListState,
    leading: Int,
    topInset: Dp,
    onJump: () -> Unit,
) {
    val inline = rememberMarkdownStyle(MarkdownTone.Answer).inline
    val latestItems by rememberUpdatedState(items)
    val latestText by rememberUpdatedState(answerText)
    val latestLeading by rememberUpdatedState(leading)
    // 打字和流式输出时节流而不是防抖：一直有新内容时也能定时算一遍
    LaunchedEffect(find, find.active, inline) {
        if (!find.active) return@LaunchedEffect
        val segments = FindSegments(inline)
        snapshotFlow { ChatFind.normalize(find.input) to latestItems }
            .conflate()
            .collect { (query, list) ->
                // 命中和条目的先后（换选中哪个时用）都在后台算，主线程只收结果
                val (found, order) =
                    withContext(Dispatchers.Default) {
                        findMatches(segments, list, query, latestText)
                    }
                find.update(query, found, order)
                delay(120)
            }
    }
    val density = LocalDensity.current
    val latestOnJump by rememberUpdatedState(onJump)
    LaunchedEffect(find, find.jumps) {
        if (find.jumps == 0) return@LaunchedEffect
        val match = find.current ?: return@LaunchedEffect
        val position = latestItems.indexOfFirst { it.id == match.itemId }
        if (position < 0) return@LaunchedEffect
        latestOnJump()
        val top = with(density) { topInset.toPx() }
        val margin = with(density) { 24.dp.toPx() }
        scrollToMatch(find, match, listState, position + latestLeading, top, margin)
    }
}

/** 查 [query] 的命中，和各条目在列表里的位置（有命中时才算）。 */
private fun findMatches(
    segments: FindSegments,
    list: List<ChatItem>,
    query: String,
    answerText: (String) -> String,
): Pair<List<FindMatch>, Map<String, Int>> {
    if (query.isEmpty()) return emptyList<FindMatch>() to emptyMap()
    val found = ChatFind.matches(segments.of(list, answerText), query)
    if (found.isEmpty()) return found to emptyMap()
    return found to list.withIndex().associate { it.value.id to it.index }
}

/**
 * 把命中处 [match]（在列表第 [index] 项）滚进视野：不在屏上的先滚到那条消息，等它排好版报上位置，再把命中处挪到视野上部三分之一处。 [topInset]
 * 是盖在列表上面的顶栏高度，命中处离视野上下边不到 [margin] 时也挪。
 */
private suspend fun scrollToMatch(
    find: ChatFindState,
    match: FindMatch,
    listState: LazyListState,
    index: Int,
    topInset: Float,
    margin: Float,
) {
    if (listState.layoutInfo.visibleItemsInfo.none { it.index == index })
        listState.scrollToItem(index)
    val bounds =
        withTimeoutOrNull(1_000) {
            snapshotFlow { find.placedFor(match) }.filterNotNull().first()
        } ?: return
    val viewport = find.viewport ?: return
    val top = viewport.top + topInset
    if (bounds.top >= top + margin && bounds.bottom <= viewport.bottom - margin) return
    listState.animateScrollBy(bounds.top - (top + (viewport.bottom - top) * 0.3f))
}

/**
 * 查找栏：查找时顶替输入栏。COUI 的搜索输入胶囊，后面是命中计数和上一个、下一个、关闭；下面一行说明查的范围。
 * 查的是整个对话：打开查找栏时界面把还没载入的更早记录一次载完，[earlierLeft] 是还剩几条没载入， [earlierProblem] 是载不下去时的说明（这时只查载入了的）。
 */
@Composable
internal fun ChatFindBar(
    find: ChatFindState,
    earlierLeft: Int,
    earlierProblem: String?,
    modifier: Modifier = Modifier,
) {
    val colors = COUITheme.colorScheme
    BackHandler(onBack = find::close)
    val count = find.matches.size
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
                    text = matchCountText(find.index, count, earlierLeft, earlierProblem),
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
        Text(
            text =
                "查提问和回答，不含工具步骤和思考" +
                    when {
                        earlierProblem != null -> "；$earlierProblem，更早的 $earlierLeft 条没查"
                        earlierLeft > 0 -> "；正在载入更早的 $earlierLeft 条…"
                        else -> ""
                    },
            style = COUITheme.textStyles.bodyXS,
            color = colors.labelSecondary,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp),
        )
    }
}

/** 查找栏里的命中计数：第几个/共几个；没有命中时看更早的记录还载不载。 */
private fun matchCountText(
    index: Int,
    count: Int,
    earlierLeft: Int,
    earlierProblem: String?,
): String =
    when {
        count > 0 -> "${index + 1}/$count"
        earlierLeft > 0 && earlierProblem == null -> "查找中"
        else -> "无结果"
    }

/** 查找栏的小按钮：36dp 的平按钮，20dp 图标。 */
@Composable
private fun BarButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) =
    FlatIconButton(
        icon,
        description,
        size = 36.dp,
        iconSize = 20.dp,
        enabled = enabled,
        onClick = onClick,
    )
