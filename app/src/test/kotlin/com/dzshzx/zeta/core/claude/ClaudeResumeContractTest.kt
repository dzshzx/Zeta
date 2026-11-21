package com.dzshzx.zeta.core.claude

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 接着已有会话时 Zeta 不带 --model、交给 Claude Code 恢复模型（[com.dzshzx.zeta.core.Conversation.model]）， 依赖的
 * Claude Code 行为（2.1.328 实测）在这里钉住：升级 Claude Code 后跑一遍，变了就会失败。
 *
 * - `--resume <id>` 不带 --model 时用这个会话记录里的模型，不是默认模型；中间新开的会话照旧用默认的。`--fork-session` 分出的也一样。 带
 *   `--model default` 时用 Claude Code 内置的默认模型（用户在旧对话里亲手选「默认」就这样传）。
 * - 思考强度不恢复：接回后是默认的那档，所以 Zeta 照会话记录自己恢复（--effort）。
 * - 记录里的模型下线了（API 说没有这个模型）：照用不误，不自动换，这一轮以出错结束（result 的 is_error），init 报的就是那个模型； 之后用 set_model
 *   换一个就照常（用户在 Zeta 的模型菜单里另选一个）。
 * - 没测到的：`[1m]` 变体接回后还在不在。用 API key 时 Claude Code 不提供 [1m] 选项，`--model sonnet[1m]` 的请求也不带 1M 的
 *   beta， 这里分不出来，要用订阅账号在真机上看。
 *
 * 和 ClaudeTaskContractTest 一样：真的 claude 命令行跑在临时 HOME 上，连本机一个假的 Messages API（假 key，不出网、不花钱）。
 */
class ClaudeResumeContractTest {
    private lateinit var home: File
    private lateinit var server: HttpServer
    private val json = Json { ignoreUnknownKeys = true }

    /** 主对话最近一次请求的模型和思考强度（标题之类不带工具的请求不算）。 */
    @Volatile private var lastRequest: Pair<String?, String?> = null to null

    @Before
    fun setUp() {
        home =
            java.nio.file.Files.createTempDirectory("zeta-resume-contract").toFile().canonicalFile
        File(home, ".claude").mkdirs()
        File(home, "proj").mkdirs()
        File(home, ".claude/settings.json").writeText("{}")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
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
        val model = body["model"]?.jsonPrimitive?.contentOrNull
        if ((body["tools"] as? JsonArray).orEmpty().isNotEmpty()) {
            lastRequest =
                model to
                    (body["output_config"] as? JsonObject)
                        ?.get("effort")
                        ?.jsonPrimitive
                        ?.contentOrNull
        }
        if (model == RETIRED) {
            val error =
                """{"type":"error","error":{"type":"not_found_error","message":"model: $model"}}"""
                    .toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(404, error.size.toLong())
            ex.responseBody.use { it.write(error) }
            return
        }
        val bytes = sse(model.orEmpty()).toByteArray()
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun sse(model: String): String = buildString {
        fun event(data: JsonObject) =
            append("event: ")
                .append(data["type"]!!.jsonPrimitive.content)
                .append("\ndata: ")
                .append(data)
                .append("\n\n")
        event(
            buildJsonObject {
                put("type", "message_start")
                putJsonObject("message") {
                    put("id", "msg_fake")
                    put("type", "message")
                    put("role", "assistant")
                    put("model", model)
                    put("content", JsonArray(emptyList()))
                    put("stop_reason", JsonNull)
                    putJsonObject("usage") {
                        put("input_tokens", 10)
                        put("output_tokens", 1)
                    }
                }
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
                    put("text", "ok")
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
                putJsonObject("usage") { put("output_tokens", 1) }
            },
        )
        event(buildJsonObject { put("type", "message_stop") })
    }

    /** 一个 stream-json 的 claude 进程，和 Zeta 起的一样，带上 [arguments]。 */
    private inner class Claude(vararg arguments: String) : AutoCloseable {
        private val process: Process
        private val lines = LinkedBlockingQueue<String>()
        private var seq = 0
        var init: JsonObject? = null
            private set

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
                        ) + arguments,
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
        }

        private fun send(o: JsonObject) {
            process.outputStream.write((o.toString() + "\n").toByteArray())
            process.outputStream.flush()
        }

