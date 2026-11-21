package com.dzshzx.zeta.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 等这个进程结束，最多 [timeoutMs] 毫秒；结束了是 true，到点还没结束是 false（进程留着，由调用方结束）。 代替 `Process.waitFor(超时)`：Android
 * 上它是 java.lang.Process 的默认实现，每 100 毫秒才看一次结束没有， 每条命令平均白等约 50 毫秒（测试版的 LatencyBench
 * 量出来的）。这里另起线程跑不带超时的 waitFor，进程一结束就返回。
 */
fun Process.exitsWithin(timeoutMs: Long): Boolean {
    val exited = CountDownLatch(1)
    thread(name = "zeta-process-wait", isDaemon = true) {
        runCatching { waitFor() }
        exited.countDown()
    }
    return exited.await(timeoutMs, TimeUnit.MILLISECONDS)
}
