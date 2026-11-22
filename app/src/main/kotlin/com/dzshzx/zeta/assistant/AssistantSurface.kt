package com.dzshzx.zeta.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
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
import io.github.suqi8.coui.kmp.effect.COUIMaterialEffect
import io.github.suqi8.coui.kmp.squircle.addSquircleRect
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.couiTouchFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// 拷自 Eta 的 agent/voice/CouiAssistantSurface.kt 和 ui/components/coui/CouiMotion.kt（GPL-3.0），
// 去掉了语音那几样（声波球、语音和键盘图标）和 Miuix 外观的分支，配色只按 COUI 取。

/** 浮窗用的一组颜色（照 Eta 的 EtaVoicePanelColors）：面板、输入栏、胶囊、遮罩和托底；主题色和错误色取 COUI 主题。 */
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

/** 回答面板 G2 顶角的超椭圆指数（照 Eta）。 */
private const val SheetCornerWeight = 2.6f

/** 浮窗里各处进场用的曲线（COUI 的进场减速曲线 cubic-bezier(0.2, 0, 0, 1)）。 */
internal val CouiEnterEasing: Easing = CubicBezierEasing(0.20f, 0f, 0f, 1f)

/** 顶部采用 Oplus G2 连续曲率、底部保持直角的半屏面板轮廓，使面板底边能完整贴合屏幕与导航区。 */
@Immutable
internal data class TopCouiSheetShape(val topRadius: Dp) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Generic(buildTopCouiSheetPath(size, with(density) { topRadius.toPx() }))
}

/** 面板轮廓的路径：两个顶角是 G2 连续圆角，两个底角是直角。 */
internal fun buildTopCouiSheetPath(size: Size, radiusPx: Float): Path =
    Path().apply {
        addSquircleRect(
            rect = Rect(Offset.Zero, size),
            topLeft = radiusPx,
            topRight = radiusPx,
            bottomRight = 0f,
            bottomLeft = 0f,
            weight = SheetCornerWeight,
        )
    }

internal enum class AssistantCapsuleElevation {
    Composer,
    Chip,
}

/**
 * 悬浮胶囊表面：硬件加速画布上启用投影，软件画布或窗口切换期回退为同轮廓纯色填充与细描边。
 *
 * [surface] 和 [highlight]（聚焦时那圈主题色描边的进度）只在绘制阶段读，跟着动画变时只重画、不重组。
 */
@Composable
internal fun Modifier.couiAssistantCapsule(
    colors: AssistantColors,
    elevation: AssistantCapsuleElevation = AssistantCapsuleElevation.Composer,
    surface: () -> Color = { colors.chipBackground },
    highlight: () -> Float = { 0f },
    highlightColor: Color = Color.Unspecified,
    interactionSource: MutableInteractionSource? = null,
    shape: Shape = CircleShape,
): Modifier {
    val shadow = capsuleShadow(elevation, colors.dark)
    val feedback =
        if (interactionSource != null) {
            Modifier.couiTouchFeedback(interactionSource)
                .indication(interactionSource, LocalIndication.current)
        } else {
            Modifier
        }
    val shadowContext = LocalGraphicsContext.current.shadowContext
    val shadowPainter =
        remember(shadowContext, shadow, shape) {
            shadowContext.createDropShadowPainter(shape, shadow)
        }
    val strokeColor = colors.chipStroke
    return this.drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val hairline = Stroke(width = 0.75.dp.toPx())
            val ring = Stroke(width = 1.15.dp.toPx())
            onDrawBehind {
                if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                    with(shadowPainter) { draw(size) }
                }
                drawOutline(outline, surface())
                if (strokeColor.alpha > 0f) drawOutline(outline, strokeColor, style = hairline)
                val progress = highlight()
                if (progress > 0.01f && highlightColor != Color.Unspecified) {
                    val ringColor = highlightColor.copy(alpha = highlightColor.alpha * progress)
                    drawOutline(outline, ringColor, style = ring)
                }
            }
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

/**
 * 为需要与进入动画叠加的悬浮控件提供独立的按压缩放（照 COUI 的 COUITouchMotionAnimator）：按下时缩到
 * [COUIMaterialEffect.pressTargetScale]，松手回弹；长宽比不过 2 的用 jelly 弹簧，更长的用 candy 弹簧。
 */
internal class CouiPressMotion(private val scope: CoroutineScope, private val density: Float) {
    private val animation = Animatable(1f)
    private var motion: Job? = null
    private var candy = false
    val scale = animation.asState()

    fun press(size: IntSize) {
        if (size.width <= 0 || size.height <= 0) return
        val width = size.width / density
        val height = size.height / density
        candy = maxOf(width / height, height / width) > PressCandyAspectRatio
        val spring = if (candy) CandyPressSpring else JellyPressSpring
        animate(COUIMaterialEffect.pressTargetScale(width, height), spring)
    }

    fun release() = animate(1f, if (candy) CandyReleaseSpring else JellyReleaseSpring)

    private fun animate(target: Float, spring: SpringSpec<Float>) {
        motion?.cancel()
        motion = scope.launch { animation.animateTo(target, spring) }
    }
}

/** 长宽比超过它的控件按 COUI 的 candy 弹簧回弹（COUIPressAnimatorUnit）。 */
private const val PressCandyAspectRatio = 2f

// COUI 的 pressParamsDefault：按下 (bounce 0, response 0.25 / 0.3)，松手 (0.6 / 0.5, 0.5)；
// 停止阈值照 COUIPressAnimatorUnit 的最小可见变化。
private val JellyPressSpring =
    couiSpring(response = 0.25f, bounce = 0f, visibilityThreshold = 0.001f)
private val JellyReleaseSpring =
    couiSpring(response = 0.5f, bounce = 0.6f, visibilityThreshold = 1.0e-4f)
private val CandyPressSpring =
    couiSpring(response = 0.3f, bounce = 0f, visibilityThreshold = 0.001f)
private val CandyReleaseSpring =
    couiSpring(response = 0.5f, bounce = 0.5f, visibilityThreshold = 1.0e-4f)

@Composable
internal fun rememberCouiPressMotion(): CouiPressMotion {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    return remember(scope, density) { CouiPressMotion(scope, density) }
}

/** 按下时让 [motion] 缩下去、松手回弹；手指被外层滚动接走时也松开。 */
internal fun Modifier.couiPressMotion(motion: CouiPressMotion, enabled: Boolean = true): Modifier {
    if (!enabled) return this
    return pointerInput(motion) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            motion.press(size)
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val stolen =
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
