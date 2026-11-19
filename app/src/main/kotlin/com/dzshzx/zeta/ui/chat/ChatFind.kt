package com.dzshzx.zeta.ui.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.dzshzx.zeta.core.EarlierHits
import com.dzshzx.zeta.ui.markdown.TextFinder
import com.dzshzx.zeta.ui.markdown.findOccurrences

/** 对话内查找的一段文字。[block] 区分同一条消息里的几段：Markdown 的文字块用源码偏移，纯文本用 0。 */
internal data class FindSegment(val itemId: String, val block: Int, val text: String)

/** 一处命中：[start] 到 [end]（不含）是它在那段文字里的区间。 */
internal data class FindMatch(val itemId: String, val block: Int, val start: Int, val end: Int)

/** 还没载入的更早记录里的一处命中：第 [event] 条消息里的第 [nth] 处。跳过去时先把那段载入，再换成列表里对应的那处。 */
internal data class EarlierMatch(val event: Int, val nth: Int)

/** 对话内查找的纯逻辑：匹配、上下切换、命中列表变了之后停在哪。 */
internal object ChatFind {
    /** 查找词去掉首尾空白（输入法常带上的空格不算）。 */
    fun normalize(query: String): String = query.trim()

    /** 各段里的命中，按段的顺序、段内从前往后。 */
    fun matches(segments: List<FindSegment>, query: String): List<FindMatch> =
        if (query.isEmpty()) emptyList() else segments.flatMap { segment ->
            findOccurrences(segment.text, query).map { FindMatch(segment.itemId, segment.block, it.first, it.last + 1) }
        }

    /** 助手报的各条消息的处数，展开成一处一项，按先后。 */
    fun expand(hits: List<EarlierHits>): List<EarlierMatch> =
        hits.sortedBy { it.event }.flatMap { hit -> (0 until hit.count).map { EarlierMatch(hit.event, it) } }

    /** 从 [index] 走 [delta] 步，两头接上。 */
    fun step(index: Int, count: Int, delta: Int): Int = if (count <= 0) -1 else Math.floorMod(index + delta, count)

    /**
     * 命中列表换了以后停在哪一处（下标，没有命中是 -1）。[previous] 还在就停在它上面；不在了（比如查找词多打了一个字）
     * 就停在它所在位置之后最近的一处，后面没有了停在最后一处；之前没选中过的从最后一处开始：聊天里最新的内容在底部。
     * [order] 是各条消息在列表里的位置。
     */
    fun reselect(previous: FindMatch?, matches: List<FindMatch>, order: Map<String, Int>): Int {
        if (matches.isEmpty()) return -1
        if (previous == null) return matches.lastIndex
        val same = matches.indexOf(previous)
        if (same >= 0) return same
        val from = position(previous, order) ?: return matches.lastIndex
        val after = matches.indexOfFirst { match -> position(match, order)?.let { compare(it, from) >= 0 } == true }
        return if (after >= 0) after else matches.lastIndex
    }

    /**
     * 选中的更早那处 [hit] 所在那段刚载入，它那条消息变成了 [itemId] 这一项：停到列表里对应的那处（这一项里的第 nth 处，
     * 不够就是这一项的最后一处）。助手估的处数和界面按排版后的文字查的可能对不上，这一项里没有命中时停在它之后最近的一处。
     */
    fun resolveLoaded(hit: EarlierMatch, itemId: String?, matches: List<FindMatch>, order: Map<String, Int>): Int {
        if (matches.isEmpty()) return -1
        val at = itemId?.let(order::get) ?: return matches.lastIndex
        val own = matches.indices.filter { matches[it].itemId == itemId }
        if (own.isNotEmpty()) return own.getOrElse(hit.nth) { own.last() }
        val after = matches.indexOfFirst { (order[it.itemId] ?: -1) >= at }
        return if (after >= 0) after else matches.lastIndex
    }

    private fun position(match: FindMatch, order: Map<String, Int>): Triple<Int, Int, Int>? =
        order[match.itemId]?.let { Triple(it, match.block, match.start) }

    private fun compare(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int =
        compareValuesBy(a, b, { it.first }, { it.second }, { it.third })
}

/**
 * 一个对话的查找状态：输入框里的字、算好的命中、当前停在哪一处。也是给正文加高亮的 [TextFinder]。
 * 命中分两段：还没载入的更早记录里的 [earlier]（助手查的），和列表里的 [matches]；[index] 在两段连起来的序列上，更早的在前。
 * 跳到另一处时 [jumps] 加一，聊天列表据此滚动（停在更早的那处时先载入）；命中列表只是刷新（比如来了新消息）不会把视野拽走。
 */
@Stable
internal class ChatFindState : TextFinder {
    var active by mutableStateOf(false)
        private set

    /** 输入框里的字。 */
    var input by mutableStateOf("")

    /** 已经算出命中的查找词（高亮用它，和计数一致）。 */
    override var query by mutableStateOf("")
        private set

