package com.dzshzx.zeta.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect as ComposeRect
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.alerts
import com.dzshzx.zeta.core.get
import com.dzshzx.zeta.phone.OverlayBridge
import com.dzshzx.zeta.phone.OverlayFocus
import com.dzshzx.zeta.phone.VirtualScreen
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 胶囊界面读的状态，全部由 [OverlayHost] 在主线程上改。 */
@Stable
internal class CapsuleState {
    /** 正在画的对话；焦点没了以后还留着，好把退场动画放完。 */
    var focus by mutableStateOf<OverlayFocus?>(null)

    /** 要不要显示（焦点没了时为 false，放退场动画）。 */
    var shown by mutableStateOf(false)
    var expanded by mutableStateOf(false)

    /** 在输入补充：窗口可获焦点，好弹输入法。 */
    var inputMode by mutableStateOf(false)

    /** 给 Claude 在主屏上的手势让路。 */
    var yielded by mutableStateOf(false)

    /** “全屏”放大动画进行中：胶囊先退场，画面由全屏窗口接着画。 */
    var materializing by mutableStateOf(false)

    /** 展开画面里画布的尺寸：按屏幕剩下的高度和后台屏幕的比例算（横屏时收小），见 [CapsuleLayout]。 */
    var stageCanvas by
        mutableStateOf(CapsuleLayout.stageCanvas(CapsuleLayout.DEFAULT_RATIO, Float.MAX_VALUE, 0f))
}

/** 胶囊界面调回窗口这边的动作。 */
internal interface CapsuleControls {
    fun toggle()

    fun collapse()

    /** 收起动画放完：这时才把窗口缩回去。 */
    fun onCollapsedSettled()

    /** 退场动画放完：焦点没了就移除窗口。 */
    fun onExitSettled()

    fun setInputMode(enabled: Boolean)

    /** 内容的实际高度（像素），窗口按档位放不下时兜底撑大。 */
    fun reportContentHeight(px: Int)

    /** 窗口的配置变了（转屏、屏幕尺寸变了）：重新量状态栏下沿和屏幕高。 */
    fun onScreenChanged()

    fun takeOver()

    /** “全屏”：[stage] 是画面此刻在屏幕上的位置（像素），[frame] 是此刻的那一帧。 */
    fun fullscreen(stage: ComposeRect, frame: VirtualScreen.Frame?)

    fun openInZeta()
}

/**
 * 顶部状态胶囊的窗口（照 Eta）：挂在 Zeta 的无障碍服务上（TYPE_ACCESSIBILITY_OVERLAY）， 不用另要悬浮窗权限。服务连上时 [attach]，断开时
 * [detach]。
 *
 * 窗口用固定宽度、分档高度，动画只在窗口里面放：WRAP_CONTENT 会让每一帧的尺寸变化都触发整个窗口重新布局（卡顿）， 但窗口里透明的部分也会吞掉触摸（包括 Claude
 * 注入的点按），所以只在展开前撑大、收起动画放完再缩回。
 */
object OverlayHost {
    private val main = Handler(Looper.getMainLooper())
    private val state = CapsuleState()
    private var service: AccessibilityService? = null
    private var scope: CoroutineScope? = null

    private var capsule: OverlayWindow? = null
    /** 窗口当前是不是展开档（展开前先撑大，收起放完才缩回，所以和 [CapsuleState.expanded] 不总一致）。 */
    private var windowExpanded = false
    private var contentHeightPx = 0
    /** 胶囊所在屏幕的高（像素），转屏时跟着更新。 */
    private var screenHeightPx = 0

    private var materialize: OverlayWindow? = null
    /** 正在“全屏”的那个对话（动画放到整屏时把它的应用移到主屏，只移一次）。 */
    private var materializeFocus: OverlayFocus? = null
    private val materializeTimeout = Runnable { finishMaterialize() }
    private val applyLater = Runnable { applyWindow() }

