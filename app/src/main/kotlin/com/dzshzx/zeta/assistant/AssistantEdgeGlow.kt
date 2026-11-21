package com.dzshzx.zeta.assistant

import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Shader
import android.view.RoundedCorner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// 拷自 Eta 的 agent/voice/EtaAssistantEdgeGlow.kt（GPL-3.0）：只留第一次唤起的光效（没有语音，
// 不随音量呼吸）；时钟从逐帧的 withFrameNanos 换成 Animatable（截图测试里动画时长按 0 算，直接放完），
// 屏幕下角的圆角取浮窗所在那块屏的。

private const val POWER_BUTTON_HEIGHT_RATIO = 0.355f
private const val POWER_HALO_HOLD_SECONDS = 0.06f
private const val POWER_HALO_MOVE_SECONDS = 0.46f
private const val POWER_HALO_END_SECONDS = 0.64f
private const val STREAMER_START_SECONDS = 0.05f
private const val ASSISTANT_EDGE_ARRIVAL_MS = 220L
private const val BOTTOM_REVEAL_SECONDS = 0.20f
private const val STREAMER_SETTLE_SECONDS = 0.48f

private const val edgeArrivalSeconds = ASSISTANT_EDGE_ARRIVAL_MS / 1000f
private const val bottomRevealEndSeconds = edgeArrivalSeconds + BOTTOM_REVEAL_SECONDS

/** 流光落到底边、底部光场起来的时刻（秒）。 */
internal const val WakeHandoffSeconds = bottomRevealEndSeconds
private val entranceDurationSeconds = max(POWER_HALO_END_SECONDS, STREAMER_SETTLE_SECONDS)

private val haloEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private val streamerEasing = CubicBezierEasing(0.20f, 0f, 0.12f, 1f)
private val bottomRevealEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

private val powerAuroraCore = Color(0xFF93C5FD).copy(alpha = 0.42f)
private val powerAuroraMid = Color(0xFFC4B5FD).copy(alpha = 0.26f)

private val streamerGradientArgb =
    intArrayOf(
        Color(0x0060A5FA).toArgb(),
        Color(0xCC60A5FA).toArgb(),
        Color(0xE6A78BFA).toArgb(),
        Color(0xF2F472B6).toArgb(),
        Color(0xFFFFF3DC).toArgb(),
    )

private val bottomRimGradientArgb =
    intArrayOf(
        Color(0x0060A5FA).toArgb(),
        Color(0xCC60A5FA).toArgb(),
        Color(0xE6A78BFA).toArgb(),
        Color(0xE6F472B6).toArgb(),
        Color(0xCCFBBF24).toArgb(),
        Color(0x00A78BFA).toArgb(),
    )

private val bottomLobePalettes =
    listOf(
        listOf(Color(0xFF4F8BFF).copy(alpha = 0.66f), Color.Transparent),
        listOf(Color(0xFFA78BFA).copy(alpha = 0.58f), Color.Transparent),
        listOf(Color(0xFFFF7AC6).copy(alpha = 0.62f), Color.Transparent),
        listOf(Color(0xFFFFE4B5).copy(alpha = 0.54f), Color.Transparent),
    )

/** 浮窗所在那块屏的左下、右下圆角半径（像素）；取不到时用 36dp。 */
@Composable
private fun rememberBottomCornerRadiiPx(): Pair<Float, Float> {
    val display = LocalView.current.display
    val fallbackPx = with(LocalDensity.current) { 36.dp.toPx() }
    return remember(display, fallbackPx) {
        fun radius(position: Int): Float =
            runCatching { display?.getRoundedCorner(position)?.radius }
                .getOrNull()
                ?.takeIf { it > 0 }
                ?.toFloat() ?: fallbackPx
        radius(RoundedCorner.POSITION_BOTTOM_LEFT) to radius(RoundedCorner.POSITION_BOTTOM_RIGHT)
    }
}

/**
 * 唤起光效（照 Eta）：从右侧电源键激发极光光晕，流光沿真实屏幕圆角连续包覆滑入底部，底部升起一片柔光和贴边的细边光， 放完（0.64 秒）后淡出。
 *
 * 时钟走到 [WakeHandoffSeconds] 时调一次 [onEntranceFinished]。[exiting] 为真（浮窗要关了）时立刻淡出。
 */
