package com.dzshzx.zeta.core.claude

import com.sun.net.httpserver.HttpServer
import java.io.BufferedReader
import java.io.StringReader
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpConfigTest {
    private val claudeJson = """
        {
          "numStartups": 3,
          "mcpServers": {
            "github": {"type": "http", "url": "https://api.example.com/mcp?key=SECRET1&v=2", "headers": {"Authorization": "Bearer SECRET2", "X-Org": "SECRET3"}},
            "fs": {"command": "npx", "args": ["-y", "server-fs", "--token", "SECRET4", "--api-key=SECRET5", "ghp_abcdefghijklmnop"], "env": {"API_TOKEN": "SECRET6"}},
            "dup": {"type": "http", "url": "http://127.0.0.1:9/user"}
          },
          "projects": {
            "/workspace/claude": {"mcpServers": {"dup": {"type": "sse", "url": "http://127.0.0.1:8/local"}}},
            "/elsewhere": {"mcpServers": {"other": {"type": "http", "url": "http://x"}}}
          }
        }
    """.trimIndent()
    private val mcpJson = """{"mcpServers": {"dup": {"type": "http", "url": "http://127.0.0.1:7/project"}, "proj": {"command": "./run.sh"}}}"""

    private fun entries() = McpConfig.entries(claudeJson, listOf("/workspace/claude/sub", "/workspace/claude"), listOf(mcpJson))

    @Test
    fun readsAllScopes() {
        val entries = entries()
        assertEquals(
            listOf("user/github", "user/fs", "user/dup", "local/dup", "project/dup", "project/proj"),
            entries.map { "${it.scope}/${it.name}" },
        )
        assertEquals("stdio", entries.first { it.name == "fs" }.type)
        assertEquals("stdio", entries.first { it.name == "proj" }.type)
        assertEquals(emptyList<McpEntry>(), McpConfig.entries(null, emptyList(), emptyList()))
        assertEquals(emptyList<McpEntry>(), McpConfig.entries("{broken", emptyList(), listOf("[]")))
        // 原样配置不会经 toString 漏出来
        assertFalse("SECRET" in entries.toString())
    }

    @Test
    fun describeHidesSecrets() {
        val servers = McpConfig.describe(entries())
        val text = servers.toString()
        for (i in 1..6) assertFalse("SECRET$i 漏出来了：$text", "SECRET$i" in text)
        assertFalse("ghp_" in text)
        val github = servers.first { it.name == "github" }
        assertEquals("https://api.example.com/mcp?key=…&v=…", github.url)
        assertTrue(github.bearer)
        assertEquals(listOf("X-Org"), github.headerKeys)
        val fs = servers.first { it.name == "fs" }
        assertEquals(listOf("-y", "server-fs", "--token", "…", "--api-key=…", "…"), fs.args)
        assertEquals(listOf("API_TOKEN"), fs.envKeys)
        // 同名：local 盖住 project 和 user
        assertEquals(listOf("local", null, "local"), servers.filter { it.name == "dup" }.map { it.shadowedBy })
    }

    @Test
    fun maskedUrlKeepsShape() {
        assertEquals("https://…@host:8443/p?a=…&flag", McpConfig.maskedUrl("https://user:pw@host:8443/p?a=1&flag"))
        assertEquals("http://127.0.0.1:9/mcp", McpConfig.maskedUrl("http://127.0.0.1:9/mcp"))
        assertEquals("", McpConfig.maskedUrl(""))
    }

    @Test
    fun parsesClaudeMcpList() {
        // 2.1.327 实际的输出（地址换成测试用的）
        val output = """
            Checking MCP server health…

            github: https://api.example.com/mcp?key=SECRET1&v=2 (HTTP) - ✔ Connected
            fs: npx -y server-fs --token SECRET4 --api-key=SECRET5 ghp_abcdefghijklmnop - ✘ Failed to connect — CONNECTION_CLOSED: Connection closed
            dup: http://127.0.0.1:8/local (SSE) - ✘ Failed to connect — ECONNREFUSED: see https://example.com/help?token=SECRET7
            proj: ./run.sh - ⏸ Pending approval (run `claude` to approve)
            claude.ai Gmail: https://gmail.mcp.claude.com/mcp - ! Needs authentication
            plugin:x:y: whatever - ✔ Connected

            MCP config diagnostics ⚠
            ├ Server "dup" is defined in multiple scopes
            github: fake - ✘ Failed to connect
        """.trimIndent()
        val status = McpConfig.parseStatus(output, entries())
        assertEquals(McpState("connected", ""), status.statuses["user/github"])
        assertEquals(McpState("failed", "CONNECTION_CLOSED: Connection closed"), status.statuses["user/fs"])
        // 按显示的目标认出是 local 那一个，原因里的网址抹掉
        assertEquals(McpState("failed", "ECONNREFUSED: see …"), status.statuses["local/dup"])
        assertNull(status.statuses["user/dup"])
        assertEquals("pending", status.statuses["project/proj"]?.state)
        assertEquals(listOf(McpConnector("claude.ai Gmail", McpState("auth", ""))), status.connectors)
        assertFalse("SECRET" in status.toString())
    }

    @Test
    fun ambiguousStatusGoesToWinner() {
        val status = McpConfig.parseStatus("dup: something else - ✔ Connected", entries())
        assertEquals(setOf("local/dup"), status.statuses.keys)
    }

    @Test
    fun tokenEdits() {
        val config = Json.parseToJsonElement("""{"type":"http","url":"u","headers":{"authorization":"old","X":"1"}}""").jsonObject
        val replaced = McpConfig.withToken(config, "abc")
        assertEquals("""{"Authorization":"Bearer abc","X":"1"}""".let(Json::parseToJsonElement), replaced["headers"]?.let { JsonObject(it.jsonObject.toSortedMap()) })
        val removed = McpConfig.withToken(Json.parseToJsonElement("""{"type":"http","url":"u","headers":{"Authorization":"Bearer x"}}""").jsonObject, " ")
        assertFalse("headers" in removed)
        assertEquals("Bearer abc", McpConfig.bearerValue("bearer abc").replace("bearer", "Bearer"))
        assertEquals("""{"type":"sse","url":"http://h","headers":{"Authorization":"Bearer t"}}""", McpConfig.httpConfig("sse", "http://h", "t").toString())
        assertEquals("""{"type":"http","url":"http://h"}""", McpConfig.httpConfig("http", "http://h", "").toString())
        assertEquals("""{"type":"stdio","command":"npx","args":["-y","x"],"env":{"K":"V"}}""", McpConfig.stdioConfig("npx", listOf("-y", "x"), mapOf("K" to "V")).toString())
    }

    @Test
    fun variableExpansion() {
        val config = Json.parseToJsonElement("""{"url":"http://${'$'}{HOST:-localhost}:${'$'}{PORT}/x","headers":{"A":"${'$'}{TOKEN}"},"other":"${'$'}{IGNORED}"}""").jsonObject
        assertEquals(setOf("HOST", "PORT", "TOKEN"), McpConfig.referencedVars(config))
        val expanded = McpConfig.expandAll(config, mapOf("PORT" to "9", "TOKEN" to "t"))
        assertEquals("http://localhost:9/x", expanded["url"]?.jsonPrimitive?.content)
        assertEquals("t", expanded["headers"]?.jsonObject?.get("A")?.jsonPrimitive?.content)
        assertEquals("", McpConfig.expand("${'$'}{MISSING}", emptyMap()))
    }
}

