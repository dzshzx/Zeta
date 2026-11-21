package com.dzshzx.zeta.core

import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Robolectric 里测试跑在主线程上，主线程的消息要手动放（idle）：用它摆出“主线程忙着、没空执行”的情形。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class MainThreadTest {
    /** 在后台线程上调 [call]，同时在主线程上一直放消息，直到它返回；交回它的结果或抛出的异常。 */
    private fun <T> fromWorker(call: () -> T): Result<T> {
        val result = AtomicReference<Result<T>>()
        val worker = thread { result.set(runCatching(call)) }
        while (worker.isAlive) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return result.get()
    }

    @Test
    fun runsOnTheMainThreadAndReturnsTheValue() {
        val result = fromWorker { onMain { Looper.myLooper() == Looper.getMainLooper() } }
        assertEquals(true, result.getOrThrow())
        // 本来就在主线程上：直接执行
        assertEquals(3, onMain { 1 + 2 })
    }

    @Test
    fun theBlocksOwnExceptionComesOutUnwrapped() {
        val failure = fromWorker {
            onMain { throw IllegalStateException("页面已经关掉了") }
        }
            .exceptionOrNull()
        assertTrue(
            failure.toString(),
            failure is IllegalStateException && failure.message == "页面已经关掉了",
        )
    }

    @Test
    fun aTimedOutTaskIsWithdrawnBeforeItRuns() {
        val ran = AtomicBoolean(false)
        // 主线程不放消息：任务一直排着，到时限
        val failure = AtomicReference<Throwable>()
        val worker = thread {
            failure.set(runCatching { onMain(50) { ran.set(true) } }.exceptionOrNull())
        }
        worker.join(5_000)
        assertTrue(failure.get().toString(), failure.get() is MainThreadTimeout)
        // 主线程之后有空了，撤下的任务也不再执行
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ran.get())
    }
}
