package com.dzshzx.zeta.core

import android.os.Handler
import android.os.Looper

/**
 * 手机上的界面线程：Android 的主线程。登记在 app 的 META-INF/services 里，由 [MainLoop.current] 取。 撤任务要用发它的同一个
 * Handler，所以留着一个；主线程的 Looper 换了（Robolectric 的测试之间）才换。
 */
class AndroidMainLoop : MainLoop {
    @Volatile private var cached: Handler? = null

    private val handler: Handler
        get() {
            val looper = Looper.getMainLooper()
            val current = cached
            if (current != null && current.looper === looper) return current
            return Handler(looper).also { cached = it }
        }

    override fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    override fun post(task: Runnable) {
        handler.post(task)
    }

    override fun postDelayed(task: Runnable, delayMs: Long) {
        handler.postDelayed(task, delayMs)
    }

    override fun removeCallbacks(task: Runnable) = handler.removeCallbacks(task)
}
