package com.dzshzx.zeta.core.claude

import com.dzshzx.zeta.core.CodeRewind
import com.dzshzx.zeta.core.ElicitField
import com.dzshzx.zeta.core.ElicitationRequest
import com.dzshzx.zeta.core.PermissionReply
import com.dzshzx.zeta.core.PermissionRequest
import com.dzshzx.zeta.core.REJECT_WITH_FEEDBACK
import com.dzshzx.zeta.core.elicitationContent
import com.dzshzx.zeta.core.elicitationResponse
import com.dzshzx.zeta.core.parsePrompt
import com.dzshzx.zeta.core.permissionResponse
import com.dzshzx.zeta.core.waitUntil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Zeta 对齐终端版（TUI）交互靠的 Claude Code 行为（2.1.330 实测），在这里钉住：升级 Claude Code 后跑一遍，变了就会失败，
 * core/Prompts.kt、Rewind.kt 的 CodeRewind、Conversation 的 `!` 命令和 zeta-helper 的 hosts 跟着改。
 *
 * 真的 claude 命令行跑在临时 HOME 上，连本机一个假的 Messages API（假 key，不出网、不花钱），做法同 [ClaudeCodeContractTest]； 应答用的就是
 * Zeta 自己拼的（[permissionResponse]、[elicitationResponse]）。钉住的是：
 * - 工具确认带着 permission_suggestions；“不再问”原样交回 updatedPermissions，规则写进项目的
 *   settings.local.json，下次同样的调用不再问。
 * - 不写理由的拒绝（interrupt）：模型看到 STOP 那句、这一轮以 aborted_tools 结束，不再请求模型；写了理由的：模型看到 TUI 那句加理由，接着做。
 * - 计划审批：计划写进计划文件后 ExitPlanMode 的 input 带 plan；批准时的 setMode 就是之后的模式（status 行报出来）。
 * - AskUserQuestion：answers 里自己写的回答、annotations 的预览和备注，模型都看得到。
 * - 主对话和后台子代理的确认会同时挂着（子代理的带 agent_id）；重新 initialize 时 pending_permission_requests 列出全部没答的。
 * - MCP 服务器的 elicitation 以 control_request 交给 Zeta（stdio + --permission-prompt-tool stdio 默认就会），交回的
 *   content 原样到服务器。
 * - bash_command（TUI 的 `!`）：直接执行，回放 <bash-input>、<bash-stdout> 两行，command_lifecycle completed
 *   收尾，不请求模型。
 * - rewind_files：起进程时设了 [CodeRewind.ENV] 才有快照；dry_run 报改过的文件，真还原把文件改回去；换个进程 --resume 照样能还原。
 */
class ClaudeInteractionContractTest {
    private lateinit var home: File
    private lateinit var server: HttpServer
    private val json = Json { ignoreUnknownKeys = true }

    /** 主对话接下来每次请求依次答的内容块；用完了答 done。 */
    private val main = LinkedBlockingQueue<List<JsonObject>>()

    /** 子代理（第一条消息含 SUBTASK）每次请求依次答的。 */
    private val sub = LinkedBlockingQueue<List<JsonObject>>()

    /** 主对话请求里见过的 tool_result：调用号 → 内容，按第一次见到的先后。 */
    private val seen = LinkedHashMap<String, String>()
    @Volatile private var mainRequests = 0

    /** 系统提醒里给的计划文件路径（计划模式下才有）。 */
    @Volatile private var planPath: String? = null