    fun attach(service: AccessibilityService) {
        detach()
        this.service = service
        val hub = ConversationHub.get(service.application)
        // snapshotFlow 收得到变化靠 SnapshotNotifier（服务连上时挂好）
        scope =
            MainScope().apply {
                // 锁屏、息屏时当作没有焦点：胶囊退场，解锁后再出来（见 ScreenLock）
                launch {
                    combine(snapshotFlow { hub.alerts.overlayFocus }, ScreenLock.locked) { focus, _
                            ->
                            focus.takeUnless {
                                ScreenLock.hides(OverlayWindow.mainDisplay(service))
                            }
                        }
                        .collect(::onFocus)
                }
                // 后台屏幕出现、消失时换窗口宽度档（有缩略画面时宽一点）
                launch { snapshotFlow { state.focus?.screen != null }.collect { applyWindow() } }
                launch {
                    OverlayBridge.yielded.collect { yielded ->
                        state.yielded = yielded
                        applyWindow()
                    }
                }
            }
    }

    fun detach() {
        scope?.cancel()
        scope = null
        main.removeCallbacks(materializeTimeout)
        main.removeCallbacks(applyLater)
        materialize?.destroy()
        materialize = null
        materializeFocus = null
        removeCapsule()
        state.focus = null
        state.shown = false
        state.materializing = false
        state.yielded = false
        service = null
    }

    private fun onFocus(next: OverlayFocus?) {
        if (next == null) {
            // 先放退场动画，放完（onExitSettled）再移除窗口
            state.shown = false
            state.expanded = false
            applyWindow()
            if (capsule == null) state.focus = null
            return
        }
        // 换了一个对话：收起来，从头看
        if (state.focus !== next) state.expanded = false
        state.focus = next
        state.shown = true
        if (capsule == null) createCapsule() else applyWindow()
    }

