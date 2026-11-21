package com.dzshzx.zeta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProcessWaitTest {
    @Test
    fun returnsOnceTheProcessExits() {
        val process = ProcessBuilder("sh", "-c", "exit 3").start()
        assertTrue(process.exitsWithin(5_000))
        assertEquals(3, process.exitValue())
    }

    @Test
    fun givesUpAtTheDeadlineAndLeavesTheProcessRunning() {
        val process = ProcessBuilder("sleep", "30").start()
        try {
            val start = System.nanoTime()
            assertFalse(process.exitsWithin(200))
            val waited = (System.nanoTime() - start) / 1_000_000
            assertTrue("等了 $waited 毫秒", waited in 200..5_000)
            assertTrue(process.isAlive)
        } finally {
            process.destroy()
        }
    }

    /** 应用代码等进程一律用 exitsWithin：Process.waitFor(超时) 在 Android 上每 100 毫秒才看一次结束没有，悄悄拖慢每条命令。 */
    @Test
    fun appCodeDoesNotUseTimedWaitFor() {
        val timed = Regex("""\.waitFor\([^)]""")
        val offenders = File("src/main/kotlin").walk()
            .filter { it.extension == "kt" && it.name != "ProcessWait.kt" }
            .filter { f -> f.readLines().any { timed.containsMatchIn(it) } }
            .map { it.name }
            .toList()
        assertEquals("改用 Process.exitsWithin", emptyList<String>(), offenders)
    }
}
