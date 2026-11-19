package com.dzshzx.zeta.core.claude

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 工具权限页和自动标题依赖的 Claude Code 行为（2.1.327 实测），在这里钉住：升级 Claude Code 后跑一遍，变了就会失败，
 * 页面上那句说明（[ClaudePermissions.SEMANTICS]）和 [com.dzshzx.zeta.core.AutoTitle] 跟着改。
 *
 * 真的 claude 命令行，跑在临时 HOME 上，连本机一个假的 Messages API（假 key，不出网、不花钱）：
 * 假模型按剧本调用工具，从下一次请求里的 tool_result 看结局；标题请求按系统提示认出来。
 */
class ClaudeCodeContractTest {
    private lateinit var home: File
    private lateinit var server: HttpServer
    private val json = Json { ignoreUnknownKeys = true }

    /** 主对话要依次调用的工具（名字、参数）。 */
    @Volatile private var script: List<Pair<String, JsonObject>> = emptyList()

    /** 最近一次主对话请求里的 tool_result（内容、是否出错），按顺序。 */
    @Volatile private var results: List<Pair<String, Boolean>> = emptyList()
    private val titleRequests = mutableListOf<String>()

    @Before
    fun setUp() {
        home = java.nio.file.Files.createTempDirectory("zeta-contract").toFile().canonicalFile
        File(home, ".claude").mkdirs()
        File(home, "proj").mkdirs()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> runCatching { serve(ex) }.onFailure { ex.sendResponseHeaders(500, -1); ex.close() } }
        server.start()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop(0)
        if (::home.isInitialized) home.deleteRecursively()
    }

    private fun claudeAvailable(): Boolean {
        val path = listOfNotNull(System.getProperty("user.home")?.let { "$it/.local/bin" }, System.getenv("PATH")).joinToString(":")
        return path.split(':').any { File(it, "claude").canExecute() }
    }

    private fun serve(ex: HttpExchange) {
        if (ex.requestMethod != "POST" || !ex.requestURI.path.startsWith("/v1/messages") || ex.requestURI.path.contains("count_tokens")) {
            ex.sendResponseHeaders(404, -1)
            ex.close()
            return
        }
        val body = json.parseToJsonElement(ex.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
        val system = when (val s = body["system"]) {
            is JsonPrimitive -> s.content
            is JsonArray -> s.joinToString(" ") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content.orEmpty() }
            else -> ""
        }
        val messages = body["messages"] as? JsonArray ?: JsonArray(emptyList())
        val tools = (body["tools"] as? JsonArray).orEmpty()
        val reply: List<JsonObject>
        val stop: String
        when {
            "naming a coding session" in system -> {
                synchronized(titleRequests) { titleRequests += messages.toString() }
                reply = listOf(text("""{"title":"假标题 一号"}"""))
                stop = "end_turn"
            }
            tools.isEmpty() -> {
                reply = listOf(text("ok"))
                stop = "end_turn"
            }
            else -> {
                val seen = messages.flatMap { m ->
                    ((m as JsonObject)["content"] as? JsonArray).orEmpty().mapNotNull { b ->
                        (b as? JsonObject)?.takeIf { it["type"]?.jsonPrimitive?.content == "tool_result" }?.let { r ->
                            val content = when (val c = r["content"]) {
                                is JsonPrimitive -> c.content
                                is JsonArray -> c.joinToString(" ") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content.orEmpty() }
                                else -> ""
                            }
                            content to (r["is_error"]?.jsonPrimitive?.content == "true")
                        }
                    }
                }
                results = seen
                val next = script.getOrNull(seen.size)
                if (next != null) {
                    reply = listOf(buildJsonObject { put("type", "tool_use"); put("id", "toolu_${seen.size}"); put("name", next.first); put("input", next.second) })
                    stop = "tool_use"
                } else {
                    reply = listOf(text("done"))
                    stop = "end_turn"
                }
            }
        }
        val stream = body["stream"]?.jsonPrimitive?.content == "true"
        val bytes = (if (stream) sse(reply, stop) else message(reply, stop).toString()).toByteArray()
        ex.responseHeaders.add("Content-Type", if (stream) "text/event-stream" else "application/json")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun text(value: String) = buildJsonObject { put("type", "text"); put("text", value) }

    private fun message(content: List<JsonObject>, stop: String) = buildJsonObject {
        put("id", "msg_fake"); put("type", "message"); put("role", "assistant"); put("model", "claude-fake")
        put("content", JsonArray(content)); put("stop_reason", stop)
        putJsonObject("usage") { put("input_tokens", 10); put("output_tokens", 5) }
    }

    private fun sse(content: List<JsonObject>, stop: String): String = buildString {
        fun event(data: JsonObject) = append("event: ").append(data["type"]!!.jsonPrimitive.content).append("\ndata: ").append(data).append("\n\n")
        event(buildJsonObject {
            put("type", "message_start")
            put("message", message(emptyList(), stop).let { JsonObject(it + ("stop_reason" to kotlinx.serialization.json.JsonNull)) })
        })
        content.forEachIndexed { i, block ->
            val isTool = block["type"]!!.jsonPrimitive.content == "tool_use"
            event(buildJsonObject {
                put("type", "content_block_start"); put("index", i)
                put("content_block", if (isTool) JsonObject(block + ("input" to JsonObject(emptyMap()))) else text(""))
            })
            event(buildJsonObject {
                put("type", "content_block_delta"); put("index", i)
                putJsonObject("delta") {
                    if (isTool) { put("type", "input_json_delta"); put("partial_json", block["input"].toString()) } else { put("type", "text_delta"); put("text", block["text"]!!) }
                }
            })
            event(buildJsonObject { put("type", "content_block_stop"); put("index", i) })
        }
        event(buildJsonObject {
            put("type", "message_delta")
            putJsonObject("delta") { put("stop_reason", stop) }
            putJsonObject("usage") { put("output_tokens", 5) }
        })
        event(buildJsonObject { put("type", "message_stop") })
    }

    /** 一个 stream-json 的 claude 进程，和 Zeta 起的一样（--permission-prompt-tool stdio）。 */
    private inner class Claude(mode: String, settings: String) : AutoCloseable {
        private val process: Process
        private val lines = LinkedBlockingQueue<String>()
        val asks = mutableListOf<String>()
        private var seq = 0

        init {
            File(home, ".claude/settings.json").writeText(settings)
            val builder = ProcessBuilder(
                "claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                "--permission-prompt-tool", "stdio", "--permission-mode", mode,
            ).directory(File(home, "proj"))
            val env = builder.environment()
            val path = listOfNotNull(System.getProperty("user.home")?.let { "$it/.local/bin" }, System.getenv("PATH")).joinToString(":")
            env.clear()
            env["HOME"] = home.path
            env["PATH"] = path
            env["LANG"] = "C.UTF-8"
            env["ANTHROPIC_API_KEY"] = "fake-key-1"
            env["ANTHROPIC_BASE_URL"] = "http://127.0.0.1:${server.address.port}"
            env["NO_PROXY"] = "127.0.0.1,localhost"
            env["DISABLE_TELEMETRY"] = "1"
            env["DISABLE_ERROR_REPORTING"] = "1"
            env["DISABLE_AUTOUPDATER"] = "1"
            builder.redirectError(File("/dev/null"))
            process = builder.start()
            Thread { process.inputStream.bufferedReader().forEachLine { lines.put(it) } }.apply { isDaemon = true; start() }
            send(buildJsonObject { put("type", "control_request"); put("request_id", "init"); putJsonObject("request") { put("subtype", "initialize") } })
        }

        fun send(o: JsonObject) {
            process.outputStream.write((o.toString() + "\n").toByteArray())
            process.outputStream.flush()
        }

        /** 发一条用户消息，一路答“拒绝”，直到这一轮结束。 */
        fun turn(prompt: String) {
            send(buildJsonObject { put("type", "user"); putJsonObject("message") { put("role", "user"); put("content", prompt) } })
            until { o ->
                if (o.str("type") == "control_request" && o.obj("request")?.str("subtype") == "can_use_tool") {
                    asks += o.obj("request")!!.str("tool_name")!!
                    send(buildJsonObject {
                        put("type", "control_response")
                        putJsonObject("response") {
                            put("subtype", "success"); put("request_id", o.str("request_id")!!)
                            putJsonObject("response") { put("behavior", "deny"); put("message", "USER-DENIED") }
                        }
                    })
                }
                o.str("type") == "result"
            }
        }

        /** 发一个控制请求，等到它的应答。 */
        fun control(request: JsonObject): JsonObject {
            val id = "c${++seq}"
            send(buildJsonObject { put("type", "control_request"); put("request_id", id); put("request", request) })
            var response: JsonObject? = null
            until { o ->
                (o.str("type") == "control_response" && o.obj("response")?.str("request_id") == id).also { if (it) response = o.obj("response") }
            }
            return response!!
        }

        private fun until(done: (JsonObject) -> Boolean) {
            val deadline = System.currentTimeMillis() + 60_000
            while (true) {
                val left = deadline - System.currentTimeMillis()
                assertTrue("claude 没有按时回应", left > 0)
                val line = lines.poll(left, TimeUnit.MILLISECONDS) ?: continue
                val o = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
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

    private fun bash(command: String) = "Bash" to buildJsonObject { put("command", command); put("description", "test") }

    private fun transcriptTitles(): List<String> = home.resolve(".claude/projects").walkTopDown().filter { it.name.endsWith(".jsonl") }
        .flatMap { it.readLines() }
        .mapNotNull { runCatching { json.parseToJsonElement(it) as JsonObject }.getOrNull() }
        .filter { it.str("type") in setOf("ai-title", "custom-title") }
        .map { it.str("type") + ":" + (it.str("aiTitle") ?: it.str("customTitle")) }
        // Claude Code 退出前会把标题行再补写一遍
        .distinct()
        .toList()

    @Test
    fun bypassModeStillHonoursDenyAndAsk() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        script = listOf(bash("touch denied.txt"), bash("mkdir asked"), bash("touch -c free.txt"))
        Claude("bypassPermissions", """{"permissions":{"deny":["Bash(touch denied.txt)"],"ask":["Bash(mkdir:*)"]}}""").use { claude ->
            claude.turn("run the tools")
            // 拒绝：不问就拒；询问：照样问（这里答了拒绝）；别的照常放行
            assertEquals(listOf("Bash"), claude.asks)
            assertTrue(results[0].toString(), results[0].second && "denied" in results[0].first)
            assertEquals("USER-DENIED" to true, results[1])
            assertEquals(false, results[2].second)
            assertTrue(File(home, "proj/denied.txt").exists().not())

            // -p 模式不会自己起标题
            assertEquals(emptyList<String>(), synchronized(titleRequests) { titleRequests.toList() })
            // generate_session_title 带 persist：用小模型起一个，写进会话记录（ai-title），应答里带回
            val title = claude.control(buildJsonObject {
                put("subtype", "generate_session_title"); put("description", "帮我给设置页加一个工具权限页面\n\ndone"); put("persist", true)
            })
            assertEquals("假标题 一号", title.obj("response")?.str("title"))
            assertEquals(1, synchronized(titleRequests) { titleRequests.size })
        }
        assertEquals(listOf("ai-title:假标题 一号"), transcriptTitles())
    }

    @Test
    fun askBeatsAllowAndCustomTitlesStay() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        script = listOf(bash("touch made.txt"))
        Claude("default", """{"permissions":{"allow":["Bash(touch:*)"],"ask":["Bash"]}}""").use { claude ->
            claude.turn("run the tools")
            // 细的允许压不过粗的询问
            assertEquals(listOf("Bash"), claude.asks)
            assertEquals("USER-DENIED" to true, results[0])

            claude.control(buildJsonObject { put("subtype", "rename_session"); put("title", "我起的名字"); put("source", "host") })
            val title = claude.control(buildJsonObject {
                put("subtype", "generate_session_title"); put("description", "帮我给设置页加一个工具权限页面"); put("persist", true)
            })
            // 已经有自定义标题：原样返回，不另起
            assertEquals("我起的名字", title.obj("response")?.str("title"))
            // 内容太短不起
            val short = claude.control(buildJsonObject { put("subtype", "generate_session_title"); put("description", "你好"); put("persist", false) })
            assertEquals(null, short.obj("response")?.str("title"))
        }
        assertEquals(emptyList<String>(), synchronized(titleRequests) { titleRequests.toList() })
        assertEquals(listOf("custom-title:我起的名字"), transcriptTitles())
    }
}