    private fun createCapsule() {
        val service = service ?: return
        val params =
            OverlayWindow.params(1, 1).apply {
                windowAnimations = 0
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                // 窗口原点就是屏幕原点，y 才正好是状态栏下沿
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                // dumpsys window 里按这个名字认（设备脚本 tools/phone-tests/capsule-live.sh）
                title = "ZetaCapsule"
            }
        val window =
            OverlayWindow(service, OverlayWindow.mainDisplay(service), params) {
                StatusCapsule(state, controls)
            }
        measureScreen(window)
        window.view.setOnTouchListener(outsideListener)
        window.view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> publishBounds() }
        capsule = window
        windowExpanded = false
        contentHeightPx = 0
        applyWindow()
        window.show()
    }

    private fun removeCapsule() {
        capsule?.destroy()
        capsule = null
        windowExpanded = false
        contentHeightPx = 0
        state.expanded = false
        state.inputMode = false
        OverlayBridge.capsuleBounds = null
    }

    /** 窗口外的触摸（FLAG_WATCH_OUTSIDE_TOUCH，只在展开时开着）：收起。输入补充时点输入法也算窗口外，不收。 */
    private val outsideListener = View.OnTouchListener { _, event ->
        if (event.actionMasked != MotionEvent.ACTION_OUTSIDE) return@OnTouchListener false
        if (state.expanded && !state.inputMode) controls.collapse()
        true
    }

    /** 按当前状态算窗口的尺寸和标志，有变化才 updateViewLayout。 */
    private fun applyWindow() {
        val window = capsule ?: return
        val lp = window.params
        val density = window.context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).roundToInt()
        val screen = state.focus?.screen
        val hasAgentDisplay = screen != null
        if (screen != null) {
            val ratio =
                if (screen.height > 0) screen.width / screen.height.toFloat()
                else CapsuleLayout.DEFAULT_RATIO
            val canvas = CapsuleLayout.stageCanvas(ratio, screenHeightPx / density, lp.y / density)
            if (state.stageCanvas != canvas) state.stageCanvas = canvas
        }
        val widthDp =
            when {
                windowExpanded -> StageWindowWidthDp
                hasAgentDisplay -> LensWindowWidthDp
                else -> WindowWidthDp
            }
        val heightDp =
            when {
                !windowExpanded -> CollapsedHeightDp
                state.inputMode -> InputHeightDp
                hasAgentDisplay -> CapsuleLayout.stageWindowHeight(state.stageCanvas).roundToInt()
                else -> ControlsHeightDp
            }
        val width = px(widthDp)
        // 档位按 Eta 的字体量好；字体或系统设置不同、内容放不下时撑大，免得把底部按钮裁掉
        val height = maxOf(px(heightDp), contentHeightPx + px(ContentMarginDp))
        var flags = lp.flags
        flags = flags.with(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, !state.inputMode)
        flags =
            flags.with(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                state.yielded || state.materializing || !state.shown,
            )
        flags = flags.with(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, windowExpanded)
        if (lp.width != width || lp.height != height || lp.flags != flags) {
            lp.width = width
            lp.height = height
            lp.flags = flags
            window.update()
        }
        publishBounds()
    }

    private fun Int.with(flag: Int, on: Boolean) = if (on) this or flag else this and flag.inv()

    /**
     * 胶囊窗口在屏上的范围（整个窗口：透明的部分也吞触摸）；没显示、在放大动画里时为 null。 不只给 Claude 的手势让路用：ConversationHub 看它是不是 null
     * 决定常驻通知要不要提升为实时通知（胶囊显示着就不提升）。
     */
    private fun publishBounds() {
        val view = capsule?.view
        OverlayBridge.capsuleBounds =
            if (
                view != null &&
                    view.isAttachedToWindow &&
                    view.width > 0 &&
                    state.shown &&
                    !state.materializing
            ) {
                val location = IntArray(2).also(view::getLocationOnScreen)
                Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
            } else {
                null
            }
    }

    private fun haptic(type: Int) {
        capsule?.view?.performHapticFeedback(type)
    }

    private val controls =
        object : CapsuleControls {
            override fun toggle() {
                if (state.expanded) {
                    collapse()
                    return
                }
                if (!state.shown || state.materializing) return
                // 先把窗口撑到展开档，再开始动画
                windowExpanded = true
                applyWindow()
                state.expanded = true
                haptic(HapticFeedbackConstants.CONTEXT_CLICK)
            }

            override fun collapse() {
                state.expanded = false
            }

            override fun onCollapsedSettled() {
                if (state.expanded || !windowExpanded) return
                windowExpanded = false
                applyWindow()
            }

            override fun onExitSettled() {
                // 这是在窗口自己的组合里回调的，移除窗口放到下一轮
                main.post {
                    if (state.shown || capsule == null) return@post
                    removeCapsule()
                    state.focus = null
                }
            }

            override fun setInputMode(enabled: Boolean) {
                if (state.inputMode == enabled) return
                state.inputMode = enabled
                applyWindow()
            }

            override fun reportContentHeight(px: Int) {
                if (contentHeightPx == px) return
                contentHeightPx = px
                // 这是在布局过程中回调的，窗口参数到下一轮再改
                main.removeCallbacks(applyLater)
                main.post(applyLater)
            }

            override fun onScreenChanged() {
                val window = capsule ?: return
                val y = window.params.y
                val height = screenHeightPx
                measureScreen(window)
                if (window.params.y == y && screenHeightPx == height) return
                if (window.params.y != y) window.update()
                // 画布高度跟着屏幕剩下的高度变
                applyWindow()
            }

            override fun takeOver() {
                val focus = state.focus ?: return
                if (focus.userControlling) return
                haptic(HapticFeedbackConstants.CONFIRM)
                focus.takeOver()
            }

            override fun fullscreen(stage: ComposeRect, frame: VirtualScreen.Frame?) {
                startMaterialize(stage, frame)
            }

            override fun openInZeta() {
                val focus = state.focus ?: return
                state.expanded = false
                focus.openInZeta()
            }
        }

    /** “全屏”：画面从展开画面的位置弹簧放大到整屏、圆角过渡成屏幕圆角（单独一个全屏、触摸穿透的窗口）， 放到整屏时再把应用移到主屏，停一会儿等应用在主屏上画出来，再淡出。 */
    private fun startMaterialize(stage: ComposeRect, frame: VirtualScreen.Frame?) {
        val service = service ?: return
        val focus = state.focus ?: return
        val screen = focus.screen ?: return
        if (state.materializing) return
        state.materializing = true
        state.expanded = false
        materializeFocus = focus
        applyWindow()
        val displayId = OverlayWindow.mainDisplay(service)
        val display = service.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
        val corner = OverlayDisplays.cornerRadius(service, displayId)
        // 有的机型 MATCH_PARENT 不含系统栏，按真实尺寸给
        val size =
            Point().also { point ->
                @Suppress("DEPRECATION") display?.getRealSize(point)
            }
        val params =
            OverlayWindow.params(
                    width = WindowManager.LayoutParams.MATCH_PARENT,
                    height = if (size.y > 0) size.y else WindowManager.LayoutParams.MATCH_PARENT,
                    flags =
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    gravity = Gravity.TOP or Gravity.START,
                )
                .apply {
                    windowAnimations = 0
                    fitInsetsTypes = 0
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
        val window =
            OverlayWindow(service, displayId, params) {
                MaterializeOverlay(
                    screen = screen,
                    initial = frame,
                    from = stage,
                    endCornerPx = corner,
                    onFull = ::handOff,
                    onDone = { main.post(::finishMaterialize) },
                )
            }
        materialize = window
        window.show()
        if (window.view.parent == null) {
            // 窗口没加上：不放动画，直接移过去
            finishMaterialize()
            return
        }
        // 兜底：动画因为什么原因没放完，也别让胶囊一直藏着、应用一直留在后台
        main.postDelayed(materializeTimeout, MaterializeTimeoutMs)
    }

    private fun handOff() {
        val focus = materializeFocus ?: return
        materializeFocus = null
        focus.handoffToMain()
    }

    private fun finishMaterialize() {
        main.removeCallbacks(materializeTimeout)
        if (!state.materializing) return
        handOff()
        materialize?.destroy()
        materialize = null
        state.materializing = false
        applyWindow()
    }

    /** 量胶囊所在屏幕：窗口上沿放在状态栏下沿，记下屏幕高。建窗口和转屏时调用（要走一次 IPC，不放在 applyWindow 里）。 */
    private fun measureScreen(window: OverlayWindow) {
        val metrics = runCatching {
            window.context.getSystemService(WindowManager::class.java).currentWindowMetrics
        }
            .getOrNull()
        screenHeightPx =
            metrics?.bounds?.height()?.takeIf { it > 0 }
                ?: window.context.resources.displayMetrics.heightPixels
        window.params.y = statusBarHeight(window, metrics)
    }

    /** 状态栏下沿（含挖孔）。 */
    private fun statusBarHeight(window: OverlayWindow, metrics: WindowMetrics?): Int {
        val context = window.context
        val insets = runCatching {
            metrics!!
                .windowInsets
                .getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout(),
                )
                .top
        }
            .getOrDefault(0)
        if (insets > 0) return insets
        @Suppress("DiscouragedApi", "InternalInsetResource")
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val dimen =
            if (id != 0) runCatching { context.resources.getDimensionPixelSize(id) }.getOrDefault(0)
            else 0
        return if (dimen > 0) dimen
        else (28 * context.resources.displayMetrics.density).roundToInt()
    }

    // 窗口档位（dp），照 Eta：胶囊本身左右各留 12dp 给阴影。展开时一律用最宽档，标题小签才放得下。
    private const val WindowWidthDp = 272
    private const val LensWindowWidthDp = 290
    private const val StageWindowWidthDp = 324
    private const val CollapsedHeightDp = 68
    private const val ControlsHeightDp = 124
    private const val InputHeightDp = 260
    private const val ContentMarginDp = 4
    private const val MaterializeTimeoutMs = 3_000L
}
