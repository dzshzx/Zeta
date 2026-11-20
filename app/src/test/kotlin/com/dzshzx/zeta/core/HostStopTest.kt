package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * zeta_host.sh stop 怎么结束会话：往输入管道写控制请求 end_session，由 Claude Code 自己结束（等价于终端里的 /exit，
 * 真 Claude Code 的行为由 ClaudeCodeContractTest 钉住）；不退才 SIGTERM、SIGKILL。等待和清理在单独的会话里做，
 * stop 写完请求就返回，Zeta 随后被杀也不影响。真的 zeta_host.sh 跑在临时 HOME 上，claude 换成替身。
 */
class HostStopTest {
    private lateinit var home: File
    private val host = File("src/main/assets/zeta_host.sh").absoluteFile
    private val name = AppVariant(AppVariant.RELEASE_PACKAGE + ".dev").linuxName
    private val key = "k-stop"
    private val hostDir get() = File(home, ".cache/$name/hosts/$key")
    private val received get() = File(home, "received.jsonl")

    @Before
    fun setUp() {
        assumeTrue("要 Linux 的 /proc 和 bash", File("/proc/self/cmdline").exists())
        home = Files.createTempDirectory("zeta-stop").toFile().canonicalFile
    }

    @After
    fun tearDown() {
        if (!::home.isInitialized) return
        runCatching { File(hostDir, "pid").readText().trim().toInt() }.getOrNull()?.let { pid ->
            ProcessBuilder("kill", "-9", "$pid").start().waitFor(5, TimeUnit.SECONDS)
        }
        home.deleteRecursively()
    }

    /** claude 的替身：把收到的每行记进 received.jsonl；[script] 是每行之后做什么（bash，行在 $line 里）。 */
    private fun standIn(script: String, setup: String = "") = File(home, "bin/claude").apply {
        parentFile!!.mkdirs()
        writeText("#!/bin/bash\n$setup\nwhile read -r line; do echo \"\$line\" >> ${received.path}; $script; done\n")
        setExecutable(true)
    }

    /** 跑 zeta_host.sh，标准输入立即关掉，返回用了多少毫秒。 */
    private fun run(vararg arguments: String): Long {
        val started = System.currentTimeMillis()
        val builder = ProcessBuilder(listOf("bash", host.path) + arguments).directory(home).redirectErrorStream(true)
        builder.environment().apply {
            clear()
            put("HOME", home.path)
            put("PATH", "${home.path}/bin:" + System.getenv("PATH"))
            put("LANG", "C.UTF-8")
            put(AppVariant.LINUX_NAME_ENV, name)
        }
        val process = builder.start()
        process.outputStream.close()
        process.inputStream.bufferedReader().readText()
        assertTrue("命令超时：${arguments.toList()}", process.waitFor(30, TimeUnit.SECONDS))
        return System.currentTimeMillis() - started
    }

    private fun start(): Int {
        run("start", key, home.path, "t", "-p", "--input-format", "stream-json", "--output-format", "stream-json")
        return File(hostDir, "pid").readText().trim().toInt()
    }

    private fun alive(pid: Int) = File("/proc/$pid").exists()

    private fun waitUntil(seconds: Int, condition: () -> Boolean): Boolean {
        repeat(seconds * 10) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    /** 替身收到 end_session 就自己退出，SIGTERM 它不理：stop 之后很快就没了，说明走的是 end_session 而不是信号。 */
    @Test
    fun stopAsksClaudeCodeToEndTheSession() {
        standIn("""case ${'$'}line in *'"subtype":"end_session"'*) exit 0 ;; esac""", setup = "trap '' TERM")
        val ending = start()

        val took = run("stop", key)
        assertTrue("stop 要立即返回（${took} 毫秒）", took < 3_000)
        assertTrue("替身收到 end_session 后没退出", waitUntil(5) { !alive(ending) })
        assertTrue("托管目录没删", waitUntil(5) { !hostDir.exists() })
        assertEquals(
            """{"type":"control_request","request_id":"zeta-end","request":{"subtype":"end_session"}}""",
            received.readLines().last(),
        )
    }

    /** 不理 end_session 的（卡住了）：stop 照样立即返回，单独的会话里等满 10 秒后 SIGTERM 收掉，托管目录删掉。 */
    @Test
    fun stuckProcessIsTerminatedInTheBackground() {
        standIn(":")
        val pid = start()
        val took = run("stop", key)
        assertTrue("stop 要立即返回（${took} 毫秒）", took < 3_000)
        assertTrue("还没到点就收掉了", alive(pid))
        assertFalse("还没到点就删了目录", !hostDir.exists())
        assertTrue("卡住的进程没被收掉", waitUntil(20) { !alive(pid) })
        assertTrue("托管目录没删", waitUntil(5) { !hostDir.exists() })
    }
}
