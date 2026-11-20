package com.dzshzx.zeta.core.claude

import com.dzshzx.zeta.phone.tools.ScheduleTools
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 设备测试用的假 Claude（tools/fake-claude/fake-claude.sh）要和真 Claude Code（2.1.328 实测）对得上，Zeta 走的才是真路径。
 * 同一段对话两边各跑一遍：真的 claude 跑在临时 HOME 上，连本机一个假的 Messages API（假 key，不出网、不花钱），
 * 做法同 ClaudeTaskContractTest；假 Claude 照等价的剧本回话。两边都像 Zeta 那样驱动：同样的参数和控制请求，
 * 答 phone 服务器的 MCP 消息和 can_use_tool。比的是：
 * - 从第一条用户消息起的事件序列（类型、子类型、流事件的种类；相邻相同的增量算一条）逐条相同；
 * - 启动时的事件（各控制请求的应答、和 phone 服务器握手）一样，不比先后：真的那边本来就是并发的；
 * - 假 Claude 每种事件里的每个字段（路径），真的同种事件里都出现过，不凭空编字段。
 * 故意不同的只有：init 和 initialize 应答里的 fake_claude 标记、get_usage 答错误（假 Claude 不查额度）、模型名。
 * 升级 Claude Code 后跑一遍，变了就会失败，fake-claude.sh 跟着改。
 */
class FakeClaudeContractTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val fake = File("../tools/fake-claude/fake-claude.sh").absoluteFile
    private val hello = """{"text":"Hello from the fake API.","chunk":6,"delay":50}"""
    private val openApp = """{"phone":"open_app","input":{"app":"com.android.traceur"}"""

    /** 一段对话：[mode] 决定假 API 怎么答、驱动方怎么做，[scenario] 是假 Claude 的等价剧本（一行一轮）。 */
    private class Case(val mode: String, val scenario: List<String>, val permissionMode: String? = null)

    private val cases = listOf(
        // 纯文字，接着是 Zeta 会发的各种控制请求，再来一轮，最后闲着时结束会话（end_session）
        Case("text", listOf("""{"steps":[$hello]}""", """{"steps":[$hello]}""")),
        // 手机工具：先问批准，批了再经控制通道调用；拒了的；不用问、结果是出错的；调用中被打断的
        Case("tool", listOf("""{"steps":[{"text":"Opening it."},$openApp,"ask":true},$hello]}""")),
        Case("deny", listOf("""{"steps":[{"text":"Opening it."},$openApp,"ask":true},$hello]}""")),
        Case("toolerr", listOf("""{"steps":[{"text":"Opening it."},$openApp},$hello]}"""), "bypassPermissions"),
        Case("toolint", listOf("""{"steps":[{"text":"Opening it."},$openApp},$hello]}"""), "bypassPermissions"),
        // Claude Code 自己的工具
        Case("bash", listOf("""{"steps":[{"tool":"Bash","input":{"command":"echo hi; echo err >&2","description":"Say hi"},"result":"hi\nerr"},$hello]}"""), "bypassPermissions"),
        Case("think", listOf("""{"steps":[{"thinking":"Let me think. Done thinking.","chunk":14},$hello]}""")),
        Case("error", listOf("""{"steps":[{"error":"API Error: 400 fake bad request","status":400}]}""")),
        // 流到一半被打断；排队的消息撤回一条、打断后另一条接着跑
        Case("slow", listOf("""{"steps":[{"text":"Hello from the fake API.","chunk":6,"delay":3000}]}""")),
        Case("queue", listOf("""{"steps":[{"text":"Hello from the fake API.","chunk":6,"delay":3000}]}""", """{"steps":[$hello]}""")),
        // 一轮进行中结束会话（end_session）：流到一半；手机工具调用中
        Case("endslow", listOf("""{"steps":[{"text":"Hello from the fake API.","chunk":6,"delay":3000}]}""")),
        Case("endtool", listOf("""{"steps":[{"text":"Opening it."},$openApp},$hello]}"""), "bypassPermissions"),
    )

    private fun onPath(command: String): Boolean {
        val path = listOfNotNull(System.getProperty("user.home")?.let { "$it/.local/bin" }, System.getenv("PATH")).joinToString(":")
        return path.split(':').any { File(it, command).canExecute() }
    }

    @Test
    fun fakeMatchesRealClaudeCode() {
        assumeTrue("没有 claude 命令", onPath("claude"))
        assumeTrue("没有 jq", onPath("jq"))
        val pool = Executors.newFixedThreadPool(6)
        val runs = try {
            cases.map { case -> pool.submit<Run> { record(case, fake = false) } to pool.submit<Run> { record(case, fake = true) } }
                .map { (real, faked) -> real.get(5, TimeUnit.MINUTES) to faked.get(5, TimeUnit.MINUTES) }
        } finally {
            pool.shutdownNow()
        }
        val realPaths = HashMap<String, MutableSet<String>>()
        val fakePaths = HashMap<String, MutableSet<String>>()
        for ((case, pair) in cases.zip(runs)) {
            val (real, faked) = pair
            assertEquals("${case.mode}：一轮里的事件", real.turnShapes(), faked.turnShapes())
            assertEquals("${case.mode}：启动时的事件", real.startShapes(), faked.startShapes())
            assertEquals("${case.mode}：退出码", real.exit, faked.exit)
            real.collectPaths(realPaths)
            faked.collectPaths(fakePaths)
        }
        val invented = fakePaths.flatMap { (shape, paths) ->
            (paths - realPaths[shape].orEmpty() - ALLOWED_EXTRA).map { "$shape $it" }
        }
        assertEquals("假 Claude 用了真 Claude Code 里没有的字段", emptyList<String>(), invented.sorted())
    }

    /** 一个进程的全部输出（按顺序）和驱动方发过的控制请求（请求号 → 子类型）。 */
    private inner class Run(val events: List<JsonObject>, val requests: Map<String, String>, val exit: Int? = null) {
        private val firstTurn = events.indexOfFirst { it.str("type") == "command_lifecycle" }.let { if (it < 0) events.size else it }

        private fun shapes(list: List<JsonObject>) = list.map { shape(it, requests) }.filterNot { it.endsWith(":get_usage") }

        fun turnShapes(): List<String> = shapes(events.drop(firstTurn)).fold(mutableListOf()) { acc, s -> if (acc.lastOrNull() != s) acc += s; acc }

        fun startShapes(): List<String> = shapes(events.take(firstTurn)).sorted()

        fun collectPaths(into: MutableMap<String, MutableSet<String>>) {
            for (event in events) {
                val shape = shape(event, requests)
                if (shape.endsWith(":get_usage")) continue
                paths(event, "", into.getOrPut(shape) { HashSet() })
            }
        }
    }

    private fun paths(element: JsonElement, prefix: String, into: MutableSet<String>) {
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                // 模型名做键的（modelUsage）不比名字
                val name = if (prefix == "modelUsage" || prefix.endsWith(".modelUsage")) "*" else key
                val path = if (prefix.isEmpty()) name else "$prefix.$name"
                into += path
                paths(value, path, into)
            }
            is JsonArray -> element.forEach { paths(it, "$prefix[]", into) }
            else -> {}
        }
    }

    private fun shape(o: JsonObject, requests: Map<String, String>): String = when (val type = o.str("type")) {
        "stream_event" -> {
            val e = o.obj("event")!!
            "stream_event:" + e.str("type") + when (e.str("type")) {
                "content_block_delta" -> ":" + e.obj("delta")?.str("type")
                "content_block_start" -> ":" + e.obj("content_block")?.str("type")
                else -> ""
            }
        }
        "system" -> "system:" + o.str("subtype") + if (o.str("subtype") == "status") ":" + (o.str("status") ?: "null") else ""
        "assistant" -> "assistant:" + (o.obj("message")?.get("content") as? JsonArray).orEmpty().joinToString(",") { (it as JsonObject).str("type").orEmpty() } +
            (if (o.bool("aborted")) ":aborted" else "") + if (o.bool("is_api_error_message")) ":api_error" else ""
        "user" -> "user:" + when (val content = o.obj("message")?.get("content")) {
            is JsonArray -> content.joinToString(",") { (it as JsonObject).let { b -> b.str("type") + if (b.bool("is_error")) "!" else "" } }
            else -> "string"
        }
        "result" -> "result:" + o.str("subtype") + ":" + o.bool("is_error") + ":" + o.str("terminal_reason")
        "command_lifecycle" -> "lifecycle:" + o.str("state")
        "control_request" -> o.obj("request")!!.let { r ->
            "control_request:" + r.str("subtype") + if (r.str("subtype") == "mcp_message") ":" + r.obj("message")?.str("method") else ""
        }
        "control_response" -> o.obj("response")!!.let { r -> "control_response:" + r.str("subtype") + ":" + (requests[r.str("request_id")] ?: "?") }
        else -> type.orEmpty()
    }

    // ---- 假的 Messages API ----

    private fun sse(ex: HttpExchange, type: String, data: JsonObject) {
        val body = JsonObject(data + ("type" to JsonPrimitive(type)))
        ex.responseBody.write("event: $type\ndata: $body\n\n".toByteArray())
        ex.responseBody.flush()
    }

    private fun message(stop: String?) = buildJsonObject {
        put("id", "msg_fake_" + System.nanoTime() % 100000); put("type", "message"); put("role", "assistant"); put("model", "claude-fake")
        putJsonArray("content") {}; put("stop_reason", stop); put("stop_sequence", null)
        putJsonObject("usage") { put("input_tokens", 10); put("output_tokens", 1); put("cache_creation_input_tokens", 0); put("cache_read_input_tokens", 0) }
    }

    private fun block(ex: HttpExchange, index: Int, start: JsonObject, vararg deltas: JsonObject) {
        sse(ex, "content_block_start", buildJsonObject { put("index", index); put("content_block", start) })
        for (delta in deltas) sse(ex, "content_block_delta", buildJsonObject { put("index", index); put("delta", delta) })
        sse(ex, "content_block_stop", buildJsonObject { put("index", index) })
    }

    private fun obj(vararg pairs: Pair<String, Any?>) = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            null -> put(k, JsonNull)
            is String -> put(k, v)
            is JsonElement -> put(k, v)
            else -> error(v)
        }
    }

    private fun serve(mode: String, stalls: AtomicInteger, ex: HttpExchange) {
        if (ex.requestMethod != "POST" || !ex.requestURI.path.startsWith("/v1/messages") || ex.requestURI.path.contains("count_tokens")) {
            ex.sendResponseHeaders(404, -1)
            ex.close()
            return
        }
        val body = json.parseToJsonElement(ex.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
        val messages = body["messages"] as? JsonArray ?: JsonArray(emptyList())
        if ((body["tools"] as? JsonArray).isNullOrEmpty()) {
            // 起标题之类的辅助请求
            val reply = JsonObject(message("end_turn") + ("content" to buildJsonArray { add(obj("type" to "text", "text" to "{\"title\":\"Fake title\"}")) }))
            val bytes = reply.toString().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            return
        }
        if (mode == "error") {
            val bytes = """{"type":"error","error":{"type":"invalid_request_error","message":"fake bad request"}}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(400, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            return
        }
        val results = messages.sumOf { m ->
            ((m as JsonObject)["content"] as? JsonArray).orEmpty().count { (it as? JsonObject)?.str("type") == "tool_result" }
        }
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.use {
            sse(ex, "message_start", obj("message" to message(null)))
            var index = 0
            fun end(stop: String) {
                sse(ex, "message_delta", obj("delta" to obj("stop_reason" to stop, "stop_sequence" to null), "usage" to buildJsonObject { put("output_tokens", 20) }))
                sse(ex, "message_stop", JsonObject(emptyMap()))
            }
            if (results == 0 && mode in setOf("tool", "deny", "toolerr", "toolint", "endtool")) {
                block(ex, 0, obj("type" to "text", "text" to ""), obj("type" to "text_delta", "text" to "Opening it."))
                block(ex, 1, obj("type" to "tool_use", "id" to "toolu_fake1", "name" to "mcp__phone__open_app", "input" to JsonObject(emptyMap())),
                    obj("type" to "input_json_delta", "partial_json" to """{"app":"com.android.traceur"}"""))
                end("tool_use")
                return
            }
            if (results == 0 && mode == "bash") {
                block(ex, 0, obj("type" to "tool_use", "id" to "toolu_bash1", "name" to "Bash", "input" to JsonObject(emptyMap())),
                    obj("type" to "input_json_delta", "partial_json" to """{"command":"echo hi; echo err >&2","description":"Say hi"}"""))
                end("tool_use")
                return
            }
            if (mode == "think") {
                block(ex, index++, obj("type" to "thinking", "thinking" to "", "signature" to ""),
                    obj("type" to "thinking_delta", "thinking" to "Let me think. "), obj("type" to "thinking_delta", "thinking" to "Done thinking."),
                    obj("type" to "signature_delta", "signature" to "c2lnbmF0dXJl"))
            }
            sse(ex, "content_block_start", obj("index" to JsonPrimitive(index), "content_block" to obj("type" to "text", "text" to "")))
            for (part in listOf("Hello ", "from the ", "fake API.")) {
                sse(ex, "content_block_delta", obj("index" to JsonPrimitive(index), "delta" to obj("type" to "text_delta", "text" to part)))
                Thread.sleep(50)
                // 卡在第一段之后，等对方打断（断开连接时写会失败，或者到点）
                if (mode == "slow" || mode == "endslow" || (mode == "queue" && stalls.incrementAndGet() == 1)) {
                    repeat(600) {
                        Thread.sleep(100)
                        ex.responseBody.write(": keep\n\n".toByteArray())
                        ex.responseBody.flush()
                    }
                    return
                }
            }
            sse(ex, "content_block_stop", obj("index" to JsonPrimitive(index)))
            end("end_turn")
        }
    }

    // ---- 像 Zeta 那样驱动一个进程 ----

    private fun record(case: Case, fake: Boolean): Run {
        val home = java.nio.file.Files.createTempDirectory("zeta-fake-contract").toFile().canonicalFile
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val stalls = AtomicInteger()
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        server.createContext("/") { ex -> runCatching { serve(case.mode, stalls, ex) }.onFailure { runCatching { ex.close() } } }
        server.start()
        try {
            File(home, ".claude").mkdirs()
            File(home, "proj").mkdirs()
            File(home, ".claude/settings.json").writeText("{}")
            val scenario = File(home, "scenario.jsonl").apply { writeText(case.scenario.joinToString("\n") + "\n") }
            val arguments = listOf(
                if (fake) this.fake.path else "claude",
                "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
                "--permission-prompt-tool", "stdio", "--append-system-prompt", "test",
                "--mcp-config", """{"mcpServers":{"phone":{"type":"sdk","name":"phone"}}}""",
            ) + (case.permissionMode?.let { listOf("--permission-mode", it) } ?: emptyList())
            val builder = ProcessBuilder(arguments).directory(File(home, "proj")).redirectError(File("/dev/null"))
            builder.environment().apply {
                val path = listOfNotNull(System.getProperty("user.home")?.let { "$it/.local/bin" }, System.getenv("PATH")).joinToString(":")
                clear()
                put("HOME", home.path)
                put("PATH", path)
                put("LANG", "C.UTF-8")
                put("ANTHROPIC_API_KEY", "fake-key-1")
                put("ANTHROPIC_BASE_URL", "http://127.0.0.1:${server.address.port}")
                put("NO_PROXY", "127.0.0.1,localhost")
                put("DISABLE_TELEMETRY", "1")
                put("DISABLE_ERROR_REPORTING", "1")
                put("DISABLE_AUTOUPDATER", "1")
                put("CLAUDE_CODE_ENABLE_TODO_TOOLS", "1")
                put("FAKE_CLAUDE_SCENARIO", scenario.path)
            }
            return Driver(builder.start(), case.mode).use { it.play() }
        } finally {
            server.stop(0)
            home.deleteRecursively()
        }
    }

    private inner class Driver(private val process: java.lang.Process, private val mode: String) : AutoCloseable {
        private val lines = LinkedBlockingQueue<String>()
        private val events = mutableListOf<JsonObject>()
        private val requests = HashMap<String, String>()
        private var seq = 0
        private val delayed = Executors.newSingleThreadScheduledExecutor { r -> Thread(r).apply { isDaemon = true } }

        init {
            Thread {
                runCatching { process.inputStream.bufferedReader().forEachLine { lines.put(it) } }
                lines.put(EOF)
            }.apply { isDaemon = true; start() }
        }

        @Synchronized
        private fun send(o: JsonObject) {
            runCatching {
                process.outputStream.write((o.toString() + "\n").toByteArray())
                process.outputStream.flush()
            }
        }

        private fun control(request: JsonObject): String {
            val id = "zeta-${++seq}"
            requests[id] = request.str("subtype").orEmpty()
            send(obj("type" to "control_request", "request_id" to id, "request" to request))
            return id
        }

        private fun user(uuid: String, text: String) = send(obj("type" to "user", "uuid" to uuid,
            "message" to obj("role" to "user", "content" to buildJsonArray { add(obj("type" to "text", "text" to text)) })))

        private fun answer(id: String, response: JsonObject) =
            send(obj("type" to "control_response", "response" to obj("subtype" to "success", "request_id" to id, "response" to response)))

        /** 对方发来的控制请求：phone 服务器的 MCP 消息、can_use_tool。 */
        private fun handle(o: JsonObject) {
            if (o.str("type") != "control_request") return
            val id = o.str("request_id")!!
            val request = o.obj("request")!!
            when (request.str("subtype")) {
                "mcp_message" -> {
                    val m = request.obj("message")!!
                    val mid = m["id"] ?: return answer(id, obj("mcp_response" to JsonObject(emptyMap())))
                    fun reply(result: JsonObject) = answer(id, obj("mcp_response" to obj("jsonrpc" to "2.0", "id" to mid, "result" to result)))
                    when (m.str("method")) {
                        "initialize" -> reply(obj("protocolVersion" to "2025-06-18", "capabilities" to obj("tools" to JsonObject(emptyMap())),
                            "serverInfo" to obj("name" to "phone", "version" to "1")))
                        "tools/list" -> reply(obj("tools" to buildJsonArray {
                            add(obj("name" to "open_app", "description" to "Open an app",
                                "inputSchema" to json.parseToJsonElement("""{"type":"object","properties":{"app":{"type":"string"}}}""")))
                        }))
                        "tools/call" -> {
                            val text = obj("content" to buildJsonArray { add(obj("type" to "text", "text" to "Opened Traceur on the background screen.")) })
                            when (mode) {
                                // 应答晚到，期间被打断
                                "toolint", "endtool" -> delayed.schedule(Runnable { reply(text) }, 3, TimeUnit.SECONDS)
                                "toolerr" -> reply(obj("content" to buildJsonArray { add(obj("type" to "text", "text" to "No such app")) }, "isError" to JsonPrimitive(true)))
                                else -> { Thread.sleep(300); reply(text) }
                            }
                        }
                        else -> reply(JsonObject(emptyMap()))
                    }
                }
                "can_use_tool" -> answer(id, if (mode == "deny") obj("behavior" to "deny", "message" to "用户拒绝了这次操作")
                    else obj("behavior" to "allow", "updatedInput" to request["input"]!!))
            }
        }

        private fun until(millis: Long, done: (JsonObject) -> Boolean): JsonObject? {
            val deadline = System.currentTimeMillis() + millis
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                val line = lines.poll(left, TimeUnit.MILLISECONDS) ?: continue
                if (line == EOF) return null
                val o = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                events += o
                handle(o)
                if (done(o)) return o
            }
        }

        private fun settle(millis: Long) = until(millis) { false }

        /** 收完它退出前的输出（输入管道一直开着，它自己退出），返回退出码；15 秒内没退出是 -1。 */
        private fun exitCode(): Int {
            settle(15_000)
            return if (process.waitFor(5, TimeUnit.SECONDS)) process.exitValue() else -1
        }

        private fun response(id: String): (JsonObject) -> Boolean = { it.str("type") == "control_response" && it.obj("response")?.str("request_id") == id }

        private fun result(): (JsonObject) -> Boolean = { it.str("type") == "result" }

        private fun firstDelta(): (JsonObject) -> Boolean = { it.str("type") == "stream_event" && it.obj("event")?.str("type") == "content_block_delta" }

        fun play(): Run {
            val initialize = control(obj("subtype" to "initialize"))
            control(obj("subtype" to "get_context_usage"))
            control(obj("subtype" to "get_settings"))
            control(obj("subtype" to "get_usage", "skip_behaviors" to JsonPrimitive(true)))
            until(60_000, response(initialize))
            settle(1500)
            user("11111111-2222-4333-8444-555555555555", "hi")
            when (mode) {
                "slow" -> {
                    until(30_000, firstDelta())
                    settle(500)
                    control(obj("subtype" to "interrupt"))
                }
                "toolint" -> {
                    until(30_000) { it.obj("request")?.obj("message")?.str("method") == "tools/call" }
                    settle(1000)
                    control(obj("subtype" to "interrupt"))
                }
                "endslow" -> {
                    until(30_000, firstDelta())
                    settle(500)
                    control(obj("subtype" to "end_session"))
                }
                "endtool" -> {
                    until(30_000) { it.obj("request")?.obj("message")?.str("method") == "tools/call" }
                    settle(1000)
                    control(obj("subtype" to "end_session"))
                }
                "queue" -> {
                    until(30_000, firstDelta())
                    user("33333333-2222-4333-8444-555555555555", "B")
                    user("44444444-2222-4333-8444-555555555555", "C")
                    settle(500)
                    until(5_000, response(control(obj("subtype" to "cancel_async_message", "message_uuid" to "44444444-2222-4333-8444-555555555555"))))
                    control(obj("subtype" to "interrupt"))
                    until(60_000, result())
                }
            }
            until(60_000, result())
            if (mode.startsWith("end")) return Run(events.toList(), requests.toMap(), exitCode())
            settle(1500)
            if (mode == "text") {
                for (request in listOf(
                    obj("subtype" to "generate_session_title", "description" to "User said hi and the assistant greeted back.", "persist" to JsonPrimitive(true)),
                    obj("subtype" to "set_model", "model" to "sonnet"),
                    obj("subtype" to "set_permission_mode", "mode" to "acceptEdits"),
                    obj("subtype" to "apply_flag_settings", "settings" to obj("effortLevel" to "high")),
                    // 后台运行加上、用户发消息前撤掉的权限规则（见 UnattendedRulesContractTest）
                    obj("subtype" to "apply_flag_settings", "settings" to obj("permissions" to ScheduleTools.unattendedRules)),
                    obj("subtype" to "apply_flag_settings", "settings" to obj("permissions" to null)),
                    obj("subtype" to "get_settings"),
                    obj("subtype" to "cancel_async_message", "message_uuid" to "00000000-0000-4000-8000-000000000000"),
                    obj("subtype" to "interrupt"),
                    obj("subtype" to "stop_task", "task_id" to "bnotexist1"),
                    obj("subtype" to "get_task_output", "task_id" to "bnotexist1"),
                    obj("subtype" to "bogus_thing"),
                )) until(20_000, response(control(request)))
                settle(300)
                user("21111111-2222-4333-8444-555555555555", "again")
                until(60_000, result())
                settle(1500)
                control(obj("subtype" to "end_session"))
                return Run(events.toList(), requests.toMap(), exitCode())
            }
            return Run(events.toList(), requests.toMap())
        }

        override fun close() {
            delayed.shutdownNow()
            runCatching { process.outputStream.close() }
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    private companion object {
        /** 读输出的线程读到头（进程退出）时放进队列的记号。 */
        const val EOF = "\u0000eof"

        /** 假 Claude 故意多出的字段：一眼看得出是假的。 */
        val ALLOWED_EXTRA = setOf("fake_claude", "response.response.fake_claude")
    }
}
