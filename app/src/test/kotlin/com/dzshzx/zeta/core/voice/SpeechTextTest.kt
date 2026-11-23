package com.dzshzx.zeta.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    private fun read(markdown: String) = SpeechText.readable(markdown.trimIndent())

    @Test
    fun headingsAndParagraphsBecomeSentences() {
        assertEquals(
            "安装步骤。\n先装好 Go，再运行构建。\nDone.",
            read(
                """
                ## 安装步骤

                先装好 **Go**，再运行*构建*

                Done
                """,
            ),
        )
    }

    @Test
    fun codeBlocksAndTablesAreSkippedWithANote() {
        val text =
            read(
                """
            改动如下：

            ```kotlin
            fun main() = println("hi")
            ```

            | 列 | 值 |
            | --- | --- |
            | a | 1 |

            就这些。
            """,
            )
        assertEquals("改动如下：\n代码略。\n表格略。\n就这些。", text)
        assertFalse("println" in text)
    }

    @Test
    fun displayMathIsSkippedInlineMathKeptWhenSimple() {
        assertEquals(
            "面积是 a*b。\n公式略。\n其中公式是常数。",
            read(
                """
                面积是 ${'$'}a*b${'$'}

                ${'$'}${'$'}
                E = mc^2
                ${'$'}${'$'}

                其中 ${'$'}\alpha${'$'} 是常数。
                """,
            ),
        )
    }

    @Test
    fun linksReadTheirTextAndUrlsBecomeAWord() {
        assertEquals(
            "详见官方文档，或者看链接。",
            read("详见[官方文档](https://example.com/docs)，或者看 https://example.com/x?y=1 。"),
        )
        // 链接文字本身就是网址时只念“链接”
        assertEquals("打开链接。", read("打开 [https://example.com](https://example.com) 。"))
        assertEquals("见链接。", read("见 <https://example.com>。"))
    }

    @Test
    fun imagesAndHtmlAreSilent() {
        assertEquals("截图在上面。", read("![截图](a.png)截图在上面<br>"))
    }

    @Test
    fun listItemsGetTheirOwnLinesAndCheckboxesAreSpoken() {
        assertEquals(
            "第一步：备份。\n第二步：升级。\n已完成，写测试。\n未完成，发版。",
            read(
                """
                1. 第一步：备份
                2. 第二步：升级

                - [x] 写测试
                - [ ] 发版
                """,
            ),
        )
    }

    @Test
    fun inlineCodeShortIsKeptLongIsSummarised() {
        assertEquals("运行 go test 就行。", read("运行 `go test` 就行。"))
        assertEquals("运行代码就行。", read("运行 `GOFLAGS=-mod=mod go test -run TestEverything ./...` 就行。"))
    }

    @Test
    fun softLineBreaksInsideChineseDoNotAddSpaces() {
        assertEquals("这一句跨了两行。", read("这一句跨了\n两行。"))
    }

    @Test
    fun quotesAndEmphasisMarkersAreDropped() {
        assertEquals("引用的话。\n删掉的字还在。", read("> 引用的话\n\n~~删掉的字~~还在。"))
    }

    @Test
    fun blankInputGivesBlankOutput() {
        assertEquals("", read("```\ncode\n```".replace("code", "")).replace("Code omitted.", ""))
        assertEquals("", read("   "))
    }

    /** 补的话跟着回答的语言：没有汉字的回答念英文的，不看界面语言。 */
    @Test
    fun englishAnswersGetEnglishPlaceholders() {
        assertEquals(
            "Run this:\nCode omitted.\nTable omitted.\nSee link.\nDone: write tests.\nNot done: ship it.",
            read(
                """
                Run this:

                ```
                go test
                ```

                | a | b |
                | --- | --- |
                | 1 | 2 |

                See https://example.com/x

                - [x] write tests
                - [ ] ship it
                """,
            ),
        )
    }

    @Test
    fun chunksMergeShortLinesAndSplitLongOnesAtSentenceEnds() {
        assertEquals(listOf("一。\n二。"), SpeechText.chunks("一。\n二。", limit = 10))
        val long = "甲".repeat(6) + "。" + "乙".repeat(6) + "。"
        val pieces = SpeechText.chunks(long, limit = 10)
        assertEquals(listOf("甲甲甲甲甲甲。", "乙乙乙乙乙乙。"), pieces)
        assertTrue(pieces.all { it.length <= 10 })
    }

    @Test
    fun chunksHardSplitWithoutPunctuationAndKeepSurrogatePairs() {
        val emoji = "😀".repeat(8) // 16 个 char
        val pieces = SpeechText.chunks(emoji, limit = 5)
        assertEquals(emoji, pieces.joinToString(""))
        assertTrue(pieces.all { it.length <= 5 && !it.last().isHighSurrogate() })
    }
}
