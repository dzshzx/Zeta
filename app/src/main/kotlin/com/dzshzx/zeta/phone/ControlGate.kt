package com.dzshzx.zeta.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 暂停、接管的闸门（一个对话一份，[PhoneTools] 持有）：用户在胶囊上点了暂停，或者接管了后台屏幕， Claude 接下来的手机工具调用先在 [await]
 * 前等着，直到继续、交还，或者被作废（停止这一轮、用户补充了要求）。
 *
 * 工具线程在 [await] 里等，主线程在 [set] 里开关。它用自己的锁，不用 PhoneTools 的屏幕锁： 等着的时候用户在后台屏幕上的手势要拿那把锁。界面看的
 * [paused]、[userControlling] 只在主线程上改（经 [set]）。
 */
internal class ControlGate(private val timeoutMs: Long = TimeUnit.MINUTES.toMillis(30)) {
    /** 用户点了暂停：之后的手机工具调用先等着。 */
    var paused by mutableStateOf(false)
        private set

    /** 用户在胶囊的展开画面上接管了后台屏幕：Claude 的手机工具调用先等着，直到交还。 */
    var userControlling by mutableStateOf(false)
        private set

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var closed = false

    /** 每作废一次加一：在闸门前等着的调用发现变了就不再执行，把 [cancelReason] 交回去。 */
    private var stopGeneration = 0
    private var cancelReason = ""

    /** 正暂停或接管着（工具线程上看）：等着的 wait 据此不再等下去。 */
    val held: Boolean
        get() = lock.withLock { closed }

    /** 在主线程上调用。[cancel]：在闸门前等着的调用作废，这是交给 Claude 的原因。 */
    fun set(pause: Boolean, control: Boolean, cancel: String? = null) {
        paused = pause
        userControlling = control
        lock.withLock {
            closed = pause || control
            if (cancel != null) {
                stopGeneration++
                cancelReason = cancel
            }
            changed.signalAll()
        }
    }

    /** 暂停或接管时在这里等（工具线程上，不持屏幕锁）。返回不该执行的原因，可以执行时返回 null。 */
    fun await(): String? = lock.withLock {
        if (!closed) return null
        val generation = stopGeneration
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (closed && stopGeneration == generation) {
            if (left <= 0) {
                return "用户暂停了手机操作，${TimeUnit.MILLISECONDS.toMinutes(timeoutMs)} 分钟内没有继续，这次操作没有执行。" +
                    "先别再操作手机，在回复里告诉用户做到哪一步了、还差什么"
            }
            left = changed.awaitNanos(left)
        }
        if (stopGeneration != generation) cancelReason else null
    }
}
