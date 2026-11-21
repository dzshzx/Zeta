package com.dzshzx.zeta.assistant

import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.anim.couiSpring
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.COUIIndication
import io.github.suqi8.coui.kmp.utils.couiTouchFeedback
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// 拷自 Eta 的 agent/voice/CouiAssistantSurface.kt 和 ui/components/coui/CouiMotion.kt（GPL-3.0），
// 去掉了语音那几样（声波球、语音和键盘图标）和 Miuix 外观的分支，配色只按 COUI 取。

/** 浮窗用的一组颜色（照 Eta 的 EtaVoicePanelColors）：面板、输入栏、胶囊、遮罩和托底；强调色和错误色取 COUI 主题。 */
@Immutable
internal data class AssistantColors(
    val dark: Boolean,
    val accent: Color,
    val onAccent: Color,
    val error: Color,
    val content: Color,
    val input: Color,
    val focusedInput: Color,
    val chipBackground: Color,
    val controlSurface: Color,
    val inputPrimary: Color,
    val inputSecondary: Color,
    val inputTertiary: Color,
    val tertiary: Color,
    val panelSpecular: Color,
    val chipStroke: Color,
    val idleScrim: Color,
    val sheetScrim: Color,
    val textBottomCushion: Color,
    /** 回答面板里用户问题的气泡（照 Eta 浮窗里的用户气泡）。 */
    val questionBubble: Color,
)

@Composable
internal fun rememberAssistantColors(): AssistantColors {
    val scheme = COUITheme.colorScheme
    val dark = scheme.isDark
    val accent = scheme.primary
    val destructive = scheme.error
    return remember(dark, accent, destructive) {
        if (dark) darkColors(accent, destructive) else lightColors(accent, destructive)
    }
}

private fun darkColors(accent: Color, destructive: Color) =
    AssistantColors(
        dark = true,
        accent = accent,
        onAccent = Color.White,
        error = destructive,
        // 回复面板底色保持完全不透明，确保向下连续覆盖导航栏区域时不透出桌面残影。
        content = Color(0xFF17191E),
        input = Color(0xF023262E),
        focusedInput = Color(0xFA2B2F39),
        chipBackground = Color(0xEB22252D),
        controlSurface = Color(0x1CFFFFFF),
        inputPrimary = Color(0xF2FFFFFF),
        inputSecondary = Color(0xB8FFFFFF),
        inputTertiary = Color(0x5CFFFFFF),
        tertiary = Color(0x36FFFFFF),
        panelSpecular = Color(0x29FFFFFF),
        chipStroke = Color(0x1EFFFFFF),
        idleScrim = Color(0x18000000),
        sheetScrim = Color(0x59000000),
        textBottomCushion = Color(0x880D1017),
        questionBubble = Color(0xFF37393D),
    )

private fun lightColors(accent: Color, destructive: Color) =
    AssistantColors(
        dark = false,
        accent = accent,
        onAccent = Color.White,
        error = destructive,
        content = Color(0xFFF7F8FA),
        // 柔和瓷白略带通透感，配合逆裁剪阴影在深色壁纸上不过曝、浅色背景上层次分明。
        input = Color(0xF2FAFAFC),
        focusedInput = Color(0xFCFFFFFF),
        chipBackground = Color(0xEEFAFAFC),
        controlSurface = Color(0x0F11141A),
        inputPrimary = Color(0xEB11141A),
        inputSecondary = Color(0xA611141A),
        inputTertiary = Color(0x5411141A),
        tertiary = Color(0x2411141A),
        panelSpecular = Color(0xEBFFFFFF),
        chipStroke = Color(0x12000000),
        idleScrim = Color(0x0A000000),
        sheetScrim = Color(0x30000000),
        textBottomCushion = Color(0x7CF3F5F9),
        questionBubble = Color(0xFFE5E7EA),
    )

/** 顶部采用 Oplus G2 连续曲率、底部保持直角的半屏面板轮廓，使面板底边能完整贴合屏幕与导航区。 */
@Immutable
internal data class TopCouiSheetShape(val topRadius: Dp, val cornerWeight: Float = 2.6f) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        if (size.width <= 0f || size.height <= 0f) {
            return Outline.Rectangle(Rect.Zero)
        }
        val radiusPx =
            (topRadius.value * density.density).coerceIn(0f, minOf(size.width * 0.5f, size.height))
        if (radiusPx <= 0.5f) {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }
        return Outline.Generic(buildTopCouiSheetPath(size, radiusPx, cornerWeight))
    }
}

private data class SheetCornerKey(val radiusPx: Int, val weightBits: Int)

private val topSheetCornerSamples = LruCache<SheetCornerKey, FloatArray>(8)

