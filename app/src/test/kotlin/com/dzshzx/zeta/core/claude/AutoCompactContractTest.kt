package com.dzshzx.zeta.core.claude

import com.dzshzx.zeta.core.AutoCompact
import com.dzshzx.zeta.core.removeTestDir
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 自动压缩设置（[AutoCompact]）依赖的 Claude Code 行为（2.1.328 实测），在这里钉住：升级 Claude Code 后跑一遍，变了就会失败，
 * [AutoCompact] 和设置页“上下文”一组的说明跟着改。
 *
 * 真的 claude 命令行，跑在临时 HOME 上，连本机一个假的 Messages API（假 key，不出网、不花钱）。假 API 报的 usage 是“固定开销 + 请求里消息的字数 /
 * 4”，和 Claude Code 自己估的差不多，所以压缩前后的估算对得上，压缩能真的做成。 每轮发约 2 万 token 的消息；压缩窗口按手机上的写法放在
 * settings.json（autoCompactWindow），设成 10 万，几轮就能到压缩点。 判断点都离压缩点 5 千以上，Claude Code 估算方式的小变化不会让测试误报。
 */
class AutoCompactContractTest {
    private lateinit var home: File
    private lateinit var server: HttpServer
    private val json = Json { ignoreUnknownKeys = true }

    /** 假 API 给主对话请求报的固定开销（系统提示、工具定义之类）。 */
    @Volatile private var baseTokens = 15_000L
    private val requests = AtomicInteger()

    @Before
    fun setUp() {
        home = java.nio.file.Files.createTempDirectory("zeta-compact").toFile().canonicalFile
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
        if (::server.isInitialized) server.stop(0)
        if (::home.isInitialized) removeTestDir(home)
    }

    private fun searchPath() =
        listOfNotNull(
                System.getProperty("user.home")?.let { "$it/.local/bin" },
                System.getenv("PATH"),
            )
            .joinToString(":")

    private fun claudeAvailable(): Boolean =
        searchPath().split(':').any { File(it, "claude").canExecute() }

