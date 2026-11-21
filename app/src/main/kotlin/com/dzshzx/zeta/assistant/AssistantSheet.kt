package com.dzshzx.zeta.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** 中间那一档在基础高度和最大高度之间的位置（照 Eta）。 */
private const val MediumFraction = 0.58f

/** 拖到最低档以下、拉过最高档以后面板整体跟手的比例（照 Eta 的 rubberYPx）。 */
private const val DismissRubber = 0.28f
private const val HandoffRubber = 0.36f

/** 拉取进度超过这么多时把手下面出现「上拉打开 Zeta」（照 Eta）。 */
internal const val HandoffHintProgress = 0.18f

/** 回答面板上半截（把手、带上的屏幕、回答区）的几个尺寸，换成了像素。 */
@Immutable
internal data class SheetMetrics(
    /** 出回答时直接升到的基础高度（照 Eta 的 assistantBaseHeightPx：304dp）。 */
    val base: Float,
    /** 拖到最低档以下再多拖这么多，松手关掉。 */
    val dismiss: Float,
    /** 在最高档从把手再往上拉这么多，松手打开 Zeta。 */
    val handoff: Float,
    /** 拉过最高档时面板往上跟手最多这么多。 */
    val maxLift: Float,
    /** 自动长高至少差这么多才动，免得来回抖。 */
    val tolerance: Float,
    /** 自动长高时回答下面多留的一点余量。 */
    val slack: Float,
) {
    companion object {
        fun of(density: Density): SheetMetrics =
            with(density) {
                SheetMetrics(
                    base = 304.dp.toPx(),
                    dismiss = 92.dp.toPx(),
                    handoff = 24.dp.toPx(),
                    maxLift = 40.dp.toPx(),
                    tolerance = 6.dp.toPx(),
                    slack = 12.dp.toPx(),
                )
            }
    }
}

/** 面板上半截松手时停得住的几档高度（像素，照 Eta 的锚点）：最低、基础、中间、最高。 */
@Immutable
internal data class SheetStops(val lowest: Float, val base: Float, val max: Float) {
    val medium: Float
        get() = base + (max - base) * MediumFraction

    /** 离 [height] 最近的一档。 */
    fun nearest(height: Float): Float = listOf(lowest, base, medium, max).minBy { abs(it - height) }

    companion object {
        /** [base] 是基础高度，[grown] 是已经长到（或上次停到）的高度，[max] 是放得下的最大高度；都截在 [max] 以内。 */
        fun of(base: Float, grown: Float, max: Float): SheetStops {
            val cap = max.coerceAtLeast(0f)
            val clampedBase = base.coerceIn(0f, cap)
            return SheetStops(minOf(clampedBase, grown).coerceIn(0f, cap), clampedBase, cap)
        }
    }
}

/** 一次拖动里面板的位置：[height] 截在最低和最高档之间，[dismiss] 是拖到最低档以下多出来的距离，[handoff] 是拉过最高档多出来的距离。 */
@Immutable
internal data class SheetPull(val height: Float, val dismiss: Float = 0f, val handoff: Float = 0f) {
    /** 手指要的高度（没截过的）。 */
    private val reach: Float
        get() = height + handoff - dismiss

    /** 手指往下移了 [delta]（根坐标，往上为负）以后的位置；[allowHandoff] 为 false 时拉过最高档不算数（列表区的拖动）。 */
    fun dragBy(delta: Float, stops: SheetStops, allowHandoff: Boolean): SheetPull {
        val wanted = reach - delta
        return SheetPull(
            height = wanted.coerceIn(stops.lowest, stops.max),
            dismiss = (stops.lowest - wanted).coerceAtLeast(0f),
            handoff = if (allowHandoff) (wanted - stops.max).coerceAtLeast(0f) else 0f,
        )
    }

    /** 已经在最高档、又拉够了：松手打开 Zeta。 */
    fun handoffReady(stops: SheetStops, metrics: SheetMetrics): Boolean =
        height >= stops.max - 1f && handoff >= metrics.handoff

