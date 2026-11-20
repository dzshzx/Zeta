package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.clipChars
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.obj
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 系统应用开给系统智能体的工具的纯逻辑：解析应用 APK 里的 `assets/app_tools.json`，决定哪些给 Claude（[offered]），
 * 排版给 Claude 看，按包名或应用名找应用，算出聊天里那一句标题。读文件和调用在 [AppToolCall]，给 Claude 的工具在 [AppTools]。
 *
 * `app_tools.json` 是一个数组，一项一个工具：`name`、说明、`params`（每个参数 `name`、`type`、说明、`required`，数组另有 `items`）。
 * 各应用用的 SDK 不同，说明有两种写法：字符串 `description`，或按语言分的 `descriptions`（`{"zh": …, "en": …}`）；
 * 多数工具还带 `class`（处理它的服务类）、`method`、`paramCount`、参数的 `index`，相册、天气这类没有。
 */
internal object AppToolDefs {
    data class Param(
        val name: String,
        val type: String,
        val description: String,
        val required: Boolean,
        /** 数组元素的类型（`items.type`）；不是数组或没写时为 null。 */
        val itemType: String?,
        val maxLength: Int?,
    )

    data class Tool(val name: String, val description: String, val serviceClass: String?, val params: List<Param>)

    /** 一个有工具服务的应用。[services] 是它声明的工具服务（`包名/完整类名`，至少一个），[tools] 是 `app_tools.json` 里的工具。 */
    data class App(val packageName: String, val label: String, val services: List<String>, val tools: List<Tool>) {
        /** 处理 [tool] 的服务：类名对得上的那个，对不上（或没写类名）时是第一个。 */
        fun serviceFor(tool: Tool): String =
            tool.serviceClass?.let { cls -> services.firstOrNull { it.substringAfter('/') == cls } } ?: services.first()
    }

    /** 应用 [packageName] 的这些 [tools] 由 Zeta 自己的工具 [use] 做，因为 [why]：不给 Claude，调到时答一句该用哪个。 */
    data class Superseded(val packageName: String, val tools: Set<String>, val use: List<String>, val why: String) {
        val instead: String get() = "use " + use.joinToString(" and ") + " instead: $why"
    }

    /** Zeta 已有专门工具、而且更周全的那几项。[use] 里的名字都是现有的手机工具（单元测试核对）。 */
    val SUPERSEDED: List<Superseded> = listOf(
        Superseded(
            "com.android.deskclock",
            setOf(
                "deskclock_query_alarms", "deskclock_add_alarm", "deskclock_update_alarm", "deskclock_delete_alarm", "deskclock_query_timer",
                "deskclock_start_timer", "deskclock_pause_timer", "deskclock_resume_timer", "deskclock_cancel_timer",
            ),
            listOf("clock"),
            "it reads and writes the same alarms and timer and works without the LSPosed module these tools need",
        ),
        Superseded(
            "com.android.calendar",
            setOf("create_event", "update_event", "delete_event", "query_events"),
            listOf("edit_calendar", "search_personal"),
            "they cover events in every calendar account, not only local ones",
        ),
        Superseded(
            "com.miui.notes",
            // rollback_operation 撤销 note_create 时会物理删除便签，见 MiuiNoteWrites
            setOf("note_create", "note_update", "note_write", "note_read", "note_search", "note_list", "rollback_operation"),
            listOf("edit_note", "search_personal"),
            "they keep rich-text notes intact, read back what was written and never delete notes",
        ),
    )

    private fun superseded(packageName: String): Superseded? = SUPERSEDED.firstOrNull { it.packageName == packageName }

    /** [tool] 被 Zeta 自己的工具取代时，该用什么；否则 null。不知道是哪个应用（[packageName] 为 null）时按工具名找。 */
    fun supersededBy(packageName: String?, tool: String): String? =
        SUPERSEDED.firstOrNull { (packageName == null || it.packageName == packageName) && tool in it.tools }?.instead

    /** 给 Claude 的那部分：去掉被取代的工具；一个都不剩时是 null。 */
    fun offered(app: App): App? {
        val hidden = superseded(app.packageName)?.tools ?: return app
        return app.copy(tools = app.tools.filter { it.name !in hidden }).takeIf { it.tools.isNotEmpty() }
    }

