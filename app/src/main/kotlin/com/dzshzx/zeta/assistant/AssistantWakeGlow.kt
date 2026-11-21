package com.dzshzx.zeta.assistant

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 唤起光效整段的时长（秒）：放完后淡出。 */
internal const val WakeGlowSeconds = 0.64f

/** 流光落到底边、底部光场起来的时刻（秒）：浮窗的输入栏从这时开始出现。 */
internal const val WakeHandoffSeconds = 0.42f

/** 整段放完后全部光淡出的时长。 */
private const val WakeFadeOutMs = 260

/** 电源键中心在屏高的这个比例处（屏幕右侧）。 */
private const val PowerButtonHeightRatio = 0.355f

/** 极光从无到亮的时长（秒），之后开始变大、淡出。 */
private const val HaloRiseSeconds = 0.06f

/** 极光往下漂到底的时长（秒）。 */
private const val HaloDriftSeconds = 0.46f

private const val StreamStartSeconds = 0.05f

/** 流光到达左下角后在这一刻淡尽（秒）。 */
private const val StreamSettleSeconds = 0.48f

private const val BottomRiseStartSeconds = 0.17f
private const val BottomRiseSeconds = 0.23f

/** 底部细边光的亮度。Eta 在这个值上下按正弦呼吸并随音量变亮，这里没有语音，取它的中值不动。 */
private const val RimBrightness = 0.46f

/** 调用方没给屏幕圆角时用的下角半径。 */
private val FallbackCornerRadius = 36.dp

private val HaloEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private val StreamEasing = CubicBezierEasing(0.20f, 0f, 0.12f, 1f)
private val BottomEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

// 色相和透明度照 Eta：比主屏边缘流光（ui/overlay/EdgeGlow.kt）那组饱和，浅色页面上也看得出底部光场。
// 这是一组渐变光带的配色（相当于美术素材），不是界面配色，所以不取主题色
private const val GlowHaloBlue = 0x93C5FD
private const val GlowHaloViolet = 0xC4B5FD
private const val GlowBlue = 0x60A5FA
private const val GlowViolet = 0xA78BFA
private const val GlowPink = 0xF472B6
private const val GlowAmber = 0xFBBF24
private const val GlowLobeBlue = 0x4F8BFF
private const val GlowLobePink = 0xFF7AC6
private const val GlowLobeCream = 0xFFE4B5
private const val GlowWarmWhite = 0xFFF3DC
private const val GlowHeadWhite = 0xFFF8EB

/** 把 [rgb]（不带透明度的 0xRRGGBB）和透明度 [alpha]（0 到 1）拼成 ARGB。 */
private fun argb(alpha: Float, rgb: Int): Int = ((alpha * 255f).roundToInt() shl 24) or rgb

// 渐变都淡到同色相的全透明，不淡到透明黑：后者在半透明处会发灰
private val HaloColors =
    intArrayOf(argb(0.42f, GlowHaloBlue), argb(0.26f, GlowHaloViolet), argb(0f, GlowHaloViolet))
private val HaloStops = floatArrayOf(0f, 0.46f, 1f)

private val StreamColors =
    intArrayOf(
        argb(0f, GlowBlue),
        argb(0.8f, GlowBlue),
        argb(0.9f, GlowViolet),
        argb(0.95f, GlowPink),
        argb(1f, GlowWarmWhite),
    )
private val StreamStops = floatArrayOf(0f, 0.25f, 0.62f, 0.86f, 1f)

private val HeadColors =
    intArrayOf(argb(1f, GlowHeadWhite), argb(0.58f, GlowPink), argb(0f, GlowPink))

private val RimColors =
    intArrayOf(
        argb(0f, GlowBlue),
        argb(0.8f, GlowBlue),
        argb(0.9f, GlowViolet),
        argb(0.9f, GlowPink),
        argb(0.8f, GlowAmber),
        argb(0f, GlowViolet),
    )
private val RimStops = floatArrayOf(0f, 0.18f, 0.42f, 0.64f, 0.84f, 1f)

// 底部四团光：中心色、横向中心位置（屏宽比例）、横向半径（屏宽比例）
private val LobeColors =
    intArrayOf(
        argb(0.66f, GlowLobeBlue),
        argb(0.58f, GlowViolet),
        argb(0.62f, GlowLobePink),
        argb(0.54f, GlowLobeCream),
    )
