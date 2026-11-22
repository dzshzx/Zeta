package com.dzshzx.zeta.ui.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.dzshzx.zeta.ui.markdown.TextFinder
import com.dzshzx.zeta.ui.markdown.findOccurrences

/** 对话内查找的一段文字。[block] 区分同一条消息里的几段：Markdown 的文字块用源码偏移，纯文本用 0。 */
data class FindSegment(val itemId: String, val block: Int, val text: String)

/** 一处命中：[start] 到 [end]（不含）是它在那段文字里的区间。 */
data class FindMatch(val itemId: String, val block: Int, val start: Int, val end: Int)

/** 对话内查找的纯逻辑：匹配、上下切换、命中列表变了之后停在哪。 */
object ChatFind {
    /** 查找词去掉首尾空白（输入法常带上的空格不算）。 */
    fun normalize(query: String): String = query.trim()

    /** 各段里的命中，按段的顺序、段内从前往后。 */
    fun matches(segments: List<FindSegment>, query: String): List<FindMatch> =
        if (query.isEmpty()) emptyList()
        else
            segments.flatMap { segment ->
                findOccurrences(segment.text, query).map {
                    FindMatch(segment.itemId, segment.block, it.first, it.last + 1)
                }
            }

    /** 从 [index] 走 [delta] 步，两头接上。 */
    fun step(index: Int, count: Int, delta: Int): Int =
        if (count <= 0) -1 else Math.floorMod(index + delta, count)

    /**
     * 命中列表换了以后停在哪一处（下标，没有命中是 -1）。[previous] 还在就停在它上面；不在了（比如查找词多打了一个字）
     * 就停在它所在位置之后最近的一处，后面没有了停在最后一处；之前没选中过的从最后一处开始：聊天里最新的内容在底部。 [order] 是各条消息在列表里的位置。
     */
    fun reselect(previous: FindMatch?, matches: List<FindMatch>, order: Map<String, Int>): Int {
        if (matches.isEmpty()) return -1
        if (previous == null) return matches.lastIndex
        val same = matches.indexOf(previous)
        if (same >= 0) return same
        val from = position(previous, order) ?: return matches.lastIndex
        val after = matches.indexOfFirst { match ->
            position(match, order)?.let { compare(it, from) >= 0 } == true
        }
        return if (after >= 0) after else matches.lastIndex
    }

    private fun position(match: FindMatch, order: Map<String, Int>): Triple<Int, Int, Int>? =
        order[match.itemId]?.let { Triple(it, match.block, match.start) }

    private fun compare(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int =
        compareValuesBy(a, b, { it.first }, { it.second }, { it.third })
}

/**
 * 一个对话的查找状态：输入框里的字、算好的命中、当前停在哪一处。也是给正文加高亮的 [TextFinder]。 跳到另一处时 [jumps]
 * 加一，聊天列表据此滚动；命中列表只是刷新（比如来了新消息）不会把视野拽走。
 */
@Stable
class ChatFindState : TextFinder {
    var active by mutableStateOf(false)
        private set

    /** 输入框里的字。 */
    var input by mutableStateOf("")

    /** 已经算出命中的查找词（高亮用它，和计数一致）。 */
    override var query by mutableStateOf("")
        private set

    var matches by mutableStateOf<List<FindMatch>>(emptyList())
        private set

    var index by mutableIntStateOf(-1)
        private set

    var jumps by mutableIntStateOf(0)
        private set

    /** 当前那一处画在哪（根坐标），只认属于当前这处的那次报告。 */
    private var placed by mutableStateOf<Pair<FindMatch, Rect>?>(null)

    /** 聊天列表在根坐标里的范围。只在滚动时读，不必是快照状态。 */
    var viewport: Rect? = null

    val current: FindMatch?
        get() = matches.getOrNull(index)

    fun open() {
        active = true
    }

    fun close() {
        active = false
        input = ""
        query = ""
        matches = emptyList()
        index = -1
        placed = null
    }

    /** 换上新算出的命中。 */
    fun update(query: String, found: List<FindMatch>, order: Map<String, Int>) {
        val before = current
        val queryChanged = query != this.query
        this.query = query
        matches = found
        index = ChatFind.reselect(before, found, order)
        val after = current
        if (after != null && (queryChanged || after != before)) jumps++
    }

    fun next() = move(1)

    fun previous() = move(-1)

    private fun move(delta: Int) {
        if (matches.isEmpty()) return
        index = ChatFind.step(index, matches.size, delta)
        jumps++
    }

    fun placedFor(match: FindMatch): Rect? = placed?.takeIf { it.first == match }?.second

    override fun isCurrent(itemId: String, block: Int, start: Int): Boolean {
        val match = current ?: return false
        return match.itemId == itemId && match.block == block && match.start == start
    }

    override fun placeCurrent(bounds: Rect) {
        val match = current ?: return
        if (placed?.first != match || placed?.second != bounds) placed = match to bounds
    }
}
