package com.dzshzx.zeta.core.claude

import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.Harnesses
import com.dzshzx.zeta.core.ServeError
import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.callCore
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.obj
import com.dzshzx.zeta.core.str
import com.dzshzx.zeta.i18n.Strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

data class AgyOrchestrator(val planner: String = "", val executor: String = "")

data class AgySandboxProxy(
    val http: String = "",
    val https: String = "",
    val noProxy: String = "",
    val username: String = "",
    val hasPassword: Boolean = false,
)

data class AgyGCP(val project: String = "", val location: String = "")

data class AgySettings(
    val toolPermission: String = "",
    val enableTerminalSandbox: Boolean = false,
    val allowNonWorkspaceAccess: Boolean = false,
    val artifactReviewPolicy: String = "",
    val useG1Credits: Boolean = false,
    val queuedMessages: String = "",
    val orchestrator: AgyOrchestrator = AgyOrchestrator(),
    val sandboxProxy: AgySandboxProxy = AgySandboxProxy(),
    val trustedWorkspaces: List<String> = emptyList(),
    val customModelsConfig: String = "",
    val gcp: AgyGCP = AgyGCP(),
)

data class AgyPlugin(
    val name: String,
    val version: String = "",
    val description: String = "",
    val enabled: Boolean = false,
    val source: String = "",
)

data class AgyHookEntry(
    val name: String,
    val scope: String,
    val plugin: String = "",
    val readOnly: Boolean = false,
    val enabled: Boolean = true,
    val event: String = "",
    val matcher: String = "",
    val command: String = "",
    val timeout: Int = 0,
    val events: List<String> = emptyList(),
    val commandCount: Int = 0,
)

data class AgyHooksView(
    val userPath: String,
    val projectPath: String = "",
    val hooks: List<AgyHookEntry> = emptyList(),
)

data class AgyHookDraft(
    val originalName: String = "",
    val name: String,
    val scope: String,
    val enabled: Boolean,
    val event: String,
    val matcher: String = "",
    val command: String,
    val timeout: Int = 0,
)

data class AgySidecar(
    val id: String,
    val displayName: String,
    val description: String = "",
    val command: String = "",
    val builtin: String = "",
    val restartPolicy: String = "",
    val plugin: String = "",
    val enabled: Boolean = false,
    val projectId: String = "",
)

