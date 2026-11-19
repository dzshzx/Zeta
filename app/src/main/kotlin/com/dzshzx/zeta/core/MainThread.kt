package com.dzshzx.zeta.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** [onMain] 等主线程超时：还没开始执行的已经撤下，不会过后再执行。 */
class MainThreadTimeout(timeoutMs: Long) : RuntimeException("Zeta 的界面线程 ${timeoutMs / 1000} 秒内没有响应")

/**
 * 在主线程上执行 [block] 并等它的结果，给工具线程这类后台线程用；本来就在主线程上时直接执行。
 * [timeoutMs] 内没做完就抛出 [MainThreadTimeout]，排着队还没开始的撤下不做（已经在执行的做完，结果不要了）。
 * [block] 自己抛出的异常原样抛出，不包一层 ExecutionException。
 */
fun <T> onMain(timeoutMs: Long = 15_000, block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val task = FutureTask(Callable { block() })
    val main = Handler(Looper.getMainLooper())
    main.post(task)
    return try {
        task.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        main.removeCallbacks(task)
        task.cancel(false)
        throw MainThreadTimeout(timeoutMs)
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    }
}
