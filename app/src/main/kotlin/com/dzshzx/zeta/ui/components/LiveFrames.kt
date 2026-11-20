package com.dzshzx.zeta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.dzshzx.zeta.phone.VirtualScreen
import kotlin.math.roundToInt

// 后台屏幕的实时画面怎么取帧、怎么画：聊天页的后台屏幕条和全屏查看（ui/chat/BackgroundScreen.kt）、
// 顶部胶囊的微缩画面、展开画面和「全屏」过渡（ui/overlay/BackgroundStage.kt）共用。

/** 取帧的快慢。 */
internal enum class FrameRate(val intervalMs: Long) {
    /** 缩略图（后台屏幕条、胶囊上的微缩画面）：看个大概就行，省电。 */
    Thumbnail(500),

    /** 放大看的（全屏查看、展开画面、「全屏」过渡）：约 20 帧每秒。 */
    Live(50),
}

/** 后台屏幕休眠时画面调暗到这么不透明（显示的是休眠前那一帧）。 */
internal const val AsleepFrameAlpha = 0.45f

/** 在屏上的时候才登记要帧，离开就停；[keepAwake]：看的期间让后台屏幕醒着。后台屏幕已经没了时不登记。帧在 [VirtualScreen.frames] 里。 */
@Composable
internal fun LiveFrames(screen: VirtualScreen, rate: FrameRate, keepAwake: Boolean = false) {
    DisposableEffect(screen, rate, keepAwake) {
        val stop = if (screen.alive) screen.watch(rate.intervalMs, keepAwake) else null
        onDispose { stop?.invoke() }
    }
}

/**
 * 把一帧画进 [dst]。位图可能比画面宽（行尾对齐的空白），只取左边 [VirtualScreen.Frame.width] 那一截；
 * [crop] 时按 [dst] 的比例居中裁切，否则拉伸铺满。
 */
internal fun DrawScope.drawFrame(frame: VirtualScreen.Frame, dst: Rect, alpha: Float = 1f, crop: Boolean = false) {
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