/** Antigravity 专有配置 RPC 客户端（agysettings / plugins / hooks / sidecars）。 */
class AgyConfigCore(
    private val call: (method: String, params: JsonObject, timeoutMs: Long) -> JsonElement?,
) {
    companion object {
        fun of(hub: ConversationHub, backend: String) = AgyConfigCore { method, params, timeoutMs ->
            hub.callCore(backend, method, params, timeoutMs)
        }
    }

    private fun req(
        method: String,
        timeoutMs: Long = ClaudeConfigCore.TIMEOUT_MS,
        params: JsonObjectBuilder.() -> Unit = {},
    ): JsonElement? =
        call(
            method,
            buildJsonObject {
                put("harness", Harnesses.AGY)
                params()
            },
            timeoutMs,
        )

    private fun objResult(
        method: String,
        timeoutMs: Long = ClaudeConfigCore.TIMEOUT_MS,
        params: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject =
        req(method, timeoutMs, params) as? JsonObject
            ?: throw ServeError(Strings.coreConfigBadReply(method))

    private fun listResult(
        method: String,
        timeoutMs: Long = ClaudeConfigCore.TIMEOUT_MS,
        params: JsonObjectBuilder.() -> Unit = {},
    ): List<JsonObject> =
        (req(method, timeoutMs, params) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    fun agySettings(): AgySettings = parseAgySettings(objResult("config.agy.settings.read"))

    fun updateAgySettings(patch: JsonObject): AgySettings =
        parseAgySettings(objResult("config.agy.settings.update") { put("patch", patch) })

    fun plugins(): List<AgyPlugin> = listResult("config.plugins.list").map(::parseAgyPlugin)

    fun setPluginEnabled(name: String, enabled: Boolean): List<AgyPlugin> =
        listResult("config.plugins.setEnabled") {
                put("name", name)
                put("enabled", enabled)
            }
            .map(::parseAgyPlugin)

    fun installPlugin(target: String): List<AgyPlugin> =
        listResult("config.plugins.install", ClaudeConfigCore.SLOW_TIMEOUT_MS) {
                put("target", target)
            }
            .map(::parseAgyPlugin)

    fun uninstallPlugin(name: String): List<AgyPlugin> =
        listResult("config.plugins.uninstall", ClaudeConfigCore.SLOW_TIMEOUT_MS) {
                put("name", name)
            }
            .map(::parseAgyPlugin)

    fun hooks(cwd: String): AgyHooksView =
        parseAgyHooks(objResult("config.hooks.read") { put("cwd", cwd) })

    fun setHookEnabled(cwd: String, scope: String, name: String, enabled: Boolean): AgyHooksView =
        parseAgyHooks(
            objResult("config.hooks.setEnabled") {
                put("cwd", cwd)
                put("scope", scope)
                put("name", name)
                put("enabled", enabled)
            },
        )

    fun saveHook(cwd: String, draft: AgyHookDraft): AgyHooksView =
        parseAgyHooks(
            objResult("config.hooks.save") {
                put("cwd", cwd)
                put(
                    "draft",
                    buildJsonObject {
                        put("originalName", draft.originalName)
                        put("name", draft.name)
                        put("scope", draft.scope)
                        put("enabled", draft.enabled)
                        put("event", draft.event)
                        put("matcher", draft.matcher)
                        put("command", draft.command)
                        if (draft.timeout > 0) put("timeout", draft.timeout)
                    },
                )
            },
        )

    fun deleteHook(cwd: String, scope: String, name: String): AgyHooksView =
        parseAgyHooks(
            objResult("config.hooks.delete") {
                put("cwd", cwd)
                put("scope", scope)
                put("name", name)
            },
        )

    fun sidecars(): List<AgySidecar> = listResult("config.sidecars.list").map(::parseAgySidecar)

    fun setSidecarEnabled(id: String, enabled: Boolean): List<AgySidecar> =
        listResult("config.sidecars.setEnabled") {
                put("id", id)
                put("enabled", enabled)
            }
            .map(::parseAgySidecar)
}

private fun parseAgySettings(o: JsonObject): AgySettings {
    val orch = o.obj("orchestrator")
    val proxy = o.obj("sandboxProxy")
    val gcp = o.obj("gcp")
    return AgySettings(
        toolPermission = o.str("toolPermission").orEmpty(),
        enableTerminalSandbox = o.bool("enableTerminalSandbox") == true,
        allowNonWorkspaceAccess = o.bool("allowNonWorkspaceAccess") == true,
        artifactReviewPolicy = o.str("artifactReviewPolicy").orEmpty(),
        useG1Credits = o.bool("useG1Credits") == true,
        queuedMessages = o.str("queuedMessages").orEmpty(),
        orchestrator =
            AgyOrchestrator(orch?.str("planner").orEmpty(), orch?.str("executor").orEmpty()),
        sandboxProxy =
            AgySandboxProxy(
                http = proxy?.str("http").orEmpty(),
                https = proxy?.str("https").orEmpty(),
                noProxy = proxy?.str("noProxy").orEmpty(),
                username = proxy?.str("username").orEmpty(),
                hasPassword = proxy?.bool("hasPassword") == true,
            ),
        trustedWorkspaces = o.arr("trustedWorkspaces").stringItems(),
        customModelsConfig = o.str("customModelsConfig").orEmpty(),
        gcp = AgyGCP(gcp?.str("project").orEmpty(), gcp?.str("location").orEmpty()),
    )
}

private fun parseAgyPlugin(o: JsonObject): AgyPlugin =
    AgyPlugin(
        name = o.str("name").orEmpty(),
        version = o.str("version").orEmpty(),
        description = o.str("description").orEmpty(),
        enabled = o.bool("enabled") == true,
        source = o.str("source").orEmpty(),
    )

private fun parseAgyHooks(o: JsonObject): AgyHooksView =
    AgyHooksView(
        userPath = o.str("userPath").orEmpty(),
        projectPath = o.str("projectPath").orEmpty(),
        hooks =
            o.arr("hooks").orEmpty().filterIsInstance<JsonObject>().map { h ->
                AgyHookEntry(
                    name = h.str("name").orEmpty(),
                    scope = h.str("scope").orEmpty(),
                    plugin = h.str("plugin").orEmpty(),
                    readOnly = h.bool("readOnly") == true,
                    enabled = h.bool("enabled") != false,
                    event = h.str("event").orEmpty(),
                    matcher = h.str("matcher").orEmpty(),
                    command = h.str("command").orEmpty(),
                    timeout = h.int("timeout") ?: 0,
                    events = h.arr("events").stringItems(),
                    commandCount = h.int("commandCount") ?: 0,
                )
            },
    )

private fun parseAgySidecar(o: JsonObject): AgySidecar =
    AgySidecar(
        id = o.str("id").orEmpty(),
        displayName = o.str("displayName").orEmpty(),
        description = o.str("description").orEmpty(),
        command = o.str("command").orEmpty(),
        builtin = o.str("builtin").orEmpty(),
        restartPolicy = o.str("restartPolicy").orEmpty(),
        plugin = o.str("plugin").orEmpty(),
        enabled = o.bool("enabled") == true,
        projectId = o.str("projectId").orEmpty(),
    )

fun jsonStringArray(items: List<String>): JsonArray = JsonArray(items.map(::JsonPrimitive))

private fun JsonArray?.stringItems(): List<String> =
    orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
