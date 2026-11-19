package com.dzshzx.zeta.core.claude

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 给界面看的一个 MCP 服务器：密钥都只说“有没有”，不带值。 */
data class McpServer(
    val name: String,
    /** user 所有项目、local 只在默认工作目录（~/.claude.json 里按项目存）、project 来自 .mcp.json。 */
    val scope: String,
    val type: String,
    /** 查询参数的值、用户名密码已经抹掉。 */
    val url: String,
    val command: String,
    /** 像是密钥的参数已经换成“…”。 */
    val args: List<String>,
    val envKeys: List<String>,
    val headerKeys: List<String>,
    val bearer: Boolean,
    val authHeader: Boolean,
    val oauth: Boolean,
    /** 同名的服务器在优先级更高的范围里也有（local 高于 project 高于 user），这一个实际上不会用到。 */
    val shadowedBy: String? = null,
)

data class McpState(val state: String, val issue: String)

data class McpConnector(val name: String, val state: McpState)

/** [statuses] 的键是 “范围/名字”，见 [McpConfig.statusKey]。 */
data class McpStatus(val statuses: Map<String, McpState>, val connectors: List<McpConnector>)

data class McpTool(val name: String, val description: String)

/**
 * 配置里的一个服务器，带原样的配置（可能有 Token）。只在应用内存里用：连接、改 Token 时要原值，从不显示、不写日志。
 * 故意不是 data class，免得 toString 把配置打出来。
 */
class McpEntry(val name: String, val scope: String, val config: JsonObject) {
    val type: String
        get() = (config["type"] as? JsonPrimitive)?.contentOrNull ?: if ("command" in config) "stdio" else "http"

    override fun toString(): String = "McpEntry($scope/$name)"
}

