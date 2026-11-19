package com.dzshzx.zeta.ui.chat

import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.ui.markdown.MarkdownInlineStyle
import com.dzshzx.zeta.ui.markdown.StreamingGfmParserSession
import com.dzshzx.zeta.ui.markdown.findOccurrences
import com.dzshzx.zeta.ui.markdown.findSegments
import com.dzshzx.zeta.ui.markdown.staticMarkdownDocumentCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFindTest {
    @Test
    fun occurrencesIgnoreCaseAndDoNotOverlap() {
        assertEquals(listOf(0..4, 6..10, 12..16), findOccurrences("Hello hello HELLO", "hello"))
        assertEquals(listOf(0..1, 2..3), findOccurrences("aaaaa", "aa"))
        assertEquals(listOf(1..1, 2..2), findOccurrences("今天天气", "天"))
        assertEquals(emptyList<IntRange>(), findOccurrences("abc", ""))
        assertEquals(emptyList<IntRange>(), findOccurrences("ab", "abc"))
    }

    @Test
    fun matchesFollowSegmentOrder() {
        val segments = listOf(
            FindSegment("u1", 0, "查一下 Zeta 的日志"),
            FindSegment("a1", 0, "zeta 的日志在这里"),
            FindSegment("a1", 12, "没有别的 ZETA"),
        )
        assertEquals(
            listOf(FindMatch("u1", 0, 4, 8), FindMatch("a1", 0, 0, 4), FindMatch("a1", 12, 5, 9)),
            ChatFind.matches(segments, ChatFind.normalize("  zeta ")),
        )
        assertEquals(emptyList<FindMatch>(), ChatFind.matches(segments, ""))
    }

    @Test
    fun stepWrapsAround() {
        assertEquals(0, ChatFind.step(2, 3, 1))
        assertEquals(2, ChatFind.step(0, 3, -1))
        assertEquals(1, ChatFind.step(0, 3, 1))
        assertEquals(-1, ChatFind.step(0, 0, 1))
    }

    @Test
    fun reselectKeepsPlace() {
        val order = mapOf("u1" to 0, "a1" to 1, "u2" to 2, "a2" to 3)
        val matches = listOf(FindMatch("u1", 0, 0, 2), FindMatch("a1", 5, 3, 5), FindMatch("a2", 0, 1, 3))
        // 第一次从最后一处（最新的）开始
        assertEquals(2, ChatFind.reselect(null, matches, order))
        // 原来那处还在：停在它上面
        assertEquals(1, ChatFind.reselect(FindMatch("a1", 5, 3, 5), matches, order))
        // 多打了一个字，区间变长了：还停在同一个起点
        val longer = listOf(FindMatch("u1", 0, 0, 3), FindMatch("a1", 5, 3, 6))
        assertEquals(1, ChatFind.reselect(FindMatch("a1", 5, 3, 5), longer, order))
        // 原来那处没了：停在它后面最近的一处
        assertEquals(2, ChatFind.reselect(FindMatch("u2", 0, 0, 2), matches, order))
        // 后面没有了：停在最后一处
        assertEquals(1, ChatFind.reselect(FindMatch("a2", 0, 9, 11), longer, order))
        // 原来那条消息被删了
        assertEquals(2, ChatFind.reselect(FindMatch("gone", 0, 0, 2), matches, order))
        assertEquals(-1, ChatFind.reselect(FindMatch("u1", 0, 0, 2), emptyList(), order))
    }

    @Test
    fun stateMovesAndCountsJumps() {
        val state = ChatFindState()
        val order = mapOf("u1" to 0, "a1" to 1)
        val matches = listOf(FindMatch("u1", 0, 0, 2), FindMatch("a1", 0, 3, 5))
        state.update("ab", matches, order)
        assertEquals(1, state.index)
        assertEquals(1, state.jumps)
        assertTrue(state.isCurrent("a1", 0, 3))
        // 只是刷新（来了新消息），当前那处没变：不跳
        state.update("ab", matches + FindMatch("a1", 9, 0, 2), order)
        assertEquals(1, state.index)
        assertEquals(1, state.jumps)
        state.next()
        assertEquals(2, state.index)
        state.next()
        assertEquals(0, state.index)
        state.previous()
        assertEquals(2, state.index)
        assertEquals(4, state.jumps)
        state.close()
        assertEquals(-1, state.index)
        assertEquals("", state.query)
    }

    /** 查的是排版后的文字：Markdown 记号、链接地址不算，各块按画出来的顺序。 */
    @Test
    fun markdownSegmentsAreRenderedText() {
        val source = "# 标题\n\n**粗体** 和 [链接](https://example.com/zeta) 文字\n\n- 第一项\n- 第二项\n\n> 引用 zeta\n\n```\ncode zeta\n```\n\n| a | b |\n|---|---|\n| 1 | zeta |\n"
        val document = StreamingGfmParserSession().parse(source, isComplete = true, style = MarkdownInlineStyle.Default).document
        val texts = document.findSegments().map { it.second }
        assertEquals(listOf("标题", "粗体 和 链接 文字", "第一项", "第二项", "引用 zeta", "code zeta", "a", "b", "1", "zeta"), texts)
        val offsets = document.findSegments().map { it.first }
        assertEquals(offsets.size, offsets.toSet().size)
        assertEquals(3, ChatFind.matches(document.findSegments().map { FindSegment("a", it.first, it.second) }, "zeta").size)
    }

    /** 往前接上一页（对话内查找时把整个对话载完）：命中多了、下标往后挪，但停着的还是同一处，不跳。 */
    @Test
    fun aPrependedPageKeepsTheCurrentMatch() {
        val state = ChatFindState()
        val order = mapOf("u1" to 0, "a1" to 1)
        val matches = listOf(FindMatch("u1", 0, 0, 2), FindMatch("a1", 0, 3, 5))
        state.update("ab", matches, order)
        assertEquals(1, state.index)
        assertEquals(1, state.jumps)
        val longerOrder = mapOf("p-u" to 0, "p-a" to 1, "u1" to 2, "a1" to 3)
        state.update("ab", listOf(FindMatch("p-a", 0, 0, 2), FindMatch("p-a", 0, 7, 9)) + matches, longerOrder)
        assertEquals(3, state.index)
        assertEquals(FindMatch("a1", 0, 3, 5), state.current)
        assertEquals(1, state.jumps)
        // 往上走得到刚接上的那页
        state.previous()
        state.previous()
        assertEquals(FindMatch("p-a", 0, 7, 9), state.current)
    }

    /** 查找的可查文字按消息记下：同一段回答不重解析，也不进回答显示共用的缓存；改了的重算，不在列表里的丢掉。 */
    @Test
    fun answerSegmentsAreKeptPerMessage() {
        val segments = FindSegments(MarkdownInlineStyle.Default)
        val answer = AnswerItem("a1", "**粗体** findme 段落", streaming = false)
        val items = listOf(UserItem("u1", "问 findme"), answer, AnswerItem("a2", "还在写 findme", streaming = true))
        val first = segments.of(items) { it }
        assertEquals(listOf("u1", "a1"), first.map { it.itemId })
        assertEquals("粗体 findme 段落", first[1].text)
        assertNull(staticMarkdownDocumentCache.get(answer.text, MarkdownInlineStyle.Default))
        // 再算一遍：同一段回答拿的是记下的那份
        assertSame(first[1], segments.of(items) { it }[1])
        // 改了的（编辑后同一项换了内容）重算
        val edited = listOf(items[0], answer.copy(text = "换了 findme"))
        assertEquals("换了 findme", segments.of(edited) { it }[1].text)
    }
}
