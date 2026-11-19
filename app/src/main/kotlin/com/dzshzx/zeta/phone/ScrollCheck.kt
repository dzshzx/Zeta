package com.dzshzx.zeta.phone

import kotlin.math.abs
import kotlin.math.roundToInt

/** 滚动方向说的是要看的新内容在哪边（down 露出下面的内容），不是手指移动的方向。 */
internal enum class ScrollDirection(val vertical: Boolean, val sign: Int, val word: String, val end: String) {
    UP(true, -1, "向上", "到顶"),
    DOWN(true, 1, "向下", "到底"),
    LEFT(false, -1, "向左", "到最左边"),
    RIGHT(false, 1, "向右", "到最右边"),
    ;

    val opposite: ScrollDirection get() = when (this) {
        UP -> DOWN
        DOWN -> UP
        LEFT -> RIGHT
        RIGHT -> LEFT
    }

    companion object {
        fun parse(value: String?): ScrollDirection? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/** 滚多少：page 约一屏，small 约四分之一屏（把某一项微调进视野）。系统的滚动动作只会整页翻，small 只能用手势。 */
internal enum class ScrollAmount(val near: Float, val far: Float) {
    PAGE(0.2f, 0.8f),
    SMALL(0.38f, 0.62f),
    ;

    companion object {
        /** 没给是 page；给了不认识的返回 null。 */
        fun parse(value: String?): ScrollAmount? =
            if (value.isNullOrBlank()) PAGE else entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
    }
}

/** 屏幕上的一块矩形（像素，右、下不含）。不用 android.graphics.Rect：单元测试里它是空壳。 */
internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val isEmpty get() = width <= 0 || height <= 0

    fun intersect(other: Box): Box =
        Box(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))

    /** 四边各往里收 [fx]、[fy] 的宽高。 */
    fun inset(fx: Float, fy: Float): Box {
        val dx = (width * fx).roundToInt()
        val dy = (height * fy).roundToInt()
        return Box(left + dx, top + dy, right - dx, bottom - dy)
    }
}

