package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.HostTesting.alive
import java.io.File
import java.nio.file.Files
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
    private val host = File("../helper/host/zeta_host.sh").absoluteFile
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
        removeTestDir(home)
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
     * [mark] 是它身上的 ZETA_HOST 记号（<包>/<标识>，冒充某个包托管的或继承来的），null 时没有（[HostTesting.marked]）。
     */
    private fun terminalHolding(session: String, mark: String? = null): Long {
        val script =
            File(home, "bin/claude-terminal").apply {
                writeText("#!/bin/bash\nwhile :; do sleep 0.2; done\n")
                setExecutable(true)
            }
        val process =
            HostTesting.marked(ProcessBuilder(script.path), mark).start().also { started += it }
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

    /** 本包自己的进程占着会话不算别处：start 接上它（zeta_attached 的 key 是它的），不另起。 */
    @Test
    fun aSessionHeldByThisPackagesOwnProcessIsJoinedNotElsewhere() {
        HostTesting.writeClaudeStandIn(home, prelude = HostTesting.registersAs(SESSION))
        start("k1")
        HostTesting.registeredPid(home)
        // 登记还在但进程没了的也不算
        register(999_999, SESSION)
        val out = start("k2", "--resume", SESSION)
        assertFalse(out, out.contains("held_elsewhere"))
        assertTrue(out, out.contains("\"key\":\"k1\""))
        assertFalse("不该另起进程", hasProcess("k2"))
    }

    /**
     * 本包托管的 Claude Code 起的子进程（Bash 工具里再起的 claude）继承了记号，却占着另一个会话：那个会话对本包来说在别处，
     * 不因为记号就当成自己的（托管目录输出开头的会话对不上）。
     */
    @Test
    fun aChildThatInheritedTheMarkHoldsItsSessionElsewhere() {
        HostTesting.writeClaudeStandIn(home, prelude = HostTesting.registersAs(SESSION))
        start("k1")
        HostTesting.registeredPid(home)
        terminalHolding(OTHER, mark = "$name/k1")
        val out = start("k2", "--resume", OTHER)
        assertTrue(out, out.contains("\"reason\":\"held_elsewhere\""))
    }

    /** claude 被包了一层（[HostTesting.Wrapper]）时本包的进程照样认得：不算别处占着（start 接上它），它身上也带着本包的记号（核心的账号页据此认归属）。 */
    @Test
    fun thisPackagesWrappedProcessIsNotElsewhere() {
        for (wrapper in HostTesting.Wrapper.entries) {
            File(home, ".claude/sessions").deleteRecursively()
            HostTesting.writeClaudeStandIn(home, prelude = HostTesting.registersAs(SESSION))
            val env = HostTesting.named(name) + HostTesting.wrapClaude(home, wrapper)
            HostTesting.run(home, command("start", "k1", home.path, "t", "-p"), env)
            val claude = HostTesting.registeredPid(home)
            assertEquals("$wrapper：记号说的归属", name, markedOwner(claude))
            val out =
                HostTesting.run(
                    home,
                    command("start", "k2", home.path, "t", "-p", "--resume", SESSION),
                    env,
                )
            assertFalse("$wrapper：$out", out.contains("held_elsewhere"))
            assertTrue("$wrapper：该接上 k1：$out", out.contains("\"key\":\"k1\"") && !hasProcess("k2"))
            HostTesting.run(home, command("stop", "k1"), HostTesting.named(name))
            assertTrue(
                "$wrapper：没收掉",
                waitUntil { !hostDir("k1").exists() && !hostDir("k2").exists() },
            )
        }
    }

    /**
     * 另一个包托管的进程（身上是那个包的记号）对本包来说是别处占着。账号页说它归哪个包由核心认（helper/accounts 的 TestProcessOwnersAndBlocker）。
     */
    @Test
    fun aProcessOfTheOtherPackageIsElsewhere() {
        val other = AppVariant(AppVariant.RELEASE_PACKAGE).linuxName
        terminalHolding(SESSION, mark = "$other/k9")
        val out = start("k1", "--resume", SESSION)
        assertTrue(out, out.contains("\"reason\":\"held_elsewhere\""))
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
            write(HostTesting.END_SESSION.toByteArray())
            flush()
        }
        // 限时只防卡死；zeta_exit 这一行托管脚本等起进程的那段写好 exit 才报，读到它不靠时机
        assertTrue("替身没退出", waitUntil { !alive(pid) })
        return generateSequence { lines.readLine() }.firstOrNull { it.contains("zeta_exit") }
    }

    /**
     * 进程 [pid] 身上的记号 ZETA_HOST=<包>/<托管目录名> 说它归哪个包，没有时为 null。认法同 helper/accounts 的 hostMark（账号页列开着的
     * Claude Code 归谁）：包名以 zeta 开头，托管目录名不空、不含 /、不以 . 开头。
     */
    private fun markedOwner(pid: Int): String? =
        File("/proc/$pid/environ").readText().split('\u0000').firstNotNullOfOrNull {
            Regex("^ZETA_HOST=(zeta[^/]*)/[^/.][^/]*\$").find(it)?.groupValues?.get(1)
        }

    private companion object {
        const val SESSION = "aaaaaaaa-1111-4222-8333-444444444444"
        const val OTHER = "bbbbbbbb-1111-4222-8333-444444444444"
    }
}