    /** 解析 `app_tools.json`。不是数组时返回空表；缺名字的工具和参数跳过。 */
    fun parse(json: String): List<Tool> {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return emptyList()
        return root.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val params = o.arr("params").orEmpty().mapNotNull { p ->
                val po = p as? JsonObject ?: return@mapNotNull null
                Param(
                    name = po.nonEmpty("name") ?: return@mapNotNull null,
                    type = po.nonEmpty("type") ?: "string",
                    description = text(po),
                    required = po.bool("required") == true,
                    itemType = po.obj("items")?.str("type"),
                    maxLength = po.int("maxLength"),
                )
            }
            Tool(o.nonEmpty("name") ?: return@mapNotNull null, text(o), o.nonEmpty("class"), params)
        }
    }

    private fun JsonObject.nonEmpty(key: String): String? = str(key)?.trim()?.takeIf { it.isNotEmpty() }

    /** 说明文字：`description`，或者 `descriptions` 里的中文（没有中文取英文，再没有取第一项）。 */
    private fun text(o: JsonObject): String {
        val localized = o.obj("descriptions")
        val value = o.str("description") ?: localized?.let {
            it.str("zh") ?: it.str("zh_CN") ?: it.str("en") ?: it.values.firstNotNullOfOrNull { v -> (v as? JsonPrimitive)?.contentOrNull }
        }
        return value?.trim().orEmpty()
    }

    /** 按 [query] 找应用：包名或应用名完全一致（不分大小写）。 */
    fun find(apps: List<App>, query: String): App? {
        val q = query.trim()
        return apps.firstOrNull { it.packageName.equals(q, ignoreCase = true) } ?: apps.firstOrNull { it.label.equals(q, ignoreCase = true) }
    }

    /** 调 [tool] 时说的是哪个应用：给了 [query] 就按它找，没给就是有这个工具的那些应用（只有一个才算找到，由调用方判断）。 */
    fun owners(apps: List<App>, query: String?, tool: String): List<App> =
        if (query != null) listOfNotNull(find(apps, query)) else apps.filter { app -> app.tools.any { it.name == tool } }

    /** app_tools 不带 app：每个应用一行，包名、应用名、工具名。 */
    fun overview(apps: List<App>): String {
        if (apps.isEmpty()) return "No system app exposes tools to the system agent on this phone."
        return "System apps' own tools (the ones HyperOS gives its built-in assistant). " +
            "Call app_tools with app to read a tool's description and parameters before app_tool.\n" +
            apps.joinToString("\n") { app -> "- ${app.packageName} (${app.label}), ${app.tools.size} tools: " + app.tools.joinToString(", ") { it.name } }
    }

    /** app_tools 带 app：这个应用的每个工具的说明和参数。 */
    fun details(app: App): String = buildString {
        append("${app.packageName} (${app.label}), ${app.tools.size} tools. ")
        append("The descriptions are Xiaomi's, written for its own assistant: ignore references to tools or skills that are not here, ")
        append("and to rules about asking the user that you would follow anyway.\n")
        superseded(app.packageName)?.let { append("Some of its tools are not offered here — ${it.instead}.\n") }
        for (tool in app.tools) {
            append("\n## ").append(tool.name).append('\n')
            if (tool.description.isNotEmpty()) append(tool.description).append('\n')
            if (tool.params.isEmpty()) {
                append("Parameters: none\n")
                continue
            }
            append("Parameters:\n")
            for (p in tool.params) {
                append("- ").append(p.name).append(" (").append(if (p.type == "array") "array of " + (p.itemType ?: "string") else p.type)
                if (p.required) append(", required")
                p.maxLength?.let { append(", at most ").append(it).append(" chars") }
                append(")")
                if (p.description.isNotEmpty()) append(": ").append(p.description.replace('\n', ' '))
                append('\n')
            }
        }
    }.trimEnd()

    /** 调用前的检查：缺了哪些必填参数。参数照原样交给应用，类型由应用自己认。 */
    fun missing(tool: Tool, arguments: JsonObject): List<String> =
        tool.params.filter { it.required && arguments[it.name].let { v -> v == null || v is JsonNull } }.map { it.name }

    /** 聊天里和胶囊上的一句：「时钟 · 新增一个闹钟」。取说明的第一句，太长就截断。 */
    fun title(label: String, tool: Tool?, toolName: String): String = "$label · " + (tool?.description?.let(::firstClause) ?: toolName)

    private val CLAUSE_ENDS = charArrayOf('。', '，', '\n', '；', '(', '（', '：')

    private fun firstClause(text: String): String? {
        val end = text.indexOfAny(CLAUSE_ENDS)
        return (if (end > 0) text.substring(0, end) else text).trim().takeIf { it.isNotEmpty() }?.clipChars(16, "…")
    }
}