/** 读 Claude Code 的 MCP 配置、解析 `claude mcp list` 的输出、改 Token。都是纯函数。 */
object McpConfig {
    private val json = Json
    val Scopes = listOf("local", "project", "user")
    private val ServerName = Regex("[A-Za-z0-9_-]{1,64}")
    private val SecretArg = Regex("(key|token|secret|password|passwd|auth|bearer|credential)", RegexOption.IGNORE_CASE)
    private val TokenLike = Regex("(sk|ghp|gho|ghs|github_pat|xox[a-z]|AKIA)[-_A-Za-z0-9]{12,}")
    private val Url = Regex("https?://\\S+")
    private val VarRef = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\\}")

    fun isValidName(name: String) = ServerName.matches(name)

    fun statusKey(scope: String, name: String) = "$scope/$name"

    /**
     * 列出配置里的服务器：~/.claude.json 的 mcpServers（user），它的 projects 里这个项目的 mcpServers（local），各级 .mcp.json（project）。
     * local 按项目路径存：cwd 本身，或 cwd 所在 git 仓库的根（在子目录里加的会记在仓库根上）。
     */
    fun entries(claudeJson: String?, projectKeys: List<String>, mcpJsonFiles: List<String>): List<McpEntry> {
        val found = mutableListOf<McpEntry>()
        val data = parseObject(claudeJson)
        (data?.get("mcpServers") as? JsonObject)?.forEach { (name, config) ->
            (config as? JsonObject)?.let { found += McpEntry(name, "user", it) }
        }
        val projects = data?.get("projects") as? JsonObject
        for (key in projectKeys.distinct()) {
            val servers = ((projects?.get(key) as? JsonObject)?.get("mcpServers") as? JsonObject) ?: continue
            servers.forEach { (name, config) ->
                if (found.none { it.scope == "local" && it.name == name }) (config as? JsonObject)?.let { found += McpEntry(name, "local", it) }
            }
        }
        for (file in mcpJsonFiles) {
            (parseObject(file)?.get("mcpServers") as? JsonObject)?.forEach { (name, config) ->
                if (found.none { it.scope == "project" && it.name == name }) (config as? JsonObject)?.let { found += McpEntry(name, "project", it) }
            }
        }
        return found
    }

    private fun parseObject(text: String?): JsonObject? =
        text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    /** 界面用的样子：秘密只说有没有；同名的按 local > project > user 标出被盖住的。 */
    fun describe(entries: List<McpEntry>): List<McpServer> = entries.map { entry ->
        val config = entry.config
        val headers = config["headers"] as? JsonObject ?: JsonObject(emptyMap())
        val env = config["env"] as? JsonObject ?: JsonObject(emptyMap())
        val authKey = headers.keys.firstOrNull { it.equals("authorization", ignoreCase = true) }
        val authValue = authKey?.let { (headers[it] as? JsonPrimitive)?.contentOrNull }
        val winner = winner(entries, entry.name)
        McpServer(
            name = entry.name,
            scope = entry.scope,
            type = entry.type,
            url = maskedUrl(config.string("url")),
            command = config.string("command"),
            args = maskedArgs((config["args"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }.orEmpty()),
            envKeys = env.keys.sorted(),
            headerKeys = headers.keys.filter { it != authKey }.sorted(),
            bearer = authValue?.trim()?.startsWith("bearer ", ignoreCase = true) == true,
            authHeader = authKey != null,
            oauth = config["oauth"] is JsonObject,
            shadowedBy = winner?.scope?.takeIf { it != entry.scope },
        )
    }

    /** 同名时实际生效的那一个：local 优先，其次 project，最后 user。 */
    fun winner(entries: List<McpEntry>, name: String): McpEntry? =
        Scopes.firstNotNullOfOrNull { scope -> entries.firstOrNull { it.name == name && it.scope == scope } }

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** 地址里的用户名密码和查询参数的值可能就是密钥，只留参数名。 */
    fun maskedUrl(url: String): String {
        if (url.isEmpty()) return url
        val uri = runCatching { URI(url) }.getOrNull() ?: return url.substringBefore('?')
        val authority = uri.rawAuthority ?: return url.substringBefore('?')
        val host = if ('@' in authority) "…@" + authority.substringAfterLast('@') else authority
        val query = uri.rawQuery?.split('&')?.joinToString("&") { part -> part.substringBefore('=') + if ('=' in part) "=…" else "" }
        return buildString {
            append(uri.scheme).append("://").append(host)
            append(uri.rawPath.orEmpty())
            if (query != null) append('?').append(query)
        }
    }

    /** 命令行参数里像是密钥的换成“…”：`--token xxx` 的下一个、`--api-key=xxx` 的值、长得像 Token 的整个参数。 */
    fun maskedArgs(args: List<String>): List<String> {
        val shown = mutableListOf<String>()
        var hideNext = false
        for (arg in args) {
            when {
                hideNext -> {
                    shown += "…"
                    hideNext = false
                }
                arg.startsWith("-") && '=' in arg && SecretArg.containsMatchIn(arg.substringBefore('=')) -> shown += arg.substringBefore('=') + "=…"
                arg.startsWith("-") && SecretArg.containsMatchIn(arg) -> {
                    shown += arg
                    hideNext = true
                }
                TokenLike.matches(arg) -> shown += "…"
                else -> shown += arg
            }
        }
        return shown
    }

    private val Phrases = listOf(
        "Needs authentication" to "auth",
        "tools fetch failed" to "partial",
        "Connected" to "connected",
        "Pending approval" to "pending",
        "Rejected" to "rejected",
        "Disabled" to "disabled",
        "Not configured" to "unconfigured",
        "Failed to connect" to "failed",
        "Connection error" to "failed",
    )

    /** `claude mcp list` 一行里 “名字: 目标 - 图标 状态 — 原因” 的目标和状态部分。 */
    private fun splitStatus(tail: String): Pair<String, String>? {
        var from = 0
        while (true) {
            val at = tail.indexOf(" - ", from)
            if (at < 0) return null
            val rest = tail.substring(at + 3)
            val head = rest.substringBefore(" — ")
            if (Phrases.any { head.contains(it.first) }) return tail.substring(0, at) to rest
            from = at + 1
        }
    }

    /**
     * 解析 `claude mcp list` 的输出。它对同名的服务器只列生效的那一个：按显示的目标（地址或命令行）认出是哪个范围的，
     * 认不出时归给优先级最高的那个。只取状态和原因，地址、命令行不往外带；原因里的网址换成“…”。
     */
    fun parseStatus(output: String, entries: List<McpEntry>): McpStatus {
        val names = entries.map { it.name }.distinct().sortedByDescending { it.length }
        val statuses = LinkedHashMap<String, McpState>()
        val connectors = mutableListOf<McpConnector>()
        for (raw in output.lineSequence()) {
            val line = raw.trimEnd()
            if (line.startsWith("MCP config diagnostics")) break
            val name = names.firstOrNull { line.startsWith("$it: ") }
            val connector = name == null && line.startsWith("claude.ai ") && ": " in line
            if (name == null && !connector) continue
            val label = name ?: line.substringBefore(": ")
            val (target, status) = splitStatus(line.substring(label.length + 2)) ?: continue
            val state = McpState(
                state = Phrases.firstOrNull { status.substringBefore(" — ").contains(it.first) }?.second ?: "unknown",
                issue = if (" — " in status) Url.replace(status.substringAfter(" — ").trim(), "…").take(300) else "",
            )
            if (connector) {
                connectors += McpConnector(label, state)
                continue
            }
            val candidates = entries.filter { it.name == label }
            val matched = candidates.filter { targetOf(it) == target || (it.type != "stdio" && target.startsWith(it.config.string("url"))) }
            val owner = matched.singleOrNull() ?: winner(candidates, label) ?: continue
            statuses[statusKey(owner.scope, owner.name)] = state
        }
        return McpStatus(statuses, connectors)
    }

    /** `claude mcp list` 显示 stdio 服务器的样子：命令和参数用空格连起来。 */
    private fun targetOf(entry: McpEntry): String {
        if (entry.type != "stdio") return entry.config.string("url") + " (" + entry.type.uppercase() + ")"
        val args = (entry.config["args"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }.orEmpty()
        return (listOf(entry.config.string("command")) + args).joinToString(" ")
    }

    fun bearerValue(token: String): String = token.trim().let { if (it.startsWith("Bearer ", ignoreCase = true)) it else "Bearer $it" }

    /** 换掉 Authorization 请求头：[token] 为空表示去掉身份认证；别的请求头照旧。 */
    fun withToken(config: JsonObject, token: String): JsonObject {
        val headers = LinkedHashMap(config["headers"] as? JsonObject ?: JsonObject(emptyMap()))
        headers.keys.filter { it.equals("authorization", ignoreCase = true) }.forEach { headers.remove(it) }
        if (token.isNotBlank()) headers["Authorization"] = JsonPrimitive(bearerValue(token))
        val result = LinkedHashMap(config)
        if (headers.isEmpty()) result.remove("headers") else result["headers"] = JsonObject(headers)
        return JsonObject(result)
    }

    fun httpConfig(type: String, url: String, token: String): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive(type))
            put("url", JsonPrimitive(url))
            if (token.isNotBlank()) put("headers", JsonObject(mapOf("Authorization" to JsonPrimitive(bearerValue(token)))))
        },
    )

    fun stdioConfig(command: String, args: List<String>, env: Map<String, String>): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive("stdio"))
            put("command", JsonPrimitive(command))
            put("args", JsonArray(args.map { JsonPrimitive(it) }))
            if (env.isNotEmpty()) put("env", JsonObject(env.mapValues { JsonPrimitive(it.value) }))
        },
    )

    /** 配置里 `${VAR}`、`${VAR:-默认值}` 引用到的变量名。 */
    fun referencedVars(config: JsonObject): Set<String> = buildSet {
        fun scan(element: JsonElement?) {
            when (element) {
                is JsonPrimitive -> element.contentOrNull?.let { text -> VarRef.findAll(text).forEach { add(it.groupValues[1]) } }
                is JsonArray -> element.forEach(::scan)
                is JsonObject -> element.values.forEach(::scan)
                null -> Unit
            }
        }
        listOf("url", "headers", "command", "args", "env").forEach { scan(config[it]) }
    }

    /** 展开 `${VAR}`：变量没设时用默认值，没有默认值就是空串（和 Claude Code 一样）。[values] 只放设了的变量。 */
    fun expand(text: String, values: Map<String, String>): String =
        VarRef.replace(text) { m -> values[m.groupValues[1]] ?: m.groups[2]?.value.orEmpty() }

    /** 把整份配置里的字符串都展开。 */
    fun expandAll(config: JsonObject, values: Map<String, String>): JsonObject {
        fun walk(element: JsonElement): JsonElement = when (element) {
            is JsonPrimitive -> if (element.isString) JsonPrimitive(expand(element.content, values)) else element
            is JsonArray -> JsonArray(element.map(::walk))
            is JsonObject -> JsonObject(element.mapValues { walk(it.value) })
        }
        return walk(config) as JsonObject
    }
}