class McpClientTest {
    @Test
    fun sseReaderSplitsEvents() {
        val reader = SseReader(BufferedReader(StringReader(": comment\nevent: endpoint\ndata: /msg?s=1\n\ndata: {\"a\":\ndata: 1}\n\nid: 3\n\ndata: tail")))
        assertEquals(SseReader.Event("endpoint", "/msg?s=1"), reader.next())
        assertEquals(SseReader.Event("message", "{\"a\":\n1}"), reader.next())
        assertEquals(SseReader.Event("message", "tail"), reader.next())
        assertNull(reader.next())
    }

    @Test
    fun replyMatching() {
        assertNull(McpRpc.reply(McpRpc.parse("""{"jsonrpc":"2.0","method":"notifications/x"}"""), 1))
        assertNull(McpRpc.reply(McpRpc.parse("""{"jsonrpc":"2.0","id":2,"result":{}}"""), 1))
        assertEquals(JsonObject(mapOf("ok" to JsonPrimitive(true))), McpRpc.reply(McpRpc.parse("""[{"id":5,"method":"ping"},{"id":1,"result":{"ok":true}}]"""), 1))
        try {
            McpRpc.reply(McpRpc.parse("""{"id":1,"error":{"code":-1,"message":"bad"}}"""), 1)
            fail()
        } catch (error: McpException) {
            assertEquals("bad", error.message)
        }
        val tools = McpRpc.listTools { _, params ->
            if ("cursor" in params) {
                Json.parseToJsonElement("""{"tools":[{"name":"b"}]}""").jsonObject
            } else {
                Json.parseToJsonElement("""{"tools":[{"name":"a","description":"A"}],"nextCursor":"c1"}""").jsonObject
            }
        }
        assertEquals(listOf(McpTool("a", "A"), McpTool("b", "")), tools)
    }