    private fun serve(ex: HttpExchange) {
        if (
            ex.requestMethod != "POST" ||
                !ex.requestURI.path.startsWith("/v1/messages") ||
                ex.requestURI.path.contains("count_tokens")
        ) {
            ex.sendResponseHeaders(404, -1)
            ex.close()
            return
        }
        val body =
            json.parseToJsonElement(ex.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
        val messages = body["messages"] as? JsonArray ?: JsonArray(emptyList())
        val last = (messages.lastOrNull() as? JsonObject)?.get("content")
        val lastText =
            when (last) {
                is JsonPrimitive -> last.content
                is JsonArray ->
                    last.joinToString(" ") {
                        (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
                else -> ""
            }
        // 压缩请求最后一条是要它写摘要的指令
        val summary = "summary" in lastText.lowercase()
        val text = if (summary) "<summary>fake summary</summary>" else "ok"
        val input = if (summary) 100L else baseTokens + messages.toString().length / 4
        val id = "msg_fake_${requests.incrementAndGet()}"
        val stream = body["stream"]?.jsonPrimitive?.booleanOrNull == true
        val bytes =
            (if (stream) sse(id, text, input) else message(id, text, input, done = true).toString())
                .toByteArray()
        ex.responseHeaders.add(
            "Content-Type",
            if (stream) "text/event-stream" else "application/json",
        )
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** 每条回复的 id 都不同：Claude Code 按 id 把消息分组，id 一样会当成同一轮，压缩时找不到可总结的内容。 */
    private fun message(id: String, text: String, input: Long, done: Boolean) = buildJsonObject {
        put("id", id)
        put("type", "message")
        put("role", "assistant")
        put("model", "claude-fake")
        put(
            "content",
            if (done)
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    ),
                )
            else JsonArray(emptyList()),
        )
        put("stop_reason", if (done) JsonPrimitive("end_turn") else JsonNull)
        putJsonObject("usage") {
            put("input_tokens", input)
            put("output_tokens", 5)
            put("cache_read_input_tokens", 0)
            put("cache_creation_input_tokens", 0)
        }
    }

    private fun sse(id: String, text: String, input: Long): String = buildString {
        fun event(data: JsonObject) =
            append("event: ")
                .append(data["type"]!!.jsonPrimitive.content)
                .append("\ndata: ")
                .append(data)
                .append("\n\n")
        event(
            buildJsonObject {
                put("type", "message_start")
                put("message", message(id, text, input, done = false))
            },
        )
        event(
            buildJsonObject {
                put("type", "content_block_start")
                put("index", 0)
                putJsonObject("content_block") {
                    put("type", "text")
                    put("text", "")
                }
            },
        )
        event(
            buildJsonObject {
                put("type", "content_block_delta")
                put("index", 0)
                putJsonObject("delta") {
                    put("type", "text_delta")
                    put("text", text)
                }
            },
        )
        event(
            buildJsonObject {
                put("type", "content_block_stop")
                put("index", 0)
            },
        )
        event(
            buildJsonObject {
                put("type", "message_delta")
                putJsonObject("delta") { put("stop_reason", "end_turn") }
                putJsonObject("usage") { put("output_tokens", 5) }
            },
        )
        event(buildJsonObject { put("type", "message_stop") })
    }

    /** 一个 stream-json 的 claude 进程，和 Zeta 起的一样；[env] 是 [AutoCompact.env] 给的那些。 */
    private inner class Claude(settings: String, env: Map<String, String>, debug: Boolean = false) :
        AutoCloseable {
        private val process: Process
        private val lines = LinkedBlockingQueue<String>()
        private var seq = 0

        init {
            File(home, ".claude/settings.json").writeText(settings)
            val builder =
                ProcessBuilder(
                        listOf(
                            "claude",
                            "-p",
                            "--input-format",
                            "stream-json",
                            "--output-format",
                            "stream-json",
                            "--verbose",
                            "--permission-mode",
                            "default",
                        ) + if (debug) listOf("--debug") else emptyList(),
                    )
                    .directory(File(home, "proj"))
            val e = builder.environment()
            e.clear()
            e["HOME"] = home.path
            e["PATH"] = searchPath()
            e["LANG"] = "C.UTF-8"
            e["ANTHROPIC_API_KEY"] = "fake-key-1"
            e["ANTHROPIC_BASE_URL"] = "http://127.0.0.1:${server.address.port}"
            e["NO_PROXY"] = "127.0.0.1,localhost"
            e["DISABLE_TELEMETRY"] = "1"
            e["DISABLE_ERROR_REPORTING"] = "1"
            e["DISABLE_AUTOUPDATER"] = "1"
            e.putAll(env)
            builder.redirectError(File("/dev/null"))
            process = builder.start()
            Thread { process.inputStream.bufferedReader().forEachLine { lines.put(it) } }
                .apply {
                    isDaemon = true
                    start()
                }
            control(buildJsonObject { put("subtype", "initialize") })
        }

        private fun send(o: JsonObject) {
            process.outputStream.write((o.toString() + "\n").toByteArray())
            process.outputStream.flush()
        }

        /** 发一条用户消息，收下这一轮的全部事件，直到 result。 */
        fun turn(prompt: String): List<JsonObject> {
            send(
                buildJsonObject {
                    put("type", "user")
                    putJsonObject("message") {
                        put("role", "user")
                        put("content", prompt)
                    }
                },
            )
            val events = ArrayList<JsonObject>()
            until { o ->
                events += o
                o.str("type") == "result"
            }
            return events
        }

        /** 发一个控制请求，返回应答里的 response。 */
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
            return response!!.obj("response") ?: JsonObject(emptyMap())
        }

        fun contextUsage() = control(buildJsonObject { put("subtype", "get_context_usage") })

        private fun until(done: (JsonObject) -> Boolean) {
            val deadline = System.currentTimeMillis() + 90_000
            while (true) {
                val left = deadline - System.currentTimeMillis()
                assertTrue("claude 没有按时回应", left > 0)
                val line = lines.poll(left, TimeUnit.MILLISECONDS) ?: continue
                val o =
                    runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                        ?: continue
                if (done(o)) return
            }
        }

        override fun close() {
            runCatching { process.outputStream.close() }
            if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

    /** 约 2 万 token 的一条消息。 */
    private fun bigPrompt(n: Int) = "hello $n " + "lorem ipsum dolor sit amet ".repeat(3000)

    /** 这一轮里有没有开始压缩（成不成都算）。 */
    private fun List<JsonObject>.compacting() = any {
        it.str("type") == "system" &&
            it.str("subtype") == "status" &&
            it.str("status") == "compacting"
    }

    private fun List<JsonObject>.boundary(): JsonObject? = firstOrNull {
        it.str("type") == "system" && it.str("subtype") == "compact_boundary"
    }
        ?.obj("compact_metadata")

    /** 手机上 settings.json 的样子：开着自动压缩，自己设了压缩窗口。 */
    private fun phoneSettings(window: Long = 100_000) =
        """{"autoCompactEnabled":true,"autoCompactWindow":$window}"""

    @Test
    fun defaultCompactsAtWindowMinusBuffers() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        Claude(phoneSettings(), AutoCompact.env(enabled = true, percent = null)).use { claude ->
            val usage = claude.contextUsage()
            assertEquals(100_000L, usage.long("maxTokens"))
            // 窗口 − min(最大输出, 2 万) − 1.3 万
            assertEquals(67_000L, usage.long("autoCompactThreshold"))
            assertEquals(true, usage.bool("isAutoCompactEnabled"))
            assertEquals("settings", usage.str("autocompactSource"))
            // 约 3.8 万、5.8 万：都在 6.7 万以下
            assertFalse(claude.turn(bigPrompt(0)).compacting())
            assertFalse(claude.turn(bigPrompt(1)).compacting())
            // 下一次请求约 7.8 万，发之前先压缩
            val boundary = claude.turn(bigPrompt(2)).boundary()
            assertEquals("auto", boundary?.str("trigger"))
        }
    }

    @Test
    fun percentOverrideCompactsEarlierButIsNotReported() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        Claude(phoneSettings(), AutoCompact.env(enabled = true, percent = 60)).use { claude ->
            val usage = claude.contextUsage()
            // get_context_usage 报的压缩点不含百分比，核心自己算（helper/usage 的 compactThreshold）：6.7 万的 60% 档是 4.8
            // 万
            assertEquals(67_000L, usage.long("autoCompactThreshold"))
            assertFalse(claude.turn(bigPrompt(0)).compacting())
            // 下一次请求约 5.8 万：过了 4.8 万，没到 6.7 万，照样开始压缩
            assertTrue(claude.turn(bigPrompt(1)).compacting())
        }
    }

    @Test
    fun disableSwitchWinsOverSettingsAndKeepsManualCompact() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        Claude(phoneSettings(), AutoCompact.env(enabled = false, percent = 60)).use { claude ->
            val usage = claude.contextUsage()
            assertEquals(false, usage.bool("isAutoCompactEnabled"))
            assertNull(usage["autoCompactThreshold"])
            // 关着时窗口回到模型本身的，不再是设置里的压缩窗口
            assertTrue(usage.toString(), (usage.long("maxTokens") ?: 0) > 100_000)
            // 约 7.8 万，过了 6.7 万也不压缩
            repeat(3) { n -> assertFalse(claude.turn(bigPrompt(n)).compacting()) }
            // 手动压缩照常
            assertEquals("manual", claude.turn("/compact").boundary()?.str("trigger"))
        }
        // 没改用户的配置
        assertEquals(phoneSettings(), File(home, ".claude/settings.json").readText())
        assertFalse(File(home, ".claude.json").readText().contains("autoCompactEnabled"))
    }