/** 一次滑动：从 (x1, y1) 到 (x2, y2)。 */
internal data class Stroke(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

/** 一个可滚动的控件，挑主列表用。[supports] 见 [ScrollPlan.supportRank]。 */
internal data class ScrollCandidate(val area: Long, val supports: Int, val depth: Int)

/** 滚动之前、之后，控件在屏上的位置（拿来比内容有没有动）。[key] 是控件的 id、类名、文字、描述拼起来的。 */
internal data class Anchor(val key: String, val x: Int, val y: Int)

/** 滚动之后比出来的结果。距离是屏幕像素，正数。 */
internal sealed interface ScrollResult {
    /** 按要的方向滚了 [distance]。 */
    data class Moved(val distance: Int) : ScrollResult

    /** 内容换了（新的一屏和原来没有重叠，或者动得不一致），量不出距离。 */
    data object Changed : ScrollResult

    /** 往反方向动了。 */
    data class Backward(val distance: Int) : ScrollResult

    /** 一点没动。 */
    data object Still : ScrollResult

    /** 前后都读不到控件，没法比。 */
    data object Unknown : ScrollResult
}

/** 滚动怎么做：用控件的哪个滚动动作，或者在哪儿滑。只看数，不碰 Android。 */
internal object ScrollPlan {
    // AccessibilityNodeInfo 的动作编号（android.R.id.accessibilityAction*，编译期常量）
    private const val FORWARD = 0x1000
    private const val BACKWARD = 0x2000
    private const val SCROLL_UP = android.R.id.accessibilityActionScrollUp
    private const val SCROLL_DOWN = android.R.id.accessibilityActionScrollDown
    private const val SCROLL_LEFT = android.R.id.accessibilityActionScrollLeft
    private const val SCROLL_RIGHT = android.R.id.accessibilityActionScrollRight
    private const val PAGE_UP = android.R.id.accessibilityActionPageUp
    private const val PAGE_DOWN = android.R.id.accessibilityActionPageDown
    private const val PAGE_LEFT = android.R.id.accessibilityActionPageLeft
    private const val PAGE_RIGHT = android.R.id.accessibilityActionPageRight

    private val VERTICAL = setOf(SCROLL_UP, SCROLL_DOWN, PAGE_UP, PAGE_DOWN)
    private val HORIZONTAL = setOf(SCROLL_LEFT, SCROLL_RIGHT, PAGE_LEFT, PAGE_RIGHT)

    /** 带任何一个滚动动作的控件都算能滚。 */
    val SCROLL_ACTIONS: Set<Int> = VERTICAL + HORIZONTAL + FORWARD + BACKWARD

    private fun exact(direction: ScrollDirection) = when (direction) {
        ScrollDirection.UP -> SCROLL_UP
        ScrollDirection.DOWN -> SCROLL_DOWN
        ScrollDirection.LEFT -> SCROLL_LEFT
        ScrollDirection.RIGHT -> SCROLL_RIGHT
    }

    private fun page(direction: ScrollDirection) = when (direction) {
        ScrollDirection.UP -> PAGE_UP
        ScrollDirection.DOWN -> PAGE_DOWN
        ScrollDirection.LEFT -> PAGE_LEFT
        ScrollDirection.RIGHT -> PAGE_RIGHT
    }

    /** 只报了另一条轴上的方向（横着的轮播要竖着滚）：不滚，免得滑成侧滑删除这类手势。 */
    fun onlyOtherAxis(actions: Set<Int>, direction: ScrollDirection): Boolean {
        val vertical = actions.any { it in VERTICAL }
        val horizontal = actions.any { it in HORIZONTAL }
        return if (direction.vertical) horizontal && !vertical else vertical && !horizontal
    }

    /**
     * 前进 / 后退没有方向，按控件的走向算：[horizontal] 是横着的列表（轮播、横向 RecyclerView，看 CollectionInfo 或类名得知），
     * 前进是向右；否则当作竖着的，前进是向下（大多数列表只报前进后退）。报了另一条轴的确切方向时不这样算。
     */
    private fun legacy(actions: Set<Int>, direction: ScrollDirection, horizontal: Boolean): Int? {
        if (direction.vertical == horizontal) return null
        if (actions.any { it in (if (direction.vertical) HORIZONTAL else VERTICAL) }) return null
        val wanted = if (direction.sign > 0) FORWARD else BACKWARD
        return wanted.takeIf { it in actions }
    }

    /** 往 [direction] 整页滚用哪个动作，没有合适的返回 null（改用手势）。 */
    fun action(actions: Set<Int>, direction: ScrollDirection, horizontal: Boolean = false): Int? {
        if (onlyOtherAxis(actions, direction)) return null
        exact(direction).takeIf { it in actions }?.let { return it }
        page(direction).takeIf { it in actions }?.let { return it }
        return legacy(actions, direction, horizontal)
    }

    /**
     * 挑主列表时，这个控件在 [direction] 这条轴上的支持程度：4 有确切方向的动作，3 有翻页动作，2 只有前进后退，0 不在这条轴上滚。
     * 两个方向一起看：滚到头的列表只报反方向的动作，也还是这条轴上的列表。
     */
    fun supportRank(actions: Set<Int>, direction: ScrollDirection, horizontal: Boolean = false): Int {
        val back = direction.opposite
        return when {
            onlyOtherAxis(actions, direction) -> 0
            exact(direction) in actions || exact(back) in actions -> 4
            page(direction) in actions || page(back) in actions -> 3
            legacy(actions, direction, horizontal) != null || legacy(actions, back, horizontal) != null -> 2
            else -> 0
        }
    }

    /**
     * 还能不能往 [direction] 滚。控件只在还能滚的时候才报那个方向的动作（RecyclerView、ScrollView 都是这样），
     * 所以报了反方向、没报这个方向就是到头了。一个滚动动作都没报的控件说不准，返回 null。
     */
    fun canScroll(actions: Set<Int>, direction: ScrollDirection, horizontal: Boolean = false): Boolean? {
        if (actions.none { it in SCROLL_ACTIONS }) return null
        if (onlyOtherAxis(actions, direction)) return null
        if (action(actions, direction, horizontal) != null) return true
        return if (action(actions, direction.opposite, horizontal) != null) false else null
    }

    /** 几个可滚动的控件里挑主列表：面积最大的，再看对这个方向支持得好的，再看层级深的（里层的列表才真正在滚）。 */
    fun best(candidates: List<ScrollCandidate>): Int? =
        candidates.indices.maxWithOrNull(
            compareBy<Int> { candidates[it].area }.thenBy { candidates[it].supports }.thenBy { candidates[it].depth },
        )

    /**
     * 在 [area] 里滑一下，让内容往 [direction] 走：手指和内容方向相反（要看下面的内容，手指往上滑）。
     * 竖着滑走在区域的横向正中，横着滑走在纵向正中。区域太小返回 null。
     */
    fun stroke(area: Box, direction: ScrollDirection, amount: ScrollAmount): Stroke? {
        val start = if (direction.vertical) area.top else area.left
        val end = if (direction.vertical) area.bottom else area.right
        if (end - start < MIN_SPAN || area.isEmpty) return null
        val near = point(start, end, amount.near)
        val far = point(start, end, amount.far)
        val from = if (direction.sign > 0) far else near
        val to = if (direction.sign > 0) near else far
        return if (direction.vertical) {
            val x = area.left + (area.width - 1) / 2
            Stroke(x, from, x, to)
        } else {
            val y = area.top + (area.height - 1) / 2
            Stroke(from, y, to, y)
        }
    }

    private fun point(start: Int, endExclusive: Int, fraction: Float): Int =
        (start + (endExclusive - 1 - start) * fraction).roundToInt().coerceIn(start, endExclusive - 1)

    private const val MIN_SPAN = 40
}

/** 比滚动前后的控件位置，判断滚没滚、往哪边滚了多少。 */
internal object ScrollCheck {
    fun compare(before: List<Anchor>, after: List<Anchor>, direction: ScrollDirection): ScrollResult {
        if (before.isEmpty() && after.isEmpty()) return ScrollResult.Unknown
        val old = unique(before)
        val new = unique(after)
        // 内容移动的距离：往下滚时内容往上走，是负数
        val deltas = old.mapNotNull { (key, anchor) ->
            val moved = new[key] ?: return@mapNotNull null
            if (direction.vertical) moved.y - anchor.y else moved.x - anchor.x
        }
        val moving = deltas.filter { abs(it) >= MIN_MOTION }
        shift(moving)?.let { contentShift ->
            val scrolled = -contentShift
            return if (scrolled * direction.sign > 0) ScrollResult.Moved(abs(scrolled)) else ScrollResult.Backward(abs(scrolled))
        }
        if (moving.isEmpty() && old.keys == new.keys) return ScrollResult.Still
        return ScrollResult.Changed
    }

    /** 同一个 key 只出现一次的才拿来比（列表里一样的图标、分隔线比不出位置）。 */
    private fun unique(anchors: List<Anchor>): Map<String, Anchor> =
        anchors.groupBy { it.key }.mapNotNull { (key, list) -> list.singleOrNull()?.let { key to it } }.toMap()

    /**
     * 多数动了的控件往同一边、差不多一样远地动了，取它们的中位数；凑不出这样一组（少于两个，或者动得乱）返回 null。
     * 不动的（吸顶的标题栏）不参与。
     */
    private fun shift(moving: List<Int>): Int? {
        if (moving.size < MIN_ANCHORS) return null
        val positive = moving.filter { it > 0 }
        val negative = moving.filter { it < 0 }
        val dominant = when {
            positive.size > negative.size -> positive
            negative.size > positive.size -> negative
            else -> return null
        }.sorted()
        val median = dominant[dominant.size / 2]
        val tolerance = maxOf(MIN_TOLERANCE, abs(median) / 2)
        val consistent = dominant.filter { abs(it - median) <= tolerance }
        if (consistent.size < MIN_ANCHORS || consistent.size * 3 < moving.size * 2) return null
        return consistent[consistent.size / 2]
    }

    private const val MIN_MOTION = 2
    private const val MIN_ANCHORS = 2
    private const val MIN_TOLERANCE = 8
}

/**
 * 滚动结果说给 Claude 听的一句。[atEnd]：滚完以后这个方向是不是到头了（不知道是 null）。
 * [toModel] 把屏幕像素换成截图坐标。
 */
internal fun scrollSummary(result: ScrollResult, atEnd: Boolean?, direction: ScrollDirection, toModel: (Int) -> Int): String {
    val end = if (atEnd == true) "，已经${direction.end}了" else ""
    return when (result) {
        is ScrollResult.Moved -> "${direction.word}滚了约 ${toModel(result.distance)} 像素（截图坐标）$end"
        ScrollResult.Changed -> "${direction.word}滚动了，换了一屏内容$end"
        is ScrollResult.Backward -> "界面往反方向动了约 ${toModel(result.distance)} 像素，可能滑到了别的控件上：用 node、match 或 id 指定要滚的列表"
        ScrollResult.Still -> if (atEnd == true) "没有滚动：已经${direction.end}了" else
            "没有滚动：这里可能不能${direction.word}滚。可以用 node、match 或 id 指定要滚的列表，或者用 swipe"
        ScrollResult.Unknown -> "已经${direction.word}滑了，但读不到控件，确认不了滚没滚动（需要的话用 screenshot 看）"
    }
}