    /** 面板整体跟手的位移：拖到最低档以下往下沉，拉过最高档往上浮（有阻尼）。 */
    fun rubber(metrics: SheetMetrics): Float =
        dismiss * DismissRubber - (handoff * HandoffRubber).coerceAtMost(metrics.maxLift)

    /** 松手（[commit] 为 false 是手势被取消）：拖够了关掉，在最高档从把手拉够了打开 Zeta，其余停到最近的一档。 */
    fun release(
        stops: SheetStops,
        metrics: SheetMetrics,
        commit: Boolean,
        allowHandoff: Boolean,
    ): SheetRelease =
        when {
            commit && dismiss >= metrics.dismiss -> SheetRelease.Dismiss
            commit && allowHandoff && handoffReady(stops, metrics) -> SheetRelease.Handoff
            else -> SheetRelease.Settle(stops.nearest(height))
        }
}

/** 松手的结果。 */
internal sealed interface SheetRelease {
    data object Dismiss : SheetRelease

    data object Handoff : SheetRelease

    data class Settle(val height: Float) : SheetRelease
}

/**
 * 回答面板上半截的高度（照 Eta 的回答面板）：出回答时直接升到基础高度，之后按回答需要平滑长高、只长不缩，到最大高度后在回答区里滚动；用户拖过以后不再自动长高，松手停到最近的一档。
 *
 * 最大高度由面板量出来：浮窗可用的高度（[room]）减去面板上半截以外的部分（整块面板 [panel] 减上半截 [frame]），所以键盘弹出时跟着变小。
 */
@Stable
internal class SheetState(val metrics: SheetMetrics) {
    /** 有回答、面板铺开着。 */
    var open by mutableStateOf(false)
        private set

    /** 自动长高、松手停档定下的高度（还没按最大高度截）。 */
    private var settled by mutableFloatStateOf(0f)

    /** 上半截此刻的高度：动画和拖动都改它。 */
    var height by mutableFloatStateOf(0f)
        private set

    /** 拖动中的位置；没在拖时为 null。 */
    private var pull by mutableStateOf<SheetPull?>(null)

    /** 面板整体跟手的上下位移。 */
    var rubber by mutableFloatStateOf(0f)
        private set

    /** 还会按回答自动长高（照 Eta 的 autoExpandSuppressed 取反）。 */
    var autoExpand by mutableStateOf(true)
        private set

    var room by mutableIntStateOf(0)
    var panel by mutableIntStateOf(0)
    var frame by mutableIntStateOf(0)

    /** 回答区的可见高度和回答内容的全高。 */
    var viewport by mutableIntStateOf(0)
    var content by mutableIntStateOf(0)

    private val heightAnim = Animatable(0f)
    private val rubberAnim = Animatable(0f)

    val dragging: Boolean
        get() = pull != null

    val max: Float
        get() = (room - (panel - frame)).toFloat().coerceAtLeast(0f)

    val stops: SheetStops
        get() = SheetStops.of(metrics.base, settled, max)

    /** 不拖的时候高度往这里走。 */
    val target: Float
        get() = if (open) settled.coerceIn(0f, max) else 0f

    /** 过顶拉取的进度：0 到 1。 */
    val handoffProgress: Float
        get() = ((pull?.handoff ?: 0f) / metrics.handoff).coerceIn(0f, 1f)

    val handoffReady: Boolean
        get() = pull?.handoffReady(stops, metrics) == true

    /** 回答出来了：直接定到基础高度。 */
    fun show() {
        if (open) return
        open = true
        autoExpand = true
        settled = metrics.base
    }

    /** 回答区需要的高度（截在基础和最大高度之间）；不该自动长高时为 null。 */
    fun wanted(): Float? {
        if (!open || !autoExpand || dragging || viewport <= 0) return null
        val need = frame - viewport + content + metrics.slack
        return minOf(maxOf(need, metrics.base), max)
    }

    /** 只长不缩：比现在定下的高出一截才长。 */
    fun grow(need: Float) {
        if (autoExpand && !dragging && need > settled + metrics.tolerance) settled = need
    }

