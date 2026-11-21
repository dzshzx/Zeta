package com.dzshzx.zeta.phone.browser

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 浏览器在屏幕外时住的窗口：Zeta 自己的私有虚拟屏（画面交给一个只丢帧的 ImageReader，谁也看不到）上的一个 Presentation。
 *
 * WebView 不能干脆不挂窗口：挂到浏览器页上再摘下来以后，Chromium 就不再出新帧，截图停在旧画面上；Zeta 在后台时同理。 放在这个窗口里，它一直有窗口、有帧，和 Zeta
 * 在前台还是后台无关。窗口不可触摸、不抢焦点，不影响主屏的输入。 只在主线程上用。
 */
internal class OffscreenWindow
private constructor(
    private val reader: ImageReader,
    private val thread: HandlerThread,
    private val display: VirtualDisplay,
    private val presentation: Presentation,
    /** 放 WebView 的容器。 */
    val container: FrameLayout,
) {
    /** 容器所在窗口的 Context（WebView 挂在这里时用它当 base context）。 */
    val context: Context
        get() = presentation.context

    fun release() {
        runCatching { presentation.dismiss() }
        runCatching { display.release() }
        runCatching { reader.close() }
        thread.quitSafely()
    }

    companion object {
        /** 建一块 [width]×[height]、[dpi] 的私有虚拟屏并在上面显示一个空窗口。建不成返回 null（调用方退回不挂窗口的做法）。 */
        fun create(context: Context, width: Int, height: Int, dpi: Int): OffscreenWindow? {
            val thread = HandlerThread("zeta-browser-frames").apply { start() }
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            // 帧不用：来了就丢，免得缓冲区占满卡住合成
            reader.setOnImageAvailableListener(
                { r -> runCatching { r.acquireLatestImage()?.close() } },
                Handler(thread.looper),
            )
            var display: VirtualDisplay? = null
            return try {
                // 标志为 0：私有虚拟屏，只有 Zeta 自己能往上放窗口，不需要权限
                display =
                    context
                        .getSystemService(DisplayManager::class.java)
                        .createVirtualDisplay("zeta-browser", width, height, dpi, reader.surface, 0)
                        ?: throw IllegalStateException("createVirtualDisplay returned null")
                val presentation = Presentation(context.applicationContext, display.display)
                presentation.window?.addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                )
                val container = FrameLayout(presentation.context)
                presentation.setContentView(container)
                presentation.show()
                OffscreenWindow(reader, thread, display, presentation, container)
            } catch (_: Exception) {
                runCatching { display?.release() }
                reader.close()
                thread.quitSafely()
                null
            }
        }
    }
}
