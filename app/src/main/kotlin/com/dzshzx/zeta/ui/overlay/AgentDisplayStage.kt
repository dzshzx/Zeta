package com.dzshzx.zeta.ui.overlay

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.phone.OverlayFocus
import com.dzshzx.zeta.phone.OverlayPhase
import com.dzshzx.zeta.phone.TouchMark
import com.dzshzx.zeta.phone.VirtualScreen
import com.dzshzx.zeta.ui.components.AsleepFrameAlpha
import com.dzshzx.zeta.ui.components.FrameRate
import com.dzshzx.zeta.ui.components.LiveFrames
import com.dzshzx.zeta.ui.components.drawFrame
import com.dzshzx.zeta.ui.components.fill8
import kotlin.math.hypot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 镶在收起胶囊右侧的 18×32dp 实时微缩画面。平时安静地放后台屏幕的缩略画面（休眠时是休眠前那一帧，调暗）； 后台屏幕上有点按、滑动时外圈泛起一下柔和的呼吸微光。 */
@Composable
internal fun MicroLens(screen: VirtualScreen, touch: TouchMark?, accent: Color) {
    LiveFrames(screen, FrameRate.Thumbnail)
    val frame = screen.frames.collectAsState()
    val asleep = screen.asleep.collectAsState()
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(touch) {
        if (touch == null || !touch.fresh()) return@LaunchedEffect
        pulse.snapTo(1f)
        pulse.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
    }
    val colors = MiuixTheme.colorScheme
    val idleBorder = colors.onBackground.copy(alpha = 0.14f)
    val shape = RoundedCornerShape(LensCorner)
    Box(
        modifier =
            Modifier.size(width = 18.dp, height = 32.dp)
                .graphicsLayer {
                    val scale = 1f + 0.06f * pulse.value
                    scaleX = scale
                    scaleY = scale
                }
                // 外圈微光画在裁切之前，才能溢出画面边缘
                .drawBehind { drawLensGlow(pulse.value, accent) }
                .clip(shape)
                .background(colors.secondaryContainer)
                .drawBehind {
                    val p = pulse.value
                    drawLensFrame(frame.value, asleep, accent)
                    drawLensTouch(touch, p, idleBorder, accent)
                },
    )
}

/** 微缩画面外圈的呼吸微光：两层由外到内渐浓，随 [p] 散开、淡去。 */
private fun DrawScope.drawLensGlow(p: Float, accent: Color) {
    if (p <= 0.01f) return
    for ((spread, alpha) in listOf(3.dp to 0.16f, 1.5.dp to 0.3f)) {
        val s = spread.toPx() * p
        drawRoundRect(
            color = accent.copy(alpha = alpha * p),
            topLeft = Offset(-s, -s),
            size = Size(size.width + 2 * s, size.height + 2 * s),
            cornerRadius = CornerRadius(LensCorner.toPx() + s),
        )
    }
}

/** 微缩画面本身：有帧画帧（休眠时调暗），还没有帧时中间一个小点。 */
private fun DrawScope.drawLensFrame(
    current: VirtualScreen.Frame?,
    asleep: State<Boolean>,
    accent: Color,
) {
    if (current != null) {
        drawFrame(
            current,
            Rect(Offset.Zero, size),
            alpha = if (asleep.value) AsleepFrameAlpha else 1f,
            crop = true,
        )
    } else {
        drawRoundRect(
            accent.copy(alpha = 0.55f),
            Offset(center.x - 2.dp.toPx(), center.y - 2.dp.toPx()),
            Size(4.dp.toPx(), 4.dp.toPx()),
            CornerRadius(2.dp.toPx()),
        )
    }
}

/** 微缩画面上刚才那一下的落点，和随微光变色的描边。 */
private fun DrawScope.drawLensTouch(mark: TouchMark?, p: Float, idleBorder: Color, accent: Color) {
    if (mark != null && p > 0.02f) {
        drawCircle(
            accent.copy(alpha = 0.85f * p),
            2.2.dp.toPx(),
            Offset(mark.endX * size.width, mark.endY * size.height),
        )
    }
    val stroke = 0.85.dp.toPx()
    drawRoundRect(
        color = lerp(idleBorder, accent.copy(alpha = 0.78f), p),
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(LensCorner.toPx() - stroke / 2),
        style = Stroke(stroke),
    )
}

