package com.dzshzx.zeta.core.claude

import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.SubagentTranscript
import com.dzshzx.zeta.core.TaskBoard
import com.dzshzx.zeta.core.TaskKind
import com.dzshzx.zeta.core.TaskState
import com.dzshzx.zeta.core.TestHelper
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.WorkItem
import com.dzshzx.zeta.core.waitUntil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 后台任务面板依赖的 Claude Code 行为（2.1.328 实测），在这里钉住：升级 Claude Code 后跑一遍，变了就会失败， [TaskBoard]
 * 和面板跟着改。收到的真实事件也喂给 [TaskBoard]，看它归约出的样子。
 *
 * - 后台任务进出都有 system 事件：background_tasks_changed（完整清单，先于 task_started）、task_started、
 *   task_progress（子代理）、task_updated（patch.status）、task_notification（completed / failed / stopped）。
 * - 控制请求 get_task_output 读后台命令和监视器输出的最后 8 KiB（子代理答 error）；stop_task 停一个任务， 对不认识的 id 也答
 *   success，所以停没停要看随后的事件。停后台命令不另起一轮；子代理结束（做完或被停）后 Claude Code 自己起一轮。
 * - 监视器（Monitor）的 task_type 也是 local_bash，靠发起它的工具调用区分。
 * - 子代理的记录（子代理查看页读的）：`<项目>/<会话 id>/subagents/agent-<id>.jsonl`，id 就是 task_started 的 task_id， 也在
 *   Agent 调用的结果（tool_use_result.agentId）里；旁边的 agent-<id>.meta.json 记着发起它的 Agent 调用（toolUseId）、
 *   前台还是后台（requestShape），做完加 finished，被停加 stoppedByUser。输出流里子代理内部的事件带 parent_tool_use_id，
 *   但前台子代理只送工具调用和结果、不送它说的话，所以以记录为准。Agent 默认放后台，前台要 run_in_background: false。
 *
 * 和 ClaudeCodeContractTest 一样：真的 claude 命令行跑在临时 HOME 上，连本机一个假的 Messages API（假 key，不出网、不花钱）。
 */
class ClaudeTaskContractTest {
    private lateinit var home: File
    private lateinit var server: HttpServer
    private val json = Json { ignoreUnknownKeys = true }

    /** 主对话要依次调用的工具（名字、参数）。 */
    @Volatile private var script: List<Pair<String, JsonObject>> = emptyList()

    /** 子代理的请求等它放行才答（停子代理的测试要它一直在跑）。 */
    private val subagentGate = CountDownLatch(1)
    @Volatile private var subagentWaits = false

    /** 子代理做完第一步、交报告之前等它放行（在它跑着的时候读记录）。 */
    private val reportGate = CountDownLatch(1)
    @Volatile private var pauseBeforeReport = false

    @Before
    fun setUp() {
        home = java.nio.file.Files.createTempDirectory("zeta-task-contract").toFile().canonicalFile
        File(home, ".claude").mkdirs()
        File(home, "proj").mkdirs()
        File(home, ".claude/settings.json").writeText("{}")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // 子代理和主对话的请求会同时来：每个请求一个线程
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        server.createContext("/") { ex ->
            runCatching { serve(ex) }
                .onFailure {
                    runCatching {
                        ex.sendResponseHeaders(500, -1)
                        ex.close()
                    }
                }
        }
        server.start()
    }

