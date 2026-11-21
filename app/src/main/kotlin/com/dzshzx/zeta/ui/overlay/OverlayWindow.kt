package com.dzshzx.zeta.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.ui.app.ZetaTheme
import io.github.suqi8.coui.kmp.effect.LocalCOUIMaterialEffectEnabled

/**
 * 挂在 Zeta 无障碍服务上的一个悬浮窗口（TYPE_ACCESSIBILITY_OVERLAY），内容用 Compose 画。
 * 不用另要悬浮窗权限；系统截图（takeScreenshotOfWindow 一类）也能把它区分出来。
 *
 * 放在“主屏”上：平时是 0，调试时是设置里的 phoneDisplay（测试用的虚拟屏），这样测试时不会出现在真屏上。 服务不是
 * LifecycleOwner，这里自己带一份生命周期，Compose 才转得起来。全部在主线程上调用。
 */
internal class OverlayWindow(
    service: AccessibilityService,
    displayId: Int,
    /** 初始的窗口参数；type 会被改成 TYPE_ACCESSIBILITY_OVERLAY。 */
    val params: WindowManager.LayoutParams,
    content: @Composable () -> Unit,
) : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedState.savedStateRegistry

    /** 窗口所在屏幕的 Context（宽高、密度、圆角都按这块屏算）；同一块屏上的悬浮窗口共用一个，见 [OverlayDisplays]。 */
    val context: Context = OverlayDisplays.context(service, displayId) ?: service

    // 找不到指定的屏（调试用的虚拟屏没了）就不显示，免得退到真屏上
    private val available = displayId == 0 || context !== service
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var attached = false

    val view: ComposeView =
        ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayWindow)
            setViewTreeSavedStateRegistryOwner(this@OverlayWindow)
            setContent {
                val hub = ConversationHub.get(service.application)
                ZetaTheme(darkTheme = hub.settings.darkTheme) {
                    // 部分 ROM 给悬浮层分配软件 Canvas，COUI 17 的材质效果（边缘光、内阴影、触摸光斑）用 RuntimeShader，在这里关掉。
                    // 悬浮窗不在 COUI 的 Scaffold 里，也没有页面背景图：Overlay* 弹窗在这里不显示，要弹东西用 Window* 版本，不给毛玻璃
                    CompositionLocalProvider(LocalCOUIMaterialEffectEnabled provides false) {
                        content()
                    }
                }
            }
        }

    init {
        params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun show() {
        if (attached || !available) return
        runCatching { windowManager.addView(view, params) }.onSuccess { attached = true }
    }

    /** 改了 [params] 之后调用。 */
    fun update() {
        if (attached) runCatching { windowManager.updateViewLayout(view, params) }
    }

    fun hide() {
        if (!attached) return
        attached = false
        runCatching { windowManager.removeViewImmediate(view) }
    }

    /** 不再使用：移除窗口、结束生命周期。 */
    fun destroy() {
        hide()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    companion object {
        /** 悬浮层所在的“主屏”。 */
        fun mainDisplay(service: AccessibilityService): Int =
            ConversationHub.get(service.application).settings.phoneDisplay

        /** 常用的窗口参数：不抢焦点、不挡窗口外的触摸、透明。 */
        fun params(
            width: Int,
            height: Int,
            flags: Int = 0,
            gravity: Int = Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ) =
            WindowManager.LayoutParams(
                    width,
                    height,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        flags,
                    PixelFormat.TRANSLUCENT,
                )
                .apply { this.gravity = gravity }
    }
}