    /** 回答区的拖动要不要接过来改面板高度（照 Eta 的 shouldInterceptListDrag）：往上拖时面板还没到最高，往下拖时回答已经滚到顶。 */
    fun claims(totalDy: Float, listAtTop: Boolean): Boolean =
        (totalDy < 0f && height < max - 1f) || (totalDy > 0f && listAtTop)

    fun startDrag() {
        // 拖过以后不再自动长高，还没长到的目标收回到当前高度
        if (autoExpand) {
            autoExpand = false
            if (settled > height + metrics.tolerance) settled = height
        }
        val stops = stops
        val start = SheetPull(height.coerceIn(stops.lowest, stops.max))
        pull = start
        height = start.height
        rubber = 0f
    }

    fun dragBy(delta: Float, allowHandoff: Boolean) {
        val current = pull ?: return
        val next = current.dragBy(delta, stops, allowHandoff)
        pull = next
        height = next.height
        rubber = next.rubber(metrics)
    }

    fun finishDrag(commit: Boolean, allowHandoff: Boolean): SheetRelease {
        val current = pull ?: return SheetRelease.Settle(height)
        val release = current.release(stops, metrics, commit, allowHandoff)
        settled =
            when (release) {
                is SheetRelease.Settle -> release.height
                else -> settled
            }
        pull = null
        return release
    }

    /** 不拖的时候把高度弹到 [target]（弹簧不回弹，拖过以后快一些）。 */
    suspend fun follow(target: Float) {
        if (abs(heightAnim.value - height) > 0.5f) heightAnim.snapTo(height)
        val response = if (autoExpand) 0.44f else 0.36f
        heightAnim.animateTo(target, spring(dampingRatio = 1f, stiffness = stiffness(response))) {
            if (!dragging) height = value
        }
    }

    /** 松手后面板整体的位移弹回 0。 */
    suspend fun settleRubber() {
        if (abs(rubber) <= 0.5f) {
            rubber = 0f
            return
        }
        rubberAnim.snapTo(rubber)
        rubberAnim.animateTo(0f, spring(dampingRatio = 0.94f, stiffness = stiffness(0.30f))) {
            if (!dragging) rubber = value
        }
    }
}

/** 照 Eta 的弹簧写法（阻尼、响应时间）换算出 Compose 的刚度。 */
private fun stiffness(response: Float): Float {
    val omega = 2f * PI.toFloat() / response
    return omega * omega
}

/** 回答面板的高度状态，跟着 [shown]（有没有回答）铺开；高度、整体位移和自动长高的动画都在这里接上。 */
@Composable
internal fun rememberSheetState(shown: Boolean): SheetState {
    val density = LocalDensity.current
    val sheet = remember { SheetState(SheetMetrics.of(density)) }
    LaunchedEffect(shown) { if (shown) sheet.show() }
    LaunchedEffect(sheet.target, sheet.dragging) { if (!sheet.dragging) sheet.follow(sheet.target) }
    LaunchedEffect(sheet.dragging) { if (!sheet.dragging) sheet.settleRubber() }
    LaunchedEffect(sheet) { snapshotFlow { sheet.wanted() }.collect { it?.let(sheet::grow) } }
    return sheet
}

/** 面板上半截按 [SheetState.height] 定高（只在布局时读，拖动不引起重组），并量下实际的高度。 */
internal fun Modifier.sheetHeight(sheet: SheetState): Modifier = layout { measurable, constraints ->
    val height = sheet.height.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
    sheet.frame = height
    val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
    layout(placeable.width, height) { placeable.place(0, 0) }
}

/** 回答面板的拖动：[scroll] 为 null 时是把手（照 Eta：Main 阶段，过顶拉取算数），否则是回答区（Initial 阶段，先于滚动拦下该归面板的拖动，过顶拉取不算）。 */
@Composable
internal fun Modifier.sheetDrag(
    sheet: SheetState,
    scroll: ScrollState?,
    onRelease: (SheetRelease) -> Unit,
): Modifier {
    val anchor = remember { RootAnchor() }
    val release = rememberUpdatedState(onRelease)
    val drag = remember(sheet, scroll) { SheetDrag(sheet, scroll, release) }
    val pass = if (scroll == null) PointerEventPass.Main else PointerEventPass.Initial
    return onGloballyPositioned { anchor.coords = it }
        .pointerInput(drag) { rootVerticalDrag(anchor, pass, drag) }
}

