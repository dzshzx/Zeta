package com.dzshzx.zeta.phone.tools

import android.content.Context
import com.dzshzx.zeta.core.clipChars
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.obj
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 系统应用开给 HyperOS 系统智能体的工具，全部接给 Claude：装着哪些就有哪些，运行时现查，不写死。
 * 哪些应用有、怎么调在 [AppToolCall]；解析、排版、哪些不给 Claude（被 Zeta 自己的工具取代的）在 [AppToolDefs]。
 *
 * 给 Claude 两个工具：`app_tools` 先看有哪些应用、某个应用的工具和参数，`app_tool` 再调用。不把上百个工具的长说明
 * 都放进工具清单：说明是小米写给它自己的助手的，很长，还提到这里没有的工具。
 */
internal object AppTools : PhoneToolGroup {
    private const val LIST = "app_tools"
    private const val CALL = "app_tool"

    /** 交回给 Claude 的结果最多这么多字：相册搜索之类可能很长。 */
    private const val OUTPUT_LIMIT = 30_000

    private const val DEFAULT_TIMEOUT_S = 60
    private const val MAX_TIMEOUT_S = 600

    override val tools: List<JsonElement> = listOf(
        tool(
            LIST,
            "List the tools that the phone's system apps expose to HyperOS's built-in assistant, e.g. Calendar birthdays, " +
                "anniversaries, countdowns and public holidays, Recorder (record, search, transcripts), Gallery (search photos by content, " +
                "albums, favourites), Notes folders, to-dos and cards, Weather, call controls, XiaoAi (operate third-party apps, translate, " +
                "its memory, media), Mi Wallet bookkeeping, Mi Share, casting, launcher settings, cleaner, feedback — whatever is installed. " +
                "Without app: every app with its tool names. With app: each tool's description and parameters. Read them before calling app_tool.",
            readOnly = true,
        ) {
            schema {
                prop("app", "string", "Package name or app name from the list, e.g. com.android.calendar.")
            }
        },
        tool(
            CALL,
            "Call one of the system apps' tools listed by app_tools. The app does the work itself, as when its own assistant asks; " +
                "the result is the app's reply.",
        ) {
            schema(required = listOf("tool")) {
                prop("app", "string", "Package name or app name. May be omitted when only one app has this tool.")
                prop("tool", "string", "Tool name, as listed by app_tools.")
                prop("arguments", "object", "The tool's parameters, as described by app_tools.")
                prop("timeout_seconds", "integer", "How long to wait for the app ($DEFAULT_TIMEOUT_S by default, up to $MAX_TIMEOUT_S). Raise it for tools that say they block until done.")
            }
        },
    )

    override fun call(context: Context, name: String, input: JsonObject): JsonObject = toolCall {
        when (name) {
            LIST -> toolText(list(context, appQuery(input)))
            CALL -> invoke(context, input)
            else -> toolFailure("没有这个工具：$name")
        }
    }

    override fun title(name: String, input: JsonObject): String {
        val query = appQuery(input)
        // 标题不带 Context：只看读过的应用，没读过时照输入写
        val apps = AppToolCall.known().mapNotNull(AppToolDefs::offered)
        return when (name) {
            LIST -> query?.let { q -> "查看" + (AppToolDefs.find(apps, q)?.label ?: q) + "的工具" } ?: "查看系统应用的工具"
            CALL -> {
                val toolName = input.str("tool").orEmpty()
                val owner = AppToolDefs.owners(apps, query, toolName).singleOrNull()
                AppToolDefs.title(owner?.label ?: query ?: "系统应用", owner?.tools?.firstOrNull { it.name == toolName }, toolName)
            }
            else -> name
        }
    }

    private fun appQuery(input: JsonObject): String? = input.str("app")?.trim()?.takeIf { it.isNotEmpty() }

    /** 给 Claude 的应用：有工具服务、读得出清单，去掉被取代的工具后还剩工具的。 */
    private fun offered(context: Context): List<AppToolDefs.App> = AppToolCall.apps(context).mapNotNull(AppToolDefs::offered)

    private fun list(context: Context, query: String?): String {
        val apps = offered(context)
        if (query == null) return AppToolDefs.overview(apps)
        return AppToolDefs.details(AppToolDefs.find(apps, query) ?: throw BadInput(notFound(query, apps)))
    }

    private fun invoke(context: Context, input: JsonObject): JsonObject {
        val toolName = input.str("tool")?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadInput("要给 tool（工具名，用 app_tools 查）")
        val query = appQuery(input)
        val all = AppToolCall.apps(context)
        val target = query?.let { AppToolDefs.find(all, it) }
        // 被取代的工具不在给 Claude 的清单里：先答该用哪个，而不是「没有这个工具」
        if (query == null || target != null) {
            AppToolDefs.supersededBy(target?.packageName, toolName)?.let { throw BadInput("$toolName 不在这里用：$it") }
        }
        val apps = all.mapNotNull(AppToolDefs::offered)
        val owners = AppToolDefs.owners(apps, query, toolName)
        val app = owners.singleOrNull() ?: throw BadInput(
            when {
                query != null -> notFound(query, apps)
                owners.isEmpty() -> "没有哪个应用有工具 $toolName：先用 app_tools 查"
                else -> "好几个应用都有 $toolName（" + owners.joinToString("、") { it.packageName } + "），要给 app"
            },
        )
        val tool = app.tools.firstOrNull { it.name == toolName }
            ?: throw BadInput("${app.label}（${app.packageName}）没有工具 $toolName：用 app_tools 加 app 查它有哪些")
        val arguments = input.obj("arguments") ?: JsonObject(emptyMap())
        val missing = AppToolDefs.missing(tool, arguments)
        if (missing.isNotEmpty()) throw BadInput("$toolName 缺必填参数：" + missing.joinToString("、"))
        val timeoutMs = (input.int("timeout_seconds") ?: DEFAULT_TIMEOUT_S).coerceIn(5, MAX_TIMEOUT_S) * 1000L
        val (reply) = AppToolCall.call(context, app.packageName, toolName, arguments, timeoutMs)
        val text = reply.text.ifEmpty { if (reply.isError) "（${app.label}报错，没有说明）" else "（${app.label}没有返回内容）" }
            .clipChars(OUTPUT_LIMIT, "\n…（太长，只交回前 $OUTPUT_LIMIT 字）")
        return if (reply.isError) toolFailure(text) else toolText(text)
    }

    private fun notFound(query: String, apps: List<AppToolDefs.App>): String =
        "没有叫「$query」的应用；有工具的是：" + apps.joinToString("、") { "${it.packageName}（${it.label}）" }
}
