package com.dzshzx.zeta.core.claude

import com.dzshzx.zeta.core.HelperProgram
import com.dzshzx.zeta.core.LinuxShell
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 真的 assets/zeta_accounts.sh，跑在临时 HOME 上；claude-switch 是一个假的 bash 脚本，和应用里带的程序一样经 [HelperProgram]
 * 第一次用时装进 ~/.cache/zeta/bin。它照 ~/fake 下的文件打印 JSON、写标准错误、给退出码，并把每次的参数记进 ~/fake/calls。
 * 不碰任何人真实的登录状态。
 */
class ClaudeAccountsTest {
    private lateinit var home: File
    private lateinit var accounts: ClaudeAccounts
    private val spawned = mutableListOf<Process>()

    /** 假的 claude-switch：--json 打印 ~/fake/json.out；别的命令按第一个参数（add、remove 或账号名）取 ~/fake/<键>.out、.err、.code。 */
    private val fakeSwitch = """
        #!/bin/bash
        d=${'$'}HOME/fake
        printf '%s\n' "${'$'}*" >>"${'$'}d/calls"
        k=${'$'}1
        [ "${'$'}k" = --json ] && k=json
        [ -f "${'$'}d/${'$'}k.out" ] && cat "${'$'}d/${'$'}k.out"
        [ -f "${'$'}d/${'$'}k.err" ] && cat "${'$'}d/${'$'}k.err" >&2
        exit "${'$'}(cat "${'$'}d/${'$'}k.code" 2>/dev/null || echo 0)"
    """.trimIndent() + "\n"

    private val program = HelperProgram({ fakeSwitch.toByteArray() }, "zeta", HelperProgram.CLAUDE_SWITCH)

    private val script get() = File("src/main/assets/zeta_accounts.sh").readText()

    @Before
    fun setUp() {
        assumeTrue("要 bash 和 /proc", File("/proc/self/stat").exists())
        home = Files.createTempDirectory("zeta-accounts").toFile().canonicalFile
        File(home, ".claude").mkdirs()
        accounts = ClaudeAccounts(ClaudeConfigClient { localShell(home) }, script, program)
    }

    @After
    fun tearDown() {
        spawned.forEach { it.destroyForcibly() }
        if (::home.isInitialized) home.deleteRecursively()
    }

    private val fake get() = File(home, "fake")

    /** 让假的 claude-switch 的 --json 打印 [status]（它本身由 [program] 在第一次用时装上）。 */
    private fun installFake(status: String) {
        fake.mkdirs()
        File(fake, "json.out").writeText(status)
    }

    /** 装好的 claude-switch。 */
    private val installed get() = File(home, program.relativePath)

    /** 让假的 claude-switch 在第一个参数为 [key] 时失败。 */
    private fun failWith(key: String, code: Int, stderr: String) {
        File(fake, "$key.code").writeText("$code")
        File(fake, "$key.err").writeText(stderr)
    }

    private fun calls(): List<String> = File(fake, "calls").takeIf { it.exists() }?.readLines().orEmpty()

    private fun account(name: String, current: Boolean = false, parked: Boolean = false, email: String? = "$name@example.com") =
        """{"name":"$name","current":$current,"parked":$parked,"uuid":"fake-uuid-$name"""" +
            (email?.let { ""","email":"$it","organization":"Org $name","displayName":"$name","subscription":"max","refreshExpiresAt":1900000000000""" } ?: "") + "}"

    private fun status(current: String, live: String?, vararg listed: String) =
        """{"current":"$current","live":${live ?: "null"},"accounts":[${listed.joinToString(",")}],"targets":[],"pending":false,"running":[]}"""

    /** 两个号：claude 在用，claude-b 有存档。 */
    private val twoAccounts = status("claude", account("claude", current = true), account("claude", current = true), account("claude-b", parked = true))

    /** 在用的登录没记过：claude-switch 认不出是哪个号（live.name 为空）。claude 记过但没有存档。 */
    private val unknownLive = status(
        "claude",
        """{"name":"","current":false,"parked":false,"uuid":"fake-uuid-x","email":"x@example.com","subscription":"pro"}""",
        """{"name":"claude","label":"主号","current":false,"parked":false}""",
    )

    // ---------- 解析 ----------