/**
 * 画廊装裱式的展开画面：外框 28dp + 10dp 留白 + 内画框 18dp 的同心圆角；画布尺寸 [canvas] 由窗口那边算好。 手指直接在画面上点按、滑动就是接管（Claude
 * 的手机操作先等着），坐标按画面归一化后作用到后台屏幕； 下面一行是应用名和模式，“接管 / 继续”与“全屏”。
 */
@Composable
internal fun AgentDisplayStage(
    focus: OverlayFocus,
    screen: VirtualScreen,
    canvas: StageSize,
    touch: TouchMark?,
    accent: Color,
    onTakeOver: () -> Unit,
    onFullscreen: (Rect, VirtualScreen.Frame?) -> Unit,
) {
    LiveFrames(screen, FrameRate.Live, keepAwake = true)
    val frame = screen.frames.collectAsState()
    val hasFrame by remember { derivedStateOf { frame.value != null } }
    val colors = MiuixTheme.colorScheme
    val phase = focus.phase
    // 暂停（橙）和接管（紫）都描边、都显示“继续”，颜色和文字跟着阶段走，分得开
    val held = phase.held
    val shape = RoundedCornerShape(StageCanvasCorner)
    val view = LocalView.current
    val placement = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val currentFocus by rememberUpdatedState(focus)
    val takeOver by rememberUpdatedState(onTakeOver)
    val idleLabel = focus.appName?.takeIf { it.isNotBlank() } ?: Strings.overlayStageCanvas()

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    // 尺寸按后台屏幕的比例和屏幕剩下的高度算好（横屏时收小），画面不变形
                    .size(canvas.width.dp, canvas.height.dp)
                    .onGloballyPositioned { placement[0] = it }
                    .clip(shape)
                    .background(colors.secondaryContainer)
                    .border(
                        if (held) 1.dp else 0.75.dp,
                        if (held) accent.copy(alpha = 0.55f) else colors.fill8,
                        shape,
                    )
                    .pointerInput(screen) { forwardTouches({ currentFocus }, { takeOver() }) }
                    .drawWithContent {
                        frame.value?.let { drawFrame(it, Rect(Offset.Zero, size)) }
                        drawContent()
                    },
            contentAlignment = Alignment.Center,
        ) {
            if (!hasFrame) {
                Text(
                    text = idleLabel,
                    color = colors.onBackgroundVariant.copy(alpha = 0.6f),
                    style = MiuixTheme.textStyles.footnote2,
                )
            }
            TouchEffect(touch, accent)
        }

        Spacer(Modifier.height(10.dp))

        StageControls(focus, accent, onTakeOver) {
            openFullscreen(placement[0], view, frame.value, onFullscreen)
        }
    }
}

/** 画面下面一行：应用名和模式，“接管 / 继续”与“全屏”。 */
@Composable
private fun StageControls(
    focus: OverlayFocus,
    accent: Color,
    onTakeOver: () -> Unit,
    onFullscreen: () -> Unit,
) {
    val phase = focus.phase
    val held = phase.held
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val mode = stageModeLabel(phase)
        val app = focus.appName
        Text(
            text = if (!app.isNullOrBlank()) "$app · $mode" else mode,
            color = if (held) accent else MiuixTheme.colorScheme.onBackgroundVariant,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        StagePill(
            label = if (held) Strings.overlayResume() else Strings.overlayTakeOver(),
            highlighted = held,
            accent = accent,
        ) {
            when (phase) {
                OverlayPhase.CONTROLLING -> focus.resumeAuto()
                OverlayPhase.PAUSED -> focus.resume()
                else -> onTakeOver()
            }
        }
        Spacer(Modifier.width(6.dp))
        StagePill(
            label = Strings.overlayFullscreen(),
            highlighted = false,
            accent = accent,
            onClick = onFullscreen,
        )
    }
}

