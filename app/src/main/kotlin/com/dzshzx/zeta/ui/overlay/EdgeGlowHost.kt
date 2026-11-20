package com.dzshzx.zeta.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.phone.OverlayPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Claude 在主屏上操作时沿屏幕边缘流动的光带（照 Eta）：全屏、触摸穿透，挂在 Zeta 的无障碍服务上。
 * 服务连上时 [attach]，断开时 [detach]。都在主线程上调用。
 *
 * 只在 `hub.overlayFocus` 最近一次操作在主屏、阶段是运行、暂停或接管时挂上窗口；其余时候窗口整个移除，
 * 不占合成层，动画也随组合一起停掉。
 */
object EdgeGlowHost {
    private val main = Handler(Looper.getMainLooper())
    private var service: AccessibilityService? = null
    private var scope: CoroutineScope? = null
    private var window: OverlayWindow? = null
    private var windowDisplay = Display.INVALID_DISPLAY

    // 窗口里的 Compose 内容读这几样
    private val phase = mutableStateOf(OverlayPhase.RUNNING)
    private val visible = mutableStateOf(false)
    private val hiddenForCapture = mutableStateOf(false)
    private val cornerRadius = mutableFloatStateOf(0f)

    private val removeWindow = Runnable {
        window?.hide()
        showing = false
    }

    /** 光带窗口此刻挂在主屏上（截图据此判断要不要把悬浮层排除掉）。任意线程可读。 */
    @Volatile
    var showing = false
        private set

    fun attach(service: AccessibilityService) {
        detach()
        this.service = service
        val hub = ConversationHub.get(service.application)
        // snapshotFlow 收得到 overlayFocus 的变化靠 SnapshotNotifier（服务连上时挂好）
        scope = MainScope().also { scope ->
            scope.launch {
                val target = snapshotFlow {
                    val focus = hub.overlayFocus
                    if (focus == null || !focus.actingOnMain) null
                    else focus.phase.takeIf { it == OverlayPhase.RUNNING || it.held }
                }
                // 锁屏、息屏时也收起（见 ScreenLock）
                combine(target, ScreenLock.locked) { phase, _ ->
                    phase.takeUnless { ScreenLock.hides(OverlayWindow.mainDisplay(service)) }
                }.collect(::update)
            }
        }
    }

    fun detach() {
        scope?.cancel()
        scope = null
        main.removeCallbacks(removeWindow)
        window?.destroy()
        window = null
        windowDisplay = Display.INVALID_DISPLAY
        service = null
        showing = false
        visible.value = false
        hiddenForCapture.value = false
    }

    /** 截图兜底时临时把光带藏起来（不淡出，下一帧就不画）。任意线程可调用。 */
    fun hideForCapture(hidden: Boolean) {
        main.post { hiddenForCapture.value = hidden }
    }

    private fun update(target: OverlayPhase?) {
        if (target == null) {
            // 先淡出，放完再移除窗口
            visible.value = false
            main.removeCallbacks(removeWindow)
            if (window != null) main.postDelayed(removeWindow, EdgeGlowFadeOutMs + 80L)
            return
        }
        main.removeCallbacks(removeWindow)
        val service = service ?: return
        phase.value = target
        val displayId = OverlayWindow.mainDisplay(service)
        val current = window?.takeIf { windowDisplay == displayId } ?: run {
            // 主屏换了（调试时改了 phoneDisplay）：在新屏上重建
            window?.destroy()
            createWindow(service, displayId).also {
                window = it
                windowDisplay = displayId
            }
        }
        cornerRadius.floatValue = OverlayDisplays.cornerRadius(service, displayId)
        showing = true
        current.show()
        visible.value = true
    }

    private fun createWindow(service: AccessibilityService, displayId: Int): OverlayWindow {
        val params = OverlayWindow.params(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            gravity = Gravity.TOP or Gravity.START,
        ).apply {
            // 非应用窗口默认按系统栏和挖孔收缩窗口框，光带的圆角就会整体下移、和物理屏幕角错开；
            // 不避让任何 inset，窗口才和整块屏幕重合，Display 报的圆角半径才对得上
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "ZetaEdgeGlow"
        }
        return OverlayWindow(service, displayId, params) {
            EdgeGlow(
                phase = phase.value,
                visible = visible.value,
                hidden = hiddenForCapture.value,
                cornerRadius = cornerRadius.floatValue,
            )
        }.also {
            // 纯装饰，读屏不要停在上面
            it.view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }
}
