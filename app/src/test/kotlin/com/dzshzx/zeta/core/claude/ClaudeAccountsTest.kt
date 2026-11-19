package com.dzshzx.zeta.core.claude

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 真的 assets/zeta_accounts.sh，跑在临时 HOME 上，凭据全是一眼能看出是假的值。不碰任何人真实的登录状态。
 */
class ClaudeAccountsTest {
    private lateinit var home: File
    private lateinit var accounts: ClaudeAccounts
    private val json = Json { ignoreUnknownKeys = true }
    private val spawned = mutableListOf<Process>()

    @Before
    fun setUp() {
        assumeTrue("要 bash、jq 和 /proc", File("/proc/self/stat").exists() && runCatching { ProcessBuilder("jq", "--version").start().waitFor() == 0 }.getOrDefault(false))
        home = Files.createTempDirectory("zeta-accounts").toFile().canonicalFile
        File(home, ".claude").mkdirs()
        accounts = ClaudeAccounts(ClaudeConfigClient { localShell(home) }, File("src/main/assets/zeta_accounts.sh").readText(), "zeta-dev")
    }

    @After
    fun tearDown() {
        spawned.forEach { it.destroyForcibly() }
        if (::home.isInitialized) home.deleteRecursively()
    }

    private val credentials get() = File(home, ".claude/.credentials.json")
    private val config get() = File(home, ".claude.json")

    /** 模拟 claude auth login 登进一个账号：写凭据（带一项 MCP 的 OAuth，换账号时不该动它）和 oauthAccount。 */
    private fun login(name: String, plan: String = "max") {
        credentials.writeText(
            """{"claudeAiOauth":{"accessToken":"fake-token-$name","refreshToken":"fake-refresh-$name","expiresAt":1,"scopes":["user:inference","user:profile"],
               "subscriptionType":"$plan","refreshTokenExpiresAt":1900000000000},"mcpOAuth":{"srv":{"accessToken":"fake-mcp-1"}}}""",
        )
        val others = if (config.exists()) json.parseToJsonElement(config.readText()).jsonObject - "oauthAccount" else mapOf("numStartups" to Json.parseToJsonElement("7"))
        config.writeText(
            JsonObject(
                others + ("oauthAccount" to Json.parseToJsonElement(
                    """{"accountUuid":"acc-$name","emailAddress":"$name@example.com","organizationName":"Org $name","displayName":"$name","planDisplayName":"Plan $name"}""",
                )),
            ).toString(),
        )
    }