internal fun buildTopCouiSheetPath(size: Size, radiusPx: Float, cornerWeight: Float = 2.6f): Path {
    val r = radiusPx.coerceIn(0f, minOf(size.width * 0.5f, size.height))
    val path = Path()
    if (r <= 0.5f) {
        path.addRect(Rect(0f, 0f, size.width, size.height))
        return path
    }
    path.moveTo(0f, size.height)
    path.lineTo(0f, r)
    appendTopCouiRim(path, size.width, r, cornerWeight)
    path.lineTo(size.width, size.height)
    path.close()
    return path
}

internal fun buildTopCouiRimPath(size: Size, radiusPx: Float, cornerWeight: Float = 2.6f): Path {
    val r = radiusPx.coerceIn(0f, minOf(size.width * 0.5f, size.height))
    val path = Path()
    if (r <= 0.5f) {
        path.moveTo(0f, 0f)
        path.lineTo(size.width, 0f)
        return path
    }
    path.moveTo(0f, r)
    appendTopCouiRim(path, size.width, r, cornerWeight)
    return path
}

private fun appendTopCouiRim(path: Path, width: Float, r: Float, cornerWeight: Float) {
    val key = SheetCornerKey((r * 10f).toInt(), cornerWeight.toRawBits())
    val points =
        topSheetCornerSamples[key]
            ?: sampleQuarterLpCorner(r, cornerWeight).also { topSheetCornerSamples.put(key, it) }
    val count = points.size / 2
    // 左上连续圆角：i 递增时 (r - y, r - x) 从 (0, r) 顺时针过渡到 (r, 0)。
    for (i in 0 until count) {
        val x = points[i * 2]
        val y = points[i * 2 + 1]
        path.lineTo(r - y, r - x)
    }
    path.lineTo(width - r, 0f)
    // 右上连续圆角：i 递增时 (width - r + x, r - y) 从 (width - r, 0) 顺时针过渡到 (width, r)。
    for (i in 0 until count) {
        val x = points[i * 2]
        val y = points[i * 2 + 1]
        path.lineTo(width - r + x, r - y)
    }
}

private fun sampleQuarterLpCorner(radiusPx: Float, cornerWeight: Float): FloatArray {
    val weight = cornerWeight.coerceAtLeast(2f).toDouble()
    val diagonal = 0.5.pow(1.0 / weight)
    val segments =
        ceil(sqrt(radiusPx * (weight - 1.0) * diagonal / (4.0 * 0.05))).toInt().coerceIn(4, 48)
    val halfCorner =
        DoubleArray(segments + 1) { index ->
            (1.0 - (diagonal * index / segments).pow(weight)).pow(1.0 / weight)
        }
    val total = segments * 2 + 1
    val coords = FloatArray(total * 2)
    for (index in 0..segments * 2) {
        val firstHalf = index <= segments
        val halfIndex = if (firstHalf) index else segments * 2 - index
        val a = diagonal * halfIndex / segments
        val b = halfCorner[halfIndex]
        coords[index * 2] = (radiusPx * if (firstHalf) a else b).toFloat()
        coords[index * 2 + 1] = (radiusPx * if (firstHalf) b else a).toFloat()
    }
    return coords
}

internal enum class AssistantCapsuleElevation {
    Composer,
    Chip,
}

/** 悬浮胶囊表面：硬件加速画布上启用投影，软件画布或窗口切换期回退为同轮廓纯色填充与细描边。 */
@Composable
internal fun Modifier.couiAssistantCapsule(
    surfaceColor: Color,
    strokeColor: Color,
    dark: Boolean,
    elevation: AssistantCapsuleElevation = AssistantCapsuleElevation.Composer,
    highlightProgress: Float = 0f,
    highlightColor: Color = Color.Unspecified,
    interactionSource: MutableInteractionSource? = null,
    pressMotion: CouiPressMotion? = null,
    shape: Shape = CircleShape,
): Modifier {
    val shadow = capsuleShadow(elevation, dark)
    val feedback =
        if (interactionSource != null) Modifier.couiButtonFeedback(dark, interactionSource)
        else Modifier
    val shadowContext = LocalGraphicsContext.current.shadowContext
    val shadowPainter =
        remember(shadowContext, shadow, shape) {
            shadowContext.createDropShadowPainter(shape, shadow)
        }
    return this.then(
            if (pressMotion == null) Modifier
            else
                Modifier.graphicsLayer {
                    scaleX = pressMotion.scale.value
                    scaleY = pressMotion.scale.value
                },
        )
        .drawBehind {
            if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                with(shadowPainter) { draw(size) }
            }
            val outline = shape.createOutline(size, layoutDirection, this)
            drawCapsuleFill(outline, surfaceColor, strokeColor, highlightColor, highlightProgress)
        }
        .clip(shape)
        .then(feedback)
}

