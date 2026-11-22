package com.dzshzx.zeta.core

import java.util.ServiceLoader
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 界面线程（Compose 的状态只在它上面改），即 Android 的主线程。实现在 META-INF/services/com.dzshzx.zeta.core.MainLoop
 * 里登记，第一次用时经 [ServiceLoader] 取，不用等哪个入口先初始化。
 */
interface MainLoop {
    fun isMainThread(): Boolean

    fun post(task: Runnable)

    fun postDelayed(task: Runnable, delayMs: Long)

    /** 撤下还没执行的 [task]（按对象认，和 post 时传的是同一个才撤得掉）。 */
    fun removeCallbacks(task: Runnable)

    companion object {
        val current: MainLoop by lazy {
            platformService<MainLoop>()
        }
    }
}

/** 往界面线程上排任务，用法和 Android 的 Handler(Looper.getMainLooper()) 一样。 */
class MainHandler {
    fun post(task: Runnable) = MainLoop.current.post(task)

    fun postDelayed(task: Runnable, delayMs: Long) = MainLoop.current.postDelayed(task, delayMs)

    fun removeCallbacks(task: Runnable) = MainLoop.current.removeCallbacks(task)
}

/** [onMain] 等主线程超时：还没开始执行的已经撤下，不会过后再执行。 */
class MainThreadTimeout(timeoutMs: Long) : RuntimeException("Zeta 的界面线程 ${timeoutMs / 1000} 秒内没有响应")

/**
 * 在主线程上执行 [block] 并等它的结果，给工具线程这类后台线程用；本来就在主线程上时直接执行。 [timeoutMs] 内没做完就抛出
 * [MainThreadTimeout]，排着队还没开始的撤下不做（已经在执行的做完，结果不要了）。 [block] 自己抛出的异常原样抛出，不包一层 ExecutionException。
 */
fun <T> onMain(timeoutMs: Long = 15_000, block: () -> T): T {
    val loop = MainLoop.current
    if (loop.isMainThread()) return block()
    val task = FutureTask(Callable { block() })
    loop.post(task)
    return try {
        task.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        loop.removeCallbacks(task)
        task.cancel(false)
        throw MainThreadTimeout(timeoutMs)
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    }
}
