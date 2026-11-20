package com.dzshzx.zeta.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.TurnResult
import io.github.suqi8.coui.kmp.layout.BottomSheetDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 结果卡片窗口里的内容读的状态，全部由 [ResultCardHost] 在主线程上改。 */
@Stable
private class ResultCardState {
    /** 正在画的那一轮；收起以后还留着，好把退场动画放完。 */
    var card by mutableStateOf<TurnResult?>(null)
    var shown by mutableStateOf(false)

    /** 卡片最高多少（约半屏）、导航条多高：建窗口时按所在屏幕量。 */
    var maxHeight by mutableStateOf(Dp.Unspecified)
    var bottomInset by mutableStateOf(0.dp)
}

/**
 * 一轮结束后屏幕底部的结果卡片（[ResultCard]）的窗口：和顶部胶囊一样挂在 Zeta 的无障碍服务上（TYPE_ACCESSIBILITY_OVERLAY），
 * 服务连上时 [attach]，断开时 [detach]。弹哪一轮、什么时候收起由 TurnAlerts 定（`hub.alerts.resultCard`，见 ResultCardPolicy），
 * 这里只管窗口：贴着屏幕底边，宽同屏（宽屏时收窄居中），高随卡片（卡片报告自己多高，不用 WRAP_CONTENT：
 * ZetaTheme 的 fillMaxSize 会把它撑满整屏），窗口以外的触摸照常落到下面的应用上。
 * 窗口可获焦点，才收得到系统返回（返回关掉卡片）；不接输入法。
 */
object ResultCardHost {
    private val main = Handler(Looper.getMainLooper())
    private val state = ResultCardState()
    private var service: AccessibilityService? = null
    private var hub: ConversationHub? = null
    private var scope: CoroutineScope? = null
    private var window: OverlayWindow? = null

    fun attach(service: AccessibilityService) {
        detach()
        this.service = service
        val hub = ConversationHub.get(service.application)
        this.hub = hub
        // snapshotFlow 收得到 resultCard 的变化靠 SnapshotNotifier（服务连上时挂好）
        scope = MainScope().apply {
            launch {
                // 锁屏状态不是快照状态：它一变就重新订阅，按新的锁屏状态判断卡片还留不留
                ScreenLock.locked.collectLatest {
                    val locked = ScreenLock.hides(OverlayWindow.mainDisplay(service))
                    snapshotFlow { hub.alerts.resultCard?.let { card -> card to hub.alerts.keepsResultCard(card, locked) } }.collect { entry ->
                        when {
                            entry == null -> hide()
                            // 收起：hub 那边清掉，下一次就是 null
                            !entry.second -> hub.alerts.dismissResultCard(entry.first)
                            else -> show(entry.first)
                        }
                    }
                }
            }
        }
    }

    fun detach() {
        scope?.cancel()
        scope = null
        removeWindow()
        // 服务断开时显示着的卡片作废，免得服务回来时又弹出一张旧的
        hub?.let { hub -> hub.alerts.resultCard?.let(hub.alerts::dismissResultCard) }
        hub = null
        state.card = null
        state.shown = false
        service = null
    }

    private fun show(card: TurnResult) {
        state.card = card
        state.shown = true
        if (window == null) createWindow()
    }

    private fun hide() {
        // 先放退场动画，放完（onExitSettled）再移除窗口
        state.shown = false
        if (window == null) state.card = null
    }

    private fun createWindow() {
        val service = service ?: return
        val params = OverlayWindow.params(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            // 先给 1 像素，卡片量出自己多高再撑开（onContentHeight）
            height = 1,
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply {
            // 可获焦点才收得到返回键；卡片上没有输入框，不做输入法的目标，下面应用的输入法不受影响
            flags = (flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            windowAnimations = 0
            // 窗口铺到屏幕底边（导航条下面），卡片自己留出导航条的高度，滑出时整张滑出屏幕
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // dumpsys window 里认得出（设备测试据此判断卡片在不在）
            title = WINDOW_TITLE
        }
        val window = OverlayWindow(service, OverlayWindow.mainDisplay(service), params) { Content() }
        measure(window)
        this.window = window
        window.show()
        if (window.view.parent == null) {
            // 窗口没加上（测试屏没了之类）：当作没弹
            removeWindow()
            state.card?.let { card -> hub?.alerts?.dismissResultCard(card) }
            state.card = null
            state.shown = false
        }
    }

    private fun removeWindow() {
        window?.destroy()
        window = null
    }

    /** 量卡片所在的屏：宽屏时窗口收窄到 COUI 面板的最大宽度，卡片最高半屏，留出导航条。 */
    private fun measure(window: OverlayWindow) {
        val context = window.context
        val density = context.resources.displayMetrics.density
        val metrics = runCatching { context.getSystemService(WindowManager::class.java).currentWindowMetrics }.getOrNull()
        val width = metrics?.bounds?.width()?.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val height = metrics?.bounds?.height()?.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val navigation = runCatching { metrics!!.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom }.getOrDefault(0)
        val widest = ((BottomSheetDefaults.PanelMaxWidth.value + 2 * WINDOW_SIDE_DP) * density).roundToInt()
        if (width > widest) window.params.width = widest
        state.maxHeight = (height * MAX_HEIGHT_FRACTION / density).dp
        state.bottomInset = (navigation / density).dp
    }

    /** 卡片报告的高度（像素）。这是在布局过程中回调的，窗口参数到下一轮再改。 */
    private fun onContentHeight(px: Int) {
        main.post {
            val window = window ?: return@post
            if (px <= 0 || window.params.height == px) return@post
            window.params.height = px
            window.update()
        }
    }

    private fun onExitSettled() {
        // 这是在窗口自己的组合里回调的，移除窗口放到下一轮
        main.post {
            if (state.shown || window == null) return@post
            removeWindow()
            state.card = null
        }
    }

    @Composable
    private fun Content() {
        val card = state.card ?: return
        key(card) {
            ResultCard(
                title = card.title,
                text = card.text,
                failed = card.failed,
                visible = state.shown,
                onDismiss = { hub?.alerts?.dismissResultCard(card) },
                onOpen = card::openInZeta,
                maxHeight = state.maxHeight,
                bottomInset = state.bottomInset,
                onExitSettled = ::onExitSettled,
                onHeight = ::onContentHeight,
            )
        }
    }

    /** 卡片最高占屏幕高度的多少。 */
    private const val MAX_HEIGHT_FRACTION = 0.5f

    /** 宽屏时窗口两边比卡片多出的宽度（dp），和 [ResultCard] 的左右边距一致。 */
    private const val WINDOW_SIDE_DP = 12f

    const val WINDOW_TITLE = "ZetaResultCard"
}