    @Test
    fun parsesAccountsFromClaudeSwitchJson() {
        val state = ClaudeAccounts.parse(
            status(
                "claude",
                account("claude", current = true),
                account("claude", current = true),
                account("claude-b", parked = true, email = null),
                // 切换记录里刚 add、还没登录的号：既不在用也没有存档，不列
                account("claude-c", email = null),
            ),
        )
        assertTrue(state.loggedIn)
        state.current!!.let {
            assertEquals("claude", it.id)
            assertEquals("claude@example.com", it.title)
            assertEquals("Org claude", it.organization)
            assertEquals("max", it.subscription)
            assertEquals("Max", it.plan)
            assertEquals(1_900_000_000_000L, it.refreshExpiresAt)
        }
        assertEquals(listOf("claude", "claude-b"), state.saved.map { it.id })
        // 没有可选字段的号：空串和 null，标题退回账号名
        state.saved[1].let {
            assertEquals("", it.email)
            assertEquals("", it.plan)
            assertNull(it.refreshExpiresAt)
            assertEquals("claude-b", it.title)
        }
        assertEquals(listOf("claude", "claude-b"), state.all.map { it.id })
    }

    @Test
    fun parsesUnknownLoginAndLoggedOut() {
        val unknown = ClaudeAccounts.parse(unknownLive)
        assertTrue(unknown.loggedIn)
        assertEquals("", unknown.current!!.id)
        assertEquals("x@example.com", unknown.current!!.title)
        assertEquals("Pro", unknown.current!!.plan)
        assertTrue(unknown.saved.isEmpty())
        assertEquals(listOf(""), unknown.all.map { it.id })

        val out = ClaudeAccounts.parse(status("claude-b", null, account("claude", parked = true)))
        assertFalse(out.loggedIn)
        assertNull(out.current)
        assertEquals(listOf("claude"), out.saved.map { it.id })

        assertThrows(ClaudeConfigException::class.java) { ClaudeAccounts.parse("not json") }
    }

    @Test
    fun targetNamesAreTaken() {
        // 非账号目标的名字不能再给新账号用：已有 claude-b 这个目标时，第二个号取名 claude-c
        val withTarget = """{"current":"claude","live":${account("claude", current = true)},"accounts":[${account("claude", current = true)}],""" +
            """"targets":[{"name":"claude-b","settings":"/home/me/.config/claude-switch/targets/claude-b.json"}],"pending":false,"running":[]}"""
        installFake(withTarget)
        assertEquals(SwitchOutcome.Done("claude-c"), accounts.prepareLogin())
        assertEquals(listOf("--json", "add claude-c"), calls())
    }

    @Test
    fun freeNamesGoClaudeThenLetters() {
        assertEquals("claude", ClaudeAccounts.freeName(emptySet()))
        assertEquals("claude", ClaudeAccounts.freeName(setOf("claude-b")))
        assertEquals("claude-c", ClaudeAccounts.freeName(setOf("claude", "claude-b")))
        val all = setOf("claude") + ('b'..'z').map { "claude-$it" }
        assertEquals("claude-27", ClaudeAccounts.freeName(all))
    }

    // ---------- 经脚本跑 ----------

    @Test
    fun listRunsClaudeSwitch() {
        installFake(twoAccounts)
        val state = accounts.list()
        assertEquals("claude", state.current!!.id)
        assertEquals(listOf("claude", "claude-b"), state.saved.map { it.id })
        assertEquals(listOf("--json"), calls())
    }

    @Test
    fun bundledClaudeSwitchIsInstalledOnFirstUse() {
        installFake(twoAccounts)
        assertFalse(installed.exists())
        accounts.list()
        // 装在 Zeta 自己的目录里，不占用户的 ~/.local/bin
        assertEquals(listOf("claude-switch-" + program.version), File(home, ".cache/zeta/bin").list()!!.toList())
        assertTrue(installed.canExecute())
        assertFalse(File(home, ".local/bin").exists())
        // 装好以后直接用，不再装
        installed.setLastModified(0)
        accounts.list()
        assertEquals(0, installed.lastModified())
        assertEquals(listOf("--json", "--json"), calls())

        // 应用升级换了 claude-switch：装新的、删掉旧的
        val newer = HelperProgram({ (fakeSwitch + "# v2\n").toByteArray() }, "zeta", HelperProgram.CLAUDE_SWITCH)
        ClaudeAccounts(ClaudeConfigClient { localShell(home) }, script, newer).list()
        assertEquals(listOf("claude-switch-" + newer.version), File(home, ".cache/zeta/bin").list()!!.toList())
    }

    @Test
    fun switchMapsExitCodes() {
        installFake(twoAccounts)
        assertEquals(SwitchOutcome.Done("claude-b"), accounts.switch("claude-b"))
        assertEquals("claude-b", calls().last())

        failWith("claude-b", 1, "claude-switch: 上次换号（换到 claude-b）中途出错，现场留在 pending.json；不自动补做\n")
        assertEquals(SwitchOutcome.Failed("上次换号（换到 claude-b）中途出错，现场留在 pending.json；不自动补做"), accounts.switch("claude-b"))

        failWith("claude-b", 1, "claude-switch: 还有 Claude Code 进程在跑，先全部退出再换号：\n  123 claude\n")
        val busy = accounts.switch("claude-b")
        assertTrue(busy.toString(), busy is SwitchOutcome.Busy && busy.message.startsWith("还有 Claude Code 进程在跑") && "123 claude" in busy.message)

        failWith("claude-z", 2, "claude-switch: 没有叫 claude-z 的账号，未改动；加新账号用 claude-switch add claude-z\n")
        assertEquals(SwitchOutcome.Missing, accounts.switch("claude-z"))

        failWith("claude-y", 2, "claude-switch: 读写文件出错：disk full\n")
        assertEquals(SwitchOutcome.Failed("读写文件出错：disk full"), accounts.switch("claude-y"))
    }

