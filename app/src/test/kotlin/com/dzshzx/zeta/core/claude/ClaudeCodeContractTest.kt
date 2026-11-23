package com.dzshzx.zeta.core.claude

import com.dzshzx.zeta.core.removeTestDir
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * 工具权限页、自动标题、排队条和结束会话依赖的 Claude Code 行为（2.1.327 实测，排队条和结束会话那几条 2.1.329），在这里钉住： 升级 Claude Code
 * 后跑一遍，变了就会失败，页面上那句说明（[com.dzshzx.zeta.ui.setup.PermissionSemantics]）、核心（helper/conv）的自动标题、 排队和
 * zeta_host.sh 的 stop 跟着改。
 *
 * 真的 claude 命令行，跑在临时 HOME 上，连本机一个假的 Messages API（假 key，不出网、不花钱）： 假模型按剧本调用工具，从下一次请求里的 tool_result
 * 看结局；标题请求按系统提示认出来。
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

    private fun claudeAvailable(): Boolean {
        val path =
            listOfNotNull(
                    System.getProperty("user.home")?.let { "$it/.local/bin" },
                    System.getenv("PATH"),
                )
                .joinToString(":")
        return path.split(':').any { File(it, "claude").canExecute() }
    }

    private fun serve(ex: HttpExchange) {
        if (!isMessagesRequest(ex)) {
            ex.sendResponseHeaders(404, -1)
            ex.close()
            return
        }
        val body =
            json.parseToJsonElement(ex.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
        val (reply, stop) = replyTo(body)
        val stream = body["stream"]?.jsonPrimitive?.content == "true"
        val bytes =
            (if (stream) sse(reply, stop) else message(reply, stop).toString()).toByteArray()
        ex.responseHeaders.add(
            "Content-Type",
            if (stream) "text/event-stream" else "application/json",
        )
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun isMessagesRequest(ex: HttpExchange): Boolean =
        ex.requestMethod == "POST" &&
            ex.requestURI.path.startsWith("/v1/messages") &&
            !ex.requestURI.path.contains("count_tokens")

    /** 假模型这一次答什么（内容块、stop_reason）：标题请求给假标题，没带工具的辅助请求答 ok，主对话照剧本。 */
    private fun replyTo(body: JsonObject): Pair<List<JsonObject>, String> {
        val system = textOf(body["system"])
        val messages = body["messages"] as? JsonArray ?: JsonArray(emptyList())
        val tools = (body["tools"] as? JsonArray).orEmpty()
        return when {
            "naming a coding session" in system -> {
                synchronized(titleRequests) { titleRequests += messages.toString() }
                listOf(text("""{"title":"假标题 一号"}""")) to "end_turn"
            }
            tools.isEmpty() -> listOf(text("ok")) to "end_turn"
            else -> scriptedReply(messages)
        }
    }

    /** 主对话：记下已有的 tool_result，按剧本调下一个工具，剧本走完了说 done。 */
    private fun scriptedReply(messages: JsonArray): Pair<List<JsonObject>, String> {
        val seen = toolResults(messages)
        results = seen
        val next = script.getOrNull(seen.size) ?: return listOf(text("done")) to "end_turn"
        return listOf(
            buildJsonObject {
                put("type", "tool_use")
                put("id", "toolu_${seen.size}")
                put("name", next.first)
                put("input", next.second)
            },
        ) to "tool_use"
    }

    /** 请求里全部的 tool_result（内容、是否出错），按顺序。 */
    private fun toolResults(messages: JsonArray): List<Pair<String, Boolean>> =
        messages.flatMap { m ->
            ((m as JsonObject)["content"] as? JsonArray).orEmpty().mapNotNull { b ->
                (b as? JsonObject)
                    ?.takeIf { it["type"]?.jsonPrimitive?.content == "tool_result" }
                    ?.let { r ->
                        textOf(r["content"]) to (r["is_error"]?.jsonPrimitive?.content == "true")
                    }
            }
        }

    /** system、tool_result 的 content：字符串，或者一串文字块（用空格接起来）。 */
    private fun textOf(element: JsonElement?): String =
        when (element) {
            is JsonPrimitive -> element.content
            is JsonArray ->
                element.joinToString(" ") {
                    (it as? JsonObject)?.get("text")?.jsonPrimitive?.content.orEmpty()
                }
            else -> ""
        }

    private fun text(value: String) = buildJsonObject {
        put("type", "text")
        put("text", value)
    }

    private fun message(content: List<JsonObject>, stop: String) = buildJsonObject {
        put("id", "msg_fake")
        put("type", "message")
        put("role", "assistant")
        put("model", "claude-fake")
        put("content", JsonArray(content))
        put("stop_reason", stop)
        putJsonObject("usage") {
            put("input_tokens", 10)
            put("output_tokens", 5)
        }
    }

    private fun sse(content: List<JsonObject>, stop: String): String = buildString {
        fun event(data: JsonObject) =
            append("event: ")
                .append(data["type"]!!.jsonPrimitive.content)
                .append("\ndata: ")
                .append(data)
                .append("\n\n")
        event(
            buildJsonObject {
                put("type", "message_start")
                put(
                    "message",
                    message(emptyList(), stop).let {
                        JsonObject(it + ("stop_reason" to kotlinx.serialization.json.JsonNull))
                    },
                )
            },
        )
        content.forEachIndexed { i, block ->
            val isTool = block["type"]!!.jsonPrimitive.content == "tool_use"
            event(
                buildJsonObject {
                    put("type", "content_block_start")
                    put("index", i)
                    put(
                        "content_block",
                        if (isTool) JsonObject(block + ("input" to JsonObject(emptyMap())))
                        else text(""),
                    )
                },
            )
            event(
                buildJsonObject {
                    put("type", "content_block_delta")
                    put("index", i)
                    putJsonObject("delta") {
                        if (isTool) {
                            put("type", "input_json_delta")
                            put("partial_json", block["input"].toString())
                        } else {
                            put("type", "text_delta")
                            put("text", block["text"]!!)
                        }
                    }
                },
            )
            event(
                buildJsonObject {
                    put("type", "content_block_stop")
                    put("index", i)
                },
            )
        }
        event(
            buildJsonObject {
                put("type", "message_delta")
                putJsonObject("delta") { put("stop_reason", stop) }
                putJsonObject("usage") { put("output_tokens", 5) }
            },
        )
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
                        "--permission-mode",
                        mode,
                    )
                    .directory(File(home, "proj"))
            val env = builder.environment()
            val path =
                listOfNotNull(
                        System.getProperty("user.home")?.let { "$it/.local/bin" },
                        System.getenv("PATH"),
                    )
                    .joinToString(":")
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
            Thread { process.inputStream.bufferedReader().forEachLine { lines.put(it) } }
                .apply {
                    isDaemon = true
                    start()
                }
            send(
                buildJsonObject {
                    put("type", "control_request")
                    put("request_id", "init")
                    putJsonObject("request") { put("subtype", "initialize") }
                },
            )
        }

        fun send(o: JsonObject) {
            process.outputStream.write((o.toString() + "\n").toByteArray())
            process.outputStream.flush()
        }

        /** 发一条用户消息，一路答“拒绝”，直到这一轮结束。 */
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
            until { o ->
                if (
                    o.str("type") == "control_request" &&
                        o.obj("request")?.str("subtype") == "can_use_tool"
                ) {
                    asks += o.obj("request")!!.str("tool_name")!!
                    send(
                        buildJsonObject {
                            put("type", "control_response")
                            putJsonObject("response") {
                                put("subtype", "success")
                                put("request_id", o.str("request_id")!!)
                                putJsonObject("response") {
                                    put("behavior", "deny")
                                    put("message", "USER-DENIED")
                                }
                            }
                        },
                    )
                }
                o.str("type") == "result"
            }
        }

        /** 发一条带 [uuid] 的用户消息（和 Zeta 发的一样），不等。 */
        fun user(uuid: String, text: String) =
            send(
                buildJsonObject {
                    put("type", "user")
                    put("uuid", uuid)
                    putJsonObject("message") {
                        put("role", "user")
                        put("content", text)
                    }
                },
            )

        /** 发一个控制请求，等到它的应答。 */
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

        /** 收事件，直到 [done] 为真（60 秒内没等到就失败），返回这期间收到的全部事件。 */
        fun until(done: (JsonObject) -> Boolean): List<JsonObject> = buildList {
            val deadline = System.currentTimeMillis() + 60_000
            while (true) {
                val left = deadline - System.currentTimeMillis()
                assertTrue("claude 没有按时回应", left > 0)
                val line = lines.poll(left, TimeUnit.MILLISECONDS) ?: continue
                val o =
                    runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                        ?: continue
                add(o)
                if (done(o)) return@buildList
            }
        }

        /** 再收 [millis] 毫秒的事件。 */
        fun settle(millis: Long): List<JsonObject> = buildList {
            val deadline = System.currentTimeMillis() + millis
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return@buildList
                val line = lines.poll(left, TimeUnit.MILLISECONDS) ?: continue
                runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()?.let(::add)
            }
        }

        /** 等进程自己退出（输入管道一直开着），最多 [seconds] 秒；退出了给退出码，没退是 null。 */
        fun exitCode(seconds: Long): Int? =
            if (process.waitFor(seconds, TimeUnit.SECONDS)) process.exitValue() else null

        override fun close() {
            runCatching { process.outputStream.close() }
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    private fun bash(command: String) =
        "Bash" to
            buildJsonObject {
                put("command", command)
                put("description", "test")
            }

    private fun transcriptTitles(): List<String> =
        home
            .resolve(".claude/projects")
            .walkTopDown()
            .filter { it.name.endsWith(".jsonl") }
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
        Claude(
                "bypassPermissions",
                """{"permissions":{"deny":["Bash(touch denied.txt)"],"ask":["Bash(mkdir:*)"]}}""",
            )
            .use { claude ->
                claude.turn("run the tools")
                // 拒绝：不问就拒；询问：照样问（这里答了拒绝）；别的照常放行
                assertEquals(listOf("Bash"), claude.asks)
                assertTrue(results[0].toString(), results[0].second && "denied" in results[0].first)
                assertEquals("USER-DENIED" to true, results[1])
                assertEquals(false, results[2].second)
                assertTrue(File(home, "proj/denied.txt").exists().not())

                // -p 模式不会自己起标题
                assertEquals(
                    emptyList<String>(),
                    synchronized(titleRequests) { titleRequests.toList() },
                )
                // generate_session_title 带 persist：用小模型起一个，写进会话记录（ai-title），应答里带回
                val title =
                    claude.control(
                        buildJsonObject {
                            put("subtype", "generate_session_title")
                            put("description", "帮我给设置页加一个工具权限页面\n\ndone")
                            put("persist", true)
                        },
                    )
                assertEquals("假标题 一号", title.obj("response")?.str("title"))
                assertEquals(1, synchronized(titleRequests) { titleRequests.size })
            }
        assertEquals(listOf("ai-title:假标题 一号"), transcriptTitles())
    }

    @Test
    fun askBeatsAllowAndCustomTitlesStay() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        script = listOf(bash("touch made.txt"))
        Claude("default", """{"permissions":{"allow":["Bash(touch:*)"],"ask":["Bash"]}}""").use {
            claude ->
            claude.turn("run the tools")
            // 细的允许压不过粗的询问
            assertEquals(listOf("Bash"), claude.asks)
            assertEquals("USER-DENIED" to true, results[0])

            claude.control(
                buildJsonObject {
                    put("subtype", "rename_session")
                    put("title", "我起的名字")
                    put("source", "host")
                },
            )
            val title =
                claude.control(
                    buildJsonObject {
                        put("subtype", "generate_session_title")
                        put("description", "帮我给设置页加一个工具权限页面")
                        put("persist", true)
                    },
                )
            // 已经有自定义标题：原样返回，不另起
            assertEquals("我起的名字", title.obj("response")?.str("title"))
            // 内容太短不起
            val short =
                claude.control(
                    buildJsonObject {
                        put("subtype", "generate_session_title")
                        put("description", "你好")
                        put("persist", false)
                    },
                )
            assertEquals(null, short.obj("response")?.str("title"))
        }
        assertEquals(emptyList<String>(), synchronized(titleRequests) { titleRequests.toList() })
        assertEquals(listOf("custom-title:我起的名字"), transcriptTitles())
    }

    /** settings.json：SessionEnd hook 把 Claude Code 交给它的输入（一行 JSON，带 reason）追加进 [log]。 */
    private fun sessionEndHook(log: File) =
        """{"hooks":{"SessionEnd":[{"hooks":[{"type":"command","command":"cat >> ${log.path}; echo >> ${log.path}"}]}]}}"""

    private fun sessionEndReasons(log: File): List<String?> =
        if (!log.exists()) emptyList()
        else
            log.readLines()
                .filter { it.isNotBlank() }
                .map { (json.parseToJsonElement(it) as JsonObject).str("reason") }

    private fun endSession(): JsonObject = buildJsonObject { put("subtype", "end_session") }

    /**
     * 控制请求 end_session 是 -p 模式下结束会话的入口，等价于终端里的 /exit（-p 模式不认 /exit，发了只答 “/exit isn't available in
     * this environment”）：答 success，输入管道一直开着也自己退出，退出码 0，SessionEnd hook 照跑 （reason 是 other，-p 模式不报
     * prompt_input_exit）。zeta_host.sh stop 先发它。
     */
    @Test
    fun endSessionExitsLikeExitCommand() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        val log = File(home, "session-end.log")
        Claude("bypassPermissions", sessionEndHook(log)).use { claude ->
            claude.turn("say hi")
            assertEquals("success", claude.control(endSession()).str("subtype"))
            assertEquals(0, claude.exitCode(60))
            assertEquals(listOf("other"), sessionEndReasons(log))
        }
    }

    /**
     * 一轮进行中（工具在跑）收到 end_session：先答 success，不等工具跑完，这一轮按打断收尾（result 是 error_during_execution），
     * 照样自己退出、跑 SessionEnd hook；退出码 1。
     */
    @Test
    fun endSessionStopsTheRunningTurn() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        // 工具跑得比下面任何一个限时都长：等它跑完才收尾的话 result 等不到，不用按秒数判断
        script = listOf(bash("timeout 300 tail -f /dev/null"))
        val log = File(home, "session-end.log")
        Claude("bypassPermissions", sessionEndHook(log)).use { claude ->
            claude.user("44444444-2222-4333-8444-555555555555", "run the tools")
            claude.until { o ->
                o.str("type") == "assistant" &&
                    "\"tool_use\"" in o.obj("message")?.get("content").toString()
            }
            claude.send(
                buildJsonObject {
                    put("type", "control_request")
                    put("request_id", "end")
                    put("request", endSession())
                },
            )
            val result = claude.until { it.str("type") == "result" }.last()
            assertEquals("error_during_execution", result.str("subtype"))
            assertEquals(1, claude.exitCode(60))
            assertEquals(listOf("other"), sessionEndReasons(log))
        }
    }

    /**
     * 一轮进行中发来的消息（Zeta 的排队条）：它有自己的 command_lifecycle started，在工具边界并进这一轮，两条消息只出一个 result。
     * Conversation 据此在 started 时把排队的消息放进聊天记录，在 result 时只要没有还在排队的就算这一轮结束。
     */
    @Test
    fun followUpJoinsTheRunningTurn() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        script = listOf(bash("sleep 2"))
        val first = "11111111-2222-4333-8444-555555555555"
        val second = "33333333-2222-4333-8444-555555555555"
        Claude("bypassPermissions", "{}").use { claude ->
            claude.user(first, "run the tools")
            val events =
                claude
                    .until { o ->
                        o.str("type") == "assistant" &&
                            "\"tool_use\"" in o.obj("message")?.get("content").toString()
                    }
                    .toMutableList()
            // 工具还在跑（sleep 2）时插话
            claude.user(second, "then say done")
            events += claude.until { it.str("type") == "result" }
            // 没并进这一轮的话，Claude Code 会接着另起一轮，假 API 随问随答，几秒内就有第二个 result
            events += claude.settle(3_000)
            fun startedAt(uuid: String) = events.indexOfFirst {
                it.str("type") == "command_lifecycle" &&
                    it.str("command_uuid") == uuid &&
                    it.str("state") == "started"
            }
            assertTrue("第一条有 started", startedAt(first) >= 0)
            assertTrue(
                "插话也有 started，而且在这一轮的 result 之前",
                startedAt(second) in 0 until events.indexOfFirst { it.str("type") == "result" },
            )
            assertEquals("两条消息只出一个 result", 1, events.count { it.str("type") == "result" })
        }
    }

    /**
     * 立即插话（终端排队提示上的 send now，Zeta 排队条的 ⚡）：interrupt 带 send_now 和排队消息的 uuid。init 的 capabilities 带
     * interrupt_send_now_v1 才认，Conversation.steerNow 据此决定发哪种。正在跑的命令能挪到后台（还要
     * background_tasks_moves_bash_v1）时不停这一轮：命令挪走，排队的消息并进这一轮，这一轮照常以 success 结束，应答 delivering；
     * 挪不了就停下这一轮（应答 stopped），排队的消息留在队里、接着另起一轮。两种都不用等那条命令跑完。
     */
    @Test
    fun sendNowDeliversWithoutWaitingForTheTool() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        // 单独一条 sleep 会被 Claude Code 拦下（要等待得用 Monitor），换一条同样一直跑的
        script = listOf(bash("timeout 40 tail -f /dev/null"))
        val first = "11111111-2222-4333-8444-666666666666"
        val second = "33333333-2222-4333-8444-666666666666"
        Claude("bypassPermissions", "{}").use { claude ->
            claude.user(first, "run the tools")
            val events =
                claude
                    .until { o ->
                        o.str("type") == "assistant" &&
                            "\"tool_use\"" in o.obj("message")?.get("content").toString()
                    }
                    .toMutableList()
            val capabilities =
                events
                    .first { it.str("type") == "system" && it.str("subtype") == "init" }[
                        "capabilities"]
                    .toString()
            assertTrue(
                "init 声明了 interrupt_send_now_v1",
                "\"interrupt_send_now_v1\"" in capabilities,
            )
            val movesAside = "\"background_tasks_moves_bash_v1\"" in capabilities
            // send now 只认已经在 Claude Code 队列里的消息
            claude.user(second, "then say done")
            events += claude.until { o ->
                o.str("type") == "command_lifecycle" &&
                    o.str("command_uuid") == second &&
                    o.str("state") == "queued"
            }
            val asked = System.currentTimeMillis()
            claude.send(
                buildJsonObject {
                    put("type", "control_request")
                    put("request_id", "send-now")
                    putJsonObject("request") {
                        put("subtype", "interrupt")
                        put("send_now", true)
                        put("message_uuid", second)
                    }
                },
            )
            events += claude.until { it.str("type") == "result" }
            val waited = System.currentTimeMillis() - asked
            assertTrue("没等那条 40 秒的命令跑完（等了 $waited 毫秒）", waited < 25_000)
            val answer =
                events
                    .first {
                        it.str("type") == "control_response" &&
                            it.obj("response")?.str("request_id") == "send-now"
                    }
                    .obj("response")
                    ?.obj("response")
            val result = events.last()
            fun startedAt(uuid: String) = events.indexOfFirst {
                it.str("type") == "command_lifecycle" &&
                    it.str("command_uuid") == uuid &&
                    it.str("state") == "started"
            }
            if (movesAside) {
                assertEquals("delivering", answer?.str("send_now"))
                assertEquals("这一轮没被打断", "success", result.str("subtype"))
                assertTrue("插话并进了这一轮", startedAt(second) in 0 until events.indexOf(result))
                assertTrue(
                    "命令挪到了后台",
                    events.any {
                        it.str("type") == "system" &&
                            it.str("subtype") == "background_tasks_changed" &&
                            it["tasks"].toString() != "[]"
                    },
                )
            } else {
                assertEquals("stopped", answer?.str("send_now"))
                assertEquals("排队的消息还在", "[\"$second\"]", answer?.get("still_queued").toString())
                assertEquals(-1, startedAt(second))
                events += claude.until { it.str("type") == "result" }
                assertTrue("插话接着另起一轮", startedAt(second) >= 0)
            }
        }
    }
}