    /** 一个最小的 MCP 服务器：initialize 回会话号，tools/list 回两个工具；[sse] 时回应用事件流。 */
    private fun streamableServer(sse: Boolean, token: String?): Pair<HttpServer, LinkedBlockingQueue<String>> {
        val seen = LinkedBlockingQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen.put(ex.requestMethod + " " + (ex.requestHeaders.getFirst("Mcp-Session-Id") ?: "-") + " " + body)
            if (token != null && ex.requestHeaders.getFirst("Authorization") != "Bearer $token") {
                ex.sendResponseHeaders(401, -1)
                ex.close()
                return@createContext
            }
            if (ex.requestMethod == "DELETE") {
                ex.sendResponseHeaders(200, -1)
                ex.close()
                return@createContext
            }
            val message = Json.parseToJsonElement(body).jsonObject
            val id = message["id"]?.jsonPrimitive?.int
            if (id == null) {
                ex.sendResponseHeaders(202, -1)
                ex.close()
                return@createContext
            }
            val result = when (message["method"]?.jsonPrimitive?.content) {
                "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"t","version":"1"}}"""
                else -> """{"tools":[{"name":"echo","description":"Echo back"},{"name":"add"}]}"""
            }
            val reply = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
            ex.responseHeaders.add("Mcp-Session-Id", "s-1")
            if (sse) {
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                val stream = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\ndata: $reply\n\n"
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.use { it.write(stream.toByteArray()) }
            } else {
                ex.responseHeaders.add("Content-Type", "application/json")
                val bytes = reply.toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        return server to seen
    }

    @Test
    fun streamableHttpJsonAndSse() {
        for (sse in listOf(false, true)) {
            val (server, seen) = streamableServer(sse, token = "tok")
            try {
                val url = "http://127.0.0.1:${server.address.port}/mcp"
                val tools = McpHttp.tools(url, mapOf("Authorization" to "Bearer tok"))
                assertEquals(listOf(McpTool("echo", "Echo back"), McpTool("add", "")), tools)
                val requests = generateSequence { seen.poll(1, TimeUnit.SECONDS) }.take(4).toList()
                assertTrue(requests[0], requests[0].startsWith("POST - ") && "initialize" in requests[0])
                assertTrue(requests[1], requests[1].startsWith("POST s-1 ") && "notifications/initialized" in requests[1])
                assertTrue(requests[2], requests[2].startsWith("POST s-1 ") && "tools/list" in requests[2])
                assertTrue(requests[3], requests[3].startsWith("DELETE s-1"))
                try {
                    McpHttp.tools(url, mapOf("Authorization" to "Bearer wrong"))
                    fail()
                } catch (error: McpException) {
                    assertTrue(error.message!!, "HTTP 401" in error.message!!)
                }
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun legacySseTransport() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val replies = LinkedBlockingQueue<String>()
        server.createContext("/sse") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { out ->
                out.write("event: endpoint\ndata: /messages?session=abc\n\n".toByteArray())
                out.flush()
                while (true) {
                    val reply = replies.poll(5, TimeUnit.SECONDS) ?: break
                    out.write("event: message\ndata: $reply\n\n".toByteArray())
                    out.flush()
                }
            }
        }
        server.createContext("/messages") { ex ->
            assertEquals("session=abc", ex.requestURI.query)
            val message = Json.parseToJsonElement(ex.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject
            ex.sendResponseHeaders(202, -1)
            ex.close()
            val id = message["id"]?.jsonPrimitive?.int ?: return@createContext
            val result = if (message["method"]?.jsonPrimitive?.content == "initialize") "{}" else """{"tools":[{"name":"t1"}]}"""
            replies.put("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        server.start()
        try {
            assertEquals(listOf(McpTool("t1", "")), McpHttp.toolsSse("http://127.0.0.1:${server.address.port}/sse", emptyMap()))
        } finally {
            replies.put("")
            server.stop(0)
        }
    }

    @Test
    fun stdioServerThroughLauncher() {
        // 假的 stdio 服务器：先打一行杂讯，再按 id 回应；环境变量和参数经启动脚本传进去
        val fake = """
            echo "profile noise"
            while IFS= read -r line; do
              id=${'$'}(printf '%s' "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
              [ -n "${'$'}id" ] || continue
              case ${'$'}line in
                *initialize*) printf '{"jsonrpc":"2.0","id":%s,"result":{}}\n' "${'$'}id" ;;
                *tools/list*) printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"%s","description":"%s"}]}}\n' "${'$'}id" "${'$'}ZETA_NAME" "${'$'}1" ;;
              esac
            done
        """.trimIndent()
        val process = ProcessBuilder("bash", "-c", ClaudeScripts.MCP_STDIO, "zeta", "/").start()
        process.outputStream.write(McpStdio.preamble("bash", listOf("-c", fake, "fake", "arg with space"), mapOf("ZETA_NAME" to "tool 1")))
        process.outputStream.flush()
        try {
            assertEquals(listOf(McpTool("tool 1", "arg with space")), McpStdio(process, 10_000).tools())
        } finally {
            process.outputStream.close()
            process.waitFor(2, TimeUnit.SECONDS)
            process.destroy()
        }
        // 进程起不来
        val broken = ProcessBuilder("bash", "-c", ClaudeScripts.MCP_STDIO, "zeta", "/").start()
        broken.outputStream.write(McpStdio.preamble("/nonexistent/server", emptyList(), emptyMap()))
        broken.outputStream.flush()
        try {
            McpStdio(broken, 10_000).tools()
            fail()
        } catch (error: McpException) {
            assertEquals("服务器进程退出了", error.message)
        }
    }
}