    @Test
    fun prepareLoginParksTheLiveAccountUnderAFreeName() {
        installFake(twoAccounts)
        assertEquals(SwitchOutcome.Done("claude-c"), accounts.prepareLogin())
        assertEquals(listOf("--json", "add claude-c"), calls())
    }

    @Test
    fun prepareLoginAddsTheSecondAccount() {
        // 只用过 claude：第二个号取名 claude-b，用 add 把在用的号存档并退出（claude-b 不是固定的号）
        installFake(status("claude", account("claude", current = true), account("claude", current = true)))
        assertEquals(SwitchOutcome.Done("claude-b"), accounts.prepareLogin())
        assertEquals(listOf("--json", "add claude-b"), calls())
    }

    @Test
    fun prepareLoginRecordsAnUnknownLoginFirst() {
        installFake(unknownLive)
        assertEquals(SwitchOutcome.Done("claude-c"), accounts.prepareLogin())
        // 先把认不出的登录 add 成 claude-b（不改登录），再 add claude-c 把它存档并退出
        assertEquals(listOf("--json", "add claude-b", "add claude-c"), calls())
    }

    @Test
    fun prepareLoginStopsWhenTheFirstAddFails() {
        installFake(unknownLive)
        failWith("add", 1, "claude-switch: 还有 Claude Code 进程在跑，先全部退出再换号：\n  123 claude\n")
        assertTrue(accounts.prepareLogin() is SwitchOutcome.Busy)
        assertEquals(listOf("--json", "add claude-b"), calls())
    }

    @Test
    fun prepareLoginDoesNothingWhenLoggedOut() {
        installFake(status("claude-b", null, account("claude", parked = true)))
        assertEquals(SwitchOutcome.Done(""), accounts.prepareLogin())
        assertEquals(listOf("--json"), calls())
    }

    @Test
    fun saveNamesOnlyAnUnknownLogin() {
        installFake(twoAccounts)
        assertEquals("claude", accounts.save())
        assertEquals(listOf("--json"), calls())

        installFake(unknownLive)
        File(fake, "calls").delete()
        assertEquals("claude-b", accounts.save())
        assertEquals(listOf("--json", "add claude-b"), calls())

        failWith("add", 2, "claude-switch: 已经有叫 claude-c 的账号，未改动\n")
        assertEquals("已经有叫 claude-c 的账号，未改动", assertThrows(ClaudeConfigException::class.java) { accounts.save() }.message)

        installFake(status("claude", null))
        assertNull(accounts.save())
    }

    @Test
    fun removeRunsClaudeSwitchRemove() {
        installFake(twoAccounts)
        accounts.remove("claude-b")
        assertEquals("remove claude-b", calls().last())
        failWith("remove", 1, "claude-switch: claude（主号）是在用的账号，未改动；先换到别的号再删\n")
        val error = assertThrows(ClaudeConfigException::class.java) { accounts.remove("claude") }
        assertEquals("claude（主号）是在用的账号，未改动；先换到别的号再删", error.message)
    }

    @Test
    fun failedInstallIsAClearFailure() {
        installFake(twoAccounts)
        // 装的目录建不起来（被一个文件占着）
        File(home, ".cache/zeta").mkdirs()
        File(home, ".cache/zeta/bin").writeText("")
        val message = assertThrows(ClaudeConfigException::class.java) { accounts.list() }.message!!
        assertTrue(message, message.startsWith("装 claude-switch 没成功："))
        assertEquals(SwitchOutcome.Failed(message), accounts.switch("claude-b"))
        assertEquals(SwitchOutcome.Failed(message), accounts.prepareLogin())
        assertEquals(message, assertThrows(ClaudeConfigException::class.java) { accounts.remove("claude-b") }.message)
        assertEquals(message, assertThrows(ClaudeConfigException::class.java) { accounts.save() }.message)
        assertEquals(emptyList<String>(), calls())
    }

