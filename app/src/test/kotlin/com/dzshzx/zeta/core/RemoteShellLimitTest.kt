package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RemoteShell.limited]：命令在那边到点结束，连同它派生的进程；没到点的命令照常返回退出码和输出。 */
class RemoteShellLimitTest {
    private fun bash(line: String): Process =
        ProcessBuilder("bash", "-c", line).redirectErrorStream(true).start()

    @Test
    fun theRemoteSideEndsTheCommandAndItsChildrenAtTheLimit() {
        val dir = Files.createTempDirectory("limit").toFile()
        val pids = File(dir, "pids")
        // 自己和一个后台子进程都记下 pid，然后不读输入一直等：本机关掉连接也结束不了它们。
        val command = "sleep 300 & echo \$! >> ${pids.path}; echo \$\$ >> ${pids.path}; wait"
        val p = bash(RemoteShell.limited(command, timeoutMs = 200))
        assertTrue("限时没有生效", p.waitFor(20, TimeUnit.SECONDS))
        val recorded = pids.readLines().filter { it.isNotBlank() }.map { it.trim().toLong() }
        assertEquals(2, recorded.size)
        for (pid in recorded) {
            val handle = ProcessHandle.of(pid)
            if (handle.isPresent) handle.get().onExit().get(20, TimeUnit.SECONDS)
            assertFalse("进程 $pid 还活着", ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        }
    }

    @Test
    fun aCommandWithinTheLimitKeepsItsExitCodeAndOutput() {
        val p = bash(RemoteShell.limited("printf '%s' \"it's\"; exit 3", timeoutMs = 30_000))
        val out = p.inputStream.readBytes().decodeToString()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS))
        assertEquals(3, p.exitValue())
        assertEquals("it's", out)
    }
}
