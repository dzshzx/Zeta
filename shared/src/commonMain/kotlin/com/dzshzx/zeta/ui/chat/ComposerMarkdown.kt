package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import com.dzshzx.zeta.ui.components.MonoFont
import com.dzshzx.zeta.ui.markdown.MarkdownStyle
import com.dzshzx.zeta.ui.markdown.TexMathDelimiters

/**
 * 输入框里的 Markdown 显示样式：标记符号原样留在文字里、颜色调淡，标记包住的内容按 Markdown 显示（加粗、斜体、删除线、行内代码、代码块、
 * 标题、引用、列表）。只改显示，发出去的还是输入的原文。认的写法是常用的一小部分，按行扫描；代码围栏和行内代码的认法同回答的渲染。
 *
 * 桌面版用的是 Go 的同一套算法（`client/mdmarks`），两边都跑 `client/mdmarks/testdata/cases.json`；改规则时两边一起改，用例加在那份文件里。
 */
enum class ComposerMark {
    Marker,
    Bold,
    Italic,
    Strike,
    Code,
    CodeBlock,
    Heading,
    Quote,
    Bullet,
}

/** 一段要加样式的文字，[start]、[end] 按 UTF-16 下标算，左闭右开。 */
data class ComposerSpan(val mark: ComposerMark, val start: Int, val end: Int)

private val HeadingPrefix = Regex("^ {0,3}(#{1,6})( |$)")
private val QuotePrefix = Regex("^ {0,3}>")
private val BulletPrefix = Regex("""^\s*([-*+]|\d{1,9}[.)]) """)

// 加粗排在斜体前，`**a *b* c**` 里的单星号才认成里面的斜体
private val Delims =
    listOf(
        "**" to ComposerMark.Bold,
        "__" to ComposerMark.Bold,
        "~~" to ComposerMark.Strike,
        "*" to ComposerMark.Italic,
        "_" to ComposerMark.Italic,
    )

/** 扫出整段文字里要加样式的区间（不排序）。区间可以重叠（加粗里套斜体、标记符号在加粗区间里面）：显示时先叠内容的样式，最后叠 Marker。 */
fun composerMarkdownSpans(text: String): List<ComposerSpan> {
    val out = mutableListOf<ComposerSpan>()
    var fence: String? = null
    var offset = 0
    for (line in text.split('\n')) {
        val end = offset + line.length
        // 代码块整行算，开、关围栏的那两行整行调淡；还没写闭合围栏时一直算到末尾（正在输入）
        val next = TexMathDelimiters.fenceAfterLine(fence, line)
        when {
            next != fence -> {
                out += ComposerSpan(ComposerMark.CodeBlock, offset, end)
                out += ComposerSpan(ComposerMark.Marker, offset, end)
            }
            fence != null -> out += ComposerSpan(ComposerMark.CodeBlock, offset, end)
            else -> lineSpans(line, offset, out)
        }
        fence = next
        offset = end + 1
    }
    return out
}

/** 代码块外的一行：行首的标题、引用、列表记号，再是行内代码和强调。 */
private fun lineSpans(line: String, offset: Int, out: MutableList<ComposerSpan>) {
    HeadingPrefix.find(line)?.let {
        out += ComposerSpan(ComposerMark.Heading, offset, offset + line.length)
        out += ComposerSpan(ComposerMark.Marker, offset, offset + it.groups[1]!!.range.last + 1)
    }
    QuotePrefix.find(line)?.let {
        out += ComposerSpan(ComposerMark.Quote, offset, offset + line.length)
        out += ComposerSpan(ComposerMark.Marker, offset, offset + it.range.last + 1)
    }
    BulletPrefix.find(line)?.groups?.get(1)?.let {
        out +=
            ComposerSpan(ComposerMark.Bullet, offset + it.range.first, offset + it.range.last + 1)
    }
    // 行内代码占着的字：代码里的星号、下划线是原文
    val busy = BooleanArray(line.length)
    for ((s, e, run) in codeSpans(line)) {
        for (i in s until e) busy[i] = true
        out += ComposerSpan(ComposerMark.Code, offset + s, offset + e)
        out += ComposerSpan(ComposerMark.Marker, offset + s, offset + s + run)
        out += ComposerSpan(ComposerMark.Marker, offset + e - run, offset + e)
    }
    for ((mark, kind) in Delims) {
        for ((s, e) in emphasis(line, busy, mark)) {
            out += ComposerSpan(kind, offset + s, offset + e)
            out += ComposerSpan(ComposerMark.Marker, offset + s, offset + s + mark.length)
            out += ComposerSpan(ComposerMark.Marker, offset + e - mark.length, offset + e)
        }
    }
}

