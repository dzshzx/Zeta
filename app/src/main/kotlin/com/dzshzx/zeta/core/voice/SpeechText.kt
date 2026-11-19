package com.dzshzx.zeta.core.voice

import com.dzshzx.zeta.ui.markdown.ChatGfmFlavourDescriptor
import com.dzshzx.zeta.ui.markdown.TexMathDelimiters
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * 把回答的 Markdown 换成适合朗读的文字。纯函数，单元测试覆盖。
 *
 * - 代码块、表格、块级公式不念内容，念一句“代码略”“表格略”“公式略”，听的人知道这里跳过了东西；
 * - 链接只念文字，网址（链接地址、裸网址）念成“链接”；图片、HTML、分隔线不念；
 * - 标题、段落、列表项各占一行，结尾没有标点的补一个句号，念的时候有停顿；勾选框念“已完成”“未完成”；
 * - 行内代码短的照念（多是命令名、文件名），长的念“代码”；行内公式短的照念，带命令的念“公式”。
 *
 * 解析用聊天页同一套 GFM 扩展（[ChatGfmFlavourDescriptor]），公式的认法和界面上一致。
 */
object SpeechText {
    fun readable(markdown: String): String {
        val source = TexMathDelimiters.normalize(markdown)
        val root = MarkdownParser(ChatGfmFlavourDescriptor(originalSource = markdown)).buildMarkdownTreeFromString(source)
        val lines = mutableListOf<String>()
        Reader(source, lines).blocks(root)
        return lines.joinToString("\n")
    }

    /**
     * 切成一段段交给朗读引擎（引擎一次收的字数有上限）：尽量按行合并到 [limit] 字以内，
     * 单行太长时在句末标点处断开，实在没有标点才硬切，不切开代理对。
     */
    fun chunks(text: String, limit: Int = 800): List<String> {
        require(limit >= 2)
        val pieces = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.flatMap { split(it, limit) }
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            if (current.isNotEmpty() && current.length + 1 + piece.length > limit) {
                result += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(piece)
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private fun split(line: String, limit: Int): List<String> {
        val result = mutableListOf<String>()
        var rest = line
        while (rest.length > limit) {
            var end = rest.take(limit).indexOfLast { it in SENTENCE_ENDS } + 1
            if (end < limit / 2) end = limit
            if (rest[end - 1].isHighSurrogate()) end--
            result += rest.substring(0, end).trim()
            rest = rest.substring(end).trim()
        }
        if (rest.isNotEmpty()) result += rest
        return result
    }

    private class Reader(private val source: String, private val lines: MutableList<String>) {
        /** 下一行前面要加的话（勾选框）。 */
        private var prefix = ""

        fun blocks(node: ASTNode) {
            when (node.type) {
                MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK -> line("代码略")
                GFMElementTypes.TABLE -> line("表格略")
                GFMElementTypes.BLOCK_MATH -> line("公式略")
                MarkdownElementTypes.HTML_BLOCK, MarkdownElementTypes.LINK_DEFINITION, MarkdownTokenTypes.HORIZONTAL_RULE -> Unit
                GFMTokenTypes.CHECK_BOX -> prefix = checkBox(node)
                in TEXT_BLOCKS -> line(inline(node))
                else -> node.children.forEach(::blocks)
            }
        }

        private fun line(text: String) {
            val clean = tidy(text)
            if (clean.isEmpty()) return
            lines += sentence(prefix + clean)
            prefix = ""
        }

        private fun inline(node: ASTNode): String = buildString { appendInline(node) }

        private fun StringBuilder.appendInline(node: ASTNode) {
            when (node.type) {
                MarkdownElementTypes.CODE_SPAN -> {
                    val code = text(node).trim('`').trim()
                    append(if (code.length <= SHORT_CODE && !URL.containsMatchIn(code)) code else "代码")
                }
                GFMElementTypes.INLINE_MATH -> {
                    val math = text(node).trim('$').trim()
                    append(if (math.length <= SHORT_CODE && '\\' !in math) math else "公式")
                }
                MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
                    val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                        ?: node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
                    val words = label?.children
                        ?.filterNot { it.type == MarkdownTokenTypes.LBRACKET || it.type == MarkdownTokenTypes.RBRACKET }
                        ?.joinToString("") { inline(it) }
                        .orEmpty().let(::withoutUrls).trim()
                    append(" ").append(words.ifEmpty { "链接" }).append(" ")
                }
                MarkdownElementTypes.AUTOLINK, GFMTokenTypes.GFM_AUTOLINK -> append(" 链接 ")
                GFMTokenTypes.CHECK_BOX -> append(checkBox(node))
                MarkdownTokenTypes.EOL, MarkdownTokenTypes.HARD_LINE_BREAK -> append(' ')
                in SILENT -> Unit
                else -> if (node.children.isEmpty()) append(source, node.startOffset, node.endOffset) else node.children.forEach { appendInline(it) }
            }
        }

        private fun checkBox(node: ASTNode) = if (text(node).contains('x', ignoreCase = true)) "已完成，" else "未完成，"

        private fun text(node: ASTNode) = source.substring(node.startOffset, node.endOffset)
    }

    /** 收拾一行：裸网址换成“链接”，空白并成一个，中文字之间的空格去掉。 */
    private fun tidy(text: String): String = withoutUrls(text)
        .replace(SPACES, " ")
        .replace(CJK_GAP) { it.groupValues[1] + it.groupValues[2] }
        .replace(CJK_GAP) { it.groupValues[1] + it.groupValues[2] }
        .trim()

    /** 结尾不是标点时补一个句号：有汉字的行补中文句号，纯外文的补英文句点。 */
    private fun sentence(line: String): String {
        val last = line.last()
        if (!last.isLetterOrDigit() && last !in "）)》」』】\"”’'") return line
        return if (HAN.containsMatchIn(line)) "$line。" else "$line."
    }

    private fun withoutUrls(text: String): String = URL.replace(text, " 链接 ")

    private val TEXT_BLOCKS: Set<IElementType> = setOf(
        MarkdownElementTypes.PARAGRAPH,
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2,
    )

    /** 行内不念的记号。 */
    private val SILENT: Set<IElementType> = setOf(
        MarkdownElementTypes.IMAGE,
        MarkdownTokenTypes.HTML_TAG,
        MarkdownTokenTypes.ATX_HEADER,
        MarkdownTokenTypes.SETEXT_1, MarkdownTokenTypes.SETEXT_2,
        MarkdownTokenTypes.BLOCK_QUOTE,
        MarkdownTokenTypes.BACKTICK,
        MarkdownTokenTypes.EMPH,
        GFMTokenTypes.TILDE,
    )

    private const val SHORT_CODE = 24
    private const val SENTENCE_ENDS = "。！？；.!?;，,"

    private val URL = Regex("""(?:https?://|www\.)[^\s<>"'`，。！？；：、（）【】《》“”‘’]+""", RegexOption.IGNORE_CASE)
    private val HAN = Regex("""\p{IsHan}""")
    private val SPACES = Regex("""\s+""")
    private const val CJK = """\p{IsHan}\p{InCJKSymbolsAndPunctuation}\p{InHalfwidthAndFullwidthForms}"""
    private val CJK_GAP = Regex("""([$CJK]) +([$CJK])""")
}