private val LobeCenters = floatArrayOf(0.12f, 0.39f, 0.66f, 0.88f)
private val LobeWidths = floatArrayOf(0.32f, 0.38f, 0.36f, 0.30f)

/**
 * 数字助理浮窗唤起时播一次的光效，照 Eta 的 EtaAssistantEdgeGlow（纯打字模式，没有语音）重写：
 * - 电源键极光：右侧电源键处亮起一团光，变大、往下漂、淡出；
 * - 流光拖尾：从电源键沿右边框滑下，绕过右下角圆角，经底边到左下角圆角；
 * - 底部光场：流光落到底边时升起四团横向拉宽的光，和一条贴底边与两个下角的细边光。
 *
 * 横屏只画底部光场。时间线和几何照 Eta，单位是秒，从唤起开始算。
 *
 * 时钟走到 [WakeHandoffSeconds] 时调一次 [onHandoff]（只调一次）。
 *
 * 时钟用 [Animatable]：截图测试把动画时长按 0 算，时钟直接到终点、随即淡完，画的是终态（不画）。
 *
 * 时钟跳到终点时 [onHandoff] 照样会调。
 *
 * [bottomCornerRadius] 是屏幕下角的圆角半径（像素），为 0 时用 36dp。窗口不接收触摸。
 */
@Composable
internal fun AssistantWakeGlow(
    bottomCornerRadius: Float,
    onHandoff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clock = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val handoff by rememberUpdatedState(onHandoff)
    LaunchedEffect(Unit) {
        launch {
            snapshotFlow { clock.value >= WakeHandoffSeconds }.first { it }
            handoff()
        }
        val durationMs = (WakeGlowSeconds * 1000f).roundToInt()
        clock.animateTo(WakeGlowSeconds, tween(durationMs, easing = LinearEasing))
        fade.animateTo(0f, tween(WakeFadeOutMs))
    }
    WakeGlowLayer(bottomCornerRadius, { clock.value }, { fade.value }, modifier)
}

/** 停在 [seconds] 那一刻的一帧，不动；截图测试用来看光效放到一半的样子。 */
@Composable
internal fun AssistantWakeGlowFrame(
    seconds: Float,
    bottomCornerRadius: Float,
    modifier: Modifier = Modifier,
) {
    WakeGlowLayer(bottomCornerRadius, { seconds }, { 1f }, modifier)
}

/** 铺满的光效层。[seconds] 和 [alpha] 只在绘制阶段读，动画每帧只重画、不重组。 */
@Composable
private fun WakeGlowLayer(
    bottomCornerRadius: Float,
    seconds: () -> Float,
    alpha: () -> Float,
    modifier: Modifier,
) {
    Spacer(
        modifier.fillMaxSize().drawWithCache {
            val corner =
                if (bottomCornerRadius > 0f) bottomCornerRadius else FallbackCornerRadius.toPx()
            val painter = WakeGlowPainter(size.width, size.height, density, corner)
            onDrawBehind {
                val opacity = alpha()
                if (opacity > 0f && size.minDimension > 0f) {
                    drawIntoCanvas { canvas ->
                        painter.draw(canvas.nativeCanvas, seconds(), opacity)
                    }
                }
            }
        },
    )
}

/** [value] 在 [start] 到 [end] 之间走到了几成，夹在 0 到 1。 */
private fun progress(value: Float, start: Float, end: Float): Float =
    ((value - start) / (end - start)).coerceIn(0f, 1f)

private fun alphaByte(alpha: Float): Int = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()

/**
 * 光效的画笔，尺寸变了才重建。流光的轨道、画笔和着色器都在这里建好。
 *
 * 径向光和流光渐变用单位大小的着色器，每帧只换局部矩阵摆到位置、拉到大小，不新建着色器。
 */
