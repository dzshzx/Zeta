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
 * zeta_host.sh 在交接里的两处：接着旧会话时，会话正被 Zeta 之外的 Claude Code（比如终端里的）占着就不起进程，报 zeta_exit 带
 * "reason":"held_elsewhere"；zeta-helper 让出会话时留了 released 记号的进程退出时，zeta_exit 带 "reason":"released"。
 * 真的 zeta_host.sh 跑在临时 HOME 上，claude 换成替身。
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

    /** 跑 zeta_host.sh start，返回它的标准输出（[HostTesting.run]：应用这头马上断开，起的进程照样在后台跑）。 */
    private fun start(key: String, vararg claudeArguments: String): String =
        HostTesting.run(
            home,
            command("start", key, home.path, "t", "-p", *claudeArguments),
            HostTesting.named(name),
        )

    private fun command(vararg arguments: String) = listOf("bash", host.path) + arguments

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

    /** 像 Claude Code 那样登记；[procStart] 是登记里的启动时刻，null 时不写（旧版）。 */
    private fun register(pid: Long, session: String, procStart: String? = null) {
        val start = procStart?.let { ",\"procStart\":\"$it\"" }.orEmpty()
        File(home, ".claude/sessions/$pid.json").apply {
            parentFile!!.mkdirs()
            writeText(
                """{"pid":$pid,"sessionId":"$session"$start,"kind":"interactive","status":"idle"}""",
            )
        }
    }

    private fun hasProcess(key: String) = File(hostDir(key), "pid").exists()

    @Test
    fun resumingASessionHeldInATerminalDoesNotStartClaude() {
        terminalHolding(SESSION)
        val out = start("k1", "--resume", SESSION)
        assertTrue(
            out,
            out.contains("\"type\":\"zeta_exit\"") && out.contains("\"reason\":\"held_elsewhere\""),
        )
        assertFalse("不该建托管目录", hostDir("k1").exists())
    }

    @Test
    fun otherSessionsAndBranchesStillStart() {
        terminalHolding(SESSION)
        start("k1", "--resume", OTHER)
        assertTrue(hasProcess("k1"))
        // 分支出来的是新会话，原会话被占着也不要紧
        start("k2", "--resume", SESSION, "--fork-session")
        assertTrue(hasProcess("k2"))
    }

    @Test
    fun aSessionHeldByThisPackagesOwnProcessIsNotElsewhere() {
        start("k1")
        val pid = File(hostDir("k1"), "pid").readText().trim().toLong()
        register(pid, SESSION)
        // 登记还在但进程没了的也不算
        register(999_999, SESSION)
        start("k2", "--resume", SESSION)
        assertTrue(hasProcess("k2"))
    }

    /** 登记里的启动时刻和进程对不上（进程号被别的进程复用了）的不算，和 zeta-helper 同一口径。 */
    @Test
    fun aRegistrationWhosePidWasReusedIsNotElsewhere() {
        val pid = terminalHolding(OTHER)
        register(pid, SESSION, procStart = "1")
        start("k1", "--resume", SESSION)
        assertTrue(hasProcess("k1"))
    }

    /** 接着的一方在进程退出时报 zeta_exit：有 released 记号的带 "reason":"released"，没有的是 null。 */
    @Test
    fun exitReportSaysWhetherTheSessionWasReleased() {
        assertEquals(true, exitAfter(released = true)?.contains("\"reason\":\"released\""))
        assertEquals(true, exitAfter(released = false)?.contains("\"reason\":null"))
    }

    private fun exitAfter(released: Boolean): String? {
        val key = "k-$released"
        start(key)
        val dir = hostDir(key)
        val pid = File(dir, "pid").readText().trim().toInt()
        if (released) File(dir, "released").writeText("")
        val attach =
            HostTesting.builder(home, command("attach", key, "1"), HostTesting.named(name))
                .start()
                .also {
                    started += it
                }
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