    @After
    fun tearDown() {
        subagentGate.countDown()
        reportGate.countDown()
        if (::server.isInitialized) server.stop(0)
        if (::home.isInitialized) home.deleteRecursively()
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

    /** 假模型这一次答什么（内容块、stop_reason）：没带工具的辅助请求答 ok，子代理按它的剧本，主对话照 [script]。 */
    private fun replyTo(body: JsonObject): Pair<List<JsonObject>, String> {
        val messages = body["messages"] as? JsonArray ?: JsonArray(emptyList())
        val tools = (body["tools"] as? JsonArray).orEmpty()
        val results = messages.sumOf { m ->
            ((m as JsonObject)["content"] as? JsonArray).orEmpty().count {
                (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == "tool_result"
            }
        }
        val firstText = firstText(messages)
        return when {
            tools.isEmpty() -> listOf(text("ok")) to "end_turn"
            // 子代理：先说一句、调一次 Bash，再交回报告
            SUBAGENT in firstText -> subagentReply(results)
            else -> {
                val next = script.getOrNull(results)
                if (next != null)
                    listOf(toolUse("toolu_$results", next.first, next.second)) to "tool_use"
                else listOf(text("done")) to "end_turn"
            }
        }
    }

    /** 第一条消息的文字（字符串，或者文字块用空格接起来）：子代理的请求靠它认出来。 */
    private fun firstText(messages: JsonArray): String =
        when (val first = (messages.firstOrNull() as? JsonObject)?.get("content")) {
            is JsonPrimitive -> first.content
            is JsonArray ->
                first.joinToString(" ") {
                    (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                }
            else -> ""
        }

    /** 子代理收到了 [results] 个工具结果时答什么；按测试要求先等放行。 */
    private fun subagentReply(results: Int): Pair<List<JsonObject>, String> {
        if (subagentWaits) subagentGate.await(60, TimeUnit.SECONDS) else Thread.sleep(1500)
        if (pauseBeforeReport && results > 0) reportGate.await(60, TimeUnit.SECONDS)
        if (results > 0) return listOf(text("REPORT: all good")) to "end_turn"
        return listOf(
            text("checking first"),
            toolUse(
                "toolu_s0",
                "Bash",
                buildJsonObject {
                    put("command", "echo from-sub")
                    put("description", "sub step")
                },
            ),
        ) to "tool_use"
    }

    private fun text(value: String) = buildJsonObject {
        put("type", "text")
        put("text", value)
    }

    private fun toolUse(id: String, name: String, input: JsonObject) = buildJsonObject {
        put("type", "tool_use")
        put("id", id)
        put("name", name)
        put("input", input)
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
                put("message", JsonObject(message(emptyList(), stop) + ("stop_reason" to JsonNull)))
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

    /** 一个 stream-json 的 claude 进程，和 Zeta 起的一样。收到的每条事件按顺序记在 [events]。 */
    private inner class Claude(asZeta: Boolean = false) : AutoCloseable {
        private val process: Process
        private val lines = LinkedBlockingQueue<String>()
        val events = mutableListOf<JsonObject>()
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
                        "--permission-mode",
                        "bypassPermissions",
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
            // [asZeta] 时发的就是 Zeta 发的那份 initialize（带着它的声明）
            control(
                if (asZeta) Conversation.initializeRequest()
                else buildJsonObject { put("subtype", "initialize") },
            )
        }

        fun send(o: JsonObject) {
            process.outputStream.write((o.toString() + "\n").toByteArray())
            process.outputStream.flush()
        }

        fun turn(prompt: String) {
            send(userMessage(prompt))
            until { it.str("type") == "result" }
        }

        /** 发一个控制请求，等到它的应答（control_response 里的 response）。 */
        fun control(request: JsonObject): JsonObject {
            val id = "c${++seq}"
            send(
                buildJsonObject {
                    put("type", "control_request")
                    put("request_id", id)
                    put("request", request)
                },
            )
            return until {
                    it.str("type") == "control_response" &&
                        it.obj("response")?.str("request_id") == id
                }
                .obj("response")!!
        }

        fun taskOutput(id: String) =
            control(
                buildJsonObject {
                    put("subtype", "get_task_output")
                    put("task_id", id)
                },
            )

        /** 读输出直到里面有 [text]（命令是异步写的）。 */
        fun awaitOutput(id: String, text: String): JsonObject {
            val deadline = System.currentTimeMillis() + 20_000
            while (true) {
                val response = taskOutput(id)
                if (text in response.obj("response")?.str("output").orEmpty()) return response
                assertTrue("输出里一直没有 $text：$response", System.currentTimeMillis() < deadline)
                Thread.sleep(200)
            }
        }

        /** 读事件直到 [done]，返回那一条。[seconds] 秒内没等到就失败。 */
        fun until(seconds: Long = 60, done: (JsonObject) -> Boolean): JsonObject =
            poll(seconds, done) ?: throw AssertionError("claude 没有按时回应")

        /** 读事件直到 [done] 或者 [seconds] 秒过去（返回 null）。 */
        fun poll(seconds: Long, done: (JsonObject) -> Boolean): JsonObject? {
            val deadline = System.currentTimeMillis() + seconds * 1000
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                val line = lines.poll(left, TimeUnit.MILLISECONDS) ?: continue
                val o =
                    runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                        ?: continue
                events += o
                if (done(o)) return o
            }
        }

        /** 从第 [from] 条起，某个 system 事件的 subtype 序列（看先后）。 */
        fun systemSince(from: Int): List<String> =
            events.drop(from).filter { it.str("type") == "system" }.mapNotNull { it.str("subtype") }

        override fun close() {
            runCatching { process.outputStream.close() }
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    private fun JsonObject.arr(key: String) = this[key] as? JsonArray

    private fun List<JsonObject>.system(subtype: String) = filter {
        it.str("type") == "system" && it.str("subtype") == subtype
    }

    private fun board(events: List<JsonObject>): TaskBoard =
        TaskBoard().also { board -> events.forEachIndexed { i, e -> board.observe(e, i.toLong()) } }

    @Test
    fun backgroundCommandOutputAndStop() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        val command = "echo hello-bg; sleep 0.3; echo second-line; sleep 300"
        script = listOf(bash(command, "ticker in background", background = true))
        Claude().use { claude ->
            claude.turn("go")
            val listed =
                claude.events
                    .system("background_tasks_changed")
                    .first()
                    .arr("tasks")!!
                    .single()
                    .jsonObject
            assertEquals("local_bash", listed.str("task_type"))
            assertEquals("ticker in background", listed.str("description"))
            val id = listed.str("task_id")!!
            // 清单先到，task_started 随后，带着发起它的 tool_use id
            val order = claude.systemSince(0)
            assertTrue(
                order.toString(),
                order.indexOf("background_tasks_changed") < order.indexOf("task_started"),
            )
            val started = claude.events.system("task_started").single()
            assertEquals(id, started.str("task_id"))
            assertEquals("toolu_0", started.str("tool_use_id"))
            assertEquals("true", started.str("is_backgrounded"))

            val output = claude.awaitOutput(id, "second-line")
            assertEquals("success", output.str("subtype"))
            assertEquals("hello-bg\nsecond-line\n", output.obj("response")!!.str("output"))
            assertEquals("21", output.obj("response")!!.str("total_bytes"))
            assertEquals("false", output.obj("response")!!.str("truncated"))

            val before = claude.events.size
            val stopped =
                claude.control(
                    buildJsonObject {
                        put("subtype", "stop_task")
                        put("task_id", id)
                    },
                )
            assertEquals("success", stopped.str("subtype"))
            // 应答之前事件已经到齐：task_updated（killed）、task_notification（stopped）、空清单
            assertEquals(
                listOf("task_updated", "task_notification", "background_tasks_changed"),
                claude.systemSince(before),
            )
            val since = claude.events.drop(before)
            assertEquals(
                "killed",
                since.system("task_updated").single().obj("patch")!!.str("status"),
            )
            assertEquals("stopped", since.system("task_notification").single().str("status"))
            assertEquals(0, since.system("background_tasks_changed").single().arr("tasks")!!.size)
            // 停后台命令不另起一轮
            assertNull(claude.poll(3) { it.str("type") == "system" && it.str("subtype") == "init" })
            // 停了以后输出还读得到，末尾标着 [killed]
            assertTrue(
                claude.taskOutput(id).obj("response")!!.str("output")!!.endsWith("[killed]\n"),
            )
            // 不认识的任务也答 success
            assertEquals(
                "success",
                claude
                    .control(
                        buildJsonObject {
                            put("subtype", "stop_task")
                            put("task_id", "bnotexist1")
                        },
                    )
                    .str("subtype"),
            )

            val task = board(claude.events).tasks.single()
            assertEquals(TaskKind.Command, task.kind)
            assertEquals(TaskState.Stopped, task.state)
            assertEquals(command, task.command)
            assertEquals("ticker in background", task.description)
        }
    }

    @Test
    fun backgroundSubagentReportsAndStartsATurn() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        script = listOf("Agent" to agentInput())
        Claude().use { claude ->
            claude.turn("go")
            val id = claude.events.system("task_started").single().str("task_id")!!
            val started = claude.events.system("task_started").single()
            assertEquals("local_agent", started.str("task_type"))
            assertEquals("general-purpose", started.str("subagent_type"))
            assertEquals("$SUBAGENT look around", started.str("prompt"))
            // 子代理没有可读的输出
            val output = claude.taskOutput(id)
            assertEquals("error", output.str("subtype"))

            // 做完：进展、结局，然后 Claude Code 自己起一轮（init，前面没有用户消息）
            claude.until { it.str("type") == "system" && it.str("subtype") == "task_notification" }
            claude.until { it.str("type") == "system" && it.str("subtype") == "init" }
            claude.until { it.str("type") == "result" }
            val progress = claude.events.system("task_progress").first()
            assertEquals("Bash", progress.str("last_tool_name"))
            val done = claude.events.system("task_notification").single()
            assertEquals("completed", done.str("status"))
            assertEquals("REPORT: all good", done.str("summary"))

            val task = board(claude.events).tasks.single()
            assertEquals(TaskKind.Agent, task.kind)
            assertEquals(TaskState.Completed, task.state)
            assertEquals("REPORT: all good", task.summary)
            assertEquals("$SUBAGENT look around", task.command)
            assertNotNull(task.progress)
        }
    }

    @Test
    fun stoppedSubagentStartsATurn() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        subagentWaits = true
        script = listOf("Agent" to agentInput())
        Claude().use { claude ->
            claude.turn("go")
            val id = claude.events.system("task_started").single().str("task_id")!!
            val before = claude.events.size
            val inits = claude.events.system("init").size
            claude.control(
                buildJsonObject {
                    put("subtype", "stop_task")
                    put("task_id", id)
                },
            )
            // 和后台命令不同，子代理的结局不一定在应答之前到齐（要先中止它进行中的模型请求）：读到清单变化为止再看先后
            if ("background_tasks_changed" !in claude.systemSince(before))
                claude.until {
                    it.str("type") == "system" && it.str("subtype") == "background_tasks_changed"
                }
            assertEquals(
                listOf("task_updated", "task_notification", "background_tasks_changed"),
                claude.systemSince(before).filter { it != "init" },
            )
            assertEquals(
                "stopped",
                claude.events.system("task_notification").single().str("status"),
            )
            // 和后台命令不同：子代理停下后 Claude Code 另起一轮处理这个结局
            if (claude.events.system("init").size == inits)
                claude.until { it.str("type") == "system" && it.str("subtype") == "init" }
            assertEquals(TaskState.Stopped, board(claude.events).tasks.single().state)
            // 记录的 meta 记着是被停下的，没有 finished。meta 是 Claude Code 另写的文件，和输出流没有先后：等它写上
            val session = claude.sessionId()
            var meta: JsonObject? = null
            assertTrue(
                "meta 一直没记上 stoppedByUser",
                waitUntil {
                    meta = runCatching { subagentMeta(session, id) }.getOrNull()
                    meta?.str("stoppedByUser") == "true"
                },
            )
            assertNull(meta!!["finished"])
        }
    }

    @Test
    fun monitorIsALocalBashTaskWithOutput() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        val command = "for i in 1 2 3; do echo evt-\$i; sleep 0.3; done; sleep 300"
        script =
            listOf(
                "ToolSearch" to
                    buildJsonObject {
                        put("query", "select:Monitor")
                        put("max_results", 1)
                    },
                "Monitor" to
                    buildJsonObject {
                        put("description", "watch events")
                        put("timeout_ms", 60000)
                        put("command", command)
                    },
            )
        Claude().use { claude ->
            claude.turn("go")
            val listed =
                claude.events
                    .system("background_tasks_changed")
                    .first()
                    .arr("tasks")!!
                    .single()
                    .jsonObject
            assertEquals("local_bash", listed.str("task_type"))
            val id = listed.str("task_id")!!
            assertEquals(
                "evt-1\nevt-2\nevt-3\n",
                claude.awaitOutput(id, "evt-3").obj("response")!!.str("output"),
            )
            claude.control(
                buildJsonObject {
                    put("subtype", "stop_task")
                    put("task_id", id)
                },
            )
            val task = board(claude.events).tasks.single()
            assertEquals(TaskKind.Monitor, task.kind)
            assertEquals(command, task.command)
            assertEquals(TaskState.Stopped, task.state)
        }
    }