/** 点“全屏”：从展开画面在屏幕上的位置放大出去；画面已经不在界面上时不动。 */
private fun openFullscreen(
    coordinates: LayoutCoordinates?,
    view: View,
    frame: VirtualScreen.Frame?,
    onFullscreen: (Rect, VirtualScreen.Frame?) -> Unit,
) {
    val placed = coordinates?.takeIf { it.isAttached } ?: return
    val location = IntArray(2).also(view::getLocationOnScreen)
    onFullscreen(
        placed.boundsInRoot().translate(location[0].toFloat(), location[1].toFloat()),
        frame,
    )
}

/** 手指在画面上的点按、滑动转给后台屏幕，坐标按画面归一化。第一下就是接管：先让 Claude 的手机操作等着（[takeOver]），再把这一下送过去。 */
private suspend fun PointerInputScope.forwardTouches(
    focus: () -> OverlayFocus,
    takeOver: () -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    val longPressMs = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val start = down.position
        val downAt = SystemClock.uptimeMillis()
        val (last, dragged) = followStroke(down, slop)
        val w = size.width.toFloat().coerceAtLeast(1f)
        val h = size.height.toFloat().coerceAtLeast(1f)
        val elapsed = (SystemClock.uptimeMillis() - downAt).toInt()
        takeOver()
        if (dragged) {
            focus()
                .userSwipe(
                    (start.x / w).coerceIn(0f, 1f),
                    (start.y / h).coerceIn(0f, 1f),
                    (last.x / w).coerceIn(0f, 1f),
                    (last.y / h).coerceIn(0f, 1f),
                    elapsed.coerceIn(180, 900),
                )
        } else {
            focus()
                .userTap(
                    (last.x / w).coerceIn(0f, 1f),
                    (last.y / h).coerceIn(0f, 1f),
                    elapsed >= longPressMs,
                )
        }
    }
}

/** 跟着手指直到全部抬起：返回最后的位置，和是否挪出过 [slop]（算滑动）。 */
private suspend fun AwaitPointerEventScope.followStroke(
    down: PointerInputChange,
    slop: Float,
): Pair<Offset, Boolean> {
    val start = down.position
    var last = start
    var dragged = false
    do {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
        if (change != null) {
            last = change.position
            if (hypot(last.x - start.x, last.y - start.y) > slop) dragged = true
            change.consume()
        }
    } while (event.changes.any { it.pressed })
    return last to dragged
}

