package com.dzshzx.zeta.ui.markdown

import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser

/**
 * 面向追加式模型输出的 GFM 解析会话。
 *
 * 每次提交都在调用线程完成一次完整 GFM 解析，再转换为 [MarkdownDocument]；调用方应把 会话限制在后台串行 dispatcher。完整解析让块级语法遵循同一套
 * CommonMark/GFM 规则， 而不是由 UI 猜测节点类型。流尚未结束时，仅在虚拟 EOF 上补齐仍在等待闭合的结构， 虚拟字符不会写回消息，也不会进入最终快照。
 */
internal class StreamingGfmParserSession(
    mathCache: MarkdownMathLayoutCache = markdownMathLayoutCache,
) {
    private val documentBuilder = MarkdownDocumentBuilder(mathCache)

    fun parse(
        source: String,
        isComplete: Boolean,
        style: MarkdownInlineStyle = MarkdownInlineStyle.Default,
    ): StreamingGfmSnapshot {
        val normalizedSource = TexMathDelimiters.normalize(source)
        val renderedSource =
            StreamingGfmProjection.project(
                source = normalizedSource,
                isComplete = isComplete,
            )
        val parser =
            MarkdownParser(
                flavour = ChatGfmFlavourDescriptor(originalSource = source),
                assertionsEnabled = false,
                cancellationToken = CancellationToken.NonCancellable,
            )
        val root = parser.buildMarkdownTreeFromString(renderedSource as CharSequence)
        // 引用式链接的定义可能出现在文末，只在终态整体解析一次；流式期间引用暂按原文显示。
        val links = if (isComplete) LinkMap.buildLinkMap(root, renderedSource) else null
        return StreamingGfmSnapshot(
            originalSource = source,
            renderedSource = renderedSource,
            isComplete = isComplete,
            document =
                documentBuilder.build(
                    root = root,
                    renderedSource = renderedSource,
                    originalSource = source,
                    links = links,
                    style = style,
                ),
        )
    }
}

internal data class StreamingGfmSnapshot(
    val originalSource: String,
    val renderedSource: String,
    val isComplete: Boolean,
    val document: MarkdownDocument,
)

/**
 * 为真实 EOF 和“暂时没有更多字符”的流式 EOF 建立不同语义。
 *
 * - 表格在分隔行确认前不发布候选表头，避免先按普通段落显示竖线。
 * - 未闭合链接保留在解析器缓冲区，避免把半截目标地址暴露给 UI。
 * - 已确认开始的围栏代码、代码 span 和强调结构使用只存在于解析快照中的 虚拟闭合符，使其从第一次可判定时就保持同一种节点类型。
 */
internal object StreamingGfmProjection {
    fun project(source: String, isComplete: Boolean): String {
        if (isComplete || source.isEmpty()) return source

        val tableSafeEnd = ambiguousTableStart(source) ?: source.length
        var projected = source.substring(0, tableSafeEnd)

        val openFence = findOpenFence(projected)
        if (openFence != null) {
            if (!projected.endsWith('\n')) projected += "\n"
            return projected + openFence.marker.toString().repeat(openFence.length)
        }

        val pendingLinkStart = findPendingLinkStart(projected)
        if (pendingLinkStart != null) {
            projected = projected.substring(0, pendingLinkStart)
        }

        val inlineClosures = findInlineClosures(projected)
        return projected + inlineClosures
    }

    private fun ambiguousTableStart(source: String): Int? {
        if (hasOpenDisplayMathBlock(source)) return null
        val lines = source.toLineSlices()
        if (lines.isEmpty()) return null

        val blockStart =
            lines
                .indexOfLast { it.text.isBlank() }
                .let { blankIndex -> if (blankIndex == -1) 0 else blankIndex + 1 }
        val blockLines = lines.subList(blockStart, lines.size)
        if (blockLines.isEmpty()) return null

        val confirmedTable =
            (1 until blockLines.size).any { index ->
                containsUnescapedPipe(blockLines[index - 1].text) &&
                    isValidTableDelimiter(blockLines[index].text)
            }
        if (confirmedTable) return null

        val current = blockLines.last()
        val previous = blockLines.getOrNull(blockLines.lastIndex - 1)
        return candidateTableStart(source, previous, current)
    }

    /** 最后一个块还没确认成表格时，从哪里起要先藏起来：上一行有竖线、这一行是空的或像没写完的分隔行时藏上一行起； 这一行自己带竖线时藏这一行起。 */
    private fun candidateTableStart(
        source: String,
        previous: LineSlice?,
        current: LineSlice,
    ): Int? {
        if (previous != null && containsUnescapedPipe(previous.text)) {
            if (current.text.isEmpty()) {
                return if (source.endsWith("\n\n")) null else previous.start
            }
            if (isTableDelimiterCandidate(current.text)) {
                return if (isValidTableDelimiter(current.text)) null else previous.start
            }
        }

        return current.start.takeIf {
            current.text.isNotBlank() && containsUnescapedPipe(current.text)
        }
    }

