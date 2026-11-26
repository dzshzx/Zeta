package com.dzshzx.zeta.assistant

import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Shader
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// 拷自 Eta 的 agent/voice/ui/EtaAssistantEdgeGlow.kt（GPL-3.0）：只留第一次唤起的光效（没有语音，
// 不随音量呼吸）；时钟从逐帧的 withFrameNanos 换成 Animatable（截图测试里动画时长按 0 算，直接放完），
// 屏幕下角的圆角由浮窗的 Activity 读好传进来。

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

// 下面几个径向渐变都按圆心在原点、半径 1 建一次，画的时候平移、缩放到位。
private val powerHaloBrush =
    Brush.radialGradient(
        0f to powerAuroraCore,
        0.46f to powerAuroraMid,
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = 1f,
    )

private val streamerHeadBrush =
    Brush.radialGradient(
        listOf(Color(0xFFFFF8EB), Color(0xFFF472B6).copy(alpha = 0.58f), Color.Transparent),
        center = Offset.Zero,
        radius = 1f,
    )

private val bottomLobeBrushes =
    listOf(
            Color(0xFF4F8BFF).copy(alpha = 0.66f),
            Color(0xFFA78BFA).copy(alpha = 0.58f),
            Color(0xFFFF7AC6).copy(alpha = 0.62f),
            Color(0xFFFFE4B5).copy(alpha = 0.54f),
        )
        .map { Brush.radialGradient(listOf(it, Color.Transparent), Offset.Zero, 1f) }

/** 底部四团柔光的横向半径占屏宽的比例。 */
private val bottomLobeRadiusRatios = floatArrayOf(0.32f, 0.38f, 0.36f, 0.30f)

private val streamerGradientArgb =
    intArrayOf(
        Color(0x0060A5FA).toArgb(),
        Color(0xCC60A5FA).toArgb(),
        Color(0xE6A78BFA).toArgb(),
        Color(0xF2F472B6).toArgb(),
        Color(0xFFFFF3DC).toArgb(),
    )
private val streamerGradientStops = floatArrayOf(0f, 0.25f, 0.62f, 0.86f, 1f)

private val bottomRimGradientArgb =
    intArrayOf(
        Color(0x0060A5FA).toArgb(),
        Color(0xCC60A5FA).toArgb(),
        Color(0xE6A78BFA).toArgb(),
        Color(0xE6F472B6).toArgb(),
        Color(0xCCFBBF24).toArgb(),
        Color(0x00A78BFA).toArgb(),
    )
private val bottomRimGradientStops = floatArrayOf(0f, 0.18f, 0.42f, 0.64f, 0.84f, 1f)

/**
 * 唤起光效（照 Eta）：从右侧电源键激发极光光晕，流光沿真实屏幕圆角连续包覆滑入底部，底部升起一片柔光和贴边的细边光， 放完（0.64 秒）后淡出。
 *
 * [bottomCornerRadius] 是屏幕下角的物理圆角半径（像素），取不到时传 0，按 36dp 估。[exiting] 为真（浮窗要关了）时立刻淡出。
 */
@Composable
internal fun AssistantEdgeGlow(
    exiting: Boolean,
    bottomCornerRadius: Float,
    modifier: Modifier = Modifier,
) {
    val clock = remember { Animatable(0f) }
    var firstWakeComplete by remember { mutableStateOf(false) }
    val shown = !firstWakeComplete && !exiting
    val opacity =
        animateFloatAsState(
            targetValue = if (shown) 1f else 0f,
            animationSpec =
                tween(durationMillis = if (shown) 140 else 260, easing = FastOutSlowInEasing),
            label = "assistant_edge_glow",
        )
    // 淡入淡出每帧都变，组合阶段只看它是不是还在画
    val drawing by remember { derivedStateOf { opacity.value > 0f } }
    LaunchedEffect(Unit) {
        val durationMs = (entranceDurationSeconds * 1000f).toInt()
        clock.animateTo(entranceDurationSeconds, tween(durationMs, easing = LinearEasing))
        firstWakeComplete = true
    }
    if (drawing) EdgeGlowCanvas({ clock.value }, { opacity.value }, bottomCornerRadius, modifier)
}

