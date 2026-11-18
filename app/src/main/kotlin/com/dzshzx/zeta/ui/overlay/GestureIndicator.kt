package com.dzshzx.zeta.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.min

/**
 * Claude 在主屏上点按、长按、滑动时的触摸指示（照 Eta 的 GestureIndicator）：
 * 每次手势挂一个触摸穿透的小窗口（TYPE_ACCESSIBILITY_OVERLAY），动画放完就移除；
 * 点按、长按是落点上的圆环，滑动是沿路径前进的指尖和尾迹。截图和控件列表都会把它排除。
 *
 * [show] 可在任意线程调用（无障碍服务做手势的工具线程），窗口的事都转到主线程。
 */
internal object GestureIndicator {
    private val main = Handler(Looper.getMainLooper())
    /** 主线程上用：正在放的那个指示。新手势来了先收掉旧的。 */
    private var active: Active? = null
    /** 非 0 号屏上加无障碍浮层要用 WindowContext；同一块屏复用一个（主线程上用）。 */
    private var displayContext: Pair<Int, Context>? = null

    /** 指示预计放完的时刻；截图据此判断要不要把悬浮层排除掉。 */
    @Volatile
    private var visibleUntil = 0L

    val showing: Boolean get() = SystemClock.uptimeMillis() < visibleUntil

    /** 在 [displayId] 上沿 [path]（屏幕像素）画一次手势指示，[durationMs] 和真正注入的手势一样长。 */
    fun show(service: AccessibilityService, displayId: Int, path: Path, durationMs: Long) {
        // 调用方随后会把同一个 Path 交给系统注入，这里拷一份再到主线程上用
        val copy = Path(path)
        val density = service.resources.displayMetrics.density
        val length = PathMeasure(copy, false).length
        val swipe = length >= SWIPE_MIN_DP * density
        // 点按注入 60ms，长按至少 300ms（PhoneTools 的下限）
        val longPress = !swipe && durationMs >= LONG_PRESS_MIN_MS
        val holdMs = if (longPress) {
            (durationMs - POP_IN_MS - FADE_OUT_MS).coerceIn(MIN_LONG_PRESS_HOLD_MS, MAX_LONG_PRESS_HOLD_MS)
        } else TAP_HOLD_MS
        val totalMs = if (swipe) durationMs.coerceAtLeast(MIN_SWIPE_MS) + SWIPE_FADE_MS else POP_IN_MS + holdMs + FADE_OUT_MS
        visibleUntil = maxOf(visibleUntil, SystemClock.uptimeMillis() + totalMs + 200)
        main.post {
            val context = contextFor(service, displayId) ?: return@post
            val windowManager = context.getSystemService(WindowManager::class.java) ?: return@post
            if (swipe) {
                attach(windowManager, SwipeIndicatorView(context, copy, durationMs), params(WindowManager.LayoutParams.MATCH_PARENT, 0, 0))
            } else {
                // 只有一个落点：窗口只要指示那么大，贴在落点上
                val point = RectF().also(copy::computeBounds)
                val sizePx = (PRESS_WINDOW_DP * density).toInt()
                val x = point.centerX().toInt() - sizePx / 2
                val y = point.centerY().toInt() - sizePx / 2
                attach(windowManager, PressIndicatorView(context, longPress, holdMs), params(sizePx, x, y))
            }
        }
    }

    /** 收掉正在放的指示（截图兜底、服务断开时）。任意线程可调用。 */
    fun dismiss() {
        visibleUntil = 0L
        main.post { active?.let(::finish) }
    }

    /** 服务断开：收掉指示，放掉缓存的 WindowContext。在主线程上调用。 */
    fun release() {
        visibleUntil = 0L
        active?.let(::finish)
        displayContext = null
    }

    private fun contextFor(service: AccessibilityService, displayId: Int): Context? {
        if (displayId == 0) return service
        displayContext?.takeIf { it.first == displayId }?.let { return it.second }
        val display = service.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return null
        val context = runCatching {
            service.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        }.getOrNull() ?: return null
        displayContext = displayId to context
        return context
    }

    private fun params(size: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        size,
        size,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
        // 坐标按整块屏幕算：不让系统按状态栏、挖孔挪窗口，指示才落在手势的真实位置上
        setFitInsetsTypes(0)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "ZetaGestureIndicator"
    }

    private fun attach(windowManager: WindowManager, view: IndicatorView, params: WindowManager.LayoutParams) {
        active?.let(::finish)
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        runCatching { windowManager.addView(view, params) }.onSuccess {
            val indicator = Active(windowManager, view)
            active = indicator
            view.start { main.post { finish(indicator) } }
        }
    }