@Composable
internal fun AssistantEdgeGlow(
    exiting: Boolean,
    onEntranceFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clock = remember { Animatable(0f) }
    var firstWakeComplete by remember { mutableStateOf(false) }
    val entranceFinished by rememberUpdatedState(onEntranceFinished)
    val opacity by
        animateFloatAsState(
            targetValue = if (!firstWakeComplete && !exiting) 1f else 0f,
            animationSpec =
                tween(
                    durationMillis = if (!firstWakeComplete && !exiting) 140 else 260,
                    easing = FastOutSlowInEasing,
                ),
            label = "assistant_edge_glow",
        )
    LaunchedEffect(Unit) {
        launch {
            snapshotFlow { clock.value >= WakeHandoffSeconds }.first { it }
            entranceFinished()
        }
        val durationMs = (entranceDurationSeconds * 1000f).toInt()
        clock.animateTo(entranceDurationSeconds, tween(durationMs, easing = LinearEasing))
        firstWakeComplete = true
    }
    if (opacity > 0f) EdgeGlowCanvas({ clock.value }, { opacity }, modifier)
}

/** 停在 [seconds] 那一刻的一帧，不动；截图测试用来看光效放到一半的样子。 */
@Composable
internal fun AssistantEdgeGlowFrame(seconds: Float, modifier: Modifier = Modifier) {
    EdgeGlowCanvas({ seconds }, { 1f }, modifier)
}

/** 铺满的光效层。[seconds] 和 [opacity] 只在绘制阶段读，动画每帧只重画、不重组。 */
@Composable
private fun EdgeGlowCanvas(seconds: () -> Float, opacity: () -> Float, modifier: Modifier) {
    val (bottomLeftRadiusPx, bottomRightRadiusPx) = rememberBottomCornerRadiiPx()
    val painter = remember { EdgeGlowPainter() }
    Canvas(modifier.fillMaxSize()) {
        val alpha = opacity()
        if (alpha <= 0f || size.width <= 0f || size.height <= 0f) return@Canvas
        painter.draw(this, seconds(), alpha, bottomLeftRadiusPx, bottomRightRadiusPx)
    }
}

/** 光效的路径和画笔（照 Eta 在 remember 里建的那几样），一帧一帧往上画。 */
private class EdgeGlowPainter {
    private val fullTrackPath = Path()
    private val segmentPath = Path()
    private val cradleRimPath = Path()
    private val pathMeasure = PathMeasure()
    private val headPos = FloatArray(2)
    private val tailPos = FloatArray(2)
    private val haloStrokePaint = strokePaint()
    private val coreStrokePaint = strokePaint()

    /** 这一帧的几何：屏幕尺寸、电源键高度、两个下角（内收后）的半径。 */
    private class Frame(
        val width: Float,
        val height: Float,
        val density: Float,
        val inset: Float,
        val cornerL: Float,
        val cornerR: Float,
        val hardware: Boolean,
    ) {
        val powerY = height * POWER_BUTTON_HEIGHT_RATIO
    }

    fun draw(
        scope: DrawScope,
        seconds: Float,
        alpha: Float,
        bottomLeftRadiusPx: Float,
        bottomRightRadiusPx: Float,
    ) =
        with(scope) {
            val width = size.width
            val height = size.height
            val inset = 1.5f * density
            val limit = minOf(width, height) * 0.22f
            val frame =
                Frame(
                    width = width,
                    height = height,
                    density = density,
                    inset = inset,
                    cornerL = (bottomLeftRadiusPx - inset).coerceIn(12f * density, limit),
                    cornerR = (bottomRightRadiusPx - inset).coerceIn(12f * density, limit),
                    hardware = drawContext.canvas.nativeCanvas.isHardwareAccelerated,
                )
            val wakeEntry = height > width
            // 1. 电源键极光呼吸点亮。
            if (wakeEntry && seconds < POWER_HALO_END_SECONDS) drawPowerHalo(frame, seconds, alpha)
            // 2. 沿右侧边框经右下真实圆角包覆滑入底边的流光拖尾。
            if (
                wakeEntry && seconds >= STREAMER_START_SECONDS && seconds < STREAMER_SETTLE_SECONDS
            ) {
                drawStreamer(frame, seconds, alpha)
            }
            // 3. 底部流光温床与双侧圆角柔光托底。
            val bottomEntry =
                if (wakeEntry) {
                    bottomRevealEasing.transform(
                        ((seconds - edgeArrivalSeconds * 0.78f) / (BOTTOM_REVEAL_SECONDS * 1.15f))
                            .coerceIn(0f, 1f),
                    )
                } else {
                    1f
                }
            if (bottomEntry > 0f) {
                drawBottomLobes(frame, seconds, alpha, bottomEntry)
                drawCradleRim(frame, seconds, alpha, bottomEntry)
            }
        }

