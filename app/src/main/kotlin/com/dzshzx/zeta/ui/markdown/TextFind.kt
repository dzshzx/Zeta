package com.dzshzx.zeta.ui.markdown

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 页面内查找：给正文加高亮。由聊天页提供，Markdown 文字块和提问气泡读取。 */
@Stable
internal interface TextFinder {
    /** 已整理好的查找词；空串时不高亮。 */
    val query: String

    /** 当前那一处是不是 [itemId] 这条消息 [block] 段里从 [start] 开始的那个。 */
    fun isCurrent(itemId: String, block: Int, start: Int): Boolean

    /** 当前那一处排好版后在根坐标里的位置，聊天页据此把它滚进视野。 */
    fun placeCurrent(bounds: Rect)
}

internal val LocalTextFinder = staticCompositionLocalOf<TextFinder?> { null }

/** 正在画的是哪条消息：Markdown 的文字块不知道自己属于谁，由外面（回答块）告诉它。 */
internal val LocalFindItem = staticCompositionLocalOf<String?> { null }

/** [text] 里 [query] 出现的区间：不分大小写，从前往后，互不重叠。[query] 为空时没有。 */
internal fun findOccurrences(text: String, query: String): List<IntRange> {
    if (query.isEmpty() || text.length < query.length) return emptyList()
    val found = ArrayList<IntRange>()
    var from = 0
    while (true) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        found += at until at + query.length
        from = at + query.length
    }
    return found
}

/**
 * 文档里能查的文字，按画出来的顺序：每个文字块一段，键是块的源码偏移（同一份文档里各不相同）。
 * 查的是排版后的文字（`**粗体**` 只算“粗体”，链接只算链接文字），和高亮的对象一致。公式和分隔线不算。
 */
internal fun MarkdownDocument.findSegments(): List<Pair<Int, String>> = ArrayList<Pair<Int, String>>().also { collectSegments(blocks, it) }

private fun collectSegments(blocks: List<MarkdownBlock>, into: MutableList<Pair<Int, String>>) {
    for (block in blocks) {
        when (block) {
            is MarkdownTextBlock -> into += block.offset to block.text.text
            is MarkdownQuote -> collectSegments(block.blocks, into)
            is MarkdownAlert -> collectSegments(block.blocks, into)
            is MarkdownList -> block.items.forEach { collectSegments(it.blocks, into) }
            is MarkdownTable -> (block.header + block.rows.flatten()).forEach { into += it.offset to it.text.text }
            is MarkdownMath, is MarkdownRule -> Unit
        }
    }
}

/** 命中处的底色：其余几处用 COUI 的选中文字底色，当前那一处是更深的主题色。 */
@Composable
private fun findColors(): Pair<Color, Color> {
    val colors = COUITheme.colorScheme
    val soft = colors.textColorHighlight
    val primary = colors.primary
    val dark = colors.isDark
    return remember(soft, primary, dark) { soft to primary.copy(alpha = if (dark) 0.62f else 0.42f) }
}

/**
 * 能被页面内查找高亮的一段文字。没在查找、或不知道属于哪条消息时就是普通的 [Text]。
 * [block] 是这段文字在消息里的编号（Markdown 块的源码偏移，纯文本为 0），和查找时切的段对应。
 */
@Composable
internal fun FindableText(
    text: AnnotatedString,
    block: Int,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    softWrap: Boolean = true,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    itemId: String? = LocalFindItem.current,
) {
    val finder = LocalTextFinder.current
    val query = finder?.query.orEmpty()
    if (finder == null || itemId == null || query.isEmpty()) {
        Text(text = text, modifier = modifier, color = color, softWrap = softWrap, inlineContent = inlineContent, style = style)
        return
    }
    val ranges = remember(text, query) { findOccurrences(text.text, query) }
    val current = ranges.firstOrNull { finder.isCurrent(itemId, block, it.first) }
    val (soft, strong) = findColors()
    val shown = remember(text, ranges, current, soft, strong) {
        if (ranges.isEmpty()) {
            text
        } else {
            buildAnnotatedString {
                append(text)
                for (range in ranges) addStyle(SpanStyle(background = if (range == current) strong else soft), range.first, range.last + 1)
            }
        }
    }
    // 当前那一处在哪：排版和位置都有了才报，任何一个变了都再报一次（滚动时位置一直在变）
    val place = remember { arrayOfNulls<Any>(2) }
    fun report() {
        val start = current?.first ?: return
        val layout = place[0] as? TextLayoutResult ?: return
        val coordinates = place[1] as? LayoutCoordinates ?: return
        if (!coordinates.isAttached || start >= layout.layoutInput.text.length) return
        val box = layout.getBoundingBox(start)
        val line = layout.getLineForOffset(start)
        val origin = coordinates.positionInRoot()
        finder.placeCurrent(Rect(origin.x + box.left, origin.y + layout.getLineTop(line), origin.x + box.right, origin.y + layout.getLineBottom(line)))
    }
    Text(
        text = shown,
        modifier = modifier.onGloballyPositioned {
            place[1] = it
            report()
        },
        color = color,
        softWrap = softWrap,
        inlineContent = inlineContent,
        onTextLayout = {
            place[0] = it
            report()
        },
        style = style,
    )
}

@Composable
internal fun FindableText(
    text: String,
    block: Int,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    itemId: String? = LocalFindItem.current,
) {
    val annotated = remember(text) { AnnotatedString(text) }
    FindableText(annotated, block, style, modifier, color, itemId = itemId)
}
