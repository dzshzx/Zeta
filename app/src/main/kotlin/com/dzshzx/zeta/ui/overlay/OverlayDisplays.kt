package com.dzshzx.zeta.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.RoundedCorner
import android.view.WindowManager

/**
 * 悬浮层窗口用的、按屏幕分的 Context。0 号屏直接用无障碍服务本身；别的屏（调试时把“主屏”指到测试屏上）要用 WindowContext 才能加无障碍浮层。
 *
 * 每个 WindowContext 在系统里登记一个窗口令牌，要等它被垃圾回收才注销（注销的 release 是隐藏接口，调不到）。
 * 以前每个悬浮窗口各建一个，测试屏上的令牌就越积越多；这里每块屏只建一个，胶囊、边缘光、全屏过渡、触摸指示共用，
 * 屏没了（[DisplayManager.DisplayListener.onDisplayRemoved]）或服务断开时丢掉，令牌随屏一起被系统收走。
 *
 * 全部在主线程上用。
 */
internal object OverlayDisplays {
    private val main = Handler(Looper.getMainLooper())
    private val contexts = HashMap<Int, Context>()
    private var owner: AccessibilityService? = null
    private var displayManager: DisplayManager? = null

    private val listener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) {
                contexts.remove(displayId)
                if (contexts.isEmpty()) stopListening()
            }
        }

    /** [displayId] 上挂悬浮窗口用的 Context；屏不在了返回 null（不退到真屏上显示）。 */
    fun context(service: AccessibilityService, displayId: Int): Context? {
        if (displayId == Display.DEFAULT_DISPLAY) return service
        if (owner !== service) release()
        contexts[displayId]?.let {
            return it
        }
        val manager = service.getSystemService(DisplayManager::class.java) ?: return null
        val display = manager.getDisplay(displayId) ?: return null
        val context =
            runCatching {
                service
                    .createDisplayContext(display)
                    .createWindowContext(
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        null,
                    )
            }
                .getOrNull() ?: return null
        if (contexts.isEmpty()) {
            manager.registerDisplayListener(listener, main)
            displayManager = manager
        }
        owner = service
        contexts[displayId] = context
        return context
    }

    /**
     * [displayId] 那块屏的物理圆角半径（像素）：四角取最大的一个（主流机型四角一样），不让任何一角露出直边。 部分 ROM 不上报时按常见大圆角估一个保守值（照 Eta 取
     * 28dp）。边缘流光的圆角、「全屏」放大到整屏时的圆角都用它。
     */
    fun cornerRadius(service: AccessibilityService, displayId: Int): Float {
        val display = service.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
        val reported =
            listOf(
                    RoundedCorner.POSITION_TOP_LEFT,
                    RoundedCorner.POSITION_TOP_RIGHT,
                    RoundedCorner.POSITION_BOTTOM_RIGHT,
                    RoundedCorner.POSITION_BOTTOM_LEFT,
                )
                .maxOf { position -> display?.getRoundedCorner(position)?.radius ?: 0 }
        return if (reported > 0) reported.toFloat()
        else FALLBACK_CORNER_DP * service.resources.displayMetrics.density
    }

    private const val FALLBACK_CORNER_DP = 28f

    /** 服务断开：丢掉全部缓存。窗口要先移除。 */
    fun release() {
        contexts.clear()
        stopListening()
        owner = null
    }

    private fun stopListening() {
        displayManager?.unregisterDisplayListener(listener)
        displayManager = null
    }
}