/** 一处拖动接到面板状态上。 */
private class SheetDrag(
    private val sheet: SheetState,
    private val scroll: ScrollState?,
    private val release: State<(SheetRelease) -> Unit>,
) {
    private val fromHandle = scroll == null

    fun shouldStart(totalDy: Float): Boolean =
        fromHandle || sheet.claims(totalDy, scroll?.canScrollBackward != true)

    fun start() = sheet.startDrag()

    fun drag(delta: Float) = sheet.dragBy(delta, allowHandoff = fromHandle)

    fun end(commit: Boolean) = release.value(sheet.finishDrag(commit, allowHandoff = fromHandle))
}

/** 拖动区域的布局坐标，用来把手指位置换成根坐标。 */
private class RootAnchor {
    var coords: LayoutCoordinates? = null

    fun toRoot(local: Offset): Offset =
        coords?.takeIf { it.isAttached }?.localToRoot(local) ?: local
}

/** 按根坐标跟一次竖直拖动（照 Eta 的 handleRootVerticalDrag，精简成只跟按下的那根手指）：面板自己在动，局部坐标的位移会抖。 */
private suspend fun PointerInputScope.rootVerticalDrag(
    anchor: RootAnchor,
    pass: PointerEventPass,
    drag: SheetDrag,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
        val start = anchor.toRoot(down.position)
        val total =
            awaitVerticalSlop(down.id, start, anchor, pass, slop, drag::shouldStart)
                ?: return@awaitEachGesture
        drag.start()
        var clean = false
        try {
            val overSlop = total - sign(total) * slop
            if (overSlop != 0f) drag.drag(overSlop)
            clean = followVertical(down.id, start.y + total, anchor, pass, drag::drag)
        } finally {
            drag.end(clean)
        }
    }
}

/** 等手指竖着移过 touch slop：返回这时的竖直总位移；横着走、松手、被别人接走或 [shouldStart] 不要时返回 null。 */
private suspend fun AwaitPointerEventScope.awaitVerticalSlop(
    id: PointerId,
    start: Offset,
    anchor: RootAnchor,
    pass: PointerEventPass,
    slop: Float,
    shouldStart: (Float) -> Boolean,
): Float? {
    while (true) {
        val change = awaitPointerEvent(pass).changes.firstOrNull { it.id == id }
        if (change == null || !change.pressed) return null
        if (pass == PointerEventPass.Main && change.isConsumed) return null
        val total = anchor.toRoot(change.position) - start
        val vertical = slopCrossed(total, slop) ?: continue
        if (!vertical || !shouldStart(total.y)) return null
        change.consume()
        return total.y
    }
}

/** 手指移了 [total] 以后：竖着过了 slop 为 true，横着过了为 false，都还没过为 null。 */
private fun slopCrossed(total: Offset, slop: Float): Boolean? {
    val dx = abs(total.x)
    val dy = abs(total.y)
    return when {
        dx > slop && dx > dy -> false
        dy > slop && dy >= dx -> true
        else -> null
    }
}

/** 过了 slop 以后跟手，直到松手：干净地松手返回 true，被取消返回 false。 */
private suspend fun AwaitPointerEventScope.followVertical(
    id: PointerId,
    startY: Float,
    anchor: RootAnchor,
    pass: PointerEventPass,
    onDrag: (Float) -> Unit,
): Boolean {
    var lastY = startY
    while (true) {
        val change = awaitPointerEvent(pass).changes.firstOrNull { it.id == id } ?: return false
        if (!change.pressed) {
            return change.changedToUpIgnoreConsumed() &&
                (pass == PointerEventPass.Initial || !change.isConsumed)
        }
        if (pass == PointerEventPass.Main && change.isConsumed) return false
        val y = anchor.toRoot(change.position).y
        change.consume()
        if (y != lastY) onDrag(y - lastY)
        lastY = y
    }
}