    private fun findOpenFence(source: String): Fence? {
        var openFence: Fence? = null
        source.toLineSlices().forEach { line ->
            val marker = line.fenceMarker() ?: return@forEach
            val current = openFence
            if (current == null) {
                openFence = marker
            } else if (
                marker.marker == current.marker &&
                    marker.length >= current.length &&
                    marker.isClosing
            ) {
                openFence = null
            }
        }
        return openFence
    }

    private fun findPendingLinkStart(source: String): Int? {
        val code = CodeSpanState()
        val links = LinkScan()
        var index = 0

        while (index < source.length) {
            val next = skipNonTextAt(source, index, code, mathAllowed = !links.insideTarget)
            if (next == STOP_SCAN) break
            if (next != index) {
                index = next
                continue
            }
            links.feed(source[index], index)
            index += 1
        }

        return links.pendingStart()?.let { start ->
            if (start > 0 && source[start - 1] == '!') start - 1 else start
        }
    }

    private fun findInlineClosures(source: String): String {
        val code = CodeSpanState()
        val delimiterStack = ArrayDeque<String>()
        var index = 0

        while (index < source.length) {
            val next = skipNonTextAt(source, index, code, mathAllowed = true)
            if (next == STOP_SCAN) break
            if (next != index) {
                index = next
                continue
            }

            val delimiter = source.delimiterAt(index)
            if (delimiter == null) {
                index += 1
                continue
            }
            updateDelimiterStack(source, index, delimiter, delimiterStack)
            index += delimiter.length
        }

        return buildString {
            if (code.ticks != 0) {
                append("`".repeat(code.ticks))
            }
            delimiterStack.reversed().forEach(::append)
        }
    }

    /** 强调记号能闭合栈顶的同种记号就出栈，否则能开启时入栈；词内的 `_` 不开启强调。 */
    private fun updateDelimiterStack(
        source: String,
        index: Int,
        delimiter: String,
        delimiterStack: ArrayDeque<String>,
    ) {
        val previous = source.getOrNull(index - 1)
        val next = source.getOrNull(index + delimiter.length)
        val canOpen = next != null && !next.isWhitespace()
        val canClose = previous != null && !previous.isWhitespace()
        val intrawordUnderscore =
            delimiter.contains('_') &&
                previous?.isLetterOrDigit() == true &&
                next?.isLetterOrDigit() == true

        when {
            canClose && delimiterStack.lastOrNull() == delimiter -> delimiterStack.removeLast()
            canOpen && !intrawordUnderscore -> delimiterStack.addLast(delimiter)
        }
    }

    /** 扫描到哪段行内代码里：开头那串反引号的个数，0 表示不在行内代码里。 */
    private class CodeSpanState {
        var ticks = 0
    }

    /**
     * 跳过不按正文语法看的部分：转义、反引号串与行内代码的内容、已闭合的公式（[mathAllowed] 时）。 返回下一个要看的下标；[index] 处是正文字符时原样返回
     * [index]；遇到末尾还在输入的公式时返回 [STOP_SCAN]。
     */
    private fun skipNonTextAt(
        source: String,
        index: Int,
        code: CodeSpanState,
        mathAllowed: Boolean,
    ): Int {
        if (source.isUnclosedTexMathTailAt(index)) return STOP_SCAN
        if (source[index] == '\\') return index + 2
        if (source[index] == '`') {
            val runLength = source.runLengthAt(index, '`')
            code.ticks =
                when {
                    code.ticks == 0 -> runLength
                    code.ticks == runLength -> 0
                    else -> code.ticks
                }
            return index + runLength
        }
        if (code.ticks != 0) return index + 1
        if (mathAllowed && source[index] == '$') {
            val mathEnd = source.consumeMathSpanOrTailAt(index)
            if (mathEnd < 0) return STOP_SCAN
            if (mathEnd > index) return mathEnd
        }
        return index
    }

    /** 跟踪方括号和链接目标的括号，找还没写完的链接从哪里开始。 */
    private class LinkScan {
        private val bracketStack = ArrayDeque<Int>()
        private var openLinkStart: Int? = null
        private var linkParenthesisDepth = 0
        private var lastClosedBracketStart: Int? = null
        private var lastClosedBracketEnd = -1

        /** 正在 `](...)` 的目标里：这里的 `$` 不当公式。 */
        val insideTarget: Boolean
            get() = openLinkStart != null