    @Before
    fun setUp() {
        home =
            java.nio.file.Files.createTempDirectory("zeta-interaction-contract")
                .toFile()
                .canonicalFile
        File(home, ".claude").mkdirs()
        File(home, "proj").mkdirs()
        File(home, ".claude/settings.json").writeText("{}")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
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

    private fun tool(id: String, name: String, input: String) = buildJsonObject {
        put("type", "tool_use")
        put("id", id)
        put("name", name)
        put("input", json.parseToJsonElement(input))
    }

    private fun text(value: String) = buildJsonObject {
        put("type", "text")
        put("text", value)
    }

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
        val raw = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val body = json.parseToJsonElement(raw).jsonObject
        val messages = body["messages"] as? JsonArray ?: JsonArray(emptyList())
        Regex("""/[^"\\ ]*/\.claude/plans/[^"\\ ]*\.md""").find(raw)?.let { planPath = it.value }
        val answer: List<JsonObject> =
            when {
                (body["tools"] as? JsonArray).isNullOrEmpty() -> listOf(text("""{"title":"t"}"""))
                messages.firstOrNull().toString().contains("SUBTASK") ->
                    sub.poll() ?: listOf(text("sub done"))
                else ->
                    synchronized(seen) {
                        mainRequests++
                        record(messages)
                        main.poll() ?: listOf(text("done"))
                    }
            }
        // 剧本里的 @PLAN@ 换成计划文件的路径：第一次请求时才知道
        val reply = answer.map {
            json.parseToJsonElement(it.toString().replace("@PLAN@", planPath.orEmpty())).jsonObject
        }
        val bytes = sse(reply).toByteArray()
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** 把请求里新出现的 tool_result 记进 [seen]。 */
    private fun record(messages: JsonArray) {
        val blocks = messages.flatMap {
            ((it as? JsonObject)?.get("content") as? JsonArray).orEmpty()
        }
        for (block in blocks.filterIsInstance<JsonObject>()) {
            val id = block["tool_use_id"]?.jsonPrimitive?.content ?: continue
            if (block["type"]?.jsonPrimitive?.content != "tool_result" || id in seen) continue
            seen[id] =
                when (val c = block["content"]) {
                    is JsonPrimitive -> c.content
                    is JsonArray ->
                        c.joinToString(" ") {
                            (it as? JsonObject)?.get("text")?.jsonPrimitive?.content.orEmpty()
                        }
                    else -> ""
                }
        }
    }

    /** 把 [reply] 这些内容块写成一次流式应答（SSE）。 */
    private fun sse(reply: List<JsonObject>): String {
        val stop =
            if (reply.any { it["type"]?.jsonPrimitive?.content == "tool_use" }) "tool_use"
            else "end_turn"
        val out = StringBuilder()
        fun event(o: JsonObject) =
            out.append("event: ")
                .append(o["type"]!!.jsonPrimitive.content)
                .append("\ndata: ")
                .append(o)
                .append("\n\n")
        event(
            buildJsonObject {
                put("type", "message_start")
                putJsonObject("message") {
                    put("id", "msg_fake")
                    put("type", "message")
                    put("role", "assistant")
                    put("model", "claude-fake")
                    put("content", JsonArray(emptyList()))
                    put("stop_reason", JsonNull)
                    putJsonObject("usage") {
                        put("input_tokens", 10)
                        put("output_tokens", 5)
                    }
                }
            },
        )
        reply.forEachIndexed { i, block ->
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
        return out.toString()
    }

    private inner class Claude(vararg extra: String, env: Map<String, String> = emptyMap()) :
        AutoCloseable {
        private val process: Process
        private val lines = LinkedBlockingQueue<String>()
        private var seq = 0

        init {
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
                            "--permission-prompt-tool",
                            "stdio",
                            "--allow-dangerously-skip-permissions",
                        ) + extra,
                    )
                    .directory(File(home, "proj"))
            builder.environment().apply {
                val path =
                    listOfNotNull(
                            System.getProperty("user.home")?.let { "$it/.local/bin" },
                            System.getenv("PATH"),
                        )
                        .joinToString(":")
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
                putAll(env)
            }
            builder.redirectError(File("/dev/null"))
            process = builder.start()
            Thread { process.inputStream.bufferedReader().forEachLine { lines.put(it) } }
                .apply {
                    isDaemon = true
                    start()
                }
        }

        fun send(o: JsonObject) {
            process.outputStream.write((o.toString() + "\n").toByteArray())
            process.outputStream.flush()
        }

        fun send(text: String) = send(json.parseToJsonElement(text).jsonObject)

        fun user(text: String, uuid: String? = null) =
            send(
                buildJsonObject {
                    put("type", "user")
                    uuid?.let { put("uuid", it) }
                    putJsonObject("message") {
                        put("role", "user")
                        put("content", text)
                    }
                },
            )

        fun respond(id: String, response: JsonObject) =
            send(
                buildJsonObject {
                    put("type", "control_response")
                    putJsonObject("response") {
                        put("subtype", "success")
                        put("request_id", id)
                        put("response", response)
                    }
                },
            )

        /** 发控制请求，等到它的应答（control_response 里的 response 一项）。 */
        fun control(body: String): JsonObject {
            val id = "c${++seq}"
            send("""{"type":"control_request","request_id":"$id","request":$body}""")
            var answer: JsonObject? = null
            until { o ->
                (o.str("type") == "control_response" && o.obj("response")?.str("request_id") == id)
                    .also { if (it) answer = o.obj("response") }
            }
            return answer!!
        }

        /** 收事件直到 [done] 为真（60 秒内），返回这期间收到的。 */
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

        /** 一轮：遇到确认交给 [answer]（返回 null 是先不答），直到 result。 */
        fun turn(answer: (JsonObject) -> JsonObject? = { null }): List<JsonObject> = until { o ->
            if (o.str("type") == "control_request")
                answer(o)?.let { respond(o.str("request_id")!!, it) }
            o.str("type") == "result"
        }

        override fun close() {
            runCatching { process.outputStream.close() }
            if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    private fun permission(o: JsonObject) = parsePrompt(o) as? PermissionRequest

    @Test
    fun toolPrompts() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        main +=
            listOf(listOf(tool("t1", "Bash", """{"command":"touch a.txt"}""")), listOf(text("ok")))
        main +=
            listOf(listOf(tool("t2", "Bash", """{"command":"touch a.txt"}""")), listOf(text("ok")))
        // “不再问”连带切到了自动接受改动，rm、touch 这类在目录里改文件的命令不再问；联网的照样问（都被拒，不会真的执行）
        main +=
            listOf(
                listOf(tool("t3", "Bash", """{"command":"curl -s https://example.invalid/a"}""")),
            )
        main +=
            listOf(
                listOf(tool("t4", "Bash", """{"command":"curl -s https://example.invalid/b"}""")),
                listOf(text("换个做法")),
            )
        Claude().use { c ->
            c.user("go")
            var asked: PermissionRequest? = null
            c.turn { o ->
                permission(o)?.let {
                    asked = it
                    permissionResponse(it, PermissionReply.AllowAlways)
                }
            }
            assertTrue("要有不再问的建议", asked!!.suggestions.isNotEmpty())
            // Claude Code 落盘这条规则不等写完就接着跑工具、结束这一轮（工作线程上先写临时文件再改名），
            // result 到了文件未必已在，等它出现
            val local = File(home, "proj/.claude/settings.local.json")
            assertTrue(
                "规则写进项目的 settings.local.json",
                waitUntil { local.isFile && local.readText().contains("Bash(touch a.txt)") },
            )
            c.user("again")
            var again = 0
            c.turn { o ->
                permission(o)?.let {
                    again++
                    permissionResponse(it, PermissionReply.Allow)
                }
            }
            assertEquals("规则生效，不再问", 0, again)

            val before = mainRequests
            c.user("stop")
            val stopped = c.turn { o ->
                permission(o)?.let { permissionResponse(it, PermissionReply.Deny(null)) }
            }
            assertEquals("aborted_tools", stopped.last().str("terminal_reason"))
            assertEquals("不写理由：调了工具就停，不再请求模型", before + 1, mainRequests)

            c.user("feedback")
            c.turn { o ->
                permission(o)?.let { permissionResponse(it, PermissionReply.Deny("用 ls")) }
            }
            assertEquals(REJECT_WITH_FEEDBACK + "用 ls", synchronized(seen) { seen.values.last() })
        }
    }

    @Test
    fun planApprovalAndQuestions() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        main += listOf(tool("w1", "Write", """{"file_path":"@PLAN@","content":"# 计划\n1. 做事"}"""))
        main += listOf(tool("p1", "ExitPlanMode", "{}"))
        main += listOf(text("planned"))
        Claude("--permission-mode", "plan").use { c ->
            c.user("plan")
            var asked: PermissionRequest? = null
            val events = c.turn { o ->
                permission(o)
                    ?.takeIf { it.isPlan }
                    ?.let {
                        asked = it
                        permissionResponse(it, PermissionReply.ApprovePlan("acceptEdits"))
                    }
            }
            assertNotNull("计划模式的系统提醒里有计划文件", planPath)
            assertEquals("# 计划\n1. 做事", (asked!!.input["plan"] as JsonPrimitive).content)
            assertTrue(
                "批准时选的模式就是之后的模式",
                events.any {
                    it.str("subtype") == "status" && it.str("permissionMode") == "acceptEdits"
                },
            )

            main +=
                listOf(
                    tool(
                        "q1",
                        "AskUserQuestion",
                        """{"questions":[{"question":"用哪个？","header":"库","multiSelect":false,
                "options":[{"label":"A","description":"a","preview":"PREVIEW-A"},{"label":"B","description":"b"}]}]}""",
                    ),
                )
            main += listOf(text("answered"))
            c.user("ask")
            c.turn { o ->
                permission(o)
                    ?.takeIf { it.questions.isNotEmpty() }
                    ?.let {
                        permissionResponse(
                            it,
                            PermissionReply.Answer(mapOf("用哪个？" to "A"), mapOf("用哪个？" to "要新版")),
                        )
                    }
            }
            val answer = synchronized(seen) { seen.values.last() }
            assertTrue(
                answer,
                answer.contains("\"用哪个？\"=\"A\"") &&
                    answer.contains("PREVIEW-A") &&
                    answer.contains("要新版"),
            )
        }
    }