    @Test
    fun claudeCodeOwnSwitchStillApplies() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        // Zeta 这边开着（不带环境变量）时，用户在 Claude Code 里关掉的照样关着：设置页和用量弹窗的说明照这个写
        Claude("""{"autoCompactEnabled":false}""", AutoCompact.env(enabled = true, percent = null))
            .use { claude ->
                assertEquals(false, claude.contextUsage().bool("isAutoCompactEnabled"))
            }
    }

    /** 压缩点的准确数：让“固定开销”大过压缩点，Claude Code 会在调试日志里写出它用的压缩点（含百分比）。 */
    private fun loggedThreshold(window: Long, percent: Int?): Long? {
        baseTokens = window - 10_000
        Claude(
                phoneSettings(window),
                AutoCompact.env(enabled = true, percent = percent),
                debug = true,
            )
            .use { claude ->
                claude.turn("hello 0")
                claude.turn("hello 1")
            }
        val pattern = Regex("autocompact: fixed prefix ~\\d+ > threshold (\\d+)")
        return File(home, ".claude/debug")
            .walkTopDown()
            .filter { it.isFile }
            .flatMap { it.readLines().asSequence() }
            .firstNotNullOfOrNull { pattern.find(it)?.groupValues?.get(1)?.toLong() }
    }

    @Test
    fun percentThresholdMatchesZetaFormula() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        // 手机上的压缩窗口是 40 万：默认 36.7 万，70% 是 ⌊38 万 × 0.7⌋（核心的 compactThreshold 照这个算式）
        assertEquals(266_000L, loggedThreshold(400_000, 70))
    }

    @Test
    fun defaultThresholdMatchesReported() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        assertEquals(67_000L, loggedThreshold(100_000, null))
    }
}
