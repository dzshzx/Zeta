package com.dzshzx.zeta.core.claude

import com.dzshzx.zeta.phone.tools.ScheduleTools
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 后台运行不让 Claude 管理定时任务，靠的是 Claude Code 的这些行为（2.1.328 实测），在这里钉住：升级 Claude Code 后跑一遍，
 * 变了就会失败，Conversation.restrict 和 [ScheduleTools.unattendedRules] 跟着改。
 *
 * 真的 claude 命令行跑在临时 HOME 上，连本机一个假的 Messages API（假 key，不出网、不花钱），做法同 [ClaudeCodeContractTest]；
 * 手机工具是 SDK 类型的 MCP 服务器 phone，由这里像 Zeta 那样应答，工具定义就是 [ScheduleTools] 的。钉住的是：
 * - 新起的进程：initialize 之后不等应答，紧接着发 apply_flag_settings（permissions 是 [ScheduleTools.unattendedRules]），
 *   再发第一条消息，第一轮就生效：上报工具不弹 can_use_tool；被拒的工具硬调得到 No such tool available，请求到不了 phone 服务器，
 *   用户 settings.json 里的允许也压不过。
 * - 之后单独改 effortLevel 不动这一层。permissions: null 只撤这一层：settings.json 里的规则、effortLevel 照旧，
 *   被拒过的工具照常要确认、照常调得到。
 * - 已经开着的进程再加上规则、紧接着发消息，这一轮就生效。
 * - 整个进程只取一次工具清单（tools/list）：规则怎么变都不重取。
 */
class UnattendedRulesContractTest {
    private lateinit var home: File
    private lateinit var server: HttpServer
    private val json = Json { ignoreUnknownKeys = true }

    /** 这一轮假模型要依次调用的工具（手机工具的短名）；调完一个、看到它的结果再调下一个。 */
    private val script = LinkedBlockingQueue<String>()

    /** 假模型发出、还没看到结果的那次调用。 */
    @Volatile private var awaiting: Pair<String, String>? = null

    /** 看到的工具结果：工具短名、内容、是否出错，按顺序。 */
    private val results = mutableListOf<Triple<String, String, Boolean>>()
    private var seq = 0

    @Before
    fun setUp() {
        home = java.nio.file.Files.createTempDirectory("zeta-rules-contract").toFile().canonicalFile
        File(home, ".claude").mkdirs()
        File(home, "proj").mkdirs()
        // 用户自己的规则：允许 run_scheduled_task（后台运行的拒绝要压过它），拒绝 WebSearch（撤规则时不能连带撤掉）
        File(home, ".claude/settings.json").writeText(
            """{"permissions":{"allow":["mcp__phone__run_scheduled_task"],"deny":["WebSearch"]}}""",
        )
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

    // ---- 假的 Messages API ----

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
        val reply = if ("naming a coding session" in system || (body["tools"] as? JsonArray).isNullOrEmpty()) {
            text("""{"title":"t"}""") to "end_turn"
        } else {
            synchronized(results) { next(body["messages"] as? JsonArray ?: JsonArray(emptyList())) }
        }
        val stream = body["stream"]?.jsonPrimitive?.content == "true"
        val bytes = (if (stream) sse(reply.first, reply.second) else message(listOf(reply.first), reply.second).toString()).toByteArray()
        ex.responseHeaders.add("Content-Type", if (stream) "text/event-stream" else "application/json")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** 主对话的下一步：上一次调用的结果到了就记下，剧本里还有就接着调，没有就说完。 */
    private fun next(messages: JsonArray): Pair<JsonObject, String> {
        awaiting?.let { (id, name) ->
            val result = messages.flatMap { ((it as JsonObject)["content"] as? JsonArray).orEmpty() }
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it.str("type") == "tool_result" && it.str("tool_use_id") == id }
            if (result != null) {
                val content = when (val c = result["content"]) {
                    is JsonPrimitive -> c.content
                    is JsonArray -> c.joinToString(" ") { (it as? JsonObject)?.str("text").orEmpty() }
                    else -> ""
                }
                results += Triple(name, content, result.str("is_error") == "true")
                awaiting = null
            }
        }
        if (awaiting == null) {
            script.poll()?.let { name ->
                val id = "toolu_rules_${++seq}"
                awaiting = id to name
                return buildJsonObject {
                    put("type", "tool_use"); put("id", id); put("name", McpNames.tool("phone", name)); put("input", JsonObject(emptyMap()))
                } to "tool_use"
            }
        }
        return text("done") to "end_turn"
    }

    private fun text(value: String) = buildJsonObject { put("type", "text"); put("text", value) }

    private fun message(content: List<JsonObject>, stop: String) = buildJsonObject {
        put("id", "msg_fake"); put("type", "message"); put("role", "assistant"); put("model", "claude-fake")
        put("content", JsonArray(content)); put("stop_reason", stop)
        putJsonObject("usage") { put("input_tokens", 10); put("output_tokens", 5) }
    }