/** 停在 [seconds] 那一刻的一帧，不动；截图测试用来看光效放到一半的样子。 */
@Composable
internal fun AssistantEdgeGlowFrame(
    seconds: Float,
    modifier: Modifier = Modifier,
    bottomCornerRadius: Float = 0f,
) {
    EdgeGlowCanvas({ seconds }, { 1f }, bottomCornerRadius, modifier)
}

/** 铺满的光效层。[seconds] 和 [opacity] 只在绘制阶段读，动画每帧只重画、不重组。 */
@Composable
private fun EdgeGlowCanvas(
    seconds: () -> Float,
    opacity: () -> Float,
    bottomCornerRadius: Float,
    modifier: Modifier,
) {
    val painter = remember { EdgeGlowPainter() }
    Canvas(modifier.fillMaxSize()) {
        val alpha = opacity()
        if (alpha <= 0f || size.width <= 0f || size.height <= 0f) return@Canvas
        val corner = if (bottomCornerRadius > 0f) bottomCornerRadius else 36f * density
        painter.draw(this, seconds(), alpha, corner)
    }
}

/**
 * 光效的几何：屏幕尺寸、电源键高度、下角（内收后）的半径。只跟着尺寸、密度和圆角变，不随帧变。
 *
 * [cornerRadius] 是屏幕下角的物理圆角半径（像素）。
 */
private class Frame(
    val width: Float,
    val height: Float,
    val density: Float,
    val cornerRadius: Float,
    val hardware: Boolean,
) {
    val inset = 1.5f * density
    val corner = (cornerRadius - inset).coerceIn(12f * density, minOf(width, height) * 0.22f)
    val powerY = height * POWER_BUTTON_HEIGHT_RATIO

    fun matches(width: Float, height: Float, density: Float, radius: Float, hardware: Boolean) =
        width == this.width &&
            height == this.height &&
            density == this.density &&
            radius == cornerRadius &&
            hardware == this.hardware
}

/** 光效的路径、渐变和画笔（照 Eta 在 remember 里建的那几样）：几何不变时都复用，一帧一帧往上画。 */
private class EdgeGlowPainter {
    private var frame: Frame? = null
    private val fullTrackPath = Path()
    private var trackLength = 0f
    private val segmentPath = Path()
    private val cradleRimPath = Path()
    private var cradleRimClimb = Float.NaN
    private val pathMeasure = PathMeasure()
    private val headPos = FloatArray(2)
    private val tailPos = FloatArray(2)

    /** 流光的渐变从 (0, 0) 到 (1, 0) 建一次，每帧用 [streamerMatrix] 映射到尾到头那一段。 */
    private val streamerShader =
        LinearGradient(
            0f,
            0f,
            1f,
            0f,
            streamerGradientArgb,
            streamerGradientStops,
            Shader.TileMode.CLAMP,
        )
    private val streamerMatrix = Matrix()
    private val streamerMatrixValues = FloatArray(9).also { it[8] = 1f }
    private val streamerHaloPaint = strokePaint().apply { shader = streamerShader }
    private val streamerCorePaint = strokePaint().apply { shader = streamerShader }

    /** 底边光的渐变按屏宽建，每帧用 [rimMatrix] 横向平移出流动感。 */
    private var rimShader: LinearGradient? = null
    private val rimMatrix = Matrix()
    private val rimHaloPaint = strokePaint()
    private val rimCorePaint = strokePaint()