        /** 发一条消息，等这一轮的 result。 */
        fun turn(prompt: String): JsonObject {
            send(
                buildJsonObject {
                    put("type", "user")
                    putJsonObject("message") {
                        put("role", "user")
                        put("content", prompt)
                    }
                },
            )
            return until { it.str("type") == "result" }
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
            return until {
                it.str("type") == "control_response" &&
                    (it["response"] as? JsonObject)?.str("request_id") == id
            }
        }

        private fun until(done: (JsonObject) -> Boolean): JsonObject {
            val deadline = System.currentTimeMillis() + 90_000
            while (true) {
                val left = deadline - System.currentTimeMillis()
                assertTrue("claude 没有按时回应", left > 0)
                val line = lines.poll(left, TimeUnit.MILLISECONDS) ?: continue
                val o =
                    runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                        ?: continue
                if (o.str("type") == "system" && o.str("subtype") == "init") init = o
                if (done(o)) return o
            }
        }

        override fun close() {
            runCatching { process.outputStream.close() }
            if (!process.waitFor(15, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    /** 跑一轮的结果：会话号、init 报的模型、实际请求的模型和思考强度、这一轮出没出错。 */
    private data class Run(
        val session: String,
        val model: String?,
        val requested: String?,
        val effort: String?,
        val failed: Boolean,
        val result: String?,
    )

    private fun run(vararg arguments: String): Run =
        Claude(*arguments).use { claude ->
            val result = claude.turn("hi")
            val init = claude.init ?: throw AssertionError("没有 system init")
            Run(
                init.str("session_id")!!,
                init.str("model"),
                lastRequest.first,
                lastRequest.second,
                failed = (result["is_error"] as? JsonPrimitive)?.contentOrNull == "true",
                result = result.str("result"),
            )
        }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    @Test
    fun resumeKeepsTheSessionModelButNotItsEffort() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        val fresh = run()
        val default = fresh.model!!
        val sonnet = run("--model", "sonnet", "--effort", "low")
        assertEquals("claude-sonnet-5-5", sonnet.model)
        assertNotEquals(default, sonnet.model)
        assertEquals("low", sonnet.effort)
        // 中间新开的会话照旧用默认的
        val between = run()
        assertEquals(default, between.model)
        assertNotEquals("low", between.effort)

        val resumed = run("--resume", sonnet.session)
        assertEquals(sonnet.session, resumed.session)
        assertEquals(sonnet.model, resumed.model)
        assertEquals(sonnet.model, resumed.requested)
        // 思考强度没恢复：是默认的那档
        assertEquals(between.effort, resumed.effort)

        val forked = run("--resume", sonnet.session, "--fork-session")
        assertNotEquals(sonnet.session, forked.session)
        assertEquals(sonnet.model, forked.model)
        assertEquals(sonnet.model, forked.requested)

        // 亲手选了默认：明说 default，用的是默认模型
        val explicit = run("--resume", sonnet.session, "--model", "default")
        assertEquals(default, explicit.model)
        assertEquals(default, explicit.requested)
    }

    @Test
    fun aRetiredModelIsKeptUntilAnotherIsChosen() {
        assumeTrue("没有 claude 命令", claudeAvailable())
        val made = run("--model", "sonnet")
        // 这个模型下线了：记录里写的模型都换成 API 不认的
        val record =
            File(home, ".claude/projects").walk().first { it.name == "${made.session}.jsonl" }
        record.writeText(record.readText().replace(made.model!!, RETIRED))
        Claude("--resume", made.session).use { claude ->
            val failed = claude.turn("hi")
            assertEquals(RETIRED, claude.init?.str("model"))
            assertEquals(RETIRED, lastRequest.first)
            assertEquals("true", (failed["is_error"] as? JsonPrimitive)?.contentOrNull)
            assertTrue(
                failed.str("result").orEmpty(),
                failed.str("result").orEmpty().contains(RETIRED),
            )
            // 另选一个模型就照常
            val switched =
                claude.control(
                    buildJsonObject {
                        put("subtype", "set_model")
                        put("model", "sonnet")
                    },
                )
            assertEquals("success", (switched["response"] as JsonObject).str("subtype"))
            val ok = claude.turn("again")
            assertEquals("false", (ok["is_error"] as? JsonPrimitive)?.contentOrNull)
            assertEquals("claude-sonnet-5-5", lastRequest.first)
        }
    }

    private companion object {
        const val RETIRED = "claude-gone-4-0"
    }
}