    @Test
    fun unsupportedMachineIsAClearFailure() {
        installFake(twoAccounts)
        val fakeBin = File(home, "fake-bin").apply { mkdirs() }
        File(fakeBin, "uname").apply { writeText("#!/bin/sh\necho riscv64\n") }.setExecutable(true)
        val riscv = ClaudeAccounts(ClaudeConfigClient { localShell(home, mapOf("PATH" to fakeBin.path + ":" + System.getenv("PATH"))) }, script, program)
        assertEquals("claude-switch 没有这台机器（riscv64）能用的版本", assertThrows(ClaudeConfigException::class.java) { riscv.list() }.message)
        assertEquals(emptyList<String>(), calls())
    }

    @Test
    fun openClaudeProcessesAreListedAndExplained() {
        assumeTrue("要 jq", runCatching { ProcessBuilder("jq", "--version").start().waitFor() == 0 }.getOrDefault(false))
        installFake(twoAccounts)
        // 一个“别处的” Claude Code：~/.claude/sessions 里登记着、进程还活着
        val other = live()
        session(other, status = "idle")
        // 另一个 Zeta 包托管的一个，正在回答
        val zeta = live()
        session(zeta, status = "busy")
        File(home, ".cache/zeta/hosts/k1").mkdirs()
        File(home, ".cache/zeta/hosts/k1/pid").writeText("${zeta.pid()}\n")
        // pid 还在但已经是别的进程了（启动时刻对不上）：不算
        File(home, ".claude/sessions/999.json").writeText("""{"pid":${other.pid()},"procStart":"1","sessionId":"s-stale","status":"busy"}""")
        // 进程已经没了：不算
        File(home, ".claude/sessions/1.json").writeText("""{"pid":2147480000,"sessionId":"s-dead","status":"busy"}""")

        val expected = setOf(ClaudeProc(other.pid().toInt(), "", "idle", "interactive"), ClaudeProc(zeta.pid().toInt(), "zeta", "busy", "interactive"))
        val state = accounts.list()
        assertEquals(expected, state.processes.toSet())
        val blocker = ClaudeAccounts.blocker(state.processes, "zeta-dev")
        assertNotNull(blocker)
        assertTrue(blocker!!, "另一个 Zeta（正式版）开着 1 个对话，其中 1 个在进行" in blocker && "1 个别处开着的 Claude Code" in blocker)

        // claude-switch 拒绝时带上脚本列的进程
        failWith("claude-b", 1, "claude-switch: 还有 Claude Code 进程在跑，先全部退出再换号：\n  1 claude\n")
        val outcome = accounts.switch("claude-b")
        assertTrue(outcome.toString(), outcome is SwitchOutcome.Busy && outcome.processes.toSet() == expected)

        // 只剩本包自己的（Zeta 正在关）：不算阻碍；换号前脚本会等它退出（最多约 8 秒）再跑 claude-switch
        File(fake, "claude-b.code").delete()
        File(fake, "claude-b.err").delete()
        other.destroyForcibly().waitFor()
        zeta.destroyForcibly().waitFor()
        val mine = live()
        session(mine, status = "idle")
        File(home, ".cache/zeta-dev/hosts/k2").mkdirs()
        File(home, ".cache/zeta-dev/hosts/k2/pid").writeText("${mine.pid()}")
        assertNull(ClaudeAccounts.blocker(accounts.list().processes, "zeta-dev"))
        Thread { Thread.sleep(600); mine.destroyForcibly() }.start()
        val started = System.currentTimeMillis()
        assertEquals(SwitchOutcome.Done("claude-b"), accounts.switch("claude-b"))
        assertTrue(System.currentTimeMillis() - started >= 500)
    }

    // ---------- 工具 ----------

    private fun live(): Process = ProcessBuilder("sleep", "300").start().also { spawned += it }

    private fun session(process: Process, status: String) {
        val stat = File("/proc/${process.pid()}/stat").readText()
        val start = stat.substringAfterLast(") ").split(' ')[19]
        File(home, ".claude/sessions").mkdirs()
        File(home, ".claude/sessions/${process.pid()}.json").writeText(
            """{"pid":${process.pid()},"procStart":"$start","sessionId":"s-${process.pid()}","kind":"interactive","status":"$status"}""",
        )
    }

    companion object {
        fun localShell(home: File, extraEnv: Map<String, String> = emptyMap()): LinuxShell = object : LinuxShell {
            override val home: String = home.path

            override fun start(command: String): Process {
                val builder = ProcessBuilder("bash", "-c", command).directory(home)
                val env = builder.environment()
                val path = listOfNotNull(System.getProperty("user.home")?.let { "$it/.local/bin" }, System.getenv("PATH")).joinToString(":")
                env.clear()
                env["HOME"] = home.path
                env["PATH"] = path
                env["LANG"] = "C.UTF-8"
                env["USER"] = System.getProperty("user.name") ?: "user"
                env.putAll(extraEnv)
                return builder.start()
            }

            override fun kill(process: Process) {
                process.destroyForcibly()
            }
        }
    }
}