    /** 同一个确认被答两次（几个客户端接着同一个进程时各答一次）：Claude Code 照第一份应答做，第二份（不管同意还是拒绝） 不报错、不改结果，工具只跑一次，进程照常接着下一轮。 */
    @Test
    fun aPromptAnsweredTwiceKeepsTheFirstAnswer() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        main += listOf(tool("d1", "Bash", """{"command":"echo once >> twice.txt"}"""))
        main += listOf(text("ok"))
        main += listOf(tool("d2", "Bash", """{"command":"echo denied >> twice.txt"}"""))
        main += listOf(text("ok"))
        Claude().use { c ->
            c.user("first")
            val events = c.turn { o ->
                permission(o)?.let {
                    c.respond(it.requestId, permissionResponse(it, PermissionReply.Allow))
                    permissionResponse(it, PermissionReply.Deny("第二份"))
                }
            }
            assertEquals("success", events.last().str("subtype"))
            val first = synchronized(seen) { seen["d1"] }.orEmpty()
            assertFalse("工具没照第一份跑：$first", first.contains("第二份"))
            assertEquals(listOf("once"), File(home, "proj/twice.txt").readLines())
            // 再一轮：先拒后同意，照拒绝的做
            c.user("second")
            val second = c.turn { o ->
                permission(o)?.let {
                    c.respond(it.requestId, permissionResponse(it, PermissionReply.Deny("先拒")))
                    permissionResponse(it, PermissionReply.Allow)
                }
            }
            assertEquals("success", second.last().str("subtype"))
            assertEquals(REJECT_WITH_FEEDBACK + "先拒", synchronized(seen) { seen["d2"] })
            assertEquals("拒绝了的没跑", listOf("once"), File(home, "proj/twice.txt").readLines())
            val errors = (events + second).filter { it.str("type") == "control_response" }
            assertTrue("多出来的应答有回话：$errors", errors.isEmpty())
            // 进程还在、照常应答
            assertEquals("success", c.control("""{"subtype":"get_context_usage"}""").str("subtype"))
        }
    }

    @Test
    fun concurrentPromptsAndReinitialize() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        sub += listOf(tool("s1", "Bash", """{"command":"touch s1"}"""))
        main +=
            listOf(
                tool(
                    "a1",
                    "Agent",
                    """{"description":"sub","prompt":"SUBTASK go","subagent_type":"general-purpose","run_in_background":true}""",
                ),
            )
        main += listOf(tool("m1", "Bash", """{"command":"touch m1"}"""))
        Claude().use { c ->
            c.user("go")
            val asked = mutableListOf<PermissionRequest>()
            c.until { o ->
                permission(o)?.let { asked += it }
                asked.size == 2
            }
            assertEquals("一个是子代理的", 1, asked.count { it.agentId != null })
            val again = c.control("""{"subtype":"initialize"}""")
            val listed =
                (again["pending_permission_requests"] as JsonArray)
                    .mapNotNull { permission(it as JsonObject)?.requestId }
                    .toSet()
            assertEquals(asked.map { it.requestId }.toSet(), listed)
            asked.forEach {
                c.respond(it.requestId, permissionResponse(it, PermissionReply.Deny("不用了")))
            }
        }
    }

    @Test
    fun mcpElicitation() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        assumeTrue("没有 jq", File("/usr/bin/jq").canExecute())
        // 一个 stdio MCP 服务器：工具 ask 一被调用就向客户端要一张表，把收到的应答原样当结果
        val script =
            File(home, "eli.sh").apply {
                writeText(
                    """
                    #!/bin/bash
                    while IFS= read -r line; do
                      method=${'$'}(jq -r '.method // empty' <<<"${'$'}line"); id=${'$'}(jq -c '.id // empty' <<<"${'$'}line")
                      case ${'$'}method in
                        initialize) echo '{"jsonrpc":"2.0","id":'"${'$'}id"',"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"eli","version":"1"}}}' ;;
                        tools/list) echo '{"jsonrpc":"2.0","id":'"${'$'}id"',"result":{"tools":[{"name":"ask","description":"ask","inputSchema":{"type":"object","properties":{}}}]}}' ;;
                        tools/call) call=${'$'}id; echo '{"jsonrpc":"2.0","id":"e1","method":"elicitation/create","params":{"message":"填一下","requestedSchema":{"type":"object","required":["name"],"properties":{"name":{"type":"string","title":"名字"},"count":{"type":"integer","minimum":1}}}}}' ;;
                        "") if [ "${'$'}id" = '"e1"' ]; then r=${'$'}(jq -c '.result' <<<"${'$'}line"); echo '{"jsonrpc":"2.0","id":'"${'$'}call"',"result":{"content":[{"type":"text","text":'"${'$'}(jq -Rs . <<<"ELICIT ${'$'}r")"'}]}}'; fi ;;
                        *) [ -n "${'$'}id" ] && echo '{"jsonrpc":"2.0","id":'"${'$'}id"',"result":{}}' ;;
                      esac
                    done
                    """
                        .trimIndent() + "\n",
                )
                setExecutable(true)
            }
        main += listOf(tool("t1", "mcp__eli__ask", "{}"))
        main += listOf(text("done"))
        val config =
            """{"mcpServers":{"eli":{"type":"stdio","command":"${script.path}","args":[]}}}"""
        Claude("--mcp-config", config, "--permission-mode", "bypassPermissions").use { c ->
            c.user("go")
            var form: ElicitationRequest? = null
            c.turn { o ->
                (parsePrompt(o) as? ElicitationRequest)?.let { request ->
                    form = request
                    elicitationResponse(
                        "accept",
                        elicitationContent(
                                request.fields,
                                mapOf("name" to listOf("svc"), "count" to listOf("2")),
                            )
                            .first,
                    )
                } ?: permission(o)?.let { permissionResponse(it, PermissionReply.Allow) }
            }
            assertEquals(listOf("name", "count"), form!!.fields.map { it.key })
            assertEquals(ElicitField.Kind.Integer, form!!.fields[1].kind)
            val result = synchronized(seen) { seen.values.last() }
            assertTrue(
                result,
                result.contains(""""content":{"name":"svc","count":2}""") &&
                    result.contains(""""action":"accept""""),
            )
        }
    }

    @Test
    fun shellCommand() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        Claude().use { c ->
            c.control("""{"subtype":"initialize"}""")
            c.send(
                """{"type":"bash_command","uuid":"33333333-3333-4333-8333-333333333333","command":"echo hi; exit 3"}""",
            )
            val events = c.until {
                it.str("type") == "command_lifecycle" && it.str("state") == "completed"
            }
            val replays =
                c.until {
                        it.str("type") == "user" &&
                            it.obj("message")?.str("content")?.startsWith("<bash-stdout>") == true
                    }
                    .filter { it.str("type") == "user" }
                    .map { it.obj("message")!!.str("content")!! }
            assertTrue(events.any { it.str("state") == "started" })
            assertEquals(
                "<bash-stdout>hi</bash-stdout><bash-stderr></bash-stderr><bash-exit-code>3</bash-exit-code>",
                replays.last(),
            )
            assertEquals("不请求模型", 0, mainRequests)
        }
    }

    @Test
    fun codeRewind() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        val file = File(home, "proj/r.txt").apply { writeText("v0\n") }
        main += listOf(tool("t1", "Read", """{"file_path":"${file.path}"}"""))
        main +=
            listOf(
                tool(
                    "t2",
                    "Edit",
                    """{"file_path":"${file.path}","old_string":"v0","new_string":"v1"}""",
                ),
            )
        main += listOf(text("edited"))
        val first = "11111111-1111-4111-8111-111111111111"
        var session = ""
        Claude("--permission-mode", "acceptEdits", env = CodeRewind.ENV).use { c ->
            c.user("edit", first)
            session = c.turn().first { it.str("session_id") != null }.str("session_id")!!
        }
        assertEquals("v1\n", file.readText())
        Claude("--resume", session, env = CodeRewind.ENV).use { c ->
            val changes =
                CodeRewind.changes(c.control(CodeRewind.request(first, dryRun = true).toString()))
            assertEquals(listOf(file.path), changes?.files)
            assertEquals(
                null,
                CodeRewind.failure(c.control(CodeRewind.request(first, dryRun = false).toString())),
            )
        }
        assertEquals("v0\n", file.readText())
        Claude("--resume", session).use { c ->
            assertEquals(
                "没设环境变量的进程不留快照",
                null,
                CodeRewind.changes(c.control(CodeRewind.request(first, dryRun = true).toString())),
            )
        }
    }
}
