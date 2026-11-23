package com.dzshzx.zeta.ui.overlay

import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.ResultCardPolicy
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.markdown.MarkdownTone
import com.dzshzx.zeta.ui.markdown.StaticMarkdown
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.layout.BottomSheetDefaults
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 一轮结束后的结果卡片（照 Eta，外观按 COUI 2 的浮动面板）：屏幕底部浮起的圆角面板，顶部抓手提示可以下滑关掉， 中间是这一轮的回答（Markdown，卡片最高
 * [maxHeight]，正文超出可以滚动，底部渐隐提示还有），最下面“完成”和“回到对话”。
 *
 * 关掉的途径：往下拖（抓手、标题、按钮这几处直接拖；正文滚到顶再往下拉）、系统返回、“完成”、
 * 一段时间没碰（[ResultCardPolicy.idleDismissMs]，按着、滚动、拖动时不算）。自己关掉时调 [onDismiss]； [visible] 变成
 * false（或自己关掉）时往下滑出，放完调 [onExitSettled]。[bottomInset] 是导航条的高度，卡片留在它上面。 [onHeight]
 * 报告卡片连同四周留白本来有多高（像素，不受外面高度限制）：悬浮窗口按它定高度， 因为 ZetaTheme 把内容放在 fillMaxSize 的 Box 里，WRAP_CONTENT
 * 的窗口会被撑满整屏。
 */
@Composable
internal fun ResultCard(
    title: String,
    text: String,
    failed: Boolean,
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = Dp.Unspecified,
    bottomInset: Dp = 0.dp,
    onExitSettled: () -> Unit = {},
    onHeight: (Int) -> Unit = {},
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dismissed = rememberUpdatedState(onDismiss)
    // 用户自己关掉的（下滑、返回、按钮、超时）：马上放退场，不等外面回话
    var leaving by remember { mutableStateOf(false) }
    val shown = visible && !leaving
    fun dismiss() {
        if (leaving) return
        leaving = true
        dismissed.value()
    }

    val visibility = remember { MutableTransitionState(false) }
    LaunchedEffect(shown) { visibility.targetState = shown }
    val exited = rememberUpdatedState(onExitSettled)
    LaunchedEffect(visibility) {
        snapshotFlow { visibility.isIdle && !visibility.currentState && !visibility.targetState }
            .collect { if (it) exited.value() }
    }

    // 往下拖了多少（像素，不往上走）。只在绘制层用，拖动时不重新布局
    val currentDensity = rememberUpdatedState(density)
    val card = remember { CardDrag(scope, currentDensity, onDismiss = ::dismiss) }
    val drag = rememberDraggableState { delta ->
        card.offset = (card.offset + delta).coerceAtLeast(0f)
    }

    // 一段时间没碰就收起：按着、滚动、拖着时不计时，每次松手重新计时
    val scroll = rememberScrollState()
    var touching by remember { mutableStateOf(false) }
    var releases by remember { mutableIntStateOf(0) }
    val dragged by remember { derivedStateOf { card.offset > 0f } }
    val interacting = touching || scroll.isScrollInProgress || dragged
    val idleMs = remember(text) { ResultCardPolicy.idleDismissMs(text) }
    LaunchedEffect(shown, interacting, releases, idleMs) {
        if (!shown || interacting) return@LaunchedEffect
        delay(idleMs)
        dismiss()
    }

    // 系统返回关掉卡片（targetSdk 37 走 OnBackInvokedCallback；窗口可获焦点才收得到）
    DismissOnBack(shown) { dismiss() }

    // 滑出时连卡片下面的空隙一起滑出屏幕
    val below = with(density) { (CardMargin + bottomInset).roundToPx() }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
                .onSizeChanged { onHeight(it.height) }
                .padding(
                    start = CardMargin,
                    end = CardMargin,
                    top = ShadowRoom,
                    bottom = CardMargin + bottomInset,
                ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visibleState = visibility,
            enter =
                slideInVertically(
                    spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                ) {
                    it + below
                } + fadeIn(tween(160)),
            exit = slideOutVertically(tween(220)) { it + below } + fadeOut(tween(200)),
        ) {
            ResultPanel(
                failed = failed,
                maxHeight = maxHeight,
                card = card,
                drag = drag,
                // 只看不拦：手指按着时不计时
                onPress = { touching = true },
                onRelease = {
                    touching = false
                    releases++
                },
            ) { panel ->
                DragHandle()
                Header(title, failed)
                ResultText(text, scroll, panel)
                ResultButtons(onDone = ::dismiss) {
                    onOpen()
                    dismiss()
                }
            }
        }
    }
}

/**
 * 卡片往下拖的位移（像素，不往上走），和松手后的去留：拖得够远或甩得够快就 [onDismiss]，否则弹回去。 [nested]
 * 让正文滚到顶还往下拉时拖卡片；卡片已经被拖下来时往上推，先把卡片推回去再滚正文。
 */
@Stable
private class CardDrag(
    private val scope: CoroutineScope,
    private val density: State<Density>,
    private val onDismiss: () -> Unit,
) {
    var offset by mutableFloatStateOf(0f)
    private var settleJob by mutableStateOf<Job?>(null)

    fun cancelSettle() {
        settleJob?.cancel()
    }

    fun settle(velocity: Float) {
        val far = offset > with(density.value) { DismissDistance.toPx() }
        val flung = velocity > with(density.value) { DismissVelocity.toPx() }
        if (far || flung) {
            onDismiss()
            return
        }
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(
                offset,
                0f,
                velocity,
                spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium),
            ) { value, _ ->
                offset = value
            }
        }
    }

    val nested =
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f || offset <= 0f) return Offset.Zero
                val used = maxOf(available.y, -offset)
                offset += used
                return Offset(0f, used)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                settleJob?.cancel()
                offset += available.y
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
}

