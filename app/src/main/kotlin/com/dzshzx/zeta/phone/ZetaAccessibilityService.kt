package com.dzshzx.zeta.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Zeta 的无障碍服务：给 [PhoneTools] 读屏幕上的控件、做手势、截图。
 * 它自己不监听什么，也不主动做任何事，只在 Claude 调用手机工具时被用到。
 */
class ZetaAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        executor.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** 某块屏幕上各个窗口的根节点，最上层的在前。 */
    fun roots(displayId: Int): List<AccessibilityNodeInfo> {
        val windows = runCatching { windowsOnAllDisplays.get(displayId) }.getOrNull().orEmpty()
        return windows.sortedByDescending { it.layer }.mapNotNull { runCatching { it.root }.getOrNull() }
    }

    /** 沿 [path] 做一次手势，等它做完。返回是否完成。 */
    fun gesture(displayId: Int, path: Path, durationMs: Long): Boolean {
        val description = GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
            .build()
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

    /** 截一张屏。系统限制两次截图的间隔，太快会失败一次，所以失败后稍等再试。 */
    fun screenshot(displayId: Int): Bitmap? {
        repeat(3) { attempt ->
            val done = CountDownLatch(1)
            var bitmap: Bitmap? = null
            runCatching {
                takeScreenshot(displayId, executor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        runCatching {
                            val buffer = result.hardwareBuffer
                            bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                            buffer.close()
                        }
                        done.countDown()
                    }

                    override fun onFailure(errorCode: Int) = done.countDown()
                })
            }.onFailure { done.countDown() }
            done.await(5, TimeUnit.SECONDS)
            bitmap?.let { return it }
            if (attempt < 2) Thread.sleep(450)
        }
        return null
    }

    companion object {
        /** 服务连上时就是它自己；没开或被系统断开时是 null。 */
        @Volatile
        var instance: ZetaAccessibilityService? = null
            private set
    }
}