    private fun sse(block: JsonObject, stop: String): String = buildString {
        fun event(data: JsonObject) = append("event: ").append(data["type"]!!.jsonPrimitive.content).append("\ndata: ").append(data).append("\n\n")
        event(buildJsonObject { put("type", "message_start"); put("message", JsonObject(message(emptyList(), stop) + ("stop_reason" to JsonNull))) })
        val isTool = block.str("type") == "tool_use"
        event(buildJsonObject {
            put("type", "content_block_start"); put("index", 0)
            put("content_block", if (isTool) JsonObject(block + ("input" to JsonObject(emptyMap()))) else text(""))
        })
        event(buildJsonObject {
            put("type", "content_block_delta"); put("index", 0)
            putJsonObject("delta") {
                if (isTool) { put("type", "input_json_delta"); put("partial_json", block["input"].toString()) } else { put("type", "text_delta"); put("text", block["text"]!!) }
            }
        })
        event(buildJsonObject { put("type", "content_block_stop"); put("index", 0) })
        event(buildJsonObject {
            put("type", "message_delta")
            putJsonObject("delta") { put("stop_reason", stop) }
            putJsonObject("usage") { put("output_tokens", 5) }
        })
        event(buildJsonObject { put("type", "message_stop") })
    }

    // ---- 像 Zeta 那样驱动一个进程 ----

