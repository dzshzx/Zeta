package com.dzshzx.zeta.ui.markdown

/**
 * 模型常用 LaTeX 的 `\(...\)` 与 `\[...\]` 写公式，GFM 不认识它们，反斜杠会被当作转义吃掉， 公式里的 `_`、`\{` 也会被改写。解析前把成对的定界符等长替换为
 * GFM 已支持的 `$$`： 源码偏移不变，块缓存、reveal key 和流式快照都不受影响。
 *
 * 只替换能确认的公式：跳过代码块与行内代码，不跨空行；`\[...\]` 必须独占一行， 以免把 Markdown 转义的方括号（如 `arr\[0\]`）误判为公式。未闭合的定界符保持原文。
 */
internal object TexMathDelimiters {
    fun normalize(source: String): String {
        if ("\\(" !in source && "\\[" !in source) return source
        val output = StringBuilder(source)
        var fence: String? = null
        var lineStart = 0
        var index = 0
        while (index < source.length) {
            // 先消费换行，保证围栏分支跳到行尾时游标一定前进。
            if (source[index] == '\n') {
                index += 1
                lineStart = index
                continue
            }

            if (index == lineStart) {
                val lineEnd = source.lineEndFrom(index)
                val open = fence
                fence = fenceAfterLine(open, source.substring(index, lineEnd))
                // 围栏的开头、结尾和里面的行整行跳过
                if (open != null || fence != null) {
                    index = lineEnd
                    continue
                }
            }
            index = normalizeAt(source, output, index, lineStart)
        }
        return output.toString()
    }

    /** 看完这一行后开着的围栏：没开着时这一行可能开启一个，开着时只有同种、不短于开头、后面没别的字的记号才闭合它。 */
    private fun fenceAfterLine(open: String?, line: String): String? {
        val marker = fenceMarker(line)
        if (open == null) return marker
        val closes =
            marker != null &&
                marker.first() == open.first() &&
                marker.length >= open.length &&
                line.trim() == marker
        return if (closes) null else open
    }

    /** 处理 [index] 处的一个字符（围栏外），能配对的定界符就地替换，返回下一个下标。 */
    private fun normalizeAt(
        source: String,
        output: StringBuilder,
        index: Int,
        lineStart: Int,
    ): Int {
        val char = source[index]
        return when {
            char == '`' -> {
                val run = source.runLength(index, '`')
                codeSpanEnd(source, index + run, run) ?: (index + run)
            }
            char == '\\' && source.getOrNull(index + 1) == '(' -> {
                val close = findClose(source, index + 2, ')', allowBlankLines = false)
                output.replaceIfClosed(index, close)
            }
            char == '\\' &&
                source.getOrNull(index + 1) == '[' &&
                source.isLineStart(lineStart, index) -> {
                val close =
                    findClose(source, index + 2, ']', allowBlankLines = true)?.takeIf {
                        source.isLineEnd(it + 2)
                    }
                output.replaceIfClosed(index, close)
            }
            // 其余反斜杠转义（包括 `\\`）整体跳过，避免 `\\(` 被当成定界符。
            char == '\\' -> index + 2
            else -> index + 1
        }
    }

    /** 找到闭合的定界符时把两端换成 `$$` 并跳过整段，否则只跳过开头的两个字符。 */
    private fun StringBuilder.replaceIfClosed(open: Int, close: Int?): Int {
        if (close == null) return open + 2
        replaceDelimiters(open, close)
        return close + 2
    }

    /** 寻找未转义的 `\` + [closer]；行内公式不跨空行，块级公式不跨入代码围栏，内容不能为空白。 */
    private fun findClose(source: String, from: Int, closer: Char, allowBlankLines: Boolean): Int? {
        var index = from
        while (index < source.length - 1) {
            when {
                source[index] == '\n' -> {
                    if (endsSearchAtNewline(source, index, allowBlankLines)) return null
                    index += 1
                }
                source[index] == '\\' && source[index + 1] == closer ->
                    return index.takeIf { source.substring(from, index).isNotBlank() }
                source[index] == '\\' -> index += 2
                else -> index += 1
            }
        }
        return null
    }

    /** [newline] 处的换行之后是空行（[allowBlankLines] 为 false 时）或代码围栏：公式到此为止。 */
    private fun endsSearchAtNewline(
        source: String,
        newline: Int,
        allowBlankLines: Boolean,
    ): Boolean {
        if (!allowBlankLines && source.isBlankLineAfter(newline)) return true
        val nextLineStart = newline + 1
        val nextLineEnd =
            source.indexOf('\n', nextLineStart).let { if (it < 0) source.length else it }
        return fenceMarker(source.substring(nextLineStart, nextLineEnd)) != null
    }

    /** 行内代码以等长反引号串闭合；找不到时开头的反引号只是普通字符。 */
    private fun codeSpanEnd(source: String, from: Int, run: Int): Int? {
        var index = from
        while (index < source.length) {
            when {
                source[index] == '\n' && source.isBlankLineAfter(index) -> return null
                source[index] == '`' -> {
                    val length = source.runLength(index, '`')
                    if (length == run) return index + length
                    index += length
                }
                else -> index += 1
            }
        }
        return null
    }

    private fun StringBuilder.replaceDelimiters(open: Int, close: Int) {
        replace(open, open + 2, "$$")
        replace(close, close + 2, "$$")
    }

    private fun fenceMarker(line: String): String? {
        val trimmed = line.trimStart(' ')
        if (line.length - trimmed.length > 3) return null
        val char = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val run = trimmed.runLength(0, char)
        return if (run >= 3) char.toString().repeat(run) else null
    }

    private fun String.runLength(start: Int, char: Char): Int {
        var end = start
        while (end < length && this[end] == char) end += 1
        return end - start
    }

    private fun String.isLineStart(lineStart: Int, index: Int): Boolean =
        substring(lineStart, index).isBlank()

    /** 从 [index] 起这一行的行尾（换行符的位置，或文末）。 */
    private fun String.lineEndFrom(index: Int): Int =
        indexOf('\n', index).let { if (it < 0) length else it }

    private fun String.isLineEnd(index: Int): Boolean {
        val lineEnd = indexOf('\n', index).let { if (it < 0) length else it }
        return substring(index, lineEnd).isBlank()
    }

    private fun String.isBlankLineAfter(newline: Int): Boolean {
        val nextEnd = indexOf('\n', newline + 1).let { if (it < 0) length else it }
        return substring(newline + 1, nextEnd).isBlank()
    }
}
