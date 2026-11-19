package com.dzshzx.zeta.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.ui.markdown.MarkdownInlineStyle
import com.dzshzx.zeta.ui.markdown.MarkdownTone
import com.dzshzx.zeta.ui.markdown.findSegments
import com.dzshzx.zeta.ui.markdown.rememberMarkdownStyle
import com.dzshzx.zeta.ui.markdown.staticMarkdownDocumentCache
import com.itos.coui.shape.CouiShape
import com.itos.coui.theme.CouiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

/**
 * 查找在聊天列表这边要做的事：内容或查找词变了就重算命中，跳到一处时把它滚进视野。
 * [leading] 是列表里排在消息前面的项数（“更早的记录未载入”那行），[topInset] 是盖在列表上面的顶栏高度。
 * 跳转前调 [onJump]（停掉贴底跟随，不然新内容一来又被拽回底部）。
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
    // 打字和流式输出时节流而不是防抖：一直有新内容时也能定时算一遍
    LaunchedEffect(find, find.active, inline) {
        if (!find.active) return@LaunchedEffect
        snapshotFlow { ChatFind.normalize(find.input) to latestItems }.conflate().collect { (query, list) ->
            val found = if (query.isEmpty()) emptyList() else withContext(Dispatchers.Default) { ChatFind.matches(segmentsOf(list, latestText, inline), query) }
            find.update(query, found, list.withIndex().associate { it.value.id to it.index })
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
        val index = position + leading
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

/** 查找栏：查找时顶替输入栏，样子和输入栏一致。下面一行说明查的范围。 */
@Composable
internal fun ChatFindBar(find: ChatFindState, skippedHistory: Int, modifier: Modifier = Modifier) {
    val dark = CouiTheme.isDark
    val colors = MiuixTheme.colorScheme
    val shape = remember { CouiShape(24.dp, 2.3f) }
    val focus = remember { FocusRequester() }
    BackHandler(onBack = find::close)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val textStyle = MiuixTheme.textStyles.body1.copy(color = colors.onSurface, fontSize = 15.sp, lineHeight = 21.sp)
    val count = find.matches.size
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .dropShadow(shape = shape, shadow = Shadow(radius = 16.dp, color = Color.Black, alpha = if (dark) 0.22f else 0.045f))
                .clip(shape)
                .background(if (dark) Color(0xFF1C1E22) else Color(0xFFF6F7F9), shape)
                .border(0.5.dp, if (dark) Color(0x20FFFFFF) else Color(0x0E000000), shape)
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.onSurfaceVariantSummary)
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f).padding(vertical = 6.dp), contentAlignment = Alignment.CenterStart) {
                if (find.input.isEmpty()) Text(text = "在对话中查找", style = textStyle, color = colors.onSurfaceVariantSummary, maxLines = 1)
                BasicTextField(
                    value = find.input,
                    onValueChange = { find.input = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = textStyle,
                    singleLine = true,
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { find.next() }),
                )
            }
            if (find.query.isNotEmpty()) {
                Text(
                    text = if (count == 0) "无结果" else "${find.index + 1}/$count",
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp),
                    color = if (count == 0) colors.onSurfaceVariantSummary.copy(alpha = 0.8f) else colors.onSurfaceVariantSummary,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            BarButton(Icons.Rounded.KeyboardArrowUp, "上一个", enabled = count > 0, onClick = find::previous)
            BarButton(Icons.Rounded.KeyboardArrowDown, "下一个", enabled = count > 0, onClick = find::next)
            BarButton(Icons.Rounded.Close, "关闭查找", enabled = true, onClick = find::close)
        }
        Text(
            text = "只查已载入的提问和回答，不含工具步骤和思考" + if (skippedHistory > 0) "；更早的 $skippedHistory 条记录未载入，不在范围内" else "",
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.5.sp, lineHeight = 15.sp),
            color = colors.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp),
        )
    }
}

@Composable
private fun BarButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val ink = MiuixTheme.colorScheme.onSurface
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp), tint = ink.copy(alpha = if (enabled) 0.72f else 0.26f))
    }
}
