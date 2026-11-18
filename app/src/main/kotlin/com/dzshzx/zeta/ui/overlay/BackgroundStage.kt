package com.dzshzx.zeta.ui.overlay

import android.graphics.Bitmap
import android.os.SystemClock
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.dzshzx.zeta.phone.OverlayFocus
import com.dzshzx.zeta.phone.OverlayPhase
import com.dzshzx.zeta.phone.TouchMark
import com.dzshzx.zeta.phone.VirtualScreen
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.theme.CouiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 微缩画面的刷新间隔：看个大概，省电。 */
private const val LensIntervalMs = 500L

/** 展开画面的刷新间隔（约 20 帧每秒）。 */
private const val StageIntervalMs = 50L

/**
 * 镶在收起胶囊右侧的 18×32dp 实时微缩画面。平时安静地放后台屏幕的缩略画面（休眠时是休眠前那一帧，调暗）；
 * 后台屏幕上有点按、滑动时外圈泛起一下柔和的呼吸微光。
 */
@Composable
internal fun MicroLens(screen: VirtualScreen, touch: TouchMark?, accent: Color) {
    LiveFrames(screen, LensIntervalMs, keepAwake = false)
    val frame = screen.frames.collectAsState()
    val asleep = screen.asleep.collectAsState()
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(touch) {
        if (touch == null || !touch.fresh()) return@LaunchedEffect
        pulse.snapTo(1f)
        pulse.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
    }
    val idleBorder = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val shape = RoundedCornerShape(LensCorner)
    Box(
        modifier = Modifier
            .size(width = 18.dp, height = 32.dp)
            .graphicsLayer {
                val scale = 1f + 0.06f * pulse.value
                scaleX = scale
                scaleY = scale
            }
            // 外圈微光画在裁切之前，才能溢出画面边缘
            .drawBehind {
                val p = pulse.value
                if (p <= 0.01f) return@drawBehind
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
            .clip(shape)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .drawBehind {
                val p = pulse.value
                val current = frame.value
                if (current != null) {
                    drawFrame(current, Rect(Offset.Zero, size), alpha = if (asleep.value) 0.45f else 1f, crop = true)
                } else {
                    drawRoundRect(accent.copy(alpha = 0.55f), Offset(center.x - 2.dp.toPx(), center.y - 2.dp.toPx()), Size(4.dp.toPx(), 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                }
                val mark = touch
                if (mark != null && p > 0.02f) {
                    drawCircle(accent.copy(alpha = 0.85f * p), 2.2.dp.toPx(), Offset(mark.endX * size.width, mark.endY * size.height))
                }
                val stroke = 0.85.dp.toPx()
                drawRoundRect(
                    color = lerp(idleBorder, accent.copy(alpha = 0.78f), p),
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(LensCorner.toPx() - stroke / 2),
                    style = Stroke(stroke),
                )
            },
    )
}

/**
 * 画廊装裱式的展开画面：外框 28dp + 10dp 留白 + 内画框 18dp 的同心圆角。
 * 手指直接在画面上点按、滑动就是接管（Claude 的手机操作先等着），坐标按画面归一化后作用到后台屏幕；
 * 下面一行是应用名和模式，“接管 / 继续”与“全屏”。
 */
@Composable
internal fun BackgroundStage(
    focus: OverlayFocus,
    screen: VirtualScreen,
    touch: TouchMark?,
    accent: Color,
    onTakeOver: () -> Unit,
    onFullscreen: (Rect, VirtualScreen.Frame?) -> Unit,
) {
    LiveFrames(screen, StageIntervalMs, keepAwake = true)
    val frame = screen.frames.collectAsState()
    val hasFrame by remember { derivedStateOf { frame.value != null } }
    val colors = MiuixTheme.colorScheme
    val controlling = focus.userControlling || focus.phase == OverlayPhase.PAUSED
    val shape = RoundedCornerShape(StageCanvasCorner)
    val ratio = if (screen.width > 0 && screen.height > 0) (screen.width / screen.height.toFloat()).coerceIn(0.38f, 0.75f) else 9f / 19.5f
    val view = LocalView.current
    val placement = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val currentFocus by rememberUpdatedState(focus)
    val takeOver by rememberUpdatedState(onTakeOver)
    val idleLabel = focus.appName?.takeIf { it.isNotBlank() } ?: "后台画布"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .height(StageCanvasHeight)
                .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                .onGloballyPositioned { placement[0] = it }
                .clip(shape)
                .background(colors.surfaceContainerHigh)
                .border(0.75.dp, if (controlling) accent.copy(alpha = 0.42f) else colors.onSurface.copy(alpha = 0.08f), shape)
                .pointerInput(screen) {
                    val slop = viewConfiguration.touchSlop
                    val longPressMs = viewConfiguration.longPressTimeoutMillis
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val start = down.position
                        val downAt = SystemClock.uptimeMillis()
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
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val elapsed = (SystemClock.uptimeMillis() - downAt).toInt()
                        // 第一下就是接管：先让 Claude 的手机操作等着，再把这一下送过去
                        takeOver()
                        if (dragged) {
                            currentFocus.userSwipe(
                                (start.x / w).coerceIn(0f, 1f),
                                (start.y / h).coerceIn(0f, 1f),
                                (last.x / w).coerceIn(0f, 1f),
                                (last.y / h).coerceIn(0f, 1f),
                                elapsed.coerceIn(180, 900),
                            )
                        } else {
                            currentFocus.userTap((last.x / w).coerceIn(0f, 1f), (last.y / h).coerceIn(0f, 1f), elapsed >= longPressMs)
                        }
                    }
                }
                .drawWithContent {
                    frame.value?.let { drawFrame(it, Rect(Offset.Zero, size)) }
                    drawContent()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (!hasFrame) {
                Text(text = idleLabel, color = colors.onSurfaceVariantSummary.copy(alpha = 0.6f), fontSize = 12.sp)
            }
            TouchEffect(touch, accent)
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val mode = if (controlling) "手动接管中" else "后台画布"
            val app = focus.appName
            Text(
                text = if (!app.isNullOrBlank()) "$app · $mode" else mode,
                color = colors.onSurfaceVariantSummary,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            StagePill(label = if (controlling) "继续" else "接管", highlighted = controlling, accent = accent) {
                when {
                    focus.userControlling -> focus.resumeAuto()
                    focus.phase == OverlayPhase.PAUSED -> focus.resume()
                    else -> onTakeOver()
                }
            }
            Spacer(Modifier.width(6.dp))
            StagePill(label = "全屏", highlighted = false, accent = accent) {
                val coordinates = placement[0]?.takeIf { it.isAttached } ?: return@StagePill
                val location = IntArray(2).also(view::getLocationOnScreen)
                onFullscreen(coordinates.boundsInRoot().translate(location[0].toFloat(), location[1].toFloat()), frame.value)
            }
        }
    }
}

@Composable
private fun StagePill(label: String, highlighted: Boolean, accent: Color, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .height(27.dp)
            .clip(CouiCapsuleShape)
            .background(if (highlighted) accent.copy(alpha = 0.16f) else colors.surfaceContainerHigh)
            .couiButtonFeedback(CouiTheme.isDark, source, enabled = true)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (highlighted) accent else colors.onSurface,
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
        val duration = when {
            mark.swipe -> SwipeEffectMs
            mark.longPress -> 540
            else -> 380
        }
        progress.animateTo(1f, tween(duration, easing = if (mark.swipe) LinearEasing else FastOutSlowInEasing))
    }
    Canvas(Modifier.fillMaxSize()) {
        val p = progress.value
        if (p >= 0.99f) return@Canvas
        if (!mark.swipe) {
            val center = Offset(mark.x * size.width, mark.y * size.height)
            val radius = lerp(3.5.dp.toPx(), (if (mark.longPress) 20.dp else 15.dp).toPx(), p)
            val fade = (1f - p).coerceIn(0f, 1f)
            drawCircle(accent.copy(alpha = 0.18f * fade), radius, center)
            drawCircle(accent.copy(alpha = 0.88f * fade), radius, center, style = Stroke(1.6.dp.toPx()))
            drawCircle(accent.copy(alpha = 0.92f * (1f - p * 0.7f)), 2.8.dp.toPx(), center)
        } else {
            val start = Offset(mark.x * size.width, mark.y * size.height)
            val head = Offset(lerp(start.x, mark.endX * size.width, p), lerp(start.y, mark.endY * size.height, p))
            val fade = if (p < 0.75f) 1f else (1f - (p - 0.75f) / 0.25f).coerceIn(0f, 1f)
            drawLine(accent.copy(alpha = 0.55f * fade), start, head, 2.2.dp.toPx(), StrokeCap.Round)
            drawCircle(accent.copy(alpha = 0.9f * fade), 4.2.dp.toPx(), head)
            drawCircle(accent.copy(alpha = 0.36f * fade), 8.5.dp.toPx(), head, style = Stroke(1.2.dp.toPx()))
        }
    }
}

/**
 * “全屏”的放大过渡（单独的全屏窗口里画）：画面从展开画面的位置以弹簧放大贴满整屏，圆角同步过渡到屏幕的物理圆角。
 * 放到整屏时定格在那一帧、调 [onFull] 把应用移到主屏；停一会儿等应用在主屏画出来再淡出，最后 [onDone]。
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
    if (still == null) LiveFrames(screen, StageIntervalMs, keepAwake = true)
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
        val rect = Rect(
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

/** 在屏上的时候才登记要帧，离开就停。后台屏幕已经没了时不登记。 */
@Composable
private fun LiveFrames(screen: VirtualScreen, intervalMs: Long, keepAwake: Boolean) {
    DisposableEffect(screen, intervalMs, keepAwake) {
        val stop = if (screen.alive) screen.watch(intervalMs, keepAwake) else null
        onDispose { stop?.invoke() }
    }
}

/** 拷一份只含画面那一截的位图：帧的位图是轮换重用的，定格要自己的一份。 */
private fun freeze(frame: VirtualScreen.Frame): VirtualScreen.Frame = runCatching {
    val bitmap = Bitmap.createBitmap(frame.bitmap, 0, 0, frame.width, frame.height)
    VirtualScreen.Frame(bitmap, bitmap.width, bitmap.height, frame.sequence)
}.getOrDefault(frame)

/**
 * 把一帧画进 [dst]。位图可能比画面宽（行尾对齐的空白），只取左边 [VirtualScreen.Frame.width] 那一截；
 * [crop] 时按 [dst] 的比例居中裁切，否则拉伸铺满。
 */
private fun DrawScope.drawFrame(frame: VirtualScreen.Frame, dst: Rect, alpha: Float = 1f, crop: Boolean = false) {
    if (frame.bitmap.isRecycled || dst.width <= 0f || dst.height <= 0f) return
    var srcX = 0
    var srcY = 0
    var srcW = frame.width
    var srcH = frame.height
    if (crop) {
        val ratio = dst.width / dst.height
        if (srcW.toFloat() / srcH > ratio) {
            val w = (srcH * ratio).roundToInt().coerceIn(1, srcW)
            srcX = (srcW - w) / 2
            srcW = w
        } else {
            val h = (srcW / ratio).roundToInt().coerceIn(1, srcH)
            srcY = (srcH - h) / 2
            srcH = h
        }
    }
    drawImage(
        image = frame.bitmap.asImageBitmap(),
        srcOffset = IntOffset(srcX, srcY),
        srcSize = IntSize(srcW, srcH),
        dstOffset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt()),
        dstSize = IntSize(dst.width.roundToInt(), dst.height.roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Medium,
    )
}

/** 刚发生的触摸才放光效。 */
private fun TouchMark.fresh() = SystemClock.uptimeMillis() - at < FreshTouchMs

private const val FreshTouchMs = 800L

/** 滑动光效的时长（Claude 的滑动时长不随触摸一起给，取 Eta 的默认）。 */
private const val SwipeEffectMs = 420

/** 放到整屏、开始移交以后定格多久再淡出：给应用在主屏上画出第一帧的时间。 */
private const val HandoffHoldMs = 450L

private val LensCorner = 6.dp
private val StageCanvasCorner = 18.dp
private val StageCanvasHeight = 336.dp
