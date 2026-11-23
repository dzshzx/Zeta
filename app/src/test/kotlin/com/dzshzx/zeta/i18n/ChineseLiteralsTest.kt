package com.dzshzx.zeta.i18n

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 手机版界面上的字都从文字表来（docs/i18n.md）：应用代码（`app/src/main`、`shared/src` 的非测试代码，生成的取字函数除外）里含中文的字符串字面量
 * （注释不算）要么在日志调用、[UiLanguage.forClaude] 里（交给 Claude 的固定用源语言），要么登记在例外清单 [ALLOWED] 里，写明理由。
 * 清单里的条目对不上任何字面量时也失败：字面量改掉了就删掉条目。
 */
class ChineseLiteralsTest {
    @Test
    fun chineseLiteralsAreLoggedSentToClaudeOrListed() {
        val allowed = allowList()
        val literals = sources().flatMap { (path, file) -> chineseLiterals(path, file.readText()) }
        assertTrue("没有扫到任何含中文的字面量，扫描的目录不对？", literals.isNotEmpty())
        val unlisted = literals.filter { literal ->
            !literal.exempt && allowed.none { it.covers(literal) }
        }
        val unused = allowed.filter { entry -> literals.none { !it.exempt && entry.covers(it) } }
        val problems = buildList {
            if (unlisted.isNotEmpty()) {
                add(
                    "这些含中文的字面量要进文字表（app/src/main/locales），或在 $ALLOWED 里登记理由：\n" +
                        unlisted.joinToString("\n") { "  ${it.path}:${it.line}  \"${it.text}\"" },
                )
            }
            if (unused.isNotEmpty()) {
                add(
                    "$ALLOWED 里这些条目对不上任何字面量，删掉它们：\n" +
                        unused.joinToString("\n") { "  ${it.path} | ${it.fragment}" },
                )
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n\n"))
    }

    /** 扫描器本身：注释、字符字面量不算，模板里嵌着的字符串单独算，日志调用和 forClaude 里的标为免查。 */
    @Test
    fun scannerSeesStringsNotComments() {
        val source =
            """
            // 注释里的"中文"
            /* 块注释 /* 嵌套的"中文" */ 还在注释里 */
            val a = "界面上的字"
            val b = '中'
            val c = "带模板 ${'$'}{if (x) "嵌着的" else "x"} 后半"
            val d = ${"\"\"\""}原始字符串
              第二行${"\"\"\""}
            fun f() { DiagLog.info(DiagTopic.App, "日志" + g("也算日志")) }
            fun h() = UiLanguage.forClaude { Strings.foo() + "给 Claude 的" }
            val e = "转义的 \" 引号里的字"
            """
                .trimIndent()
        val found = chineseLiterals("x.kt", source).map { it.text to it.exempt }
        assertTrue(
            found.toString(),
            found ==
                listOf(
                    "界面上的字" to false,
                    "嵌着的" to false,
                    "带模板 \${…} 后半" to false,
                    "原始字符串\n  第二行" to false,
                    "日志" to true,
                    "也算日志" to true,
                    "给 Claude 的" to true,
                    "转义的 \\\" 引号里的字" to false,
                ),
        )
    }

    /** 例外清单的一条：[fragment] 是字面量里的一段（`*` 是整个文件的含中文字面量都算）。 */
    private data class Entry(val path: String, val fragment: String) {
        fun covers(literal: Literal): Boolean =
            literal.path == path && (fragment == "*" || fragment in literal.text)
    }

    private data class Literal(
        val path: String,
        val line: Int,
        /** 引号之间的原文，`${…}` 里的代码换成 `${…}`（里面的字符串单独算）。 */
        val text: String,
        /** 在日志调用或 [UiLanguage.forClaude] 里。 */
        val exempt: Boolean,
    )

    private fun allowList(): List<Entry> =
        File(ROOT, ALLOWED).readLines().mapIndexedNotNull { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
            val first = line.indexOf(" | ")
            val last = line.lastIndexOf(" | ")
            if (first < 0 || last <= first)
                fail("$ALLOWED 第 ${i + 1} 行要写成「路径 | 字面量里的一段 | 理由」：$line")
            val reason = line.substring(last + 3).trim()
            if (reason.isEmpty()) fail("$ALLOWED 第 ${i + 1} 行没写理由：$line")
            Entry(line.substring(0, first).trim(), line.substring(first + 3, last).trim())
        }

    /** 要扫的文件：相对仓根的路径 → 文件。 */
    private fun sources(): List<Pair<String, File>> =
        SCANNED.flatMap { dir ->
                File(ROOT, dir)
                    .walk()
                    .filter { it.isFile && it.extension == "kt" && !it.name.endsWith(".gen.kt") }
                    .map { it.relativeTo(ROOT).invariantSeparatorsPath to it }
                    .toList()
            }
            .sortedBy { it.first }
            .also { assertTrue("$SCANNED 下没有 Kotlin 文件", it.isNotEmpty()) }

    private companion object {
        val ROOT = File("..").canonicalFile
        val SCANNED = listOf("app/src/main", "shared/src/commonMain", "shared/src/androidMain")
        const val ALLOWED = "app/src/test/resources/i18n/chinese-literals-allowed.txt"

        /** 这些调用的参数、这些块里的字面量免查：日志不翻译；forClaude 里的是交给 Claude 的，固定用源语言。 */
        val EXEMPT_CALLS =
            Regex(
                """^(DiagLog\.(log|info|warn|error)|Log\.[vdiwe]|log\.info|UiLanguage\.forClaude)$""",
            )

        fun isChinese(c: Char): Boolean =
            Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN ||
                c in '　'..'〿' ||
                c in '＀'..'￯'

        /** Kotlin 源码 [text] 里含中文的字符串字面量。 */
        fun chineseLiterals(path: String, text: String): List<Literal> =
            KotlinStrings(text)
                .scan()
                .filter { it.text.any(::isChinese) }
                .map {
                    Literal(path, it.line, it.text, it.exempt)
                }
    }

    /** 只认得注释、字符和字符串字面量（含模板）的 Kotlin 扫描器；括号、花括号前的调用名用来判断是不是在日志调用里。 */
    private class KotlinStrings(private val s: String) {
        class Found(val line: Int, val text: String, val exempt: Boolean)

        private var i = 0
        private val found = ArrayList<Found>()

        /** 还开着的 `(`、`{`，各记着它前面的调用名（没有时是空串）。 */
        private val frames = ArrayList<String>()

        fun scan(): List<Found> {
            code(stopAtBrace = false)
            return found.sortedBy { it.line }
        }

        private fun line(at: Int) = s.substring(0, at).count { it == '\n' } + 1

        private fun exempt() = frames.any { EXEMPT_CALLS.matches(it) }

        /** 代码，直到文件结束；[stopAtBrace] 时到配对的 `}`（模板 `${…}` 里）为止。 */
        private fun code(stopAtBrace: Boolean) {
            val depth = frames.size
            while (i < s.length) {
                val c = s[i]
                when {
                    s.startsWith("//", i) ->
                        i = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                    s.startsWith("/*", i) -> blockComment()
                    s.startsWith("\"\"\"", i) -> string(raw = true)
                    c == '"' -> string(raw = false)
                    c == '\'' -> charLiteral()
                    c == '(' || c == '{' -> frames += callee(i++)
                    c == ')' || c == '}' -> if (close(c, stopAtBrace, depth)) return
                    else -> i++
                }
            }
        }

        /** 遇到 `)` 或 `}`：关掉这一层；是模板 `${…}` 的结尾（[stopAtBrace]、回到了进来时的层数）时返回 true。 */
        private fun close(c: Char, stopAtBrace: Boolean, depth: Int): Boolean {
            i++
            if (stopAtBrace && c == '}' && frames.size == depth) return true
            if (frames.size > depth) frames.removeAt(frames.size - 1)
            return false
        }

        /**
         * `(` 或 `{` 前面的名字（`DiagLog.info`、`forClaude`），跳过中间的空白和 `(…)` 参数（`forClaude {` 前面可以没有括号）。
         */
        private fun callee(at: Int): String {
            var j = at - 1
            while (j >= 0 && s[j].isWhitespace()) j--
            val end = j + 1
            while (j >= 0 && (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == '.')) j--
            return s.substring(j + 1, end)
        }

        private fun blockComment() {
            var nesting = 0
            while (i < s.length) {
                when {
                    s.startsWith("/*", i) -> {
                        nesting++
                        i += 2
                    }
                    s.startsWith("*/", i) -> {
                        nesting--
                        i += 2
                        if (nesting == 0) return
                    }
                    else -> i++
                }
            }
        }

        private fun charLiteral() {
            i++
            while (i < s.length && s[i] != '\'') i += if (s[i] == '\\') 2 else 1
            i++
        }

        private fun string(raw: Boolean) {
            val start = i
            val exempt = exempt()
            i += if (raw) 3 else 1
            val text = StringBuilder()
            while (i < s.length) {
                when {
                    raw && s.startsWith("\"\"\"", i) -> {
                        // 结尾可以多出引号："""a"""" 是 a"
                        while (s.startsWith("\"\"\"\"", i)) {
                            text.append('"')
                            i++
                        }
                        i += 3
                        break
                    }
                    !raw && s[i] == '"' -> {
                        i++
                        break
                    }
                    !raw && s[i] == '\\' -> {
                        text.append(s, i, minOf(i + 2, s.length))
                        i += 2
                    }
                    s.startsWith("\${", i) -> {
                        text.append("\${…}")
                        i += 2
                        code(stopAtBrace = true)
                    }
                    else -> text.append(s[i++])
                }
            }
            found += Found(line(start), text.toString(), exempt)
        }
    }
}