    private fun token(): String? = json.parseToJsonElement(credentials.readText()).jsonObject["claudeAiOauth"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content
    private fun configAccount(): String? = json.parseToJsonElement(config.readText()).jsonObject["oauthAccount"]?.jsonObject?.get("accountUuid")?.jsonPrimitive?.content

    @Test
    fun saveSwitchAndRemove() {
        // 没登录：什么都没有，也存不了
        accounts.list().let {
            assertFalse(it.loggedIn)
            assertNull(it.current)
            assertTrue(it.saved.isEmpty())
        }
        assertNull(accounts.save())

        login("a")
        accounts.list().current!!.let {
            assertEquals("acc-a", it.id)
            assertEquals("a@example.com", it.title)
            assertEquals("Plan a", it.plan)
            assertEquals(1_900_000_000_000L, it.refreshExpiresAt)
        }
        assertEquals("acc-a", accounts.save())
        val store = File(home, ".config/zeta-dev/accounts")
        assertEquals("700", mode(store))
        assertEquals("600", mode(File(store, "acc-a.json")))

        // 登进第二个账号（组织名为空）：两个都列出来，当前的在前
        login("b", plan = "pro")
        accounts.save()
        accounts.list().let { state ->
            assertEquals("acc-b", state.current!!.id)
            assertEquals(listOf("acc-b", "acc-a"), state.all.map { it.id })
        }

        // 换回 a：凭据和 oauthAccount 都是 a 的，MCP 的 OAuth 和 ~/.claude.json 里别的键照旧；b 刚才的状态存回去了
        assertEquals(SwitchOutcome.Done("acc-a"), accounts.switch("acc-a"))
        assertEquals("fake-token-a", token())
        assertEquals("acc-a", configAccount())
        assertEquals("600", mode(credentials))
        val cred = json.parseToJsonElement(credentials.readText()).jsonObject
        assertEquals("fake-mcp-1", cred["mcpOAuth"]!!.jsonObject["srv"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content)
        assertEquals("7", json.parseToJsonElement(config.readText()).jsonObject["numStartups"].toString())
        assertEquals("fake-token-b", json.parseToJsonElement(File(store, "acc-b.json").readText()).jsonObject["claudeAiOauth"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content)

        // Claude Code 刷新过 a 的令牌：换走时存的是新的那份
        credentials.writeText(credentials.readText().replace("fake-token-a", "fake-token-a2").replace("fake-refresh-a", "fake-refresh-a2"))
        assertEquals(SwitchOutcome.Done("acc-b"), accounts.switch("acc-b"))
        assertEquals("fake-token-b", token())
        assertTrue("fake-refresh-a2" in File(store, "acc-a.json").readText())

        // 输出里没有令牌
        val raw = run("exec bash -c \"\$(cat '${File("src/main/assets/zeta_accounts.sh").absolutePath}')\" zeta list zeta-dev")
        assertFalse(raw, "fake-token" in raw || "fake-refresh" in raw)

        assertEquals(SwitchOutcome.Missing, accounts.switch("acc-zzz"))
        assertEquals(SwitchOutcome.Missing, accounts.switch("../acc-a"))
        accounts.remove("acc-a")
        assertEquals(listOf("acc-b"), accounts.list().saved.map { it.id })
        // 删的只是副本：当前登录不动
        assertEquals("fake-token-b", token())
    }

    @Test
    fun liveAccountIsRecognisedByItsTokenEvenIfConfigWasRevertedByAnOpenProcess() {
        login("a")
        accounts.save()
        login("b")
        accounts.save()
        assertEquals(SwitchOutcome.Done("acc-a"), accounts.switch("acc-a"))
        // 开着的 Claude Code 把旧账号写回了 ~/.claude.json（见 ClaudeCodeQuotaContractTest）：令牌是 a 的，记录说是 b
        config.writeText(config.readText().replace("acc-a", "acc-b").replace("a@example.com", "b@example.com"))
        val state = accounts.list()
        assertEquals("acc-a", state.current!!.id)
        assertEquals("a@example.com", state.current!!.email)
        // 换走时存回 a 那一份，不会把 a 的令牌存到 b 名下
        assertEquals(SwitchOutcome.Done("acc-b"), accounts.switch("acc-b"))
        assertTrue("fake-token-a" in File(home, ".config/zeta-dev/accounts/acc-a.json").readText())
        assertTrue("fake-token-b" in File(home, ".config/zeta-dev/accounts/acc-b.json").readText())
        assertEquals("acc-b", configAccount())
    }

    @Test
    fun openClaudeProcessesBlockTheSwitch() {
        login("a")
        accounts.save()
        login("b")
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

        val state = accounts.list()
        assertEquals(setOf(ClaudeProc(other.pid().toInt(), "", "idle", "interactive"), ClaudeProc(zeta.pid().toInt(), "zeta", "busy", "interactive")), state.processes.toSet())
        val blocker = ClaudeAccounts.blocker(state.processes, "zeta-dev", ownWorking = false)
        assertNotNull(blocker)
        assertTrue(blocker!!, "另一个 Zeta（正式版）开着 1 个对话，其中 1 个在进行" in blocker && "1 个别处开着的 Claude Code" in blocker)

        val outcome = accounts.switch("acc-a")
        assertTrue(outcome.toString(), outcome is SwitchOutcome.Busy && outcome.processes.size == 2)
        // 没换
        assertEquals("fake-token-b", token())

        // 只剩本包自己的（Zeta 正在关）：不算阻碍；关掉以后就能换
        other.destroyForcibly().waitFor()
        zeta.destroyForcibly().waitFor()
        val mine = live()
        session(mine, status = "idle")
        File(home, ".cache/zeta-dev/hosts/k2").mkdirs()
        File(home, ".cache/zeta-dev/hosts/k2/pid").writeText("${mine.pid()}")
        assertNull(ClaudeAccounts.blocker(accounts.list().processes, "zeta-dev", ownWorking = false))
        assertNotNull(ClaudeAccounts.blocker(emptyList(), "zeta-dev", ownWorking = true))
        // 换的脚本会等它退出（最多约 8 秒）
        Thread { Thread.sleep(600); mine.destroyForcibly() }.start()
        assertEquals(SwitchOutcome.Done("acc-a"), accounts.switch("acc-a"))
        assertEquals("fake-token-a", token())
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

    private fun mode(file: File): String = run("stat -c %a '${file.path}'").trim()

    private fun run(command: String): String {
        val p = localShell(home).start(command)
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(30, TimeUnit.SECONDS)
        return out
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