    private fun DrawScope.drawPowerHalo(frame: Frame, seconds: Float, alpha: Float) {
        val early = (seconds / POWER_HALO_HOLD_SECONDS).coerceIn(0f, 1f)
        val late =
            haloEasing.transform(
                ((seconds - POWER_HALO_HOLD_SECONDS) /
                        (POWER_HALO_END_SECONDS - POWER_HALO_HOLD_SECONDS))
                    .coerceIn(0f, 1f),
            )
        val radiusScale = frame.density / 3f
        val radius = (((240f + 560f * early) + 880f * late) * radiusScale).coerceAtLeast(1f)
        val driftY = haloEasing.transform((seconds / POWER_HALO_MOVE_SECONDS).coerceIn(0f, 1f))
        val center =
            Offset(
                frame.width * 1.24f,
                frame.powerY + (frame.height * 0.62f - frame.powerY) * 0.55f * driftY,
            )
        val haloFade = if (seconds < POWER_HALO_HOLD_SECONDS) early else (1f - late)
        drawCircle(
            brush =
                Brush.radialGradient(
                    colorStops =
                        arrayOf(
                            0f to powerAuroraCore,
                            0.46f to powerAuroraMid,
                            1f to Color.Transparent,
                        ),
                    center = center,
                    radius = radius,
                ),
            center = center,
            radius = radius,
            alpha = alpha * haloFade,
        )
    }

    private fun buildTrack(frame: Frame) {
        val width = frame.width
        val height = frame.height
        val inset = frame.inset
        val cornerR = frame.cornerR
        val cornerL = frame.cornerL
        fullTrackPath.reset()
        fullTrackPath.moveTo(width - inset, frame.powerY)
        fullTrackPath.lineTo(width - inset, height - inset - cornerR)
        fullTrackPath.arcTo(
            width - inset - cornerR * 2f,
            height - inset - cornerR * 2f,
            width - inset,
            height - inset,
            0f,
            90f,
            false,
        )
        fullTrackPath.lineTo(inset + cornerL, height - inset)
        fullTrackPath.arcTo(
            inset,
            height - inset - cornerL * 2f,
            inset + cornerL * 2f,
            height - inset,
            90f,
            72f,
            false,
        )
    }

