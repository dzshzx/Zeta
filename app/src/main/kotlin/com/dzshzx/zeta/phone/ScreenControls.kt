package com.dzshzx.zeta.phone

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 读屏幕上的控件做事（按文字找控件、滚动、等某段文字出现），给 [PhoneTools] 用。 规则（怎么匹配、滚没滚动）在
 * [ControlSelector]、[ScrollPlan]、[ScrollCheck] 里，这里只管从无障碍节点里取数、发动作。
 */
internal object ScreenControls {
    /** 屏上看得见的一个控件。 */
    class Live(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val facts: ControlFacts,
        val depth: Int,
    )

    /** 按选择器找到的控件，[ties] 是跟它一样好的有几个（含它自己）。 */
    class Found(val control: Live, val ties: Int)

    /** 各个窗口（最上层的在前）里看得见、有大小的控件，按先后排。 */
    fun visible(roots: List<AccessibilityNodeInfo>, limit: Int = MAX_NODES): List<Live> {
        val out = ArrayList<Live>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || out.size >= limit) return
            if (!node.isVisibleToUser) return
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.width() > 0 && bounds.height() > 0)
                out += Live(node, bounds, facts(node), depth)
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                visit(child, depth + 1)
            }
        }
        for (root in roots) visit(root, 0)
        return out
    }

    fun facts(node: AccessibilityNodeInfo) =
        ControlFacts(
            text = node.text?.toString(),
            description = node.contentDescription?.toString(),
            hint = node.hintText?.toString(),
            viewId = node.viewIdResourceName,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            scrollable = canScrollAtAll(node),
            enabled = node.isEnabled,
        )

    fun find(
        roots: List<AccessibilityNodeInfo>,
        selector: ControlSelector,
        purpose: ControlPurpose,
    ): Found? {
        val controls = visible(roots)
        val pick = selector.pick(controls.map { it.facts }, purpose) ?: return null
        return Found(controls[pick.index], pick.ties)
    }

    /** 某处文字或 id 对得上的控件现在有没有（wait 用）。 */
    fun present(roots: List<AccessibilityNodeInfo>, selector: ControlSelector): Boolean =
        visible(roots).any { selector.score(it.facts) > 0 }

    // ---- 滚动 ----

    private fun actions(node: AccessibilityNodeInfo): Set<Int> =
        node.actionList.mapTo(HashSet()) { it.id }

    private fun canScrollAtAll(node: AccessibilityNodeInfo): Boolean =
        node.isScrollable || actions(node).any { it in ScrollPlan.SCROLL_ACTIONS }

    /** 横着的列表：CollectionInfo 只有一行多列，或者是横向滚动、翻页类的控件。 */
    private fun horizontal(node: AccessibilityNodeInfo): Boolean {
        node.collectionInfo?.let { info ->
            if (info.rowCount <= 1 && info.columnCount > 1) return true
        }
        val cls = node.className?.toString().orEmpty()
        return cls.endsWith("HorizontalScrollView") ||
            cls.endsWith("ViewPager") ||
            cls.endsWith("ViewPager2")
    }

    /** 控件本身或它的上层里第一个能滚的。 */
    fun scrollableAround(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < MAX_DEPTH) {
            if (canScrollAtAll(current)) return current
            current = runCatching { current.parent }.getOrNull()
            hops++
        }
        return null
    }

    /** 屏上的主列表：在这条轴上能滚的控件里面积最大的。只说能滚、不报滚动动作的控件（不知道走向）也算。 没有返回 null（改为在整屏中间滑）。 */
    fun mainScrollable(
        roots: List<AccessibilityNodeInfo>,
        direction: ScrollDirection,
        screen: Box,
    ): AccessibilityNodeInfo? {
        val candidates = ArrayList<Pair<Live, ScrollCandidate>>()
        for (live in visible(roots)) {
            val node = live.node
            if (!node.isEnabled || !live.facts.scrollable) continue
            val actions = actions(node)
            val rank = ScrollPlan.supportRank(actions, direction, horizontal(node))
            if (rank == 0 && actions.any { it in ScrollPlan.SCROLL_ACTIONS }) continue
            val area = box(live.bounds).intersect(screen)
            if (area.isEmpty || ScrollPlan.stroke(area, direction, ScrollAmount.PAGE) == null)
                continue
            candidates +=
                live to ScrollCandidate(area.width.toLong() * area.height, rank, live.depth)
        }
        val index = ScrollPlan.best(candidates.map { it.second }) ?: return null
        return candidates[index].first.node
    }

    fun box(rect: Rect) = Box(rect.left, rect.top, rect.right, rect.bottom)

    /** 一次滚动的结果。[atEnd]：滚完以后这个方向还能不能接着滚（控件不报时是 null）。 */
    class Report(val result: ScrollResult, val atEnd: Boolean?)

    /**
     * 滚一次并核对有没有滚动。[container] 是要滚的控件，null 时在整屏中间滑（[screen] 是整屏）。
     * 整页滚优先用控件自己的滚动动作（准，不会甩出惯性）；没有这个动作、或者只滚一点时，用 [swipe] 在控件范围里滑。 返回结果，或者失败原因。
     */
    fun scroll(
        roots: () -> List<AccessibilityNodeInfo>,
        container: AccessibilityNodeInfo?,
        direction: ScrollDirection,
        amount: ScrollAmount,
        screen: Box,
        swipe: (Stroke, Long) -> Boolean,
    ): Pair<Report?, String?> {
        val horizontal = container?.let(::horizontal) ?: false
        if (container != null)
            cannotScroll(container, direction, horizontal)?.let {
                return it
            }
        // 容器自己要重新取一次，才看得到新加进来的子控件；子控件走无障碍缓存，内容变化的事件会让缓存跟上
        val snapshot = {
            if (container != null) {
                runCatching { container.refresh() }
                anchors(listOf(container))
            } else anchors(roots())
        }
        val before = snapshot()
        if (!scrollByAction(container, direction, amount, horizontal)) {
            swipeWithin(container, direction, amount, screen, swipe)?.let {
                return null to it
            }
        }
        val after = settle(before, snapshot)
        val result = ScrollCheck.compare(before, after, direction)
        val atEnd = container?.let {
            ScrollPlan.canScroll(actions(it), direction, horizontal)?.not()
        }
        return Report(result, atEnd) to null
    }

    /** 滚之前先看 [container] 报的动作：只能往另一个方向滚的不执行，已经到头的不再滑（免得手势落到列表项上变成点按或侧滑）。都不是时返回 null。 */
    private fun cannotScroll(
        container: AccessibilityNodeInfo,
        direction: ScrollDirection,
        horizontal: Boolean,
    ): Pair<Report?, String?>? {
        val actions = actions(container)
        if (ScrollPlan.onlyOtherAxis(actions, direction)) {
            return null to "这个控件只能" + (if (direction.vertical) "横着" else "竖着") + "滚，没有执行"
        }
        if (ScrollPlan.canScroll(actions, direction, horizontal) == false) {
            return Report(ScrollResult.Still, atEnd = true) to null
        }
        return null
    }

    /** 整页滚时用控件自己的滚动动作。做成了返回 true；不是整页、没有控件或控件没有这个动作时返回 false。 */
    private fun scrollByAction(
        container: AccessibilityNodeInfo?,
        direction: ScrollDirection,
        amount: ScrollAmount,
        horizontal: Boolean,
    ): Boolean {
        if (amount != ScrollAmount.PAGE || container == null) return false
        val action = ScrollPlan.action(actions(container), direction, horizontal) ?: return false
        return runCatching { container.performAction(action) }.getOrDefault(false)
    }

    /** 在 [container] 的范围里（null 时在整屏中间）用 [swipe] 滑一下。返回问题。 */
    private fun swipeWithin(
        container: AccessibilityNodeInfo?,
        direction: ScrollDirection,
        amount: ScrollAmount,
        screen: Box,
        swipe: (Stroke, Long) -> Boolean,
    ): String? {
        val area =
            container?.let { box(Rect().also(it::getBoundsInScreen)).intersect(screen) }
                ?: screen.inset(0.1f, 0.15f)
        val stroke = ScrollPlan.stroke(area, direction, amount) ?: return "可滚动的区域太小，滑不动"
        if (!swipe(stroke, if (amount == ScrollAmount.SMALL) SMALL_SWIPE_MS else PAGE_SWIPE_MS))
            return "滑动手势没有做成"
        return null
    }

    /**
     * 等滚动（和惯性）停下来：隔一会儿读一次，两次一样就算停了，最多等 [SETTLE_MAX_MS]。
     * 和滚动前一模一样时多等一会儿再下结论：无障碍缓存要等内容变化的事件到了才更新，太早读会把滚了当成没滚。
     */
    private fun settle(before: List<Anchor>, snapshot: () -> List<Anchor>): List<Anchor> {
        val start = SystemClock.uptimeMillis()
        Thread.sleep(SETTLE_FIRST_MS)
        var last = snapshot()
        while (SystemClock.uptimeMillis() - start < SETTLE_MAX_MS) {
            Thread.sleep(SETTLE_STEP_MS)
            val next = snapshot()
            if (
                next == last &&
                    (next != before || SystemClock.uptimeMillis() - start >= UNCHANGED_MIN_MS)
            )
                return next
            last = next
        }
        return last
    }

    /**
     * 等界面停下来（页面切换、弹窗展开这类动画还在走时读到的位置是半路上的）： 隔一会儿比一次各控件的位置，两次一样就返回，最多等 [maxMs]。只比位置不比文字，走秒的时钟不算在动。
     * 间隔比无障碍事件的节流（200 毫秒）长，免得两次读到的都是还没更新的缓存。
     */
    fun awaitStill(roots: () -> List<AccessibilityNodeInfo>, maxMs: Long) {
        val start = SystemClock.uptimeMillis()
        var last = anchors(roots()).map { it.x to it.y }
        while (SystemClock.uptimeMillis() - start < maxMs) {
            Thread.sleep(STILL_STEP_MS)
            val next = anchors(roots()).map { it.x to it.y }
            if (next == last) return
            last = next
        }
    }

    /** [roots] 里看得见、有文字、描述或 id 的控件的位置，拿来比滚动前后。 */
    private fun anchors(roots: List<AccessibilityNodeInfo>): List<Anchor> {
        val out = ArrayList<Anchor>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || out.size >= MAX_ANCHORS) return
            if (!node.isVisibleToUser) return
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            if (text.isNotBlank() || description.isNotBlank() || id.isNotBlank()) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    val key =
                        id + '|' + node.className + '|' + text.take(80) + '|' + description.take(80)
                    out += Anchor(key, bounds.centerX(), bounds.centerY())
                }
            }
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                visit(child, depth + 1)
            }
        }
        for (root in roots) visit(root, 0)
        return out
    }

    private const val MAX_DEPTH = 60
    private const val MAX_NODES = 1500
    private const val MAX_ANCHORS = 200
    private const val PAGE_SWIPE_MS = 400L
    private const val SMALL_SWIPE_MS = 500L
    private const val STILL_STEP_MS = 250L
    private const val SETTLE_FIRST_MS = 250L
    private const val SETTLE_STEP_MS = 150L
    private const val UNCHANGED_MIN_MS = 900L
    private const val SETTLE_MAX_MS = 2000L
}
