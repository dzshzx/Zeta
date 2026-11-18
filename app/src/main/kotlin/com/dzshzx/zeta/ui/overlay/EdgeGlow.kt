package com.dzshzx.zeta.ui.overlay

import android.graphics.BlurMaskFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.dzshzx.zeta.phone.OverlayPhase
import top.yukonga.miuix.kmp.squircle.addSquircleRect

// 照 Eta：流光取强调色和相邻的冷暖色，不用高饱和彩虹，免得在浅色页面上喧宾夺主
private val EdgeLightColors = intArrayOf(
    0xFF7AB8FF.toInt(),
    0xFFB59CFF.toInt(),
    0xFFFF9EC7.toInt(),
    0xFFFFC98A.toInt(),
    0xFF7AB8FF.toInt(),
)

/** 暂停时的静止描边色（和 Eta 胶囊的“暂停”同色）。 */
private const val PausedAccent = 0xFFFF9F0A.toInt()

internal const val EdgeGlowFadeInMs = 420
internal const val EdgeGlowFadeOutMs = 240

/**
 * 主屏边缘光（照 Eta 的 AgentOverlayGlow）：
 * - RUNNING：沿屏幕圆角流动的细光带，外面一层柔光，告诉用户手机正被 Claude 操作；
 * - PAUSED：静止的淡色描边，任务还在、控制权暂回用户；
 * - 其他阶段不画（窗口由 [EdgeGlowHost] 收起）。
 *
 * [visible] 控制淡入淡出；[hidden] 为截图兜底时立刻不画。[cornerRadius] 是屏幕的物理圆角半径（像素）。
 */
@Composable
internal fun EdgeGlow(phase: OverlayPhase, visible: Boolean, hidden: Boolean, cornerRadius: Float) {
    // 窗口每次挂上都是新的组合，从 0 淡入
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        reveal.animateTo(
            if (visible) 1f else 0f,
            tween(if (visible) EdgeGlowFadeInMs else EdgeGlowFadeOutMs),
        )
    }
    val running = phase == OverlayPhase.RUNNING
    // 只在运行时转动；角度只在绘制阶段读取，每帧不重组
    val rotation = if (running) {
        rememberInfiniteTransition(label = "edge_light").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
            label = "edge_rotation",
        )
    } else null

    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val inset = 1.5f * density
            // Display 只报一个圆弧半径，实际面板是连续曲率的圆角：用同一半径画连续曲线，
            // 路径内收 inset、转角同步减小，光带才和屏幕边缘等距，不会在角上缩进一截
            val edge = Path().apply {
                addSquircleRect(
                    width = size.width - inset * 2,
                    height = size.height - inset * 2,
                    cornerRadius = (cornerRadius - inset).coerceAtLeast(0f),
                )
                translate(Offset(inset, inset))
            }.asAndroidPath()
            val cx = size.width / 2f
            val cy = size.height / 2f
            val shader = SweepGradient(cx, cy, EdgeLightColors, null)
            val matrix = Matrix()
            // 外层柔光和内层亮线共用同一个旋转渐变，看起来是一条整体流动的光带
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 14f * density
                maskFilter = BlurMaskFilter(12f * density, BlurMaskFilter.Blur.NORMAL)
                this.shader = shader
            }
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f * density
                this.shader = shader
            }
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f * density
                color = PausedAccent
            }
            onDrawBehind {
                val alpha = if (hidden) 0f else reveal.value
                if (alpha <= 0f) return@onDrawBehind
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    if (rotation == null) {
                        outline.alpha = (0.55f * alpha * 255).toInt()
                        native.drawPath(edge, outline)
                        return@drawIntoCanvas
                    }
                    matrix.setRotate(rotation.value, cx, cy)
                    shader.setLocalMatrix(matrix)
                    halo.alpha = (0.55f * alpha * 255).toInt()
                    line.alpha = (0.95f * alpha * 255).toInt()
                    native.drawPath(edge, halo)
                    native.drawPath(edge, line)
                }
            }
        },
    )
}