    private fun DrawScope.drawStreamer(frame: Frame, seconds: Float, alpha: Float) {
        val rawProgress =
            ((seconds - STREAMER_START_SECONDS) / (bottomRevealEndSeconds - STREAMER_START_SECONDS))
                .coerceIn(0f, 1f)
        val travel = streamerEasing.transform(rawProgress)
        val fadeOut =
            (1f -
                ((seconds - bottomRevealEndSeconds) /
                        (STREAMER_SETTLE_SECONDS - bottomRevealEndSeconds))
                    .coerceIn(0f, 1f))
        buildTrack(frame)
        pathMeasure.setPath(fullTrackPath, false)
        val totalLength = pathMeasure.length
        if (totalLength <= 1f) return
        val headDist = totalLength * travel
        val tailSpan = totalLength * (0.34f + 0.18f * sin(travel * Math.PI).toFloat())
        val tailDist = (headDist - tailSpan).coerceAtLeast(0f)
        segmentPath.reset()
        if (headDist <= tailDist + 1f) return
        if (!pathMeasure.getSegment(tailDist, headDist, segmentPath, true)) return
        pathMeasure.getPosTan(headDist, headPos, null)
        pathMeasure.getPosTan(tailDist, tailPos, null)
        val ribbonShader =
            LinearGradient(
                tailPos[0],
                tailPos[1],
                headPos[0] + 0.5f,
                headPos[1] + 0.5f,
                streamerGradientArgb,
                floatArrayOf(0f, 0.25f, 0.62f, 0.86f, 1f),
                Shader.TileMode.CLAMP,
            )
        val density = frame.density
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            haloStrokePaint.shader = ribbonShader
            haloStrokePaint.strokeWidth = 16f * density
            haloStrokePaint.maskFilter =
                if (frame.hardware) BlurMaskFilter(11f * density, BlurMaskFilter.Blur.NORMAL)
                else null
            haloStrokePaint.alpha =
                (alpha * fadeOut * (if (frame.hardware) 0.62f else 0.24f) * 255f).toInt()
            native.drawPath(segmentPath, haloStrokePaint)

            coreStrokePaint.shader = ribbonShader
            coreStrokePaint.strokeWidth = 3.2f * density
            coreStrokePaint.alpha = (alpha * fadeOut * 0.92f * 255f).toInt()
            native.drawPath(segmentPath, coreStrokePaint)
        }
        val headCenter = Offset(headPos[0], headPos[1])
        val headRadius = 20f * density
        drawCircle(
            brush =
                Brush.radialGradient(
                    colors =
                        listOf(
                            Color(0xFFFFF8EB),
                            Color(0xFFF472B6).copy(alpha = 0.58f),
                            Color.Transparent,
                        ),
                    center = headCenter,
                    radius = headRadius,
                ),
            center = headCenter,
            radius = headRadius,
            alpha = alpha * fadeOut * 0.72f,
        )
    }

    private fun DrawScope.drawBottomLobes(
        frame: Frame,
        seconds: Float,
        alpha: Float,
        bottomEntry: Float,
    ) {
        val width = frame.width
        val slowDrift = sin(seconds * 0.95f) * 0.045f
        val secondaryDrift = cos(seconds * 0.68f) * 0.03f
        val glowHeight = 88f * frame.density * (0.72f + 0.28f * bottomEntry)
        val centersX =
            floatArrayOf(
                (0.12f + slowDrift) * width,
                (0.39f - secondaryDrift) * width,
                (0.66f + secondaryDrift) * width,
                (0.88f - slowDrift * 0.8f) * width,
            )
        val radiiRatio = floatArrayOf(0.32f, 0.38f, 0.36f, 0.30f)
        val radius = glowHeight.coerceAtLeast(1f)
        for (index in bottomLobePalettes.indices) {
            val center = Offset(centersX[index], frame.height + glowHeight * 0.12f)
            val horizontalRadius = (width * radiiRatio[index]).coerceAtLeast(glowHeight)
            val lobeAlpha = (alpha * bottomEntry * 0.78f).coerceIn(0f, 1f)
            withTransform({ scale(horizontalRadius / radius, 1f, pivot = center) }) {
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            colors = bottomLobePalettes[index],
                            center = center,
                            radius = radius,
                        ),
                    center = center,
                    radius = radius,
                    alpha = lobeAlpha,
                )
            }
        }
    }

    /** 贴合底部与左右圆角的细腻呼吸边光。 */
    private fun DrawScope.drawCradleRim(
        frame: Frame,
        seconds: Float,
        alpha: Float,
        bottomEntry: Float,
    ) {
        val width = frame.width
        val height = frame.height
        val inset = frame.inset
        val cornerL = frame.cornerL
        val cornerR = frame.cornerR
        val density = frame.density
        val rimClimb = 22f * density * bottomEntry
        cradleRimPath.reset()
        cradleRimPath.moveTo(inset, height - inset - cornerL - rimClimb)
        cradleRimPath.lineTo(inset, height - inset - cornerL)
        cradleRimPath.arcTo(
            inset,
            height - inset - cornerL * 2f,
            inset + cornerL * 2f,
            height - inset,
            180f,
            -90f,
            false,
        )
        cradleRimPath.lineTo(width - inset - cornerR, height - inset)
        cradleRimPath.arcTo(
            width - inset - cornerR * 2f,
            height - inset - cornerR * 2f,
            width - inset,
            height - inset,
            90f,
            -90f,
            false,
        )
        cradleRimPath.lineTo(width - inset, height - inset - cornerR - rimClimb)

        val shimmerShift = sin(seconds * 1.35f) * width * 0.12f
        val rimShader =
            LinearGradient(
                shimmerShift,
                height,
                width + shimmerShift,
                height,
                bottomRimGradientArgb,
                floatArrayOf(0f, 0.18f, 0.42f, 0.64f, 0.84f, 1f),
                Shader.TileMode.CLAMP,
            )
        val rimBreath = 0.46f + 0.12f * sin(seconds * 2.1f)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            if (frame.hardware) {
                haloStrokePaint.shader = rimShader
                haloStrokePaint.strokeWidth = 8.5f * density
                haloStrokePaint.maskFilter =
                    BlurMaskFilter(8f * density, BlurMaskFilter.Blur.NORMAL)
                haloStrokePaint.alpha =
                    (alpha * bottomEntry * rimBreath * 0.65f * 255f).toInt().coerceIn(0, 255)
                native.drawPath(cradleRimPath, haloStrokePaint)
            }
            coreStrokePaint.shader = rimShader
            coreStrokePaint.strokeWidth = 2.2f * density
            coreStrokePaint.alpha =
                (alpha * bottomEntry * rimBreath * 0.88f * 255f).toInt().coerceIn(0, 255)
            native.drawPath(cradleRimPath, coreStrokePaint)
        }
    }

    private companion object {
        fun strokePaint() =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
    }
}