    fun draw(scope: DrawScope, seconds: Float, alpha: Float, cornerRadius: Float) =
        with(scope) {
            val frame = layout(this, cornerRadius)
            val wakeEntry = frame.height > frame.width
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

    /** 这一帧的几何；尺寸、密度和圆角都没变时沿用上一帧的轨道、底边渐变和画笔设置。 */
    private fun layout(scope: DrawScope, cornerRadius: Float): Frame {
        val width = scope.size.width
        val height = scope.size.height
        val density = scope.density
        val hardware = scope.drawContext.canvas.nativeCanvas.isHardwareAccelerated
        frame
            ?.takeIf { it.matches(width, height, density, cornerRadius, hardware) }
            ?.let {
                return it
            }
        val next = Frame(width, height, density, cornerRadius, hardware)
        frame = next
        buildTrack(next)
        pathMeasure.setPath(fullTrackPath, false)
        trackLength = pathMeasure.length
        cradleRimClimb = Float.NaN
        val shader =
            LinearGradient(
                0f,
                height,
                width,
                height,
                bottomRimGradientArgb,
                bottomRimGradientStops,
                Shader.TileMode.CLAMP,
            )
        rimShader = shader
        streamerHaloPaint.strokeWidth = 16f * density
        streamerHaloPaint.maskFilter =
            if (hardware) BlurMaskFilter(11f * density, BlurMaskFilter.Blur.NORMAL) else null
        streamerCorePaint.strokeWidth = 3.2f * density
        rimHaloPaint.shader = shader
        rimHaloPaint.strokeWidth = 8.5f * density
        rimHaloPaint.maskFilter = BlurMaskFilter(8f * density, BlurMaskFilter.Blur.NORMAL)
        rimCorePaint.shader = shader
        rimCorePaint.strokeWidth = 2.2f * density
        return next
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
        val centerX = frame.width * 1.24f
        val centerY = frame.powerY + (frame.height * 0.62f - frame.powerY) * 0.55f * driftY
        val haloFade = if (seconds < POWER_HALO_HOLD_SECONDS) early else (1f - late)
        drawUnitGlow(powerHaloBrush, centerX, centerY, radius, radius, alpha * haloFade)
    }

    private fun buildTrack(frame: Frame) {
        val width = frame.width
        val height = frame.height
        val inset = frame.inset
        val corner = frame.corner
        fullTrackPath.reset()
        fullTrackPath.moveTo(width - inset, frame.powerY)
        fullTrackPath.lineTo(width - inset, height - inset - corner)
        fullTrackPath.arcTo(
            width - inset - corner * 2f,
            height - inset - corner * 2f,
            width - inset,
            height - inset,
            0f,
            90f,
            false,
        )
        fullTrackPath.lineTo(inset + corner, height - inset)
        fullTrackPath.arcTo(
            inset,
            height - inset - corner * 2f,
            inset + corner * 2f,
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
        val totalLength = trackLength
        if (totalLength <= 1f) return
        val headDist = totalLength * travel
        val tailSpan = totalLength * (0.34f + 0.18f * sin(travel * Math.PI).toFloat())
        val tailDist = (headDist - tailSpan).coerceAtLeast(0f)
        segmentPath.reset()
        if (headDist <= tailDist + 1f) return
        if (!pathMeasure.getSegment(tailDist, headDist, segmentPath, true)) return
        pathMeasure.getPosTan(headDist, headPos, null)
        pathMeasure.getPosTan(tailDist, tailPos, null)
        alignStreamerGradient()
        val density = frame.density
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            streamerHaloPaint.alpha =
                (alpha * fadeOut * (if (frame.hardware) 0.62f else 0.24f) * 255f).toInt()
            native.drawPath(segmentPath, streamerHaloPaint)
            streamerCorePaint.alpha = (alpha * fadeOut * 0.92f * 255f).toInt()
            native.drawPath(segmentPath, streamerCorePaint)
        }
        val headRadius = 20f * density
        drawUnitGlow(
            streamerHeadBrush,
            headPos[0],
            headPos[1],
            headRadius,
            headRadius,
            alpha * fadeOut * 0.72f,
        )
    }

    /** 把流光渐变的 (0, 0)→(1, 0) 映射到尾端→头端（头端外推半个像素，照 Eta），只旋转、等比缩放和平移。 */
    private fun alignStreamerGradient() {
        val dx = headPos[0] + 0.5f - tailPos[0]
        val dy = headPos[1] + 0.5f - tailPos[1]
        val values = streamerMatrixValues
        values[0] = dx
        values[1] = -dy
        values[2] = tailPos[0]
        values[3] = dy
        values[4] = dx
        values[5] = tailPos[1]
        streamerMatrix.setValues(values)
        streamerShader.setLocalMatrix(streamerMatrix)
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
        val radius = glowHeight.coerceAtLeast(1f)
        val centerY = frame.height + glowHeight * 0.12f
        val lobeAlpha = (alpha * bottomEntry * 0.78f).coerceIn(0f, 1f)
        for (index in bottomLobeBrushes.indices) {
            val centerX =
                width *
                    when (index) {
                        0 -> 0.12f + slowDrift
                        1 -> 0.39f - secondaryDrift
                        2 -> 0.66f + secondaryDrift
                        else -> 0.88f - slowDrift * 0.8f
                    }
            val horizontalRadius = (width * bottomLobeRadiusRatios[index]).coerceAtLeast(glowHeight)
            drawUnitGlow(
                bottomLobeBrushes[index],
                centerX,
                centerY,
                horizontalRadius,
                radius,
                lobeAlpha,
            )
        }
    }

    /** 画一团按单位半径建的径向光：圆心平移到 ([x], [y])，横竖各缩放到 [radiusX]、[radiusY]。 */
    private fun DrawScope.drawUnitGlow(
        brush: Brush,
        x: Float,
        y: Float,
        radiusX: Float,
        radiusY: Float,
        alpha: Float,
    ) {
        withTransform(
            {
                translate(x, y)
                scale(radiusX, radiusY, pivot = Offset.Zero)
            },
        ) {
            drawCircle(brush = brush, radius = 1f, center = Offset.Zero, alpha = alpha)
        }
    }

    /** 贴合底部与左右圆角的细腻呼吸边光。 */
    private fun DrawScope.drawCradleRim(
        frame: Frame,
        seconds: Float,
        alpha: Float,
        bottomEntry: Float,
    ) {
        val rimClimb = 22f * frame.density * bottomEntry
        if (rimClimb != cradleRimClimb) {
            buildCradleRim(frame, rimClimb)
            cradleRimClimb = rimClimb
        }
        rimMatrix.setTranslate(sin(seconds * 1.35f) * frame.width * 0.12f, 0f)
        rimShader?.setLocalMatrix(rimMatrix)
        val rimBreath = 0.46f + 0.12f * sin(seconds * 2.1f)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            if (frame.hardware) {
                rimHaloPaint.alpha =
                    (alpha * bottomEntry * rimBreath * 0.65f * 255f).toInt().coerceIn(0, 255)
                native.drawPath(cradleRimPath, rimHaloPaint)
            }
            rimCorePaint.alpha =
                (alpha * bottomEntry * rimBreath * 0.88f * 255f).toInt().coerceIn(0, 255)
            native.drawPath(cradleRimPath, rimCorePaint)
        }
    }

    private fun buildCradleRim(frame: Frame, rimClimb: Float) {
        val width = frame.width
        val height = frame.height
        val inset = frame.inset
        val corner = frame.corner
        cradleRimPath.reset()
        cradleRimPath.moveTo(inset, height - inset - corner - rimClimb)
        cradleRimPath.lineTo(inset, height - inset - corner)
        cradleRimPath.arcTo(
            inset,
            height - inset - corner * 2f,
            inset + corner * 2f,
            height - inset,
            180f,
            -90f,
            false,
        )
        cradleRimPath.lineTo(width - inset - corner, height - inset)
        cradleRimPath.arcTo(
            width - inset - corner * 2f,
            height - inset - corner * 2f,
            width - inset,
            height - inset,
            90f,
            -90f,
            false,
        )
        cradleRimPath.lineTo(width - inset, height - inset - corner - rimClimb)
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
