package com.dzshzx.zeta.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.dzshzx.zeta.ui.overlay.EdgeGlowHost
import com.dzshzx.zeta.ui.overlay.GestureIndicator
import com.dzshzx.zeta.ui.overlay.OverlayHost
import com.dzshzx.zeta.ui.overlay.OverlayWindow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Zeta 的无障碍服务：给 [PhoneTools] 读屏幕上的控件、做手势、截图。
 * 它自己不监听什么，也不主动做任何事，只在 Claude 调用手机工具时被用到。
 *
 * Zeta 自己的悬浮层（顶部胶囊、主屏边缘光、触摸指示）也挂在这里，但 Claude 看到的控件列表和截图都不含它们；
 * Claude 在主屏上的手势经过胶囊时，先请胶囊让开再注入。
 */
class ZetaAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        instance = this
        // 顶部状态胶囊和主屏边缘光都挂在这个服务上
        OverlayHost.attach(this)
        EdgeGlowHost.attach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        detachOverlays()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detachOverlays()
        executor.shutdown()
        super.onDestroy()
    }

    private fun detachOverlays() {
        if (instance !== this) return
        instance = null
        OverlayHost.detach()
        EdgeGlowHost.detach()
        GestureIndicator.release()
        synchronized(yieldLock) {
            yieldHolds = 0
            main.removeCallbacks(restoreCapsule)
            OverlayBridge.yielded.value = false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** 某块屏幕上各个窗口的根节点，最上层的在前。Zeta 自己的悬浮层不算在内。 */
    fun roots(displayId: Int): List<AccessibilityNodeInfo> {
        readRoots(displayId).takeIf { it.isNotEmpty() }?.let { return it }
        // 应用在几块屏之间挪过以后，本地的窗口缓存可能还是旧的，读出来一个根节点都没有（系统那边窗口其实在）：
        // 清掉缓存再读一次
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !runCatching { clearCache() }.getOrDefault(false)) return emptyList()
        return readRoots(displayId).also { if (it.isNotEmpty()) Log.i("ZetaPhone", "display $displayId: stale window cache cleared") }
    }

    /**
     * 丢掉本地的控件缓存，下次读的都是应用那边的最新状态。页面切换、返回的动画只挪窗口，不发内容变化的事件，
     * 缓存里的位置会一直停在动画半路上（实测返回后整列控件偏出屏幕）。Android 14 以下没有这个接口，什么也不做。
     */
    fun dropCache() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) runCatching { clearCache() }
    }

    /** 某块屏幕最上层的应用窗口属于哪个包（状态栏、输入法这些系统窗口不算）。读不到返回 null。 */
    fun topApp(displayId: Int): String? {
        val windows = runCatching { windowsOnAllDisplays.get(displayId) }.getOrNull().orEmpty()
        val top = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer } ?: return null
        return runCatching { top.root?.packageName?.toString() }.getOrNull()
    }

    /** 某块屏幕上输入法键盘开着没有。 */
    fun imeShown(displayId: Int): Boolean =
        runCatching { windowsOnAllDisplays.get(displayId) }.getOrNull().orEmpty().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    private fun readRoots(displayId: Int): List<AccessibilityNodeInfo> {
        val windows = runCatching { windowsOnAllDisplays.get(displayId) }.getOrNull().orEmpty()
        return windows.sortedByDescending { it.layer }.mapNotNull { window ->
            val root = runCatching { window.root }.getOrNull() ?: return@mapNotNull null
            root.takeUnless { window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && root.packageName?.toString() == packageName }
        }
    }

    // ---- 手势 ----

    /**
     * 沿 [path] 做一次手势，等它做完。返回是否完成。
     * [holdMs] 大于 0 时，走到终点后按住不动这么久再抬起：列表就不会按抬手时的速度甩出惯性，滚动的距离和手指走的一样。
     */
    fun gesture(displayId: Int, path: Path, durationMs: Long, holdMs: Long = 0): Boolean {
        val duration = durationMs.coerceAtLeast(1)
        // 路径越界、时长超限时系统直接抛异常：当作没做成，调用方会改用 input 命令
        val stroke = runCatching { GestureDescription.StrokeDescription(path, 0, duration, holdMs > 0) }.getOrElse { return false }
        val description = runCatching {
            GestureDescription.Builder().setDisplayId(displayId).addStroke(stroke).build()
        }.getOrElse { return false }
        val hold = if (holdMs <= 0) null else runCatching {
            val end = FloatArray(2).also { PathMeasure(path, false).run { getPosTan(length, it, null) } }
            val still = Path().apply { moveTo(end[0], end[1]) }
            GestureDescription.Builder().setDisplayId(displayId).addStroke(stroke.continueStroke(still, 0, holdMs, false)).build()
        }.getOrElse { return false }
        val onMain = displayId == OverlayWindow.mainDisplay(this)
        // 胶囊窗口接收触摸，经过它的手势会被它截走：先请它让开（上移淡出、不接收触摸），等动画走完再注入
        val clear = onMain && Looper.myLooper() != Looper.getMainLooper() && crossesCapsule(path)
        val wait = if (clear) holdCapsule() else 0L
        try {
            if (wait > 0) Thread.sleep(wait)
            if (onMain) GestureIndicator.show(this, displayId, path, duration)
            if (!dispatchAndWait(description, duration)) return false
            return hold == null || dispatchAndWait(hold, holdMs)
        } finally {
            if (clear) releaseCapsule(RESTORE_AFTER_GESTURE_MS)
        }
    }

    /** 派发一段手势并等它做完。返回是否完成。 */
    private fun dispatchAndWait(description: GestureDescription, durationMs: Long): Boolean {
        val done = CountDownLatch(1)
        var completed = false
        val accepted = runCatching {
            dispatchGesture(
                description,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        completed = true
                        done.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) = done.countDown()
                },
                main,
            )
        }.getOrDefault(false)
        if (!accepted) return false
        done.await(durationMs + 3000, TimeUnit.MILLISECONDS)
        return completed
    }

    /**
     * 只画触摸指示、不注入：控件自己处理了点击（ACTION_CLICK）时用，用户照样看得到 Claude 点了哪里（照 Eta）。
     * 只在主屏上画；[durationMs] 和 [gesture] 的一样（点按 60，长按的时长）。
     */
    fun indicateTap(displayId: Int, x: Int, y: Int, durationMs: Long) {
        if (displayId != OverlayWindow.mainDisplay(this)) return
        GestureIndicator.show(this, displayId, Path().apply { moveTo(x.toFloat(), y.toFloat()) }, durationMs)
    }

    /** 手势路径（逐点取样，向外放宽一点）有没有碰到胶囊。 */
    private fun crossesCapsule(path: Path): Boolean {
        val capsule = OverlayBridge.capsuleBounds ?: return false
        val margin = CLEARANCE_MARGIN_DP * resources.displayMetrics.density
        val area = RectF(capsule).apply { inset(-margin, -margin) }
        val measure = PathMeasure(path, false)
        val step = 8f * resources.displayMetrics.density
        val point = FloatArray(2)
        var sampled = false
        do {
            val length = measure.length
            var distance = 0f
            while (true) {
                if (measure.getPosTan(distance.coerceAtMost(length), point, null)) {
                    sampled = true
                    if (area.contains(point[0], point[1])) return true
                }
                if (distance >= length) break
                distance += step
            }
        } while (measure.nextContour())
        if (sampled) return false
        // 点按只有一个 moveTo，量不出长度：按路径的外框算
        val bounds = RectF().also(path::computeBounds)
        return bounds.left <= area.right && area.left <= bounds.right && bounds.top <= area.bottom && area.top <= bounds.bottom
    }

    // 让路的计数：多个手势（或截图兜底）同时要胶囊让开时，最后一个结束后才回来
    private val yieldLock = Any()
    private var yieldHolds = 0
    private var yieldedAt = 0L
    private val restoreCapsule = Runnable {
        synchronized(yieldLock) { if (yieldHolds == 0) OverlayBridge.yielded.value = false }
    }

    /** 请胶囊让开，返回还要等多久它才让完（已经让开着就不用再等）。 */
    private fun holdCapsule(): Long = synchronized(yieldLock) {
        main.removeCallbacks(restoreCapsule)
        yieldHolds++
        val now = SystemClock.uptimeMillis()
        if (!OverlayBridge.yielded.value) {
            OverlayBridge.yielded.value = true
            yieldedAt = now
        }
        (yieldedAt + YIELD_ANIMATION_MS - now).coerceAtLeast(0L)
    }

    /**
     * 让胶囊回来。恢复带去抖（照 Eta）：动作之后通常紧接着看一眼屏幕、再做下一步，
     * 连续的点按、滑动只在最后一次之后才回来，胶囊不会随每一步上下闪。
     */
    private fun releaseCapsule(delayMs: Long) = synchronized(yieldLock) {
        yieldHolds = (yieldHolds - 1).coerceAtLeast(0)
        if (yieldHolds == 0) {
            main.removeCallbacks(restoreCapsule)
            main.postDelayed(restoreCapsule, delayMs)
        }
    }

    // ---- 截图 ----

    /**
     * 截一张屏，不含 Zeta 自己的悬浮层（胶囊、边缘光、触摸指示），Claude 看到的只有屏幕上的应用本身。
     *
     * 屏上有我们的悬浮层时（照 Eta）逐个窗口截（takeScreenshotOfWindow，Android 14 起），跳过自己的浮层再按层级拼起来；
     * 没有时直接整屏截。逐窗口拼不出来时，先请悬浮层都让开再整屏截。
     * 系统限制两次截图的间隔，太快会失败一次，所以失败后稍等再试。
     */
    fun screenshot(displayId: Int): Bitmap? {
        var overlaid = false
        for (attempt in 0 until 3) {
            if (attempt > 0) Thread.sleep(450)
            val windows = runCatching { windowsOnAllDisplays.get(displayId) }.getOrNull().orEmpty()
            if (!overlaysShown(displayId, windows)) {
                displayShot(displayId)?.let { return it }
                continue
            }
            overlaid = true
            val shot = compositeShot(displayId, windows)
            shot.bitmap?.let { return it }
            if (!shot.retry) break
        }
        if (!overlaid) return null
        // 兜底：胶囊让开、边缘光和触摸指示先藏起来，整屏截完再放回来。胶囊会闪一下，但 Claude 不会看到它
        val wait = holdCapsule()
        EdgeGlowHost.hideForCapture(true)
        GestureIndicator.dismiss()
        try {
            Thread.sleep(maxOf(wait, ASIDE_SETTLE_MS))
            return displayShot(displayId) ?: run {
                Thread.sleep(450)
                displayShot(displayId)
            }
        } finally {
            EdgeGlowHost.hideForCapture(false)
            releaseCapsule(0L)
        }
    }

    /** 这块屏上此刻有没有 Zeta 自己的悬浮层。 */
    private fun overlaysShown(displayId: Int, windows: List<AccessibilityWindowInfo>): Boolean {
        // 触摸穿透的窗口（边缘光、触摸指示）不一定出现在无障碍窗口列表里，所以也问一下它们自己
        if (displayId == OverlayWindow.mainDisplay(this) &&
            (OverlayBridge.capsuleBounds != null || EdgeGlowHost.showing || GestureIndicator.showing)
        ) return true
        return windows.any {
            it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && isOwnOverlay(it, runCatching { it.root }.getOrNull())
        }
    }

    /**
     * Zeta 自己的无障碍浮层。别的无障碍服务的浮层照样截进去：它们真的挡在屏幕上、会接住触摸，
     * Claude 得看到。读不到内容的无障碍浮层也当作自己的（它在控件列表里同样是空的）。
     */
    private fun isOwnOverlay(window: AccessibilityWindowInfo, root: AccessibilityNodeInfo?): Boolean {
        if (window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return false
        val owner = root?.packageName?.toString()
        return owner == null || owner == packageName
    }

    private class Shot(val bitmap: Bitmap?, val retry: Boolean)

    /** 要截的一个窗口。[frame] 是窗口内容在屏上的位置（根节点的范围），[bounds] 是无障碍报的（触摸）范围。 */
    private class Target(val id: Int, val frame: Rect, val bounds: Rect, val critical: Boolean)

    /** 逐窗口截图再拼起来，跳过 Zeta 自己的浮层。 */
    private fun compositeShot(displayId: Int, windows: List<AccessibilityWindowInfo>): Shot {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return Shot(null, retry = false)
        // 别的进程建的虚拟屏应用自己查不到尺寸：按各窗口的范围估
        val size = displaySize(displayId) ?: windows.fold(Rect()) { all, window ->
            all.apply { union(Rect().also(window::getBoundsInScreen)) }
        }.takeUnless { it.isEmpty || it.right > 8192 || it.bottom > 8192 }?.let { Point(it.right, it.bottom) }
            ?: return Shot(null, retry = false)
        val screen = Rect(0, 0, size.x, size.y)
        val kept = windows.mapNotNull { window ->
            val root = runCatching { window.root }.getOrNull()
            if (isOwnOverlay(window, root)) return@mapNotNull null
            val bounds = Rect().also(window::getBoundsInScreen)
            if (bounds.isEmpty || !Rect.intersects(bounds, screen)) return@mapNotNull null
            window to Pair(root, bounds)
        }.sortedBy { (window, _) -> window.layer }
        if (kept.isEmpty()) return Shot(null, retry = true)
        // 最上层的应用窗口和焦点 / 活动窗口是画面的主体，截不到就不拼，免得给 Claude 一张缺了主体的图
        val topApp = kept.lastOrNull { (window, _) -> window.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.first ?: kept.last().first
        val targets = kept.map { (window, info) ->
            val (root, bounds) = info
            val frame = root?.let { Rect().also(it::getBoundsInScreen) }?.takeUnless { it.isEmpty } ?: bounds
            Target(window.id, frame, bounds, critical = window === topApp || window.isActive || window.isFocused)
        }

        val bitmaps = ConcurrentHashMap<Int, Bitmap>()
        val failures = ConcurrentHashMap<Int, Int>()
        val open = AtomicBoolean(true)
        val done = CountDownLatch(targets.size)
        for (target in targets) {
            runCatching {
                takeScreenshotOfWindow(target.id, executor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bitmap = runCatching { softwareCopy(result) }.getOrNull()
                        when {
                            bitmap == null -> failures[target.id] = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                            open.get() -> bitmaps[target.id] = bitmap
                            else -> bitmap.recycle()
                        }
                        done.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        failures[target.id] = errorCode
                        done.countDown()
                    }
                })
            }.onFailure {
                failures[target.id] = ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR
                done.countDown()
            }
        }
        val finished = done.await(3, TimeUnit.SECONDS)
        open.set(false)
        try {
            // 安全窗口（FLAG_SECURE）本来在整屏截图里也是黑的，涂黑即可，不算缺
            val missing = targets.filter { !bitmaps.containsKey(it.id) && failures[it.id] != ERROR_TAKE_SCREENSHOT_SECURE_WINDOW }
            if (missing.any { it.critical }) {
                val retry = !finished || failures.values.any { it == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT }
                return Shot(null, retry)
            }
            return Shot(merge(size, targets, bitmaps), retry = true)
        } finally {
            bitmaps.values.forEach { it.recycle() }
        }
    }

    /** 黑底上按层级从下往上画各个窗口。 */
    private fun merge(size: Point, targets: List<Target>, bitmaps: Map<Int, Bitmap>): Bitmap? = runCatching {
        val output = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val black = Paint().apply { color = Color.BLACK }
        for (target in targets) {
            val bitmap = bitmaps[target.id]
            if (bitmap == null) {
                // 截不到的窗口涂黑，不能把被它挡住的下层露给 Claude
                canvas.drawRect(target.bounds, black)
                continue
            }
            canvas.drawBitmap(bitmap, null, placement(bitmap, target.frame), paint)
        }
        output
    }.getOrNull()

    /**
     * 窗口截图在屏上的位置。截到的是窗口整个图层，可能比无障碍报的触摸范围大（输入法、带阴影的对话框），
     * 所以按窗口内容的位置摆：一样大就原样放；四周大出一圈相同的边（阴影留的 surface inset）就居中放；
     * 都不是（应用在兼容缩放下运行）才缩放进窗口范围。
     */
    private fun placement(bitmap: Bitmap, frame: Rect): Rect {
        val extraX = bitmap.width - frame.width()
        val extraY = bitmap.height - frame.height()
        return when {
            abs(extraX) <= 2 && abs(extraY) <= 2 ->
                Rect(frame.left, frame.top, frame.left + bitmap.width, frame.top + bitmap.height)
            extraX > 0 && extraY > 0 && abs(extraX - extraY) <= 2 -> {
                val left = frame.left - extraX / 2
                val top = frame.top - extraY / 2
                Rect(left, top, left + bitmap.width, top + bitmap.height)
            }
            else -> frame
        }
    }

    private fun displaySize(displayId: Int): Point? {
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return null
        val size = Point()
        @Suppress("DEPRECATION") display.getRealSize(size)
        return size.takeIf { it.x > 0 && it.y > 0 }
    }

    /** 整屏截一张。 */
    private fun displayShot(displayId: Int): Bitmap? {
        val done = CountDownLatch(1)
        var bitmap: Bitmap? = null
        runCatching {
            takeScreenshot(displayId, executor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    bitmap = runCatching { softwareCopy(result) }.getOrNull()
                    done.countDown()
                }

                override fun onFailure(errorCode: Int) = done.countDown()
            })
        }.onFailure { done.countDown() }
        done.await(5, TimeUnit.SECONDS)
        return bitmap
    }

    /** 系统给的是硬件缓冲区：拷成普通位图（才能缩放、编码、往画布上画），缓冲区随即关掉。 */
    private fun softwareCopy(result: ScreenshotResult): Bitmap? = result.hardwareBuffer.use { buffer ->
        val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return@use null
        try {
            wrapped.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            wrapped.recycle()
        }
    }

    companion object {
        /** 服务连上时就是它自己；没开或被系统断开时是 null。 */
        @Volatile
        var instance: ZetaAccessibilityService? = null
            private set

        /** 手势路径向外放宽多少再和胶囊比（照 Eta 胶囊触摸范围的余量，略放大）。 */
        private const val CLEARANCE_MARGIN_DP = 12f

        /** 胶囊让路的动画时长（照 Eta：180ms 上移淡出）；等它走完再注入，触摸穿透也已经生效。 */
        private const val YIELD_ANIMATION_MS = 180L

        /** 最后一个手势结束后多久胶囊回来（照 Eta 900ms）。 */
        private const val RESTORE_AFTER_GESTURE_MS = 900L

        /** 截图兜底时至少等这么久，让藏起来的边缘光、触摸指示真正从屏上消失。 */
        private const val ASIDE_SETTLE_MS = 120L
    }
}