    private fun finish(indicator: Active) {
        if (active === indicator) active = null
        indicator.view.cancel()
        runCatching { indicator.windowManager.removeViewImmediate(indicator.view) }
    }

    private class Active(val windowManager: WindowManager, val view: IndicatorView)

    private const val PRESS_WINDOW_DP = 60f
    /** 路径短于这个就当作原地按下。 */
    private const val SWIPE_MIN_DP = 4f
    private const val POP_IN_MS = 200L
    private const val TAP_HOLD_MS = 100L
    private const val FADE_OUT_MS = 180L
    private const val LONG_PRESS_MIN_MS = 300L
    private const val MIN_LONG_PRESS_HOLD_MS = 240L
    private const val MAX_LONG_PRESS_HOLD_MS = 620L
    private const val MIN_SWIPE_MS = 300L
    private const val SWIPE_FADE_MS = 160L
}

/** Eta 的指示色（偏科技感的蓝）。 */
private const val IndicatorColor = 0xFF2879FB.toInt()

private abstract class IndicatorView(context: Context) : View(context) {
    abstract fun start(onFinished: () -> Unit)
    abstract fun cancel()
}

/** 点按 / 长按：落点上弹出一个空心圆环，停一下再放大淡出。 */
private class PressIndicatorView(
    context: Context,
    private val longPress: Boolean,
    private val holdMs: Long,
) : IndicatorView(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = IndicatorColor }
    private val density = resources.displayMetrics.density

    override fun start(onFinished: () -> Unit) {
        alpha = 0f
        scaleX = 0.5f
        scaleY = 0.5f
        val grow = if (longPress) 1.12f else 1.06f
        animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(200L)
            .setInterpolator(OvershootInterpolator(2f))
            .withEndAction {
                animate().alpha(0f).scaleX(grow).scaleY(grow)
                    .setDuration(180L)
                    .setStartDelay(holdMs)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { onFinished() }
                    .start()
            }
            .start()
    }

    override fun cancel() {
        animate().cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val ring = 20f * density
        // 和目标控件之间留一圈清楚的空心边界，里面只给很轻的落点着色
        paint.style = Paint.Style.FILL
        paint.alpha = if (longPress) 0x38 else 0x2A
        canvas.drawCircle(cx, cy, ring, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (if (longPress) 2.5f else 2f) * density
        paint.alpha = 255
        canvas.drawCircle(cx, cy, ring, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 230
        canvas.drawCircle(cx, cy, (if (longPress) 4f else 3f) * density, paint)
        if (longPress) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density
            paint.alpha = 110
            canvas.drawCircle(cx, cy, 25f * density, paint)
        }
    }
}

/** 滑动：先画极淡的整条路径预告，实线和指尖跟着注入的手势匀速前进，最后整体淡出。 */
private class SwipeIndicatorView(
    context: Context,
    private val path: Path,
    private val durationMs: Long,
) : IndicatorView(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = IndicatorColor
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val density = resources.displayMetrics.density
    private val measure = PathMeasure(path, false)
    private val length = measure.length
    private val trail = Path()
    private val head = FloatArray(2)
    private val start = FloatArray(2).also { measure.getPosTan(0f, it, null) }
    private var progress = 0f
    private var fade = 1f
    private var animator: AnimatorSet? = null

    override fun start(onFinished: () -> Unit) {
        // 系统按路径匀速注入，指尖也匀速走，才和真实触点对得上
        val advance = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs.coerceAtLeast(300L)
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
        }
        val fadeOut = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 160L
            addUpdateListener {
                fade = it.animatedValue as Float
                invalidate()
            }
        }
        animator = AnimatorSet().apply {
            playSequentially(advance, fadeOut)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = onFinished()
            })
            start()
        }
    }

    override fun cancel() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        val distance = length * progress
        trail.reset()
        measure.getSegment(0f, distance, trail, true)
        measure.getPosTan(distance, head, null)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.alpha = (fade * 0.12f * 255).toInt()
        canvas.drawPath(path, paint)

        paint.strokeWidth = 3f * density
        paint.alpha = (fade * 0.62f * 255).toInt()
        canvas.drawPath(trail, paint)

        paint.style = Paint.Style.FILL
        paint.alpha = (fade * 0.9f * 255).toInt()
        canvas.drawCircle(head[0], head[1], 7f * density, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.alpha = (fade * 0.48f * 255).toInt()
        canvas.drawCircle(head[0], head[1], 13f * density, paint)

        paint.style = Paint.Style.FILL
        paint.alpha = (fade * 0.5f * 255).toInt()
        canvas.drawCircle(start[0], start[1], min(4f * density, 4f * density * (1f - progress) + density), paint)
    }
}