    @Test
    fun foregroundSubagentIsRecordedAndOnlyPartlyStreamed() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        script = listOf("Agent" to agentInput(background = false))
        Claude().use { claude ->
            claude.turn("go")
            val started = claude.events.system("task_started").single()
            assertEquals("false", started.str("is_backgrounded"))
            val id = started.str("task_id")!!
            // Agent 调用做完时，它的结果里也有子代理 id
            val result =
                claude.events.single {
                    it.str("type") == "user" &&
                        it["parent_tool_use_id"] is JsonNull &&
                        it.obj("tool_use_result") != null
                }
            assertEquals(id, result.obj("tool_use_result")!!.str("agentId"))
            // 子代理内部的事件带 parent_tool_use_id 和 agent_id，但前台子代理不送它说的话
            val inner = claude.events.filter { it.str("parent_tool_use_id") == "toolu_0" }
            assertTrue(inner.all { it.str("agent_id") == id })
            val innerBlocks =
                inner
                    .filter { it.str("type") == "assistant" }
                    .flatMap {
                        it.obj("message")!!.arr("content").orEmpty().map { b -> b.jsonObject }
                    }
            assertEquals(
                listOf("toolu_s0"),
                innerBlocks.filter { it.str("type") == "tool_use" }.map { it.str("id") },
            )
            assertTrue(innerBlocks.none { it.str("type") == "text" })

            val session = claude.sessionId()
            val meta = subagentMeta(session, id)
            assertEquals("toolu_0", meta.str("toolUseId"))
            assertEquals("foreground", meta.str("requestShape"))
            assertEquals("true", meta.str("finished"))
            // 助手脚本照 Agent 调用找到记录：它说的话、它的步骤和报告都在；输出流里送来的已经都在记录里
            val transcript = SubagentTranscript("toolu_0")
            assertTrue(transcript.applyRead(subagentRead(transcript, "-", session)))
            assertEquals("$SUBAGENT look around", transcript.prompt)
            assertEquals(id, transcript.meta!!.agentId)
            assertEquals(false, transcript.meta!!.background)
            assertEquals(
                listOf("checking first", "Bash:Success", "REPORT: all good"),
                transcript.items(running = false).map(::label),
            )
            inner.forEach { assertFalse(it.toString(), transcript.observeLive(it)) }
        }
    }

    @Test
    fun backgroundSubagentRecordIsReadWhileItRuns() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        pauseBeforeReport = true
        script = listOf("Agent" to agentInput())
        Claude().use { claude ->
            claude.turn("go")
            val id = claude.events.system("task_started").single().str("task_id")!!
            val session = claude.sessionId()
            // 后台子代理内部的事件在这一轮结束后陆续送来
            claude.until {
                it.str("type") == "user" &&
                    it.str("parent_tool_use_id") == "toolu_0" &&
                    "tool_result" in it.toString()
            }
            // 第一步做完、还没交报告：记录里已经有它说的话和这一步，meta 还没结束
            val transcript = SubagentTranscript("toolu_0")
            val deadline = System.currentTimeMillis() + 20_000
            while (true) {
                transcript.applyRead(subagentRead(transcript, id, session))
                if (
                    transcript.items(running = true).map(::label) ==
                        listOf("checking first", "Bash:Success")
                )
                    break
                assertTrue(
                    transcript.items(running = true).map(::label).toString(),
                    System.currentTimeMillis() < deadline,
                )
                Thread.sleep(200)
            }
            assertFalse(transcript.meta!!.ended)
            assertEquals(true, transcript.meta!!.background)
            val offset = transcript.offset

            reportGate.countDown()
            claude.until { it.str("type") == "system" && it.str("subtype") == "task_notification" }
            // task_notification 到的时候，meta 的结局和最后那句报告都可能还没写进文件，先后也不一定
            // （多数时候 meta 先、报告晚两百毫秒上下，机器忙时 meta 也会晚）。所以结束后要读到 meta 记着做完、
            // 记录又停下来（settled）才停止读；接着上次的位置读，只读新写的，报告接在后面
            transcript.applyRead(subagentRead(transcript, id, session))
            val until = System.currentTimeMillis() + 20_000
            while (transcript.meta?.finished != true || !transcript.settled) {
                assertTrue(
                    "meta.finished=${transcript.meta?.finished} settled=${transcript.settled}",
                    System.currentTimeMillis() < until,
                )
                Thread.sleep(300)
                transcript.applyRead(subagentRead(transcript, id, session))
            }
            assertTrue(transcript.offset > offset)
            assertEquals(
                listOf("checking first", "Bash:Success", "REPORT: all good"),
                transcript.items(running = false).map(::label),
            )
        }
    }

    private fun label(item: ChatItem): String =
        when (item) {
            is AnswerItem -> item.text
            is WorkItem ->
                item.steps.joinToString { step ->
                    (step as ToolStep).let { it.name + ":" + it.status }
                }
            else -> item.toString()
        }

    private fun Claude.sessionId(): String =
        events.first { it.str("type") == "result" }.str("session_id")!!

    private fun subagentMeta(session: String, id: String): JsonObject {
        val project = File(home, ".claude/projects").listFiles()!!.single()
        return json
            .parseToJsonElement(File(project, "$session/subagents/agent-$id.meta.json").readText())
            .jsonObject
    }

    /** zeta-helper subagent：从 [transcript] 上次读到的地方接着读，和 Zeta 在手机上做的一样（这里在临时 HOME 上）。 */
    private fun subagentRead(
        transcript: SubagentTranscript,
        hint: String,
        session: String,
    ): String {
        val args =
            listOf(
                "subagent",
                transcript.offset.toString(),
                transcript.line.toString(),
                transcript.toolUseId,
                hint,
                session,
            )
        val process =
            ProcessBuilder(listOf(TestHelper.path) + args)
                .apply { environment()["HOME"] = home.path }
                .start()
        process.outputStream.close()
        val out = process.inputStream.bufferedReader().readText()
        process.errorStream.readBytes()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(out, 0, process.exitValue())
        return out
    }

    private fun bash(command: String, description: String, background: Boolean) =
        "Bash" to
            buildJsonObject {
                put("command", command)
                put("description", description)
                if (background) put("run_in_background", true)
            }

    private fun userMessage(text: String) = buildJsonObject {
        put("type", "user")
        putJsonObject("message") {
            put("role", "user")
            put("content", text)
        }
    }

    /** 一轮进行中（前台命令在跑），后台有一个命令和一个子代理，这时停止这一轮（interrupt）。返回 interrupt 之后 8 秒里的事件。 */
    private fun interruptWithBackgroundWork(claude: Claude): List<JsonObject> {
        claude.send(userMessage("go"))
        claude.until { it.str("type") == "assistant" && "toolu_2" in it.toString() }
        // 前台命令起来以后再停
        Thread.sleep(1500)
        val before = claude.events.size
        val answer = claude.control(buildJsonObject { put("subtype", "interrupt") })
        assertEquals("success", answer.str("subtype"))
        claude.poll(8) { false }
        return claude.events.drop(before)
    }

    private fun backgroundWorkScript() =
        listOf(
            bash("sleep 300", "bg sleeper", background = true),
            "Agent" to agentInput(),
            bash("sleep 60", "foreground sleeper", background = false),
        )

    /** 不声明有逐个停止的按钮时，停止一轮会把后台子代理一起结束（后台命令留着）。 */
    @Test
    fun interruptKillsBackgroundSubagentsWhenUndeclared() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        subagentWaits = true
        script = backgroundWorkScript()
        Claude().use { claude ->
            val since = interruptWithBackgroundWork(claude)
            val agent =
                claude.events.system("task_started").first { it.str("task_type") == "local_agent" }
            val killed = since.system("task_updated").single()
            assertEquals(agent.str("task_id"), killed.str("task_id"))
            assertEquals("killed", killed.obj("patch")!!.str("status"))
            assertEquals("stopped", since.system("task_notification").single().str("status"))
            // 清单里只剩后台命令
            val left = since.system("background_tasks_changed").last().arr("tasks")!!
            assertEquals(listOf("local_bash"), left.map { it.jsonObject.str("task_type") })
        }
    }

    /** 用 Zeta 发的那份 initialize（里面声明了）：停止一轮只停这一轮，后台子代理和后台命令都接着跑，之后还能逐个停。 */
    @Test
    fun interruptSparesBackgroundSubagentsWhenDeclared() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        subagentWaits = true
        script = backgroundWorkScript()
        Claude(asZeta = true).use { claude ->
            val since = interruptWithBackgroundWork(claude)
            // 这一轮停了
            assertTrue(since.any { it.str("type") == "result" })
            // 后台的两样都没动
            assertEquals(emptyList<JsonObject>(), since.system("task_updated"))
            assertEquals(emptyList<JsonObject>(), since.system("task_notification"))
            val agent =
                claude.events.system("task_started").first { it.str("task_type") == "local_agent" }
            val before = claude.events.size
            claude.control(
                buildJsonObject {
                    put("subtype", "stop_task")
                    put("task_id", agent.str("task_id"))
                },
            )
            if (claude.events.drop(before).system("task_notification").isEmpty())
                claude.until {
                    it.str("type") == "system" && it.str("subtype") == "task_notification"
                }
            assertEquals(
                "stopped",
                claude.events.drop(before).system("task_notification").single().str("status"),
            )
        }
    }

    private fun agentInput(background: Boolean = true) = buildJsonObject {
        put("description", "look around")
        put("prompt", "$SUBAGENT look around")
        put("subagent_type", "general-purpose")
        put("run_in_background", background)
    }

    private companion object {
        /** 子代理的指令里带这个记号，假模型据此认出子代理的请求。 */
        const val SUBAGENT = "SUBAGENT-PROMPT"
    }
}