    /** 一个 stream-json 的 claude 进程，参数和 Zeta 起的一样；phone 服务器的 MCP 消息和 can_use_tool 由这里答（一律允许）。 */
    private inner class Claude : AutoCloseable {
        private val process: Process
        private val lines = LinkedBlockingQueue<String>()
        private var requests = 0
        val asks = mutableListOf<String>()
        val calls = mutableListOf<String>()
        var toolLists = 0

        init {
            val builder = ProcessBuilder(
                "claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                "--permission-prompt-tool", "stdio", "--permission-mode", "default",
                "--mcp-config", """{"mcpServers":{"phone":{"type":"sdk","name":"phone"}}}""",
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
            Thread { runCatching { process.inputStream.bufferedReader().forEachLine { lines.put(it) } } }.apply { isDaemon = true; start() }
        }

        fun send(o: JsonObject) {
            process.outputStream.write((o.toString() + "\n").toByteArray())
            process.outputStream.flush()
        }

        /** 发出一个控制请求，返回请求号（不等应答）。 */
        fun request(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String {
            val id = "zeta-${++requests}"
            send(buildJsonObject { put("type", "control_request"); put("request_id", id); putJsonObject("request", build) })
            return id
        }

        fun user(text: String) = send(buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                put("role", "user")
                putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
            }
        })

        /** 等请求 [id] 的应答，返回它（subtype 和 response）。 */
        fun response(id: String): JsonObject {
            var found: JsonObject? = null
            until { o -> (o.str("type") == "control_response" && o.obj("response")?.str("request_id") == id).also { if (it) found = o.obj("response") } }
            return found!!
        }

        fun result() = until { it.str("type") == "result" }

        /** 这时生效的设置（get_settings 的 effective）。 */
        fun effective(): JsonObject = response(request { put("subtype", "get_settings") }).obj("response")!!.obj("effective")!!

        private fun until(done: (JsonObject) -> Boolean) {
            val deadline = System.currentTimeMillis() + 60_000
            while (true) {
                val left = deadline - System.currentTimeMillis()
                assertTrue("claude 没有按时回应", left > 0)
                val line = lines.poll(left, TimeUnit.MILLISECONDS) ?: continue
                val o = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                answer(o)
                if (done(o)) return
            }
        }

        private fun answer(o: JsonObject) {
            if (o.str("type") != "control_request") return
            val id = o.str("request_id")!!
            val request = o.obj("request")!!
            val response: JsonObject = when (request.str("subtype")) {
                "mcp_message" -> {
                    val m = request.obj("message")!!
                    val mid = m["id"]
                    buildJsonObject {
                        put("mcp_response", if (mid == null) JsonObject(emptyMap()) else buildJsonObject {
                            put("jsonrpc", "2.0"); put("id", mid)
                            put("result", when (m.str("method")) {
                                "initialize" -> buildJsonObject {
                                    put("protocolVersion", "2025-06-18")
                                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                                    putJsonObject("serverInfo") { put("name", "phone"); put("version", "1") }
                                }
                                "tools/list" -> {
                                    toolLists++
                                    buildJsonObject { put("tools", JsonArray(ScheduleTools.tools)) }
                                }
                                "tools/call" -> {
                                    val name = m.obj("params")!!.str("name")!!
                                    calls += name
                                    buildJsonObject { putJsonArray("content") { add(text("called $name")) } }
                                }
                                else -> JsonObject(emptyMap())
                            })
                        })
                    }
                }
                "can_use_tool" -> {
                    asks += request.str("tool_name")!!.removePrefix("mcp__phone__")
                    buildJsonObject { put("behavior", "allow"); put("updatedInput", request["input"] ?: JsonObject(emptyMap())) }
                }
                else -> return
            }
            send(buildJsonObject {
                put("type", "control_response")
                putJsonObject("response") { put("subtype", "success"); put("request_id", id); put("response", response) }
            })
        }

        override fun close() {
            runCatching { process.outputStream.close() }
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    private fun names(element: JsonElement?): List<String> = (element as? JsonArray).orEmpty().map { it.jsonPrimitive.content }

    private fun applyRules(claude: Claude, rules: JsonElement): String = claude.request {
        put("subtype", "apply_flag_settings")
        putJsonObject("settings") { put("permissions", rules) }
    }

    /** 跑一轮：假模型依次调 [tools]，返回这一轮看到的结果。 */
    private fun turn(claude: Claude, prompt: String, vararg tools: String): List<Triple<String, String, Boolean>> {
        val before = synchronized(results) { results.size }
        script.addAll(tools)
        claude.user(prompt)
        claude.result()
        return synchronized(results) { results.drop(before) }
    }

    private val noSuchTool = "No such tool available"

    @Test
    fun unattendedRulesBlockOnlyThisLayer() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        val rules = ScheduleTools.unattendedRules
        val denied = names(rules["deny"])
        Claude().use { claude ->
            // 新起的进程：initialize、规则、第一条消息一口气发出去，和 Zeta 一样不等应答
            script.addAll(listOf("report_scheduled_run", "run_scheduled_task", "create_scheduled_task"))
            val init = claude.request { put("subtype", "initialize") }
            val applied = applyRules(claude, rules)
            claude.user("run 1")
            assertEquals("success", claude.response(init).str("subtype"))
            assertEquals("success", claude.response(applied).str("subtype"))
            claude.result()
            val first = synchronized(results) { results.toList() }
            assertEquals(listOf("report_scheduled_run", "run_scheduled_task", "create_scheduled_task"), first.map { it.first })
            // 上报不用问、照常调到；被拒的到不了 phone 服务器，用户自己的允许（run_scheduled_task）也压不过
            assertEquals(false, first[0].third)
            assertTrue(first[1].toString(), first[1].third && noSuchTool in first[1].second)
            assertTrue(first[2].toString(), first[2].third && noSuchTool in first[2].second)
            assertEquals(emptyList<String>(), claude.asks)
            assertEquals(listOf("report_scheduled_run"), claude.calls)

            // 这一层叠在 settings.json 上；单独改思考强度不动它
            claude.response(claude.request { put("subtype", "apply_flag_settings"); putJsonObject("settings") { put("effortLevel", "low") } })
            val during = claude.effective()
            assertEquals("low", during.str("effortLevel"))
            assertEquals(listOf("WebSearch") + denied, names(during.obj("permissions")?.get("deny")))
            assertEquals(listOf("mcp__phone__run_scheduled_task") + names(rules["allow"]), names(during.obj("permissions")?.get("allow")))
            val still = turn(claude, "run 2", "list_scheduled_tasks")
            assertTrue(still.toString(), still.single().third && noSuchTool in still.single().second)

            // 撤掉：只撤这一层，settings.json 的规则和思考强度照旧；被拒过的工具照常要确认（用户允许过的不用）、照常调到
            claude.response(applyRules(claude, JsonNull))
            val after = claude.effective()
            assertEquals("low", after.str("effortLevel"))
            assertEquals(listOf("WebSearch"), names(after.obj("permissions")?.get("deny")))
            assertEquals(listOf("mcp__phone__run_scheduled_task"), names(after.obj("permissions")?.get("allow")))
            val lifted = turn(claude, "by hand", "create_scheduled_task", "run_scheduled_task", "report_scheduled_run")
            assertEquals(listOf(false, false, false), lifted.map { it.third })
            assertEquals(listOf("create_scheduled_task", "report_scheduled_run"), claude.asks)
            assertEquals(listOf("report_scheduled_run", "create_scheduled_task", "run_scheduled_task", "report_scheduled_run"), claude.calls)

            // 已经开着的进程：加上规则、紧接着发消息，这一轮就生效
            applyRules(claude, rules)
            val again = turn(claude, "run 3", "delete_scheduled_task", "report_scheduled_run")
            assertTrue(again.toString(), again[0].third && noSuchTool in again[0].second)
            assertEquals(false, again[1].third)
            assertEquals(listOf("create_scheduled_task", "report_scheduled_run"), claude.asks)

            // 规则怎么变，工具清单都只在起进程时取一次
            assertEquals(1, claude.toolLists)
        }
    }
}
