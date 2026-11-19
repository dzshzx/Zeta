package com.dzshzx.zeta.core.claude

import com.dzshzx.zeta.core.Quota
import com.dzshzx.zeta.core.QuotaProbe
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 额度显示和账号切换依赖的 Claude Code 行为（2.1.328 实测），在这里钉住：升级 Claude Code 后跑一遍，变了就会失败，
 * [Quota]、[QuotaProbe]、[ClaudeAccounts] 跟着改。换号用的是真的 claude-switch（工作站 ~/.local/bin 里有时链进临时 HOME，没有时跳过那几条）。
 *
 * 真的 claude 命令行，跑在临时 HOME 上，用假的 claude.ai 登录（假令牌），连本机一个假的 Messages API：它在响应头里带上
 * anthropic-ratelimit-unified-*，并记下每次请求用的是哪个令牌。代理指到一个不通的端口，claude.ai 那边的接口（取用量、取账号资料）
 * 一律连不上，假令牌不会出本机。
 */
class ClaudeCodeQuotaContractTest {
    private lateinit var home: File
    private lateinit var server: HttpServer
    private val json = Json { ignoreUnknownKeys = true }
    private val bearers = mutableListOf<String>()
    private val spawned = mutableListOf<Process>()

    @Volatile private var fiveHour = 0.25

