package com.dzshzx.zeta.phone

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 暂停、接管的闸门：工具线程在 await 里等，另一个线程开关；作废时交回原因，等太久交回超时的说明。 */
class ControlGateTest {
    /** 在另一个线程里过闸门，返回它的结果（还在等就是没完成的 future）。 */
    private fun ControlGate.awaitAsync(): CompletableFuture<String?> =
        CompletableFuture.supplyAsync {
            await()
        }

    private fun CompletableFuture<String?>.stillWaiting(): Boolean =
        try {
            get(200, TimeUnit.MILLISECONDS)
            false
        } catch (_: TimeoutException) {
            true
        }

    @Test
    fun openGateLetsCallsThrough() {
        val gate = ControlGate()
        assertNull(gate.await())
        assertFalse(gate.held)
        assertFalse(gate.paused || gate.userControlling)
    }

    @Test
    fun pausedCallWaitsUntilResumed() {
        val gate = ControlGate()
        gate.set(pause = true, control = false)
        assertTrue(gate.paused && gate.held)
        val call = gate.awaitAsync()
        assertTrue(call.stillWaiting())
        gate.set(pause = false, control = false)
        assertNull(call.get(2, TimeUnit.SECONDS))
        assertFalse(gate.held)
    }

    @Test
    fun takingOverAlsoHoldsAndHandingBackReleases() {
        val gate = ControlGate()
        gate.set(pause = false, control = true)
        assertTrue(gate.userControlling && gate.held)
        val call = gate.awaitAsync()
        assertTrue(call.stillWaiting())
        // 接管改成暂停（比如屏没了）：照样等着
        gate.set(pause = true, control = false)
        assertTrue(call.stillWaiting())
        gate.set(pause = false, control = false)
        assertNull(call.get(2, TimeUnit.SECONDS))
    }

    @Test
    fun cancellingHandsBackTheReason() {
        val gate = ControlGate()
        gate.set(pause = true, control = false)
        val call = gate.awaitAsync()
        assertTrue(call.stillWaiting())
        // 补充了要求：作废，但仍暂停着
        gate.set(pause = true, control = false, cancel = "补充了")
        assertEquals("补充了", call.get(2, TimeUnit.SECONDS))
        assertTrue(gate.held)
        // 之后来的调用不受前一次作废影响，照样等
        val next = gate.awaitAsync()
        assertTrue(next.stillWaiting())
        gate.set(pause = false, control = false, cancel = "停了")
        assertEquals("停了", next.get(2, TimeUnit.SECONDS))
    }

    @Test
    fun cancellingWhileOpenDoesNotStopLaterCalls() {
        val gate = ControlGate()
        gate.set(pause = false, control = false, cancel = "停了")
        assertNull(gate.await())
    }

    @Test
    fun waitingTooLongGivesUp() {
        val gate = ControlGate(timeoutMs = 100)
        gate.set(pause = true, control = false)
        val reason = gate.awaitAsync().get(2, TimeUnit.SECONDS)
        assertTrue(reason, reason!!.startsWith("用户暂停了手机操作") && "没有执行" in reason)
    }
}