        fun feed(char: Char, index: Int) {
            if (openLinkStart != null) {
                feedTarget(char)
                return
            }
            when (char) {
                '[' -> bracketStack.addLast(index)
                ']' ->
                    if (bracketStack.isNotEmpty()) {
                        lastClosedBracketStart = bracketStack.removeLast()
                        lastClosedBracketEnd = index
                    }
                '(' ->
                    if (lastClosedBracketEnd == index - 1) {
                        openLinkStart = lastClosedBracketStart
                        linkParenthesisDepth = 1
                    }
            }
        }

        private fun feedTarget(char: Char) {
            when (char) {
                '(' -> linkParenthesisDepth += 1
                ')' -> {
                    linkParenthesisDepth -= 1
                    if (linkParenthesisDepth == 0) {
                        openLinkStart = null
                    }
                }
            }
        }

        fun pendingStart(): Int? = openLinkStart ?: bracketStack.lastOrNull()
    }

    private fun String.delimiterAt(index: Int): String? =
        when {
            startsWith("**", index) -> "**"
            startsWith("__", index) -> "__"
            startsWith("~~", index) -> "~~"
            this[index] == '*' -> "*"
            this[index] == '_' -> "_"
            else -> null
        }

    private fun String.runLengthAt(start: Int, char: Char): Int {
        var end = start
        while (end < length && this[end] == char) end += 1
        return end - start
    }

