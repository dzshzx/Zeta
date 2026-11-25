package com.dzshzx.zeta.core.claude

import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.ServeError
import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.callCore
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.jsonStrings
import com.dzshzx.zeta.core.long
import com.dzshzx.zeta.core.obj
import com.dzshzx.zeta.core.relayModel
import com.dzshzx.zeta.core.str
import com.dzshzx.zeta.i18n.Strings
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 经一台主机上的核心读写那台 Claude Code 自己的配置（docs/core.md「Claude Code 配置」的
 * config.*）：新对话默认、记忆、Skills、MCP、Subagent、工具权限。
 * 解析、校验、路径、改写文件都在核心里（helper/claudecfg），手机本机和远程主机一样，桌面版用的也是同一套；这里只发请求、把结果读成界面要的样子。
 *
 * 出错时抛 [ServeError]，message 是核心给的中文说明（不含 Token）。全是阻塞调用，别在主线程上用。
 */
class ClaudeConfigCore(
    /** 发一个请求：方法、参数、最多等多久；ok 时返回 result（同 [com.dzshzx.zeta.core.ServeRequests.call]）。 */
    private val call: (method: String, params: JsonObject, timeoutMs: Long) -> JsonElement?,
) {
    companion object {
        /** 主机 [backend] 上的 Claude Code 配置。 */
        fun of(hub: ConversationHub, backend: String) =
            ClaudeConfigCore { method, params, timeoutMs ->
                hub.callCore(backend, method, params, timeoutMs)
            }

        /** 快的方法（只读写小文件）最多等多久。 */
        const val TIMEOUT_MS = 60_000L

        /** 标「慢」的方法（跑 claude、连 MCP 服务器）：核心自己最多做 2 分钟（helper/claudecfg 的 mcp list），这里多留半分钟。 */
        const val SLOW_TIMEOUT_MS = 150_000L

        /**
         * 能导入的 Skill ZIP 的上限：经 serve 的一行最长 64 MiB，base64 以后要放得下（同 helper/coreapi 的 MaxSkillZip）。
         */
        const val MAX_SKILL_ZIP = 45 * 1024 * 1024
    }

    private fun request(
        method: String,
        timeoutMs: Long = TIMEOUT_MS,
        params: JsonObjectBuilder.() -> Unit = {},
    ): JsonElement? = call(method, buildJsonObject(params), timeoutMs)

    private fun result(
        method: String,
        timeoutMs: Long = TIMEOUT_MS,
        params: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject =
        request(method, timeoutMs, params) as? JsonObject
            ?: throw ServeError(Strings.coreConfigBadReply(method))

    private fun list(method: String, params: JsonObjectBuilder.() -> Unit = {}): List<JsonObject> =
        (request(method, TIMEOUT_MS, params) as? JsonArray).objects()

    /** 当前来源和中转清单；订阅的清单仍由 Claude Code 的 initialize 提供。 */
    fun modelSource(): ModelSource {
        val o = result("config.models.read")
        val relay =
            o.str("relay") ?: throw ServeError(Strings.coreConfigBadReply("config.models.read"))
        return ModelSource(
            relay.ifBlank { null },
            o.str("model")?.ifBlank { null },
            o.arr("models").orEmpty().mapNotNull(::relayModel),
        )
    }

    // ---------- 新对话默认 ----------

    fun defaults(): ClaudeDefaults = parseDefaults(result("config.defaults.read"))

    /** 默认模型由核心按来源写入；null 是删掉当前来源的覆盖。 */
    fun setDefaultModel(value: String?): ClaudeDefaults =
        parseDefaults(result("config.defaults.setModel") { put("value", value.orEmpty()) })

    /** 上下文窗口（token 数）；null 是删掉，按模型本身的窗口。比核心肯认的最小值小时出错。 */
    fun setCompactWindow(tokens: Long?): ClaudeDefaults =
        parseDefaults(result("config.defaults.setCompactWindow") { put("value", tokens ?: 0L) })

    /** 设置这台设备上新对话的默认权限模式。 */
    fun setDefaultPermissionMode(mode: String): ClaudeDefaults =
        parseDefaults(result("config.defaults.setPermissionMode") { put("value", mode) })

    /** 默认思考强度：[model] 是完整模型 ID 时只改这个模型的，null 时改没单独设的模型用的那一档；[level] 为 null 是删掉。 */
    fun setDefaultEffort(model: String?, level: String?): ClaudeDefaults =
        parseDefaults(
            result("config.defaults.setEffort") {
                put("model", model.orEmpty())
                put("value", level.orEmpty())
            },
        )

    // ---------- 记忆 ----------

    /** 记忆页：[cwd] 是默认工作目录，[project] 是要看的项目（null 时选默认工作目录所在的）。 */
    fun memory(cwd: String, project: String?): MemoryOverview =
        parseMemory(
            result("config.memory.read") {
                put("cwd", cwd)
                put("project", project.orEmpty())
            },
        )

    /** 读记忆页上的一个文件（CLAUDE.md、MEMORY.md、一条记忆）。 */
    fun read(path: String): TextDocument {
        val o = result("config.memory.readFile") { put("path", path) }
        return TextDocument(
            o.str("content").orEmpty(),
            o.str("sha").orEmpty(),
            o.bool("exists") == true,
        )
    }

    /**
     * 写记忆页上的一个文件，返回新内容的校验值；文件在编辑期间被别人改过时返回 null，什么都没写。 [expectedSha] 是读到时的（文件原来不存在时是空串），null
     * 表示不比对、直接覆盖。
     */
    fun write(path: String, content: String, expectedSha: String?): String? {
        val o =
            result("config.memory.writeFile") {
                put("path", path)
                put("content", content)
                put("sha", expectedSha.orEmpty())
                put("force", expectedSha == null)
            }
        if (o.bool("conflict") == true) return null
        return o.str("sha") ?: throw ServeError(Strings.coreConfigNoSha())
    }

    /** 删一条记忆，连同 MEMORY.md 里指向它的那一行。 */
    fun deleteMemory(path: String) {
        request("config.memory.delete") { put("path", path) }
    }

    fun setAutoMemory(on: Boolean) {
        request("config.memory.setAuto") { put("on", on) }
    }

    // ---------- Skills ----------

    fun skills(): SkillList {
        val o = result("config.skills.list")
        return SkillList(
            skills =
                o.arr("user").objects().map {
                    SkillInfo(
                        name = it.str("name").orEmpty(),
                        title = it.str("title").orEmpty(),
                        description = it.str("description").orEmpty(),
                        enabled = it.bool("enabled") == true,
                        valid = it.bool("valid") == true,
                    )
                },
            synced =
                o.arr("synced").objects().map {
                    SyncedSkill(
                        it.str("name").orEmpty(),
                        it.str("description").orEmpty(),
                        it.bool("enabled") == true,
                    )
                },
        )
    }

    /** 从 ZIP 装一个 Skill；同名的已有又没 [replace] 时什么都不装，返回 [SkillImport.Exists] 让用户确认。 */
    fun importSkill(fileName: String, zip: ByteArray, replace: Boolean): SkillImport {
        if (zip.size > MAX_SKILL_ZIP)
            throw ServeError(Strings.coreConfigZipTooLarge(MAX_SKILL_ZIP shr 20))
        val o =
            result("config.skills.import", SLOW_TIMEOUT_MS) {
                put("fileName", fileName)
                put("zip", Base64.getEncoder().encodeToString(zip))
                put("replace", replace)
            }
        val name = o.str("name").orEmpty()
        return if (o.bool("exists") == true)
            SkillImport.Exists(name, o.str("existingDescription").orEmpty())
        else SkillImport.Installed(name, o.bool("replaced") == true, o.bool("enabled") == true)
    }

    /** 停用写进 settings.json 的 skillOverrides（"off"），和 Claude Code 里 /skills 关掉的效果一样；启用就把它去掉。 */
    fun setSkillEnabled(skill: SkillInfo, enabled: Boolean) {
        request("config.skills.setEnabled") {
            put("name", skill.name)
            put("title", skill.title)
            put("enabled", enabled)
        }
    }

    fun deleteSkill(name: String) {
        request("config.skills.delete") { put("name", name) }
    }

    // ---------- Subagent ----------

    fun agents(): List<Subagent> =
        list("config.agents.list").map {
            Subagent(
                file = it.str("file").orEmpty(),
                name = it.str("name").orEmpty(),
                description = it.str("description").orEmpty(),
                model = it.str("model").orEmpty(),
                tools = it.arr("tools")?.strings(),
                prompt = it.str("prompt").orEmpty(),
                extraKeys = it.arr("extraKeys").strings(),
                sha = it.str("sha").orEmpty(),
            )
        }

    /** 新建（[original] 为 null）或改一个 Subagent，返回保存后的文件（相对 ~/.claude/agents）。改了名字时旧文件会删掉。 */
    fun saveAgent(original: Subagent?, draft: SubagentDraft): String =
        result("config.agents.save") {
                put("original", original?.let(::agentJson) ?: JsonNull)
                putJsonObject("draft") {
                    put("name", draft.name)
                    put("description", draft.description)
                    put("model", draft.model)
                    put("tools", draft.tools?.let(::jsonStrings) ?: JsonNull)
                    put("prompt", draft.prompt)
                }
            }
            .str("file")
            .orEmpty()

    fun deleteAgent(file: String) {
        request("config.agents.delete") { put("file", file) }
    }

    // ---------- MCP ----------

    fun mcpServers(cwd: String): List<McpServer> =
        list("config.mcp.list") { put("cwd", cwd) }
            .map {
                McpServer(
                    name = it.str("name").orEmpty(),
                    scope = it.str("scope").orEmpty(),
                    type = it.str("type").orEmpty(),
                    url = it.str("url").orEmpty(),
                    command = it.str("command").orEmpty(),
                    args = it.arr("args").strings(),
                    envKeys = it.arr("envKeys").strings(),
                    headerKeys = it.arr("headerKeys").strings(),
                    bearer = it.bool("bearer") == true,
                    authHeader = it.bool("authHeader") == true,
                    oauth = it.bool("oauth") == true,
                    shadowedBy = it.str("shadowedBy")?.ifEmpty { null },
                )
            }

    /** 逐个连接做健康检查（claude mcp list），可能要十几秒；它会把配置里的 stdio 服务器都起一遍（和每次开对话时一样）。 */
    fun mcpStatus(cwd: String): McpStatus {
        val o = result("config.mcp.status", SLOW_TIMEOUT_MS) { put("cwd", cwd) }
        return McpStatus(
            statuses =
                o.obj("servers")
                    .orEmpty()
                    .mapNotNull { (key, value) ->
                        (value as? JsonObject)?.let { key to mcpState(it) }
                    }
                    .toMap(),
            connectors =
                o.arr("connectors").objects().map {
                    McpConnector(it.str("name").orEmpty(), mcpState(it.obj("state")))
                },
        )
    }

    /** 连上服务器要工具清单：HTTP、SSE 从主机连，stdio 在主机上经登录 shell 起。 */
    fun mcpTools(scope: String, name: String, cwd: String): List<McpTool> =
        (request("config.mcp.tools", SLOW_TIMEOUT_MS) { serverParams(scope, name, cwd) }
                as? JsonArray)
            .objects()
            .map { McpTool(it.str("name").orEmpty(), it.str("description").orEmpty()) }

    /** 新服务器放进用户配置（所有项目可用）。[token] 只经标准输入交给 claude mcp add-json。 */
    fun addHttpServer(name: String, type: String, url: String, token: String) {
        request("config.mcp.addHttp", SLOW_TIMEOUT_MS) {
            put("name", name)
            put("type", type)
            put("url", url)
            put("token", token)
        }
    }

    fun addStdioServer(
        name: String,
        command: String,
        args: List<String>,
        env: Map<String, String>,
    ) {
        request("config.mcp.addStdio", SLOW_TIMEOUT_MS) {
            put("name", name)
            put("command", command)
            put("args", jsonStrings(args))
            putJsonObject("env") { env.forEach { (k, v) -> put(k, v) } }
        }
    }

    fun removeMcp(server: McpServer, cwd: String) {
        request("config.mcp.remove", SLOW_TIMEOUT_MS) { serverParams(server, cwd) }
    }

    /** 换掉 Bearer Token；空串表示去掉身份认证。核心经 claude mcp 删掉重加，重加失败时把原样放回去。 */
    fun setMcpToken(server: McpServer, cwd: String, token: String) {
        request("config.mcp.setToken", SLOW_TIMEOUT_MS) {
            serverParams(server, cwd)
            put("token", token.trim())
        }
    }

    private fun JsonObjectBuilder.serverParams(server: McpServer, cwd: String) =
        serverParams(server.scope, server.name, cwd)

    private fun JsonObjectBuilder.serverParams(scope: String, name: String, cwd: String) {
        put("cwd", cwd)
        put("scope", scope)
        put("name", name)
    }

    // ---------- 工具权限 ----------

    fun permissions(query: PermissionsQuery): PermissionsView =
        permissionsCall("config.permissions.read", query) {}

    /** 整个工具（内置工具、mcp__phone__xxx，或整个 MCP 服务器 mcp__xxx）设成允许、询问、拒绝，null 是去掉。 */
    fun setToolPermission(
        query: PermissionsQuery,
        rule: String,
        behavior: PermissionBehavior?,
    ): PermissionsView =
        permissionsCall("config.permissions.setTool", query) {
            put("tool", rule)
            put("behavior", behavior?.key.orEmpty())
        }

    fun addPermissionRule(query: PermissionsQuery, rule: PermissionRule): PermissionsView =
        permissionsCall("config.permissions.add", query) { ruleParams(rule) }

    fun removePermissionRule(query: PermissionsQuery, rule: PermissionRule): PermissionsView =
        permissionsCall("config.permissions.remove", query) { ruleParams(rule) }

    /** 原来那条已经被别人删了或改了时出错，界面重新读。 */
    fun replacePermissionRule(
        query: PermissionsQuery,
        old: PermissionRule,
        new: PermissionRule,
    ): PermissionsView =
        permissionsCall("config.permissions.replace", query) {
            putJsonObject("old") { ruleParams(old) }
            putJsonObject("new") { ruleParams(new) }
        }

    /** 一键开启或关闭一组工具；核心按文件里最新的内容算要加、要删的。 */
    fun setGroupPermission(
        query: PermissionsQuery,
        group: PermissionGroup,
        enable: Boolean,
    ): PermissionsView =
        permissionsCall("config.permissions.bulk", query) {
            putJsonObject("group") {
                if (group.tools.isNotEmpty()) put("tools", jsonStrings(group.tools))
                if (group.server.isNotEmpty()) put("server", group.server)
            }
            put("enable", enable)
        }

    private fun JsonObjectBuilder.ruleParams(rule: PermissionRule) {
        put("behavior", rule.behavior.key)
        put("rule", rule.rule)
    }

    private fun permissionsCall(
        method: String,
        query: PermissionsQuery,
        params: JsonObjectBuilder.() -> Unit,
    ): PermissionsView =
        parsePermissions(
            result(method) {
                put("cwd", query.cwd)
                if (query.server.isNotEmpty()) {
                    put("server", query.server)
                    put("tools", jsonStrings(query.tools))
                }
                params()
            },
        )
}

// ---------- 读结果 ----------

private fun JsonArray?.objects(): List<JsonObject> = orEmpty().mapNotNull { it as? JsonObject }

private fun JsonArray?.strings(): List<String> =
    orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

private fun parseDefaults(o: JsonObject): ClaudeDefaults =
    ClaudeDefaults(
        model = o.str("model")?.ifEmpty { null },
        permissionMode = o.str("permissionMode")?.ifEmpty { null },
        efforts =
            o.arr("efforts")
                .objects()
                .mapNotNull { e ->
                    val model = e.str("model")?.ifEmpty { null } ?: return@mapNotNull null
                    e.str("level")?.ifEmpty { null }?.let { model to it }
                }
                .toMap(),
        effort = o.str("effort")?.ifEmpty { null },
        compactWindow = o.long("compactWindow")?.takeIf { it > 0 },
    )

private fun configFile(o: JsonObject?): ConfigFile? = o?.let {
    ConfigFile(it.str("path").orEmpty(), it.bool("exists") == true, it.long("size") ?: 0)
}

private fun parseMemory(o: JsonObject): MemoryOverview =
    MemoryOverview(
        autoMemory = o.bool("autoMemory") == true,
        forcedOff = o.bool("forcedOff") == true,
        customDir = o.str("customDir")?.ifEmpty { null },
        instructions = configFile(o.obj("instructions")) ?: ConfigFile("", false, 0),
        projects =
            o.arr("projects").objects().map {
                MemoryProject(
                    key = it.str("key").orEmpty(),
                    label = it.str("label").orEmpty(),
                    count = it.int("count") ?: 0,
                    isDefault = it.bool("default") == true,
                )
            },
        selected = o.str("selected")?.ifEmpty { null },
        dir = o.str("dir")?.ifEmpty { null },
        index = configFile(o.obj("index")),
        files =
            o.arr("files").objects().map {
                MemoryEntry(
                    file = it.str("path").orEmpty(),
                    fileName = it.str("fileName").orEmpty(),
                    name = it.str("name").orEmpty(),
                    description = it.str("description").orEmpty(),
                    type = it.str("type").orEmpty(),
                    size = it.long("size") ?: 0,
                )
            },
    )

private fun agentJson(agent: Subagent): JsonObject = buildJsonObject {
    put("file", agent.file)
    put("name", agent.name)
    put("description", agent.description)
    put("model", agent.model)
    put("tools", agent.tools?.let(::jsonStrings) ?: JsonNull)
    put("prompt", agent.prompt)
    put("extraKeys", jsonStrings(agent.extraKeys))
    put("sha", agent.sha)
}

private fun mcpState(o: JsonObject?): McpState =
    McpState(o?.str("state").orEmpty(), o?.str("issue").orEmpty())

private fun rule(o: JsonObject): PermissionRule? {
    val behavior = PermissionBehavior.of(o.str("behavior")) ?: return null
    return PermissionRule(behavior, o.str("rule") ?: return null)
}

private fun rules(a: JsonArray?): List<PermissionRule> = a.objects().mapNotNull(::rule)

private fun toolState(o: JsonObject?): ToolState =
    ToolState(
        behavior = PermissionBehavior.of(o?.str("behavior")),
        inherited = o?.bool("inherited") == true,
        specific = rules(o?.arr("specific")),
    )

private fun bulkChange(o: JsonObject?): BulkChange =
    BulkChange(rules(o?.arr("add")), rules(o?.arr("remove")))

private fun bulkPair(o: JsonObject?): BulkPair =
    BulkPair(bulkChange(o?.obj("enable")), bulkChange(o?.obj("disable")))

private fun toolView(o: JsonObject): ToolView =
    ToolView(
        name = o.str("name").orEmpty(),
        label = o.str("label").orEmpty(),
        rule = o.str("rule").orEmpty(),
        own = PermissionBehavior.of(o.str("own")),
        more = o.bool("more") == true,
        state = toolState(o.obj("state")),
    )

private fun serverView(o: JsonObject): ServerView =
    ServerView(
        name = o.str("name").orEmpty(),
        rule = o.str("rule").orEmpty(),
        scope = o.str("scope").orEmpty(),
        oauth = o.bool("oauth") == true,
        state = toolState(o.obj("state")),
        bulk = bulkPair(o.obj("bulk")),
    )

private fun parsePermissions(o: JsonObject): PermissionsView {
    val ruleViews = o.arr("rules").objects()
    return PermissionsView(
        rules = ruleViews.mapNotNull(::rule),
        malformed = ruleViews.filter { it.bool("malformed") == true }.mapNotNull(::rule).toSet(),
        builtin = o.arr("builtin").objects().map(::toolView),
        builtinBulk = bulkPair(o.obj("builtinBulk")),
        servers = o.arr("servers").objects().map(::serverView),
        serverTools = o.arr("serverTools").objects().map(::toolView),
        focus = o.obj("focus")?.let(::serverView),
    )
}
