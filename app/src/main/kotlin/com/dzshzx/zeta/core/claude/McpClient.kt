package com.dzshzx.zeta.core.claude

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** 连 MCP 服务器没成功：[message] 是给人看的中文说明（不含 Token）。 */
class McpException(message: String) : Exception(message)

/** MCP 的 JSON-RPC 消息：拼请求、认回应、取工具清单。 */
object McpRpc {
    const val PROTOCOL = "2025-06-18"
    private val json = Json

    fun request(id: Int, method: String, params: JsonObject = JsonObject(emptyMap())): String =
        JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to JsonPrimitive(id), "method" to JsonPrimitive(method), "params" to params)).toString()

    fun notification(method: String): String =
        JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), "method" to JsonPrimitive(method))).toString()

    val initializeParams: JsonObject = JsonObject(
        mapOf(
            "protocolVersion" to JsonPrimitive(PROTOCOL),
            "capabilities" to JsonObject(emptyMap()),
            "clientInfo" to JsonObject(mapOf("name" to JsonPrimitive("zeta"), "version" to JsonPrimitive("1"))),
        ),
    )

    fun parse(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()

    /**
     * 在一条消息（或一批）里找 id 为 [id] 的回应：找到返回 result，是错误时抛出，找不到返回 null。
     * 服务器发来的请求、通知（带 method）不算。
     */
    fun reply(message: JsonElement?, id: Int): JsonObject? {
        val candidates = when (message) {
            is JsonArray -> message.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(message)
            else -> return null
        }
        val hit = candidates.firstOrNull { "method" !in it && (it["id"] as? JsonPrimitive)?.intOrNull == id } ?: return null
        (hit["error"] as? JsonObject)?.let { error ->
            throw McpException((error["message"] as? JsonPrimitive)?.contentOrNull?.take(300) ?: "服务器返回了错误")
        }
        return hit["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    /** 分页取完 tools/list（最多 20 页、2000 个）。[call] 发一个请求、等它的回应。 */
    fun listTools(call: (String, JsonObject) -> JsonObject): List<McpTool> {
        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        repeat(20) {
            val params = if (cursor != null) JsonObject(mapOf("cursor" to JsonPrimitive(cursor))) else JsonObject(emptyMap())
            val result = call("tools/list", params)
            (result["tools"] as? JsonArray)?.forEach { tool ->
                val o = tool as? JsonObject ?: return@forEach
                tools += McpTool(
                    name = (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    description = (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(500),
                )
            }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull
            if (cursor.isNullOrEmpty() || tools.size > 2000) return tools
        }
        return tools
    }
}

/** text/event-stream 的读法：一行行读，空行结束一个事件。 */
class SseReader(private val reader: BufferedReader) {
    data class Event(val event: String, val data: String)

    /** 下一个事件；流结束返回 null。 */
    fun next(): Event? {
        var event = "message"
        val data = mutableListOf<String>()
        while (true) {
            val line = reader.readLine() ?: return if (data.isNotEmpty()) Event(event, data.joinToString("\n")) else null
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) return Event(event, data.joinToString("\n"))
                    event = "message"
                }
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> data += line.removePrefix("data:").removePrefix(" ")
            }
        }
    }

    /** 读到 id 为 [id] 的回应为止。 */
    fun awaitReply(id: Int): JsonObject {
        while (true) {
            val event = next() ?: throw McpException("服务器没有回应就断开了")
            if (event.event != "message") continue
            McpRpc.reply(McpRpc.parse(event.data), id)?.let { return it }
        }
    }
}

/**
 * 从应用这边直接连 HTTP 和 SSE 的 MCP 服务器取工具清单（Debian 和 Android 共用网络，127.0.0.1 也连得上）。
 * Claude Code 本身不提供这个查询。需要 OAuth 登录的服务器取不到（Token 在 Claude Code 自己的凭据里）。
 */
object McpHttp {
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 20_000

    /** Streamable HTTP：每个请求一次 POST，回应可能是 JSON，也可能是一段事件流。 */
    fun tools(url: String, headers: Map<String, String>): List<McpTool> {
        var session: String? = null
        var next = 1
        fun post(body: String, notification: Boolean): JsonObject? = network {
            val id = next
            val connection = open(url, "POST", headers + mapOf("Content-Type" to "application/json", "Accept" to "application/json, text/event-stream"))
            session?.let { connection.setRequestProperty("Mcp-Session-Id", it) }
            if (next > 1 || notification) connection.setRequestProperty("MCP-Protocol-Version", McpRpc.PROTOCOL)
            try {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
                check(connection)
                connection.getHeaderField("Mcp-Session-Id")?.let { session = it }
                if (notification) return@network null
                val type = connection.contentType.orEmpty()
                connection.inputStream.use { input ->
                    val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                    if ("text/event-stream" in type) {
                        SseReader(reader).awaitReply(id)
                    } else {
                        McpRpc.reply(McpRpc.parse(reader.readText()), id) ?: throw McpException("服务器的回应里没有这次请求的结果")
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        fun call(method: String, params: JsonObject): JsonObject {
            val body = McpRpc.request(next, method, params)
            return post(body, notification = false)!!.also { next++ }
        }
        try {
            call("initialize", McpRpc.initializeParams)
            runCatching { post(McpRpc.notification("notifications/initialized"), notification = true) }
            return McpRpc.listTools(::call)
        } finally {
            session?.let { id ->
                runCatching {
                    val connection = open(url, "DELETE", headers + mapOf("Mcp-Session-Id" to id))
                    connection.readTimeout = 5_000
                    connection.responseCode
                    connection.disconnect()
                }
            }
        }
    }

    /** 旧的 SSE 传输：先 GET 一条事件流，从 endpoint 事件拿到发消息的地址，回应都从这条流里回来。 */
    fun toolsSse(url: String, headers: Map<String, String>): List<McpTool> = network {
        val stream = open(url, "GET", headers + mapOf("Accept" to "text/event-stream"))
        try {
            check(stream)
            val events = SseReader(BufferedReader(InputStreamReader(stream.inputStream, Charsets.UTF_8)))
            fun findEndpoint(): String {
                while (true) {
                    val event = events.next() ?: throw McpException("服务器没有给出消息地址")
                    if (event.event == "endpoint") return URI(url).resolve(event.data.trim()).toString()
                }
            }
            val endpoint = findEndpoint()
            var next = 0
            fun send(body: String) {
                val connection = open(endpoint, "POST", headers + mapOf("Content-Type" to "application/json"))
                try {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray()) }
                    check(connection)
                } finally {
                    connection.disconnect()
                }
            }
            fun call(method: String, params: JsonObject): JsonObject {
                next++
                send(McpRpc.request(next, method, params))
                return events.awaitReply(next)
            }
            call("initialize", McpRpc.initializeParams)
            send(McpRpc.notification("notifications/initialized"))
            McpRpc.listTools(::call)
        } finally {
            stream.disconnect()
        }
    }

    /** 网络层的异常换成给人看的说明（不带地址，地址里可能有密钥）。 */
    private inline fun <T> network(block: () -> T): T = try {
        block()
    } catch (error: SocketTimeoutException) {
        throw McpException("连接超时")
    } catch (error: IOException) {
        throw McpException("连不上：" + (error.message?.let { Regex("https?://\\S+").replace(it, "…") } ?: error.javaClass.simpleName))
    }

    private fun open(url: String, method: String, headers: Map<String, String>): HttpURLConnection {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw McpException("地址不对")
        }
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.instanceFollowRedirects = true
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        return connection
    }

    private fun check(connection: HttpURLConnection) {
        val code = try {
            connection.responseCode
        } catch (error: SocketTimeoutException) {
            throw McpException("连接超时")
        } catch (error: IOException) {
            throw McpException("连不上：" + (error.message?.let { Regex("https?://\\S+").replace(it, "…") } ?: error.javaClass.simpleName))
        }
        when {
            code in 200..299 -> Unit
            code == 401 || code == 403 -> throw McpException("服务器要求登录或 Token 不对（HTTP $code）")
            else -> throw McpException("服务器返回 HTTP $code")
        }
    }
}

/**
 * stdio 的 MCP 服务器：进程由调用方在 Linux 环境里起好（见 [ClaudeScripts.MCP_STDIO]），这里按行收发 JSON-RPC。
 * 标准输出里不是 JSON 的行（登录 shell 打的字之类）跳过。
 */
class McpStdio(private val process: Process, private val deadlineMs: Long) {
    private val lines = LinkedBlockingQueue<String>()
    private val end = "\u0000end"
    private val deadline = System.currentTimeMillis() + deadlineMs

    init {
        thread(isDaemon = true, name = "zeta-mcp-stdio") {
            runCatching { process.inputStream.bufferedReader().forEachLine { lines.put(it) } }
            lines.put(end)
        }
    }

    private var next = 0

    private fun send(text: String) {
        try {
            process.outputStream.write((text + "\n").toByteArray())
            process.outputStream.flush()
        } catch (error: IOException) {
            throw McpException("服务器进程退出了")
        }
    }

    fun call(method: String, params: JsonObject): JsonObject {
        next++
        send(McpRpc.request(next, method, params))
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw McpException("服务器没有在 ${deadlineMs / 1000} 秒内回应")
            val line = lines.poll(remaining, TimeUnit.MILLISECONDS) ?: continue
            if (line == end) throw McpException("服务器进程退出了")
            val trimmed = line.trim()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) continue
            McpRpc.reply(McpRpc.parse(trimmed), next)?.let { return it }
        }
    }

    fun tools(): List<McpTool> {
        call("initialize", McpRpc.initializeParams)
        send(McpRpc.notification("notifications/initialized"))
        return McpRpc.listTools(::call)
    }

    companion object {
        /** 交给 [ClaudeScripts.MCP_STDIO] 的开头：命令、参数和环境变量，各一行 base64，空行结束。 */
        fun preamble(command: String, args: List<String>, env: Map<String, String>): ByteArray = buildString {
            (listOf(command) + args).forEach { append("A ").append(ClaudeScripts.encode(it.toByteArray())).append('\n') }
            env.forEach { (k, v) -> append("E ").append(ClaudeScripts.encode("$k=$v".toByteArray())).append('\n') }
            append('\n')
        }.toByteArray()

        /** 读完一个流（只留最后 [keep] 个字符），免得管道写满把对面卡住。 */
        fun drain(stream: InputStream, keep: Int = 4096): () -> String {
            val text = StringBuilder()
            val worker = thread(isDaemon = true) {
                runCatching {
                    stream.bufferedReader().forEachLine { line ->
                        synchronized(text) {
                            text.append(line).append('\n')
                            if (text.length > keep) text.delete(0, text.length - keep)
                        }
                    }
                }
            }
            return {
                worker.join(1_000)
                synchronized(text) { text.toString() }
            }
        }
    }
}