/** 胶囊的投影：输入栏重一些，建议问题这类小胶囊轻一些；深色下半径小、浓一些。 */
private fun capsuleShadow(elevation: AssistantCapsuleElevation, dark: Boolean): Shadow =
    when (elevation) {
        AssistantCapsuleElevation.Composer ->
            Shadow(
                radius = if (dark) 12.dp else 20.dp,
                color = Color.Black,
                alpha = if (dark) 0.36f else 0.13f,
            )
        AssistantCapsuleElevation.Chip ->
            Shadow(
                radius = if (dark) 8.dp else 14.dp,
                color = Color.Black,
                alpha = if (dark) 0.28f else 0.10f,
            )
    }

/** 胶囊的底色、细描边和聚焦时的一圈强调色描边。 */
private fun DrawScope.drawCapsuleFill(
    outline: Outline,
    surfaceColor: Color,
    strokeColor: Color,
    highlightColor: Color,
    highlightProgress: Float,
) {
    drawOutline(outline, surfaceColor)
    if (strokeColor.alpha > 0f) {
        drawOutline(outline, strokeColor, style = Stroke(width = 0.75.dp.toPx()))
    }
    if (highlightProgress > 0.01f && highlightColor != Color.Unspecified) {
        drawOutline(
            outline = outline,
            color = highlightColor.copy(alpha = highlightColor.alpha * highlightProgress),
            style = Stroke(width = 1.15.dp.toPx()),
        )
    }
}

/** COUI 按钮的按压蒙层：按下就出现（不等点击的超时），被外层滚动接走时撤掉。 */
@Composable
private fun Modifier.couiButtonFeedback(
    dark: Boolean,
    interactionSource: MutableInteractionSource,
): Modifier {
    val colors = COUITheme.colorScheme
    val ink = if (dark) Color.White else Color.Black
    val press = colors.press
    val hover = colors.hover
    val focus = colors.focus
    val indication =
        remember(ink, press, hover, focus) {
            COUIIndication(ink, pressColor = press, hoverColor = hover, focusColor = focus)
        }
    return couiTouchFeedback(interactionSource).indication(interactionSource, indication)
}

/** 为需要与进入动画叠加的悬浮控件提供独立的按压进度。 */
internal class CouiPressMotion(private val scope: CoroutineScope, private val density: Float) {
    private val animation = Animatable(1f)
    private var motion: Job? = null
    val scale = animation.asState()

    fun press(size: IntSize) {
        if (size.width <= 0 || size.height <= 0) return
        val shortestSide = minOf(size.width, size.height) / density
        val progress = ((shortestSide - 20f) / 130f).coerceIn(0f, 1f)
        animate(0.85f + 0.12f * progress, response = 0.25f, bounce = 0f)
    }

    fun release() = animate(1f, response = 0.5f, bounce = 0.55f)

    private fun animate(target: Float, response: Float, bounce: Float) {
        motion?.cancel()
        motion = scope.launch {
            animation.animateTo(target, couiSpring(response = response, bounce = bounce))
        }
    }
}

@Composable
internal fun rememberCouiPressMotion(): CouiPressMotion {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    return remember(scope, density) { CouiPressMotion(scope, density) }
}

internal fun Modifier.couiPressMotion(
    motion: CouiPressMotion,
    enabled: Boolean = true,
    cancelOnConsumedMove: Boolean = false,
): Modifier {
    if (!enabled) return this
    return pointerInput(motion, cancelOnConsumedMove) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            motion.press(size)
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val stolen =
                        cancelOnConsumedMove &&
                            event.changes.any {
                                it.isConsumed && it.pressed && it.positionChangedIgnoreConsumed()
                            }
                } while (!stolen && event.changes.any { it.pressed })
            } finally {
                motion.release()
            }
        }
    }
}

internal enum class AssistantGlyphKind {
    Send,
    Stop,
}

/** 胶囊内部动作图标，避免旧矢量自带实心外圈放进圆形按钮后形成双层套环。 */
@Composable
internal fun AssistantActionGlyph(
    kind: AssistantGlyphKind,
    tint: Color,
    modifier: Modifier = Modifier.size(20.dp),
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val u = minOf(w, h) / 24f
        when (kind) {
            AssistantGlyphKind.Send -> {
                val stroke = 2.25f * u
                val centerX = w * 0.5f
                val topY = 5.6f * u
                val bottomY = 18.4f * u
                drawLine(
                    color = tint,
                    start = Offset(centerX, bottomY),
                    end = Offset(centerX, topY + 0.6f * u),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                val wing =
                    Path().apply {
                        moveTo(6.8f * u, 11.0f * u)
                        lineTo(centerX, topY)
                        lineTo(17.2f * u, 11.0f * u)
                    }
                drawPath(
                    path = wing,
                    color = tint,
                    style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            AssistantGlyphKind.Stop -> {
                val side = 9.4f * u
                val topLeft = Offset((w - side) * 0.5f, (h - side) * 0.5f)
                drawRoundRect(
                    color = tint,
                    topLeft = topLeft,
                    size = Size(side, side),
                    cornerRadius = CornerRadius(2.5f * u, 2.5f * u),
                )
            }
        }
    }
}