    var matches by mutableStateOf<List<FindMatch>>(emptyList())
        private set

    /** 还没载入的更早记录里的命中，按先后。 */
    var earlier by mutableStateOf<List<EarlierMatch>>(emptyList())
        private set

    /** 正在查更早的记录。 */
    var searchingEarlier by mutableStateOf(false)
        private set

    /** 更早的记录没查成时的说明。 */
    var earlierProblem by mutableStateOf<String?>(null)
        private set

    var index by mutableIntStateOf(-1)
        private set

    var jumps by mutableIntStateOf(0)
        private set

    /** 上次算命中时各条消息在列表里的位置。 */
    private var order: Map<String, Int> = emptyMap()

    /** 当前那一处画在哪（根坐标），只认属于当前这处的那次报告。 */
    private var placed by mutableStateOf<Pair<FindMatch, Rect>?>(null)

    /** 聊天列表在根坐标里的范围。只在滚动时读，不必是快照状态。 */
    var viewport: Rect? = null

    /** 两段一共几处。 */
    val count: Int get() = earlier.size + matches.size

    /** 当前停在列表里的哪一处；停在更早的那段里时是 null。 */
    val current: FindMatch? get() = if (index >= earlier.size) matches.getOrNull(index - earlier.size) else null

    /** 当前停在更早记录里的哪一处；停在列表里时是 null。 */
    val currentEarlier: EarlierMatch? get() = if (index in earlier.indices) earlier[index] else null

    fun open() {
        active = true
    }

    fun close() {
        active = false
        input = ""
        query = ""
        matches = emptyList()
        earlier = emptyList()
        searchingEarlier = false
        earlierProblem = null
        index = -1
        placed = null
        order = emptyMap()
    }

    /**
     * 换上新算出的列表里的命中。[skipped] 是现在还没载入的条数：更早那段里已经载入的命中从 [earlier] 去掉，改由 [found] 算；
     * 当前停着的那处正好在刚载入的那段里时，用 [itemOf]（第几条消息变成了哪一项）换成列表里对应的那处，并跳过去。
     * 查找词变了时更早那段要重新查（[earlierSearch]），先清空。
     */
    fun update(query: String, found: List<FindMatch>, order: Map<String, Int>, skipped: Int = 0, itemOf: (Int) -> String? = { null }) {
        val beforeLocal = current
        val beforeEarlier = currentEarlier
        val queryChanged = query != this.query
        this.query = query
        if (queryChanged) earlierProblem = null
        val remaining = if (queryChanged) emptyList() else earlier.filter { it.event < skipped }
        earlier = remaining
        matches = found
        this.order = order
        if (beforeEarlier != null && !queryChanged) {
            val still = remaining.indexOf(beforeEarlier)
            if (still >= 0) {
                index = still
                return
            }
            // 停着的那处刚载入：换成列表里的那处，跳过去
            val local = ChatFind.resolveLoaded(beforeEarlier, itemOf(beforeEarlier.event), found, order)
            index = if (local >= 0) remaining.size + local else remaining.lastIndex
            if (index >= 0) jumps++
            return
        }
        val local = ChatFind.reselect(beforeLocal, found, order)
        index = if (local >= 0) remaining.size + local else remaining.lastIndex
        val after = current
        val afterEarlier = currentEarlier
        val moved = if (after != null) {
            after.itemId != beforeLocal?.itemId || after.block != beforeLocal.block || after.start != beforeLocal.start
        } else {
            afterEarlier != null
        }
        if ((after != null || afterEarlier != null) && (queryChanged || moved)) jumps++
    }

    /** 开始查更早的记录（查找词是 [query]）。 */
    fun earlierSearch(query: String) {
        if (query == this.query) {
            searchingEarlier = true
            earlierProblem = null
        }
    }

    /**
     * 更早那段查完了：[hits] 是查找词 [query] 的结果（null 是没查成），[skipped] 是现在还没载入的条数。
     * 停在列表里的那处不动（只是下标往后挪）；列表里一处都没有时停到更早那段最近的一处，跳过去。
     */
    fun earlierFound(query: String, hits: List<EarlierHits>?, skipped: Int) {
        if (query != this.query) return
        searchingEarlier = false
        val beforeLocal = current
        val beforeEarlier = currentEarlier
        earlierProblem = if (hits == null) "更早的记录没查成" else null
        earlier = ChatFind.expand(hits.orEmpty()).filter { it.event < skipped }
        index = when {
            beforeLocal != null -> earlier.size + matches.indexOf(beforeLocal)
            beforeEarlier != null && beforeEarlier in earlier -> earlier.indexOf(beforeEarlier)
            earlier.isNotEmpty() -> earlier.lastIndex.also { jumps++ }
            else -> -1
        }
    }

    fun next() = move(1)

    fun previous() = move(-1)

    private fun move(delta: Int) {
        if (count == 0) return
        index = ChatFind.step(index, count, delta)
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