/** 系统返回时调 [onBack]（targetSdk 37 走 OnBackInvokedCallback；窗口可获焦点才收得到）。 */
@Composable
private fun DismissOnBack(enabled: Boolean, onBack: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val dispatcher = view.findOnBackInvokedDispatcher()
        val callback = OnBackInvokedCallback { onBack() }
        dispatcher?.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
        onDispose { dispatcher?.unregisterOnBackInvokedCallback(callback) }
    }
}

/** 卡片面板：浮层底色、阴影和描边，跟着 [card] 往下挪；能拖动，手指按下、全部抬起时报给 [onPress]、[onRelease]。内容拿到面板底色（正文底部渐隐用）。 */
@Composable
private fun ResultPanel(
    failed: Boolean,
    maxHeight: Dp,
    card: CardDrag,
    drag: DraggableState,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    content: @Composable ColumnScope.(panel: Color) -> Unit,
) {
    val colors = COUITheme.colorScheme
    val dark = colors.isDark
    val shape = SquircleShape(CardCorner)
    // 浮层面板底色（和胶囊一样留一点点透明）；正文底部的渐隐用同一个颜色
    val panel = colors.surface.copy(alpha = if (dark) 0.96f else 0.99f)
    Column(
        modifier =
            Modifier.widthIn(max = BottomSheetDefaults.PanelMaxWidth)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .graphicsLayer { translationY = card.offset }
                .shadow(
                    elevation = 16.dp,
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = if (dark) 0.45f else 0.18f),
                    spotColor = Color.Black.copy(alpha = if (dark) 0.55f else 0.22f),
                )
                .clip(shape)
                .background(panel, shape)
                .border(0.6.dp, colors.labelQuaternary, shape)
                .semantics {
                    paneTitle =
                        if (failed) Strings.overlayResultFailed()
                        else Strings.overlayPhaseFinished()
                }
                .pointerInput(Unit) { watchTouches(onPress, onRelease) }
                .nestedScroll(card.nested)
                .draggable(
                    state = drag,
                    orientation = Orientation.Vertical,
                    onDragStarted = { card.cancelSettle() },
                    onDragStopped = { velocity -> card.settle(velocity) },
                ),
    ) {
        content(panel)
    }
}

/** 只看不拦：每次手指按下调 [onPress]，全部抬起（或手势被打断）调 [onRelease]。 */
private suspend fun PointerInputScope.watchTouches(onPress: () -> Unit, onRelease: () -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onPress()
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
        } finally {
            onRelease()
        }
    }
}

/** 回答正文，超出卡片高度时可以滚动，底部渐隐（用面板底色 [panel]）提示还有。 */
@Composable
private fun ColumnScope.ResultText(text: String, scroll: ScrollState, panel: Color) {
    Box(Modifier.weight(1f, fill = false)) {
        StaticMarkdown(
            content = text,
            tone = MarkdownTone.Answer,
            modifier =
                Modifier.fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(
                        start = ContentPadding,
                        end = ContentPadding,
                        top = 8.dp,
                        bottom = 12.dp,
                    ),
        )
        if (scroll.canScrollForward) {
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, panel))),
            )
        }
    }
}

/** 最下面的“完成”和“回到对话”。 */
@Composable
private fun ResultButtons(onDone: () -> Unit, onOpen: () -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(start = ContentPadding, end = ContentPadding, top = 4.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(text = Strings.commonDone(), onClick = onDone, modifier = Modifier.weight(1f))
        TextButton(
            text = Strings.overlayBackToConversation(),
            onClick = onOpen,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

/** COUI 面板的抓手：16dp 高的一条，36×4dp 的小胶囊离上沿 12dp（照 COUI 2 的 BottomSheet）。 */
@Composable
private fun DragHandle() {
    val color = COUITheme.colorScheme.dragHandle
    Box(
        Modifier.fillMaxWidth().height(16.dp).drawBehind {
            val width = 36.dp.toPx()
            val height = 4.dp.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset((size.width - width) / 2f, 12.dp.toPx()),
                size = Size(width, height),
                cornerRadius = CornerRadius(height / 2f, height / 2f),
            )
        },
    )
}

/** 标题行：结果（已完成 / 出错了）和是哪个对话。 */
@Composable
private fun Header(title: String, failed: Boolean) {
    val colors = COUITheme.colorScheme
    val accent = if (failed) colors.red else colors.green
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(start = ContentPadding, end = ContentPadding, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(22.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (failed) ZetaIcons.Warning else COUIIcons.Ok,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = accent,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (failed) Strings.overlayResultFailed() else Strings.overlayPhaseFinished(),
            color = colors.labelPrimary,
            style = COUITheme.textStyles.headlineXS,
        )
        if (title.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                color = colors.labelSecondary,
                style = COUITheme.textStyles.bodyS,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** 卡片离屏幕左右、底边（导航条之上）的距离。 */
private val CardMargin = 12.dp

/** 卡片上方给阴影留的透明空间。 */
private val ShadowRoom = 16.dp

/** COUI couiRoundCornerXXXXL：COUI 浮动面板的圆角（OS 37 以前的 BottomSheet 也用它）。 */
private val CardCorner = COUIRoundCorner.XXXXL

/** 内容离卡片左右的距离（COUI 面板内容的边距）。 */
private val ContentPadding = 20.dp

/** 往下拖超过这么远，或松手时往下甩得够快，就关掉；否则弹回去。 */
private val DismissDistance = 96.dp
private val DismissVelocity = 1_200.dp