private class WakeGlowPainter(
    private val width: Float,
    private val height: Float,
    private val density: Float,
    cornerRadius: Float,
) {
    private val portrait = height > width
    private val inset = 1.5f * density

    // 和 Eta 一样内收后夹在 12dp 到短边的 0.22 之间，免得圆角太小看不出绕角、太大绕成半圆
    private val corner =
        (cornerRadius - inset).coerceAtMost(min(width, height) * 0.22f).coerceAtLeast(12f * density)
    private val powerY = height * PowerButtonHeightRatio
    private val bottom = height - inset
    private val right = width - inset

    private val track = trackPath()
    private val measure = PathMeasure(track, false)
    private val trackLength = measure.length
    private val segment = Path()
    private val rim = Path()
    private val headPos = FloatArray(2)
    private val tailPos = FloatArray(2)
    private val matrix = Matrix()

    private val haloShader =
        RadialGradient(0f, 0f, 1f, HaloColors, HaloStops, Shader.TileMode.CLAMP)
    private val headShader = RadialGradient(0f, 0f, 1f, HeadColors, null, Shader.TileMode.CLAMP)
    private val lobeShaders = LobeColors.map {
        RadialGradient(0f, 0f, 1f, it, it and 0xFFFFFF, Shader.TileMode.CLAMP)
    }
    private val streamShader =
        LinearGradient(0f, 0f, 1f, 0f, StreamColors, StreamStops, Shader.TileMode.CLAMP)
    private val rimShader =
        LinearGradient(0f, height, width, height, RimColors, RimStops, Shader.TileMode.CLAMP)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streamHalo = stroke(16f, streamShader, blur = 11f)
    private val streamLine = stroke(3.2f, streamShader)
    private val rimHalo = stroke(8.5f, rimShader, blur = 8f)
    private val rimLine = stroke(2.2f, rimShader)

    /** 画 [seconds] 那一刻的一帧，整体再乘 [alpha]。竖屏三段都画，横屏只画底部光场。 */
    fun draw(canvas: Canvas, seconds: Float, alpha: Float) {
        if (portrait) {
            drawPowerHalo(canvas, seconds, alpha)
            drawStream(canvas, seconds, alpha)
        }
        drawBottomField(canvas, seconds, alpha)
    }

    /** 电源键极光：圆心在屏幕右外侧，0.06 秒亮起，之后变大、往下漂、淡出，到整段结束时淡尽。 */
    private fun drawPowerHalo(canvas: Canvas, seconds: Float, alpha: Float) {
        if (seconds >= WakeGlowSeconds) return
        val rise = progress(seconds, 0f, HaloRiseSeconds)
        val spread = HaloEasing.transform(progress(seconds, HaloRiseSeconds, WakeGlowSeconds))
        val drift = HaloEasing.transform(progress(seconds, 0f, HaloDriftSeconds))
        // 半径是 Eta 按 3 倍密度量的像素值，换算到当前密度
        val radius = ((240f + 560f * rise + 880f * spread) * density / 3f).coerceAtLeast(1f)
        val cy = powerY + (height * 0.62f - powerY) * 0.55f * drift
        val fade = if (seconds < HaloRiseSeconds) rise else 1f - spread
        fillGlow(canvas, haloShader, width * 1.24f, cy, radius, radius, alpha * fade)
    }

    /**
     * 流光拖尾：光头沿轨道从电源键走到左下角，拖尾长度在路程中段最长。
     *
     * 外层模糊柔光、内层亮线，光头一颗亮点。到达后 0.06 秒内淡尽。
     */
    private fun drawStream(canvas: Canvas, seconds: Float, alpha: Float) {
        if (seconds < StreamStartSeconds || seconds >= StreamSettleSeconds) return
        val travel =
            StreamEasing.transform(progress(seconds, StreamStartSeconds, WakeHandoffSeconds))
        val fade = 1f - progress(seconds, WakeHandoffSeconds, StreamSettleSeconds)
        val head = trackLength * travel
        val span = trackLength * (0.34f + 0.18f * sin(travel * PI).toFloat())
        val tail = (head - span).coerceAtLeast(0f)
        segment.reset()
        if (head <= tail + 1f || !measure.getSegment(tail, head, segment, true)) return
        measure.getPosTan(head, headPos, null)
        measure.getPosTan(tail, tailPos, null)
        alignStreamShader()
        streamHalo.alpha = alphaByte(alpha * fade * 0.62f)
        streamLine.alpha = alphaByte(alpha * fade * 0.92f)
        canvas.drawPath(segment, streamHalo)
        canvas.drawPath(segment, streamLine)
        val headRadius = 20f * density
        val headAlpha = alpha * fade * 0.72f
        fillGlow(canvas, headShader, headPos[0], headPos[1], headRadius, headRadius, headAlpha)
    }

    /** 把单位长度的流光渐变摆到从尾到头的方向上：尾端透明，头端最亮。 */
    private fun alignStreamShader() {
        val dx = headPos[0] - tailPos[0]
        val dy = headPos[1] - tailPos[1]
        val length = hypot(dx, dy).coerceAtLeast(1f)
        matrix.setScale(length, length)
        matrix.postRotate(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
        matrix.postTranslate(tailPos[0], tailPos[1])
        streamShader.setLocalMatrix(matrix)
    }

    /** 底部光场：竖屏约 0.17 秒起从底边升起，横屏一开始就是满的。 */
    private fun drawBottomField(canvas: Canvas, seconds: Float, alpha: Float) {
        val rise =
            if (portrait) {
                val end = BottomRiseStartSeconds + BottomRiseSeconds
                BottomEasing.transform(progress(seconds, BottomRiseStartSeconds, end))
            } else 1f
        if (rise <= 0f) return
        drawLobes(canvas, rise, alpha)
        drawRim(canvas, rise, alpha)
    }

    /** 四团横向拉宽的径向光，圆心略低于底边，升起时一起长高。 */
    private fun drawLobes(canvas: Canvas, rise: Float, alpha: Float) {
        val glowHeight = 88f * density * (0.72f + 0.28f * rise)
        val cy = height + glowHeight * 0.12f
        val lobeAlpha = alpha * rise * 0.78f
        for (index in lobeShaders.indices) {
            val halfWidth = max(width * LobeWidths[index], glowHeight)
            val cx = width * LobeCenters[index]
            fillGlow(canvas, lobeShaders[index], cx, cy, halfWidth, glowHeight, lobeAlpha)
        }
    }

    /** 细边光：贴着底边和两个下角圆角，两端沿左右边框往上爬一小段，爬的高度随升起变长。 */
    private fun drawRim(canvas: Canvas, rise: Float, alpha: Float) {
        val climb = 22f * density * rise
        val diameter = corner * 2f
        rim.reset()
        rim.moveTo(inset, bottom - corner - climb)
        rim.lineTo(inset, bottom - corner)
        rim.arcTo(inset, bottom - diameter, inset + diameter, bottom, 180f, -90f, false)
        rim.lineTo(right - corner, bottom)
        rim.arcTo(right - diameter, bottom - diameter, right, bottom, 90f, -90f, false)
        rim.lineTo(right, bottom - corner - climb)
        rimHalo.alpha = alphaByte(alpha * rise * RimBrightness * 0.65f)
        rimLine.alpha = alphaByte(alpha * rise * RimBrightness * 0.88f)
        canvas.drawPath(rim, rimHalo)
        canvas.drawPath(rim, rimLine)
    }

    /** 用单位半径的径向着色器 [shader] 画一个以 ([cx], [cy]) 为中心、半轴 [rx] 和 [ry] 的椭圆光团。 */
    private fun fillGlow(
        canvas: Canvas,
        shader: Shader,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        alpha: Float,
    ) {
        matrix.setScale(rx, ry)
        matrix.postTranslate(cx, cy)
        shader.setLocalMatrix(matrix)
        fill.shader = shader
        fill.alpha = alphaByte(alpha)
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, fill)
    }

    /** 流光的轨道：从电源键沿右边框往下，绕右下角圆角，沿底边到左下角，再绕左下角圆角的前 72 度（照 Eta）。 */
    private fun trackPath(): Path {
        val diameter = corner * 2f
        return Path().apply {
            moveTo(right, powerY)
            lineTo(right, bottom - corner)
            arcTo(right - diameter, bottom - diameter, right, bottom, 0f, 90f, false)
            lineTo(inset + corner, bottom)
            arcTo(inset, bottom - diameter, inset + diameter, bottom, 90f, 72f, false)
        }
    }

    /** 圆头圆角的描边画笔，宽度和模糊半径以 dp 计。 */
    private fun stroke(widthDp: Float, shader: Shader, blur: Float = 0f): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = widthDp * density
            this.shader = shader
            if (blur > 0f) maskFilter = BlurMaskFilter(blur * density, BlurMaskFilter.Blur.NORMAL)
        }
}
