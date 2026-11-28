package com.dzshzx.zeta.core.claude

import com.dzshzx.zeta.core.AccountsCore
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.HelperProgram
import com.dzshzx.zeta.core.HelperServe
import com.dzshzx.zeta.core.LinuxShell
import com.dzshzx.zeta.core.QuotaProbe
import com.dzshzx.zeta.core.ServeError
import com.dzshzx.zeta.core.TestDeps
import com.dzshzx.zeta.core.TestHelper
import com.dzshzx.zeta.core.removeTestDir
import com.dzshzx.zeta.core.waitUntil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 额度显示和账号切换依赖的 Claude Code 行为（2.1.328 实测），在这里钉住：升级 Claude Code 后跑一遍，变了就会失败， 核心读额度的
 * helper/accounts/quota.go、[QuotaProbe]、helper/accounts 跟着改。账号经真的核心（本机架构的 zeta-helper
 * serve）和应用里带的那份 claude-switch（构建出的本机架构那一份，和应用里一样核心说没装时装进临时 HOME），走的是应用里的 [AccountsCore]。
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

    @Volatile private var fiveHour = 0.25

    @Before
    fun setUp() {
        TestDeps.requireClaude()
        home = java.nio.file.Files.createTempDirectory("zeta-quota").toFile().canonicalFile
        File(home, ".claude").mkdirs()
        File(home, "proj").mkdirs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            runCatching { serve(ex) }
                .onFailure {
                    ex.sendResponseHeaders(500, -1)
                    ex.close()
                }
        }
        server.start()
    }

    @After
    fun tearDown() {
        core?.close()
        if (::server.isInitialized) server.stop(0)
        if (::home.isInitialized) removeTestDir(home)
    }

    private val env: Map<String, String>
        get() =
            mapOf(
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
        if (
            ex.requestMethod != "POST" ||
                !path.startsWith("/v1/messages") ||
                path.contains("count_tokens")
        ) {
            ex.sendResponseHeaders(404, -1)
            ex.close()
            return
        }
        synchronized(bearers) { bearers += ex.requestHeaders.getFirst("Authorization").orEmpty() }
        val body =
            json.parseToJsonElement(ex.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
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
            put("id", "msg_fake")
            put("type", "message")
            put("role", "assistant")
            put("model", "claude-fake")
            put("content", Json.parseToJsonElement("""[{"type":"text","text":"ok"}]"""))
            put("stop_reason", "end_turn")
            putJsonObject("usage") {
                put("input_tokens", 10)
                put("output_tokens", 5)
            }
        }
        val bytes =
            if (stream) {
                listOf(
                        "message_start" to
                            """{"type":"message_start","message":${JsonObject(message + ("content" to Json.parseToJsonElement("[]")))}}""",
                        "content_block_start" to
                            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                        "content_block_delta" to
                            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
                        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                        "message_delta" to
                            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}""",
                        "message_stop" to """{"type":"message_stop"}""",
                    )
                    .joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" }
                    .toByteArray()
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
        File(home, ".claude.json")
            .writeText("""{"hasCompletedOnboarding":true,"oauthAccount":${oauthAccount(name)}}""")
    }

    private fun credentials(name: String): String {
        val expires = System.currentTimeMillis() + 30L * 86_400_000
        return """{"claudeAiOauth":{"accessToken":"fake-token-$name","refreshToken":"fake-refresh-$name","expiresAt":$expires,
               "scopes":["user:inference","user:profile","user:sessions:claude_code"],"subscriptionType":"max","rateLimitTier":"default_claude_max_20x"}}"""
    }

    private fun oauthAccount(name: String) =
        """{"accountUuid":"acc-$name","emailAddress":"$name@example.com","organizationUuid":"org-$name","organizationName":"Org $name","displayName":"$name"}"""

    private fun configAccount(): String? =
        json
            .parseToJsonElement(File(home, ".claude.json").readText())
            .jsonObject["oauthAccount"]
            ?.jsonObject
            ?.get("accountUuid")
            ?.jsonPrimitive
            ?.content

    private val claudeSwitch = TestHelper.program(HelperProgram.CLAUDE_SWITCH)

    /** 临时 HOME 上的 bash：环境变量只有这几样，核心和它起的 claude-switch 都继承它们。 */
    private fun shell(): LinuxShell =
        object : LinuxShell {
            override val home: String = this@ClaudeCodeQuotaContractTest.home.path

            override fun start(command: String): Process {
                val builder = ProcessBuilder("bash", "-c", command).directory(File(home))
                builder.environment().apply {
                    clear()
                    put("HOME", home)
                    put("PATH", TestDeps.searchPath)
                    put("LANG", "C.UTF-8")
                    put(AppVariant.LINUX_NAME_ENV, "zeta")
                    putAll(env)
                }
                return builder.start()
            }

            override fun kill(process: Process) {
                process.destroyForcibly()
            }
        }

    /** 到临时 HOME 上核心的连接，第一次用时连上。 */
    private var core: HelperServe? = null

    private fun serve(): HelperServe =
        core
            ?: HelperServe(
                    name = "quota-contract",
                    open = {
                        HelperServe.open(shell(), TestHelper.program(HelperProgram.ZETA_HELPER))
                    },
                    subscriptions = emptyList(),
                    onMessage = {},
                    onProblem = {},
                )
                .also { core = it }

    private fun accounts() =
        AccountsCore(claudeSwitch, { m, p, t -> serve().call(m, p, t) }, ::shell)

    /** 一个 stream-json 的 claude 进程，参数和 Zeta 起对话时一样。 */
    private inner class Claude : AutoCloseable {
        val process: Process
        private val lines = LinkedBlockingQueue<String>()
        val seen = mutableListOf<JsonObject>()
        private var seq = 0

        init {
            val builder =
                ProcessBuilder(
                        "claude",
                        "-p",
                        "--input-format",
                        "stream-json",
                        "--output-format",
                        "stream-json",
                        "--verbose",
                        "--permission-prompt-tool",
                        "stdio",
                    )
                    .directory(File(home, "proj"))
            builder.environment().apply {
                clear()
                put("HOME", home.path)
                put("PATH", TestDeps.searchPath)
                put("LANG", "C.UTF-8")
                putAll(env)
            }
            builder.redirectError(File("/dev/null"))
            process = builder.start()
            Thread { process.inputStream.bufferedReader().forEachLine { lines.put(it) } }
                .apply {
                    isDaemon = true
                    start()
                }
            control(buildJsonObject { put("subtype", "initialize") })
        }

        fun send(o: JsonObject) {
            process.outputStream.write((o.toString() + "\n").toByteArray())
            process.outputStream.flush()
        }

        fun turn(prompt: String) {
            send(
                buildJsonObject {
                    put("type", "user")
                    putJsonObject("message") {
                        put("role", "user")
                        put("content", prompt)
                    }
                },
            )
            until { it.str("type") == "result" }
        }

        fun control(request: JsonObject): JsonObject {
            val id = "c${++seq}"
            send(
                buildJsonObject {
                    put("type", "control_request")
                    put("request_id", id)
                    put("request", request)
                },
            )
            var response: JsonObject? = null
            until { o ->
                (o.str("type") == "control_response" && o.obj("response")?.str("request_id") == id)
                    .also { if (it) response = o.obj("response") }
            }
            return response!!
        }

        fun status(label: String): String? =
            control(buildJsonObject { put("subtype", "get_status") })
                .obj("response")!!["sections"]!!
                .jsonArray
                .flatMap { it.jsonObject["rows"]!!.jsonArray }
                .map { it.jsonObject }
                .firstOrNull { it.str("label") == label }
                ?.str("value")

        private fun until(done: (JsonObject) -> Boolean) {
            val deadline = System.currentTimeMillis() + 60_000
            while (true) {
                val left = deadline - System.currentTimeMillis()
                assertTrue("claude 没有按时回应", left > 0)
                val line = lines.poll(left, TimeUnit.MILLISECONDS) ?: continue
                val o =
                    runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                        ?: continue
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

    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull

    @Test
    fun rateLimitEventsAndGetUsageCarryTheWindows() {
        login("a")
        Claude().use { claude ->
            claude.turn("hello")
            // 响应头里的 5 小时、每周窗口，原样（0～1 的比例、秒级重置时刻）出现在 rate_limit_event 的 unifiedWindows 里
            val event = claude.seen.last { it.str("type") == "rate_limit_event" }
            val unified = event.obj("rate_limit_info")!!.obj("unifiedWindows")!!
            val windows = listOf("five_hour", "seven_day").map { unified.obj(it)!! }
            assertEquals(0.25, windows[0].num("utilization")!!, 1e-9)
            assertEquals(0.4, windows[1].num("utilization")!!, 1e-9)
            assertTrue(windows.all { it.num("resetsAt")!! * 1000 > System.currentTimeMillis() })

            // 数变了才再发一条
            fiveHour = 0.35
            claude.turn("again")
            assertEquals(2, claude.seen.count { it.str("type") == "rate_limit_event" })

            // get_usage：claude.ai 的用量接口连不上时，退回响应头里的数（百分数、ISO 时刻）
            val usage =
                claude.control(
                    buildJsonObject {
                        put("subtype", "get_usage")
                        put("skip_behaviors", true)
                    },
                )
            assertEquals("success", usage.str("subtype"))
            val answer = usage.obj("response")!!
            assertEquals("max", answer.str("subscription_type"))
            val limits = answer.obj("rate_limits")!!
            // 没有服务器的额度行（limits），退回 five_hour、seven_day 这几项
            assertTrue(limits["limits"] !is JsonArray)
            assertEquals(35.0, limits.obj("five_hour")!!.num("utilization")!!, 1e-6)
            assertEquals(40.0, limits.obj("seven_day")!!.num("utilization")!!, 1e-6)

            // 开着的进程在 ~/.claude/sessions 里登记着（pid、启动时刻、状态），核心据此说清是谁挡着；claude-switch 也认出它，不让换
            assertEquals(
                "其他位置还运行着 1 个 Claude Code（如终端中的 claude），请先退出。",
                accounts().list().blocker,
            )
            assertEquals("claude", accounts().save())
            val refused = assertThrows(ServeError::class.java) { accounts().switch("claude") }
            assertEquals(refused.message, AccountsCore.BUSY, refused.code)
        }
    }

    @Test
    fun probeAnswersWithoutLeavingASession() {
        login("a")
        val answer = QuotaProbe.query(shell(), File(home, "proj").path)
        assertEquals("true", answer.str("rate_limits_available"))
        assertEquals("max", answer.str("subscription_type"))
        // 不留会话记录；退出后 ~/.claude/sessions 里的登记也删了
        assertEquals(
            emptyList<String>(),
            File(home, ".claude/projects")
                .walkTopDown()
                .filter { it.isFile }
                .map { it.name }
                .toList(),
        )
        assertEquals(
            emptyList<String>(),
            File(home, ".claude/sessions").listFiles().orEmpty().map { it.name },
        )
        // 没有发过模型请求
        assertEquals(emptyList<String>(), synchronized(bearers) { bearers.toList() })
    }

    @Test
    fun switchedLoginIsWhatTheNextProcessUses() {
        login("a")
        assertEquals("claude", accounts().save())
        // 添加账号：claude 存档并退出，新登录记成 claude-b（应用经 accounts.login_start 做这一步，再起 claude auth
        // login；这里直接登录假账号）
        val prepared =
            serve()
                .call(
                    "accounts.prepare_login",
                    buildJsonObject { put("program", claudeSwitch.relativePath) },
                    AccountsCore.TIMEOUT_MS,
                )
        assertEquals("claude-b", (prepared as JsonObject).str("name"))
        assertTrue(!accounts().list().loggedIn)
        login("b")
        assertEquals("claude-b", accounts().save())
        assertEquals(listOf("claude-b", "claude"), accounts().list().all.map { it.id })
        accounts().switch("claude")
        Claude().use { claude ->
            claude.turn("hello")
            assertEquals("a@example.com", claude.status("Email"))
            assertEquals("Org a", claude.status("Organization"))
        }
        assertEquals(listOf("Bearer fake-token-a"), synchronized(bearers) { bearers.toList() })
        assertEquals("acc-a", configAccount())
    }

    /**
     * 绕过进程检查换成 [name]，和 claude-switch 一样拿着 Claude Code 的 ~/.claude.json.lock（一个目录，建成的拿着）改两个文件：先改
     * ~/.claude.json 再改凭据，各自原子替换。Claude Code 自己改 ~/.claude.json
     * 也是先拿这把锁再读改写，不拿锁的替换要是落在它读和写之间，就被它用读到的旧内容盖掉。
     */
    private fun switchLogin(name: String) {
        val lock = File(home, ".claude.json.lock")
        assertTrue("~/.claude.json.lock 一直被占着", waitUntil { lock.mkdir() })
        try {
            val config = json.parseToJsonElement(File(home, ".claude.json").readText()).jsonObject
            File(home, ".claude.json.tmp")
                .writeText(
                    JsonObject(
                            config +
                                ("oauthAccount" to json.parseToJsonElement(oauthAccount(name))),
                        )
                        .toString(),
                )
            assertTrue(File(home, ".claude.json.tmp").renameTo(File(home, ".claude.json")))
            File(home, ".claude/.credentials.json.tmp").writeText(credentials(name))
            assertTrue(
                File(home, ".claude/.credentials.json.tmp")
                    .renameTo(File(home, ".claude/.credentials.json")),
            )
        } finally {
            lock.delete()
        }
    }

    /**
     * 开着的 Claude Code 进程（闲着的）碰上换号时怎样（2.1.330 实测）：
     * - 每次请求前比对 ~/.claude/.credentials.json 的修改时间（stat 的 mtimeMs，精确到毫秒以下），变了才丢掉内存里的令牌重读，所以下一轮就用新令牌；
     * - 凭据变了它不会把旧账号写回 ~/.claude.json，它记着的账号随它每秒一次看 ~/.claude.json 换成新的；
     * - 它自己改 ~/.claude.json 都拿着 ~/.claude.json.lock 读改写，比如第一次收到回应后记
     *   cachedExtraUsageDisabledReason（不等写完就输出 result）。claude-switch 拿着这把锁换号，不会被它盖掉。
     *
     * 换号时要先拿这把锁：不拿锁、第一轮一结束就换，会正好撞上上面那次写，被它用读到的旧内容盖回旧账号。 有 Claude Code
     * 开着时仍然不换号，原因不在这几条，在按账号统计用量（helper/accounts 的 Blocker，见 claude-switch 的 README「为什么是冷切换」）。
     */
    @Test
    fun anOpenProcessFollowsASwitchedLogin() {
        login("a")
        Claude().use { claude ->
            claude.turn("hello")
            // 第一轮刚结束：它那次读改写可能还没落盘，拿着锁换就和它排开了先后
            switchLogin("b")
            claude.turn("again")
            assertEquals(
                listOf("Bearer fake-token-a", "Bearer fake-token-b"),
                synchronized(bearers) { bearers.toList() },
            )
            // 它记着的账号要等它下一次看 ~/.claude.json（每秒一次）才换；限时只防卡死
            assertTrue(
                "开着的进程一直报旧账号",
                waitUntil { claude.status("Email") == "b@example.com" },
            )
            assertEquals("Org b", claude.status("Organization"))
            assertEquals("acc-b", configAccount())
        }
        assertEquals("acc-b", configAccount())
    }
}