    private fun containsUnescapedPipe(line: String): Boolean {
        var escaped = false
        line.forEach { char ->
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '|' -> return true
            }
        }
        return false
    }

    private fun isTableDelimiterCandidate(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.isNotEmpty() &&
            trimmed.all { char -> char == '|' || char == ':' || char == '-' || char.isWhitespace() }
    }

    private fun isValidTableDelimiter(line: String): Boolean {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        val cells = trimmed.split('|').map(String::trim)
        return cells.isNotEmpty() &&
            cells.all { cell ->
                val withoutColons = cell.removePrefix(":").removeSuffix(":")
                withoutColons.length >= 3 && withoutColons.all { it == '-' }
            }
    }

    private fun String.toLineSlices(): List<LineSlice> {
        val result = mutableListOf<LineSlice>()
        var start = 0
        for (index in indices) {
            if (this[index] != '\n') continue
            val end = if (index > start && this[index - 1] == '\r') index - 1 else index
            result += LineSlice(start = start, text = substring(start, end))
            start = index + 1
        }
        result += LineSlice(start = start, text = substring(start))
        return result
    }

    private fun LineSlice.fenceMarker(): Fence? {
        var index = 0
        while (index < text.length && index < 4 && text[index] == ' ') index += 1
        if (index > 3) return null
        val marker = text.getOrNull(index)?.takeIf { it == '`' || it == '~' } ?: return null
        val runLength = text.runLengthAt(index, marker)
        if (runLength < 3) return null
        val suffix = text.substring(index + runLength)
        return Fence(
            marker = marker,
            length = runLength,
            isClosing = suffix.isBlank(),
        )
    }

    private data class LineSlice(
        val start: Int,
        val text: String,
    )

    private data class Fence(
        val marker: Char,
        val length: Int,
        val isClosing: Boolean,
    )

    private fun hasOpenDisplayMathBlock(source: String): Boolean {
        val fences = FenceTracker()
        var openDisplayMath = false
        for (line in source.toLineSlices()) {
            if (fences.consume(line.fenceMarker(), mayOpen = !openDisplayMath)) continue
            if (fences.isOpen) continue

            if (!openDisplayMath) {
                val opening = line.displayMathOpening()
                if (opening == DisplayMathOpening.Bracket) return true
                openDisplayMath = opening == DisplayMathOpening.UnclosedDollars
            } else if (line.text.endsWithUnescapedDoubleDollar()) {
                openDisplayMath = false
            }
        }
        return openDisplayMath
    }

    /** 逐行跟踪围栏代码块是否开着。 */
    private class FenceTracker {
        private var open: Fence? = null

        val isOpen: Boolean
            get() = open != null

        /** 这一行是开启（[mayOpen] 时）或闭合围栏的那一行时记下并返回 true。 */
        fun consume(marker: Fence?, mayOpen: Boolean): Boolean {
            if (marker == null) return false
            val current = open
            if (current == null && mayOpen) {
                open = marker
                return true
            }
            if (current != null && marker.closes(current)) {
                open = null
                return true
            }
            return false
        }
    }

    private fun Fence.closes(open: Fence): Boolean =
        marker == open.marker && length >= open.length && isClosing

    private enum class DisplayMathOpening {
        /** `\[` 开头：一律算作没闭合的块公式。 */
        Bracket,

        /** `$$` 开头且同一行没有闭合。 */
        UnclosedDollars,
    }

    /** 这一行是否开始一个块公式（最多缩进 3 格）。 */
    private fun LineSlice.displayMathOpening(): DisplayMathOpening? {
        val trimmedStart = text.trimStart(' ')
        val indent = text.length - trimmedStart.length
        if (indent > 3) return null
        if (trimmedStart.startsWith("\\[")) return DisplayMathOpening.Bracket
        if (!trimmedStart.startsWith("$$") || trimmedStart.startsWith("$$$")) return null
        val afterOpen = trimmedStart.substring(2)
        return if (afterOpen.endsWithUnescapedDoubleDollar()) null
        else DisplayMathOpening.UnclosedDollars
    }

    private fun String.isUnclosedTexMathTailAt(index: Int): Boolean {
        if (!startsWith("\\(", index) && !startsWith("\\[", index)) return false
        if (index > 0 && this[index - 1] == '\\') return false
        if (startsWith("\\[", index)) {
            val lineStart = lastIndexOf('\n', index - 1) + 1
            return substring(lineStart, index).isBlank()
        }
        return (index + 2 until length).none { cursor -> isNewlineBeforeBlankLine(cursor) }
    }

    /** [cursor] 处是换行，且它后面那一行是空行（或只有空白）。 */
    private fun String.isNewlineBeforeBlankLine(cursor: Int): Boolean {
        if (this[cursor] != '\n') return false
        val nextEnd = indexOf('\n', cursor + 1).let { if (it < 0) length else it }
        return substring(cursor + 1, nextEnd).isBlank()
    }

    /** 返回跳过已闭合数学公式后的下一个下标；若命中末尾正在流式输入的未闭合公式则返回 `-1`。 */
    private fun String.consumeMathSpanOrTailAt(index: Int): Int {
        val run = runLengthAt(index, '$')
        if (run > 2) return index + run
        if (run == 2) return consumeDoubleDollarAt(index)

        val prev = getOrNull(index - 1)
        val next = getOrNull(index + 1)
        if (next == null || next.isWhitespace() || prev.isAsciiWordOrDollar()) {
            return index + 1
        }
        return consumeSingleDollarAt(index)
    }

    /** `$$` 开头：找到闭合的 `$$` 时跳过整段（内容为空则只跳过开头），找不到返回 `-1`。 */
    private fun String.consumeDoubleDollarAt(index: Int): Int {
        var cursor = index + 2
        while (cursor + 1 < length) {
            if (this[cursor] == '\\') {
                cursor += 2
                continue
            }
            if (this[cursor] == '$' && this[cursor + 1] == '$' && getOrNull(cursor + 2) != '$') {
                return if (substring(index + 2, cursor).isNotBlank()) cursor + 2 else index + 2
            }
            cursor += 1
        }
        return -1
    }

    /** 能开启行内公式的单个 `$`：往后找闭合的 `$`；遇到空行或配不上就只跳过它，末尾像在输入的公式返回 `-1`。 */
    private fun String.consumeSingleDollarAt(index: Int): Int {
        var cursor = index + 1
        while (cursor < length) {
            if (this[cursor] == '\\') {
                cursor += 2
                continue
            }
            if (isNewlineBeforeBlankLine(cursor)) return index + 1
            if (this[cursor] == '$') return singleDollarCloseAt(index, cursor)
            cursor += 1
        }
        val tail = substring(index + 1)
        return if (looksLikeStreamingFormula(tail)) -1 else index + 1
    }

    /** [cursor] 处的 `$` 能闭合 [index] 处开头时跳过整段公式，否则只跳过开头。 */
    private fun String.singleDollarCloseAt(index: Int, cursor: Int): Int {
        val closeRun = runLengthAt(cursor, '$')
        if (closeRun != 1) return index + 1
        val closePrev = getOrNull(cursor - 1)
        val closeNext = getOrNull(cursor + 1)
        val canClose =
            closePrev != null && !closePrev.isWhitespace() && !closeNext.isAsciiWordOrDollar()
        return if (canClose && substring(index + 1, cursor).isNotBlank()) cursor + 1 else index + 1
    }

    private fun looksLikeStreamingFormula(tail: String): Boolean =
        '\n' !in tail &&
            tail.any { it == '\\' || it == '^' || it == '_' || it == '{' || it == '[' || it == '=' }

    private fun String.endsWithUnescapedDoubleDollar(): Boolean {
        val trimmed = trimEnd(' ', '\t', '\r')
        if (trimmed.length < 2 || !trimmed.endsWith("$$") || trimmed.endsWith("$$$")) return false
        var backslashes = 0
        var cursor = trimmed.length - 3
        while (cursor >= 0 && trimmed[cursor] == '\\') {
            backslashes += 1
            cursor -= 1
        }
        return backslashes % 2 == 0
    }

    private fun Char?.isAsciiWordOrDollar(): Boolean =
        this != null &&
            (this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '$')
}

/** [StreamingGfmProjection] 扫描时遇到末尾还在输入的公式：到此为止。 */
private const val STOP_SCAN = -1
