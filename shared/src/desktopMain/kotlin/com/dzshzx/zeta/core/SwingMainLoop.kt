package com.dzshzx.zeta.core

import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * 桌面上的界面线程：Swing 的事件线程（Compose Desktop 在它上面重组）。登记在 shared 的 desktopMain 资源里。
 * 排任务不等事件线程（invokeLater），延时的先在计时线程上等够再交过去；撤下的打上记号，轮到时跳过。
 */
class SwingMainLoop : MainLoop {
    private class Entry {
        @Volatile var cancelled = false
    }

    /** 还没执行的任务，按任务对象记，[removeCallbacks] 照它撤。用它自己当锁。 */
    private val pending = IdentityHashMap<Runnable, MutableList<Entry>>()

    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "zeta-main-timer").apply { isDaemon = true }
    }

    override fun isMainThread(): Boolean = SwingUtilities.isEventDispatchThread()

    override fun post(task: Runnable) = postDelayed(task, 0)

    override fun postDelayed(task: Runnable, delayMs: Long) {
        val entry = Entry()
        synchronized(pending) { pending.getOrPut(task) { ArrayList() } += entry }
        val run = Runnable {
            synchronized(pending) {
                pending[task]?.let { list ->
                    list.remove(entry)
                    if (list.isEmpty()) pending.remove(task)
                }
            }
            if (!entry.cancelled) task.run()
        }
        if (delayMs <= 0) SwingUtilities.invokeLater(run)
        else timer.schedule({ SwingUtilities.invokeLater(run) }, delayMs, TimeUnit.MILLISECONDS)
    }

    override fun removeCallbacks(task: Runnable) {
        synchronized(pending) { pending.remove(task) }?.forEach { it.cancelled = true }
    }
}
