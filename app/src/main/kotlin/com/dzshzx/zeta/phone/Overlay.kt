package com.dzshzx.zeta.phone

import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow

/*
 * 悬浮层（照 Eta 的顶部状态胶囊）和手机工具之间的约定。
 *
 * - [OverlayFocus]：胶囊要显示的那个对话，或经外部入口操作手机的客户端。由 TurnAlerts 算出（`hub.alerts.overlayFocus`），主线程上可观察，没有时为 null。
 * - [OverlayBridge]：悬浮窗口和无障碍服务之间要共享的几样东西（胶囊在屏上的位置、让路）。
 */

/** 胶囊的阶段：运行中（转圈）、暂停（用户点了暂停，Claude 停下等你，橙色空心环）、 接管（用户在展开画面上自己操作后台屏幕，紫色触点）、刚结束（实心点，稍后收起）。 */
enum class OverlayPhase {
    RUNNING,
    PAUSED,
    CONTROLLING,
    FINISHED;

    /** Claude 的手机操作停着、等用户（暂停或接管）：胶囊上是“继续”，展开画面描边。 */
    val held: Boolean
        get() = this == PAUSED || this == CONTROLLING

    companion object {
        /** 结束优先，其次是接管（用户在动手），再是暂停。 */
        fun of(finishing: Boolean, controlling: Boolean, paused: Boolean): OverlayPhase =
            when {
                finishing -> FINISHED
                controlling -> CONTROLLING
                paused -> PAUSED
                else -> RUNNING
            }
    }
}

/** 一次触摸，画在缩略画面和展开画面上做光效（点按是水波纹，滑动是流光尾迹，缩略画面外圈呼吸一下）。 坐标按所在屏幕的宽高归一化到 0..1。[byUser]：用户在展开画面上接管时点的。 */
data class TouchMark(
    val onAgentDisplay: Boolean,
    val x: Float,
    val y: Float,
    val endX: Float = x,
    val endY: Float = y,
    val swipe: Boolean = false,
    val longPress: Boolean = false,
    val byUser: Boolean = false,
    val at: Long = SystemClock.uptimeMillis(),
)

/**
 * 胶囊显示的那个对话（Zeta 之外经外部入口操作手机的客户端也是一个，[inZeta] 为 false）。属性都是 Compose 可观察的状态；
 * 动作可以在主线程上直接调用（耗时的自己另起线程）。
 */
@Stable
interface OverlayFocus {
    /** 对话的标题（还没有时为空）。 */
    val title: String

    /** 是 Zeta 里的对话：能补充一句话（[supplement]）、能在 Zeta 里打开（[openInZeta]）。外部客户端没有这两样，胶囊上不放它们的按钮。 */
    val inZeta: Boolean
        get() = true

    val phase: OverlayPhase
    /** 一句当前步骤，比如“正在思考”“在后台操作计算器”“点按「搜索」”“正在回答”。 */
    val step: String
    /** 标题行下面的一行小字，可以为空：对话是思考尾句（最近一小段可见推理，只在运行中有），外部客户端是谁在操作、在哪个应用。 */
    val thought: String
    /** 这个对话的后台屏幕（醒着或睡着）；没有时为 null。胶囊右侧的缩略画面、展开画面都用它。 */
    val screen: VirtualScreen?
    /** 后台屏幕上当前应用的名字。 */
    val appName: String?
    /** 这一轮最近一次手机操作是在主屏上（主屏边缘流光据此显示）。 */
    val actingOnMain: Boolean
    /** 用户正在展开画面上手动接管（Claude 的手机操作先等着）。和 [phase] 是 [OverlayPhase.CONTROLLING] 一回事。 */
    val userControlling: Boolean
    /** Claude 和用户的每次触摸。 */
    val touches: SharedFlow<TouchMark>

    /** 接管：Claude 之后的手机操作先挂起，直到 [resumeAuto]。 */
    fun takeOver()

    /** 交还给 Claude 继续。 */
    fun resumeAuto()

    /** 暂停（不接管画面，只让 Claude 的手机操作先等着）。 */
    fun pause()

    fun resume()

    /** 停止这一轮（和聊天页的停止键一样）。 */
    fun stop()

    /** 补充一句话给正在运行的这一轮（Claude Code 会在下一个工具调用的间隙读到）。 */
    fun supplement(text: String)

    /** 用户在展开画面上点按 / 滑动，坐标按画面归一化到 0..1，作用在后台屏幕上。 */
    fun userTap(x: Float, y: Float, longPress: Boolean)

    fun userSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Int)

    /** 把后台屏幕最上面的应用移到主屏（“全屏”）。放大动画放完再调用。 */
    fun handoffToMain()

    /** 打开 Zeta 的这个对话。 */
    fun openInZeta()
}

/** 悬浮窗口和无障碍服务共用的状态。 */
object OverlayBridge {
    /**
     * 胶囊此刻在主屏上占的位置（屏幕像素）；没显示时为 null（锁屏、息屏时退场，放大成全屏的动画期间也是 null）。 由胶囊窗口更新。无障碍服务按它让开触摸，TurnAlerts
     * 按它决定胶囊显示着的那个对话发不发通知、上不上超级岛。
     */
    @Volatile var capsuleBounds: Rect? = null

    /** 为 Claude 在主屏上的手势让路：为 true 时胶囊上移淡出、不接收触摸。由无障碍服务在手势前后设置。 */
    val yielded = MutableStateFlow(false)
}
