package com.dzshzx.zeta.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 第 [attempt] 次重连之前等多久：1、2、4、8、15 秒，之后十分钟里每 30 秒一次，再往后每分钟一次。 没网时 ssh 立刻失败，有网连不上时最多等
 * ConnectTimeout，所以一直重试也花不了多少。
 */
fun reconnectDelay(attempt: Int): Long =
    when {
        attempt <= 0 -> 0L
        attempt <= 4 -> 1_000L shl (attempt - 1)
        attempt == 5 -> 15_000L
        attempt <= 24 -> 30_000L
        else -> 60_000L
    }

/** 手机的网络变了（重新有网、换了网）：等着重连的连接提前醒来，不等满退避的时间。 */
object NetworkWake {
    private val lock = Object()
    private var generation = 0L

    fun signal() =
        synchronized(lock) {
            generation++
            lock.notifyAll()
        }

    /** 等 [ms] 毫秒，网络中途变了就提前返回。 */
    fun sleep(ms: Long) =
        synchronized(lock) {
            val start = generation
            val deadline = System.currentTimeMillis() + ms
            while (generation == start) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return@synchronized
                lock.wait(left)
            }
        }
}

/** 从 [input] 一行行读到结束，每读到完整的一行（以换行结尾）交给 [onLine]，不带换行。 连接断在一行中间时，剩下的半行不交出去（也就不算进行数），重连后从这一行读起。 */
fun readLines(input: InputStream, onLine: (String) -> Unit) {
    val buffer = ByteArray(64 * 1024)
    val pending = ByteArrayOutputStream()
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        var from = 0
        for (i in 0 until n) {
            if (buffer[i] != '\n'.code.toByte()) continue
            pending.write(buffer, from, i - from)
            onLine(pending.toString(Charsets.UTF_8.name()))
            pending.reset()
            from = i + 1
        }
        if (from < n) pending.write(buffer, from, n - from)
    }
}