    @Before
    fun setUp() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        home = java.nio.file.Files.createTempDirectory("zeta-quota").toFile().canonicalFile
        File(home, ".claude").mkdirs()
        File(home, "proj").mkdirs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> runCatching { serve(ex) }.onFailure { ex.sendResponseHeaders(500, -1); ex.close() } }
        server.start()
    }

    @After
    fun tearDown() {
        spawned.forEach { it.destroyForcibly() }
        if (::server.isInitialized) server.stop(0)
        if (::home.isInitialized) home.deleteRecursively()
    }

    private fun claudeAvailable(): Boolean = path().split(':').any { File(it, "claude").canExecute() }

    private fun path() = listOfNotNull(System.getProperty("user.home")?.let { "$it/.local/bin" }, System.getenv("PATH")).joinToString(":")

    private val env: Map<String, String>
        get() = mapOf(
            "ANTHROPIC_BASE_URL" to "http://127.0.0.1:${server.address.port}",
            "NO_PROXY" to "127.0.0.1,localhost",
            "HTTPS_PROXY" to "http://127.0.0.1:9",
            "HTTP_PROXY" to "http://127.0.0.1:9",
            "DISABLE_TELEMETRY" to "1",
            "DISABLE_ERROR_REPORTING" to "1",
            "DISABLE_AUTOUPDATER" to "1",
        )

    private fun serve(ex: HttpExchange) {
        val path = ex.requestURI.path
        if (ex.requestMethod != "POST" || !path.startsWith("/v1/messages") || path.contains("count_tokens")) {
            ex.sendResponseHeaders(404, -1)
            ex.close()
            return
        }
        synchronized(bearers) { bearers += ex.requestHeaders.getFirst("Authorization").orEmpty() }
        val body = json.parseToJsonElement(ex.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
        val stream = body["stream"]?.jsonPrimitive?.content == "true"
        val now = System.currentTimeMillis() / 1000
        val h = ex.responseHeaders
        h.add("anthropic-ratelimit-unified-status", "allowed")
        h.add("anthropic-ratelimit-unified-representative-claim", "five_hour")
        h.add("anthropic-ratelimit-unified-reset", (now + 3600).toString())
        h.add("anthropic-ratelimit-unified-5h-utilization", fiveHour.toString())
        h.add("anthropic-ratelimit-unified-5h-reset", (now + 3600).toString())
        h.add("anthropic-ratelimit-unified-5h-status", "allowed")
        h.add("anthropic-ratelimit-unified-7d-utilization", "0.4")
        h.add("anthropic-ratelimit-unified-7d-reset", (now + 3 * 86400).toString())
        h.add("anthropic-ratelimit-unified-7d-status", "allowed")
        val message = buildJsonObject {
            put("id", "msg_fake"); put("type", "message"); put("role", "assistant"); put("model", "claude-fake")
            put("content", Json.parseToJsonElement("""[{"type":"text","text":"ok"}]""")); put("stop_reason", "end_turn")
            putJsonObject("usage") { put("input_tokens", 10); put("output_tokens", 5) }
        }
        val bytes = if (stream) {
            listOf(
                "message_start" to """{"type":"message_start","message":${JsonObject(message + ("content" to Json.parseToJsonElement("[]")))}}""",
                "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}""",
                "message_stop" to """{"type":"message_stop"}""",
            ).joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" }.toByteArray()
        } else {
            message.toString().toByteArray()
        }
        h.add("Content-Type", if (stream) "text/event-stream" else "application/json")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** 假的 claude.ai 登录：和 claude auth login 写下的两个文件同样的键。 */
    private fun login(name: String) {
        File(home, ".claude/.credentials.json").writeText(credentials(name))
        File(home, ".claude.json").writeText("""{"hasCompletedOnboarding":true,"oauthAccount":${oauthAccount(name)}}""")
    }

    private fun credentials(name: String): String {
        val expires = System.currentTimeMillis() + 30L * 86_400_000
        return """{"claudeAiOauth":{"accessToken":"fake-token-$name","refreshToken":"fake-refresh-$name","expiresAt":$expires,
               "scopes":["user:inference","user:profile","user:sessions:claude_code"],"subscriptionType":"max","rateLimitTier":"default_claude_max_20x"}}"""
    }

    private fun oauthAccount(name: String) =
        """{"accountUuid":"acc-$name","emailAddress":"$name@example.com","organizationUuid":"org-$name","organizationName":"Org $name","displayName":"$name"}"""

    private fun configAccount(): String? =
        json.parseToJsonElement(File(home, ".claude.json").readText()).jsonObject["oauthAccount"]?.jsonObject?.get("accountUuid")?.jsonPrimitive?.content

    private fun accounts() = ClaudeAccounts(ClaudeConfigClient { ClaudeAccountsTest.localShell(home, env) }, File("src/main/assets/zeta_accounts.sh").readText())

    /** 把工作站上真的 claude-switch 链进临时 HOME（Zeta 认的位置是 ~/.local/bin/claude-switch）；没有时跳过这条测试。 */
    private fun installSwitch() {
        val real = path().split(':').map { File(it, "claude-switch") }.firstOrNull { it.canExecute() }
        assumeTrue("没有 claude-switch", real != null)
        val bin = File(home, ".local/bin").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(bin, "claude-switch").toPath(), real!!.canonicalFile.toPath())
    }

    /** 一个 stream-json 的 claude 进程，参数和 Zeta 起对话时一样。 */
    private inner class Claude : AutoCloseable {
        val process: Process
        private val lines = LinkedBlockingQueue<String>()
        val seen = mutableListOf<JsonObject>()
        private var seq = 0

        init {
            val builder = ProcessBuilder(
                "claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--permission-prompt-tool", "stdio",
            ).directory(File(home, "proj"))
            builder.environment().apply {
                clear()
                put("HOME", home.path); put("PATH", path()); put("LANG", "C.UTF-8")
                putAll(env)
            }
            builder.redirectError(File("/dev/null"))
            process = builder.start().also { spawned += it }
            Thread { process.inputStream.bufferedReader().forEachLine { lines.put(it) } }.apply { isDaemon = true; start() }
            control(buildJsonObject { put("subtype", "initialize") })
        }

        fun send(o: JsonObject) {
            process.outputStream.write((o.toString() + "\n").toByteArray())
            process.outputStream.flush()
        }

        fun turn(prompt: String) {
            send(buildJsonObject { put("type", "user"); putJsonObject("message") { put("role", "user"); put("content", prompt) } })
            until { it.str("type") == "result" }
        }

        fun control(request: JsonObject): JsonObject {
            val id = "c${++seq}"
            send(buildJsonObject { put("type", "control_request"); put("request_id", id); put("request", request) })
            var response: JsonObject? = null
            until { o -> (o.str("type") == "control_response" && o.obj("response")?.str("request_id") == id).also { if (it) response = o.obj("response") } }
            return response!!
        }

        fun status(label: String): String? = control(buildJsonObject { put("subtype", "get_status") }).obj("response")!!["sections"]!!.jsonArray
            .flatMap { it.jsonObject["rows"]!!.jsonArray }.map { it.jsonObject }.firstOrNull { it.str("label") == label }?.str("value")

        private fun until(done: (JsonObject) -> Boolean) {
            val deadline = System.currentTimeMillis() + 60_000
            while (true) {
                val left = deadline - System.currentTimeMillis()
                assertTrue("claude 没有按时回应", left > 0)
                val line = lines.poll(left, TimeUnit.MILLISECONDS) ?: continue
                val o = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                seen += o
                if (done(o)) return
            }
        }

        override fun close() {
            runCatching { process.outputStream.close() }
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    @Test
    fun rateLimitEventsAndGetUsageCarryTheWindows() {
        assumeTrue("没有 jq", runCatching { ProcessBuilder("jq", "--version").start().waitFor() == 0 }.getOrDefault(false))
        installSwitch()
        login("a")
        Claude().use { claude ->
            claude.turn("hello")
            // 响应头里的 5 小时、每周窗口，原样（0～1 的比例、秒级重置时刻）出现在 rate_limit_event 的 unifiedWindows 里
            val event = claude.seen.last { it.str("type") == "rate_limit_event" }
            val fromEvent = Quota.applyEvent(null, event.obj("rate_limit_info"), 0)!!
            assertEquals(listOf("session" to 0.25, "weekly_all" to 0.4), fromEvent.windows.map { it.kind to it.used })
            assertTrue(fromEvent.windows.all { it.resetsAt!! > System.currentTimeMillis() })

            // 数变了才再发一条
            fiveHour = 0.35
            claude.turn("again")
            assertEquals(2, claude.seen.count { it.str("type") == "rate_limit_event" })

            // get_usage：claude.ai 的用量接口连不上时，退回响应头里的数（百分数、ISO 时刻）
            val usage = claude.control(buildJsonObject { put("subtype", "get_usage"); put("skip_behaviors", true) })
            assertEquals("success", usage.str("subtype"))
            val parsed = Quota.parseUsage(usage.obj("response"), 0)!!
            assertEquals("max", parsed.subscription)
            assertEquals(listOf("session" to 0.35, "weekly_all" to 0.4), parsed.windows.map { it.kind to it.used })

            // 开着的进程在 ~/.claude/sessions 里登记着（pid、启动时刻、状态），账号脚本据此说清是谁挡着；claude-switch 也认出它，不让换
            val procs = accounts().list().processes
            assertEquals(listOf(ClaudeProc(claude.process.pid().toInt(), "", "idle", procs.single().kind)), procs)
            assertEquals("claude", accounts().save())
            val outcome = accounts().switch("claude")
            assertTrue(outcome.toString(), outcome is SwitchOutcome.Busy && outcome.processes == procs)
        }
    }

    @Test
    fun probeAnswersWithoutLeavingASession() {
        login("a")
        val answer = QuotaProbe.query(ClaudeAccountsTest.localShell(home, env), File(home, "proj").path)
        val parsed = Quota.parseUsage(answer, 0)
        assertNotNull(parsed)
        assertTrue(parsed!!.available)
        assertEquals("max", parsed.subscription)
        // 不留会话记录；退出后 ~/.claude/sessions 里的登记也删了
        assertEquals(emptyList<String>(), File(home, ".claude/projects").walkTopDown().filter { it.isFile }.map { it.name }.toList())
        assertEquals(emptyList<String>(), File(home, ".claude/sessions").listFiles().orEmpty().map { it.name })
        // 没有发过模型请求
        assertEquals(emptyList<String>(), synchronized(bearers) { bearers.toList() })
    }

    @Test
    fun switchedLoginIsWhatTheNextProcessUses() {
        installSwitch()
        login("a")
        assertEquals("claude", accounts().save())
        // 添加账号：claude 存档并退出，新登录记成 claude-b
        assertEquals(SwitchOutcome.Done("claude-b"), accounts().prepareLogin())
        assertTrue(!accounts().list().loggedIn)
        login("b")
        assertEquals("claude-b", accounts().save())
        assertEquals(listOf("claude-b", "claude"), accounts().list().all.map { it.id })
        assertEquals(SwitchOutcome.Done("claude"), accounts().switch("claude"))
        Claude().use { claude ->
            claude.turn("hello")
            assertEquals("a@example.com", claude.status("Email"))
            assertEquals("Org a", claude.status("Organization"))
        }
        assertEquals(listOf("Bearer fake-token-a"), synchronized(bearers) { bearers.toList() })
        assertEquals("acc-a", configAccount())
    }

    /**
     * 为什么换账号前要等所有 Claude Code 进程退出：开着的进程（哪怕闲着）在凭据文件变了以后，会把它记着的 oauthAccount 写回
     * ~/.claude.json，下一次请求却已经用新令牌了。哪天这条不成立了，claude-switch 的进程检查和 [ClaudeAccounts.blocker] 可以放宽。
     */
    @Test
    fun anOpenProcessWritesItsAccountBack() {
        login("a")
        Claude().use { claude ->
            claude.turn("hello")
            // 绕过进程检查，直接换成 b（先改 ~/.claude.json 再改凭据）
            val config = json.parseToJsonElement(File(home, ".claude.json").readText()).jsonObject
            File(home, ".claude.json.tmp").writeText(JsonObject(config + ("oauthAccount" to json.parseToJsonElement(oauthAccount("b")))).toString())
            File(home, ".claude.json.tmp").renameTo(File(home, ".claude.json"))
            File(home, ".claude/.credentials.json.tmp").writeText(credentials("b"))
            File(home, ".claude/.credentials.json.tmp").renameTo(File(home, ".claude/.credentials.json"))
            val deadline = System.currentTimeMillis() + 10_000
            while (configAccount() != "acc-a" && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertEquals("acc-a", configAccount())
            claude.turn("again")
            assertEquals("a@example.com", claude.status("Email"))
        }
        assertEquals(listOf("Bearer fake-token-a", "Bearer fake-token-b"), synchronized(bearers) { bearers.toList() })
    }
}