/** 一行里的行内代码（同回答的渲染）：以等长的反引号串闭合，返回 (起点, 终点, 反引号个数)；找不到闭合时开头的反引号只是字。 */
private fun codeSpans(line: String): List<Triple<Int, Int, Int>> {
    val found = mutableListOf<Triple<Int, Int, Int>>()
    var i = 0
    while (i < line.length) {
        if (line[i] != '`') {
            i++
            continue
        }
        val run = runOf(line, i, '`')
        val close = codeSpanClose(line, i + run, run)
        if (close >= 0) found += Triple(i, close + run, run)
        i = if (close >= 0) close + run else i + run
    }
    return found
}

/** 从 [from] 起和开头一样长的反引号串的位置，没有时 -1。 */
private fun codeSpanClose(line: String, from: Int, run: Int): Int {
    var j = from
    while (j < line.length) {
        val n = runOf(line, j, '`')
        if (n == run) return j
        j += maxOf(n, 1)
    }
    return -1
}

private fun runOf(line: String, start: Int, char: Char): Int {
    var end = start
    while (end < line.length && line[end] == char) end++
    return end - start
}

/**
 * 找一种强调写法 [mark] 包住的各段（含两头的标记），返回 [起点, 终点)。开头的标记后面紧跟非空白，结尾的标记前面紧跟非空白； 标记前后不能再是同一个符号（`**` 不当成两个
 * `*`），也不能是反斜杠转义；下划线两边贴着字母数字的不算（snake_case）。碰到行内代码的不算。
 */
private fun emphasis(line: String, busy: BooleanArray, mark: String): List<Pair<Int, Int>> {
    val found = mutableListOf<Pair<Int, Int>>()
    val w = mark.length
    var i = 0
    while (i + w < line.length) {
        if (!opens(line, busy, mark, i)) {
            i++
            continue
        }
        var j = i + w + 1
        while (j + w <= line.length && !busy[j] && !closes(line, mark, j)) j++
        val closed = j + w <= line.length && !busy[j]
        if (closed) found += i to j + w
        // 没找到结尾时，i 到 j 之间的开头往后找的范围都在这一段里，也找不到：直接跳过去，整行只扫一遍
        i = if (closed) j + w else j
    }
    return found
}

private fun opens(line: String, busy: BooleanArray, mark: String, i: Int): Boolean {
    if (!line.startsWith(mark, i) || busy[i]) return false
    val c = mark[0]
    val prev = line.getOrNull(i - 1)
    if (prev != null && (prev == c || prev == '\\' || c == '_' && prev.isLetterOrDigit()))
        return false
    val next = line[i + mark.length]
    return !next.isWhitespace() && next != c
}

private fun closes(line: String, mark: String, j: Int): Boolean {
    if (!line.startsWith(mark, j)) return false
    val c = mark[0]
    val prev = line[j - 1]
    if (prev.isWhitespace() || prev == c || prev == '\\') return false
    val next = line.getOrNull(j + mark.length) ?: return true
    return next != c && !(c == '_' && next.isLetterOrDigit())
}

/**
 * 接到输入框 `outputTransformation` 上；只加样式、不改文字，所以光标和选区不受影响。颜色取回答的 Markdown 样式 [answer]： 标记符号用它的
 * markerColor，引用用次要色，行内代码的底色和列表记号的颜色同回答的行内代码、链接。
 */
class ComposerMarkdownStyle(answer: MarkdownStyle) : OutputTransformation {
    private val code = SpanStyle(fontFamily = MonoFont, fontSize = 0.9.em)
    private val styles =
        mapOf(
            ComposerMark.Marker to
                SpanStyle(color = answer.markerColor, fontWeight = FontWeight.Normal),
            ComposerMark.Bold to SpanStyle(fontWeight = FontWeight.Bold),
            ComposerMark.Italic to SpanStyle(fontStyle = FontStyle.Italic),
            ComposerMark.Strike to SpanStyle(textDecoration = TextDecoration.LineThrough),
            ComposerMark.Code to code.copy(background = answer.inline.codeBackground),
            // 代码块按行铺底色会参差不齐，只换等宽字
            ComposerMark.CodeBlock to code,
            ComposerMark.Heading to SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.1.em),
            ComposerMark.Quote to SpanStyle(color = answer.secondaryColor),
            ComposerMark.Bullet to
                SpanStyle(color = answer.inline.linkColor, fontWeight = FontWeight.Bold),
        )

    /** 上次扫过的文字和按叠放先后排好的区间：只动了光标、选区时文字没变，不重扫（桌面版 `draftMarks.of` 同样）。 */
    private var lastText: String? = null
    private var lastSpans: List<ComposerSpan> = emptyList()

    private fun spansOf(text: String): List<ComposerSpan> {
        if (text != lastText) {
            // 标记符号最后叠，压过它所在的加粗、标题
            val (markers, content) =
                composerMarkdownSpans(text).partition { it.mark == ComposerMark.Marker }
            lastText = text
            lastSpans = content + markers
        }
        return lastSpans
    }

    override fun TextFieldBuffer.transformOutput() {
        for (span in spansOf(originalText.toString())) {
            if (span.start < span.end) addStyle(styles.getValue(span.mark), span.start, span.end)
        }
    }
}
