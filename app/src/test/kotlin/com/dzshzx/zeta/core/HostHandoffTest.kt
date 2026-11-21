package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.HostTesting.alive
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
 * zeta_host.sh 在交接里的两处：接着旧会话时，会话正被 Zeta 之外的 Claude Code（比如终端里的）占着就不起进程（退出码 96）； zeta-helper release
 * 留了 released 记号的进程退出时，zeta_exit 带上 "released":true。真的 zeta_host.sh 跑在临时 HOME 上，claude 换成替身。
 */
class HostHandoffTest {
    private lateinit var home: File
    private val host = File("src/main/assets/zeta_host.sh").absoluteFile
    private val name = AppVariant(AppVariant.RELEASE_PACKAGE + ".dev").linuxName
    private val started = mutableListOf<Process>()

    @Before
    fun setUp() {
        assumeTrue("要 Linux 的 /proc 和 bash", File("/proc/self/cmdline").exists())
        home = Files.createTempDirectory("zeta-handoff").toFile().canonicalFile
        HostTesting.writeClaudeStandIn(home)
    }

    @After
    fun tearDown() {
        if (!::home.isInitialized) return
        started.forEach { it.destroyForcibly() }
        File(home, ".cache/$name/hosts").listFiles().orEmpty().forEach { dir ->
            runCatching { File(dir, "pid").readText().trim() }
                .getOrNull()
                ?.let { ProcessBuilder("kill", "-9", it).start().waitFor(5, TimeUnit.SECONDS) }
        }
        home.deleteRecursively()
    }

    private fun hostDir(key: String) = File(home, ".cache/$name/hosts/$key")

    /** 跑 zeta_host.sh start，返回退出码（标准输入立即关掉：应用这头马上断开，起的进程照样在后台跑）。 */
    private fun start(key: String, vararg claudeArguments: String): Int {
        val process =
            builder("start", key, home.path, "t", "-p", *claudeArguments)
                .redirectOutput(File("/dev/null"))
                .start()
        process.outputStream.close()
        assertTrue("start 超时", process.waitFor(60, TimeUnit.SECONDS))
        return process.exitValue()
    }

    private fun builder(vararg arguments: String): ProcessBuilder =
        ProcessBuilder(listOf("bash", host.path) + arguments)
            .directory(home)
            .redirectError(File("/dev/null"))
            .apply {
                environment().apply {
                    clear()
                    put("HOME", home.path)
                    put("PATH", "${home.path}/bin:" + System.getenv("PATH"))
                    put("LANG", "C.UTF-8")
                    put("ZETA_IDLE_STEP", "1")
                    putAll(HostTesting.named(name))
                }
            }

    /**
     * 终端里开着的 Claude Code：一个命令行里带 claude 的进程，像 Claude Code 那样在 ~/.claude/sessions 登记会话 [session]。
     */
    private fun terminalHolding(session: String): Long {
        val script =
            File(home, "bin/claude-terminal").apply {
                writeText("#!/bin/bash\nwhile :; do sleep 0.2; done\n")
                setExecutable(true)
            }
        val process = ProcessBuilder(script.path).start().also { started += it }
        register(process.pid(), session)
        return process.pid()
    }

    private fun register(pid: Long, session: String) {
        File(home, ".claude/sessions/$pid.json").apply {
            parentFile!!.mkdirs()
            writeText(
                """{"pid":$pid,"sessionId":"$session","kind":"interactive","status":"idle"}""",
            )
        }
    }

    @Test
    fun resumingASessionHeldInATerminalDoesNotStartClaude() {
        terminalHolding(SESSION)
        assertEquals(96, start("k1", "--resume", SESSION))
        assertFalse("不该建托管目录", hostDir("k1").exists())
    }

    @Test
    fun otherSessionsAndBranchesStillStart() {
        terminalHolding(SESSION)
        assertEquals(0, start("k1", "--resume", OTHER))
        // 分支出来的是新会话，原会话被占着也不要紧
        assertEquals(0, start("k2", "--resume", SESSION, "--fork-session"))
        assertTrue(File(hostDir("k2"), "pid").exists())
    }

    @Test
    fun aSessionHeldByThisPackagesOwnProcessIsNotElsewhere() {
        assertEquals(0, start("k1"))
        val pid = File(hostDir("k1"), "pid").readText().trim().toLong()
        register(pid, SESSION)
        // 登记还在但进程没了的也不算
        register(999_999, SESSION)
        assertEquals(0, start("k2", "--resume", SESSION))
    }

    /** 接着的一方在进程退出时报 zeta_exit：有 released 记号的带 "released":true，没有的是 false。 */
    @Test
    fun exitReportSaysWhetherTheSessionWasReleased() {
        assertEquals(true, exitAfter(released = true)?.contains("\"released\":true"))
        assertEquals(true, exitAfter(released = false)?.contains("\"released\":false"))
    }

    private fun exitAfter(released: Boolean): String? {
        val key = "k-$released"
        assertEquals(0, start(key))
        val dir = hostDir(key)
        val pid = File(dir, "pid").readText().trim().toInt()
        if (released) File(dir, "released").writeText("")
        val attach = builder("attach", key, "1").start().also { started += it }
        val lines = attach.inputStream.bufferedReader()
        assertTrue(lines.readLine().orEmpty().contains("zeta_attached"))
        attach.outputStream.apply {
            write(
                "{\"type\":\"control_request\",\"request\":{\"subtype\":\"end_session\"}}\n"
                    .toByteArray(),
            )
            flush()
        }
        assertTrue("替身没退出", waitUntil(10) { !alive(pid) })
        return generateSequence { lines.readLine() }.firstOrNull { it.contains("zeta_exit") }
    }

    private companion object {
        const val SESSION = "aaaaaaaa-1111-4222-8333-444444444444"
        const val OTHER = "bbbbbbbb-1111-4222-8333-444444444444"
    }
}