@Composable
private fun StagePill(label: String, highlighted: Boolean, accent: Color, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier =
            Modifier.height(27.dp)
                .clip(CircleShape)
                .background(
                    if (highlighted) accent.copy(alpha = 0.16f) else colors.secondaryVariant,
                )
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (highlighted) accent else colors.onBackground,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 画面里的触摸光效：点按是两层阻尼水波纹，滑动是流光尾迹。Claude 的和用户接管时的都画。 */
@Composable
private fun TouchEffect(mark: TouchMark?, accent: Color) {
    if (mark == null) return
    // 画面刚出现时不补放之前那一下
    val progress = remember(mark) { Animatable(if (mark.fresh()) 0f else 1f) }
    LaunchedEffect(mark) {
        progress.animateTo(
            1f,
            tween(
                touchEffectMs(mark),
                easing = if (mark.swipe) LinearEasing else FastOutSlowInEasing,
            ),
        )
    }
    Canvas(Modifier.fillMaxSize()) {
        val p = progress.value
        if (p >= 0.99f) return@Canvas
        if (!mark.swipe) drawTapRipple(mark, p, accent) else drawSwipeTrail(mark, p, accent)
    }
}

private fun touchEffectMs(mark: TouchMark): Int =
    when {
        mark.swipe -> SwipeEffectMs
        mark.longPress -> 540
        else -> 380
    }

/** 点按：两层阻尼水波纹（长按的散得更大），中间一个渐隐的落点。 */
private fun DrawScope.drawTapRipple(mark: TouchMark, p: Float, accent: Color) {
    val center = Offset(mark.x * size.width, mark.y * size.height)
    val radius = lerp(3.5.dp.toPx(), (if (mark.longPress) 20.dp else 15.dp).toPx(), p)
    val fade = (1f - p).coerceIn(0f, 1f)
    drawCircle(accent.copy(alpha = 0.18f * fade), radius, center)
    drawCircle(
        accent.copy(alpha = 0.88f * fade),
        radius,
        center,
        style = Stroke(1.6.dp.toPx()),
    )
    drawCircle(accent.copy(alpha = 0.92f * (1f - p * 0.7f)), 2.8.dp.toPx(), center)
}

/** 滑动：从起点拖到当前头部的流光尾迹，最后四分之一段淡出。 */
private fun DrawScope.drawSwipeTrail(mark: TouchMark, p: Float, accent: Color) {
    val start = Offset(mark.x * size.width, mark.y * size.height)
    val head =
        Offset(
            lerp(start.x, mark.endX * size.width, p),
            lerp(start.y, mark.endY * size.height, p),
        )
    val fade = if (p < 0.75f) 1f else (1f - (p - 0.75f) / 0.25f).coerceIn(0f, 1f)
    drawLine(accent.copy(alpha = 0.55f * fade), start, head, 2.2.dp.toPx(), StrokeCap.Round)
    drawCircle(accent.copy(alpha = 0.9f * fade), 4.2.dp.toPx(), head)
    drawCircle(
        accent.copy(alpha = 0.36f * fade),
        8.5.dp.toPx(),
        head,
        style = Stroke(1.2.dp.toPx()),
    )
}

/**
 * “全屏”的放大过渡（单独的全屏窗口里画）：画面从展开画面的位置以弹簧放大贴满整屏，圆角同步过渡到屏幕的物理圆角。 放到整屏时定格在那一帧、调 [onFull]
 * 把应用移到主屏；停一会儿等应用在主屏画出来再淡出，最后 [onDone]。
 */
@Composable
internal fun MaterializeOverlay(
    screen: VirtualScreen,
    initial: VirtualScreen.Frame?,
    from: Rect,
    endCornerPx: Float,
    onFull: () -> Unit,
    onDone: () -> Unit,
) {
    var still by remember { mutableStateOf<VirtualScreen.Frame?>(null) }
    // 定格以后就不再要帧：应用移走后后台屏幕上就不是它了
    if (still == null) LiveFrames(screen, FrameRate.Live, keepAwake = true)
    val live = screen.frames.collectAsState()
    val progress = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val startCorner = with(LocalDensity.current) { StageCanvasCorner.toPx() }
    LaunchedEffect(Unit) {
        val grow = launch { progress.animateTo(1f, spring(0.88f, Spring.StiffnessMediumLow)) }
        snapshotFlow { progress.value }.first { it >= 0.97f }
        still = (live.value ?: initial)?.let(::freeze)
        onFull()
        delay(HandoffHoldMs)
        grow.join()
        fade.animateTo(0f, tween(220))
        onDone()
    }
    Canvas(Modifier.fillMaxSize()) {
        val frame = still ?: live.value ?: initial ?: return@Canvas
        val p = progress.value
        val rect =
            Rect(
                lerp(from.left, 0f, p),
                lerp(from.top, 0f, p),
                lerp(from.right, size.width, p),
                lerp(from.bottom, size.height, p),
            )
        val corner = lerp(startCorner, endCornerPx, p.coerceIn(0f, 1f))
        clipPath(Path().apply { addRoundRect(RoundRect(rect, CornerRadius(corner))) }) {
            drawFrame(frame, rect, alpha = fade.value)
        }
    }
}

/** 拷一份只含画面那一截的位图：帧的位图是轮换重用的，定格要自己的一份。 */
private fun freeze(frame: VirtualScreen.Frame): VirtualScreen.Frame = runCatching {
    val bitmap = Bitmap.createBitmap(frame.bitmap, 0, 0, frame.width, frame.height)
    VirtualScreen.Frame(bitmap, bitmap.width, bitmap.height, frame.sequence)
}
    .getOrDefault(frame)

/** 刚发生的触摸才放光效。 */
private fun TouchMark.fresh() = SystemClock.uptimeMillis() - at < FreshTouchMs

private const val FreshTouchMs = 800L

/** 滑动光效的时长（Claude 的滑动时长不随触摸一起给，取 Eta 的默认）。 */
private const val SwipeEffectMs = 420

/** 放到整屏、开始移交以后定格多久再淡出：给应用在主屏上画出第一帧的时间。 */
private const val HandoffHoldMs = 450L

private val LensCorner = 6.dp
private val StageCanvasCorner = 18.dp
