package com.dzshzx.zeta.ui.chat

import androidx.compose.ui.graphics.vector.ImageVector
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.str
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.ZetaIcons
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.MindMap
import top.yukonga.miuix.kmp.icon.extended.ReplyAll
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Timer

class ToolPresentation(val icon: ImageVector, val title: String, val detail: String)

private val pretty = Json { prettyPrint = true }

private fun baseName(path: String?): String = path?.trimEnd('/')?.substringAfterLast('/').orEmpty()

private fun firstLine(text: String?, limit: Int = 80): String =
    text
        ?.lineSequence()
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.let { if (it.length > limit) it.take(limit) + "…" else it }
        .orEmpty()

/** 识别 `mcp__phone__*` 与 `mcp_phone_*` 两种手机工具名前缀，返回去掉前缀后的工具名；不是手机工具时为 null。 */
fun phoneToolSuffix(name: String): String? =
    when {
        name.startsWith("mcp__phone__") -> name.removePrefix("mcp__phone__")
        name.startsWith("mcp_phone_") -> name.removePrefix("mcp_phone_")
        else -> null
    }

/** 一次工具调用在列表里怎么显示：图标、一行标题、展开后的细节。 */
fun presentTool(name: String, input: JsonObject?): ToolPresentation {
    // 只有一部分工具的细节是整份输入；写文件这类的输入里带着整个文件，用不到就不格式化
    val json = lazy {
        input
            ?.let { runCatching { pretty.encodeToString(JsonObject.serializer(), it) }.getOrNull() }
            .orEmpty()
    }
    return presentClaudeTool(name, input, json)
        ?: presentAgyTool(name, input, json)
        ?: presentMcpOrGenericTool(name, input, json.value)
}

private fun presentClaudeTool(
    name: String,
    input: JsonObject?,
    json: Lazy<String>,
): ToolPresentation? {
    fun s(key: String) = input?.str(key)
    return when (name) {
        "Bash" -> presentCommandTool(s("description"), s("command"))
        "Read" -> presentReadTool(s("file_path"))
        "Write" -> presentWriteTool(s("file_path"), s("content"))
        "Edit",
        "MultiEdit",
        "NotebookEdit" ->
            presentEditTool(s("file_path") ?: s("notebook_path"), s("old_string"), s("new_string"))
        "Grep" ->
            ToolPresentation(
                ZetaIcons.SearchText,
                Strings.chatToolGrep(firstLine(s("pattern"), 40)),
                json.value,
            )
        "Glob" ->
            ToolPresentation(
                ZetaIcons.SearchText,
                Strings.chatToolGlob(firstLine(s("pattern"), 40)),
                json.value,
            )
        "WebSearch" -> presentWebSearchTool(s("query"), null)
        "WebFetch" ->
            ToolPresentation(
                ZetaIcons.Globe,
                Strings.chatToolWebFetch(
                    s("url").orEmpty().substringAfter("://").substringBefore('/'),
                ),
                s("url").orEmpty() + "\n\n" + s("prompt").orEmpty(),
            )
        "Task",
        "Agent" -> presentSubagentTool(s("description") ?: s("prompt"), s("prompt"), json)
        "TodoWrite",
        "TaskCreate",
        "TaskUpdate",
        "TaskList",
        "TaskGet" -> ToolPresentation(MiuixIcons.SelectAll, todoToolTitle(name, input), json.value)
        "AskUserQuestion" ->
            ToolPresentation(MiuixIcons.ReplyAll, Strings.chatToolAsk(), json.value)
        "ExitPlanMode" ->
            ToolPresentation(MiuixIcons.SelectAll, Strings.chatToolPlan(), s("plan") ?: json.value)
        "Skill" ->
            ToolPresentation(
                ZetaIcons.Sparkle,
                Strings.chatToolSkill((s("skill") ?: s("command")).orEmpty()),
                json.value,
            )
        else -> null
    }
}

private fun presentAgyTool(
    name: String,
    input: JsonObject?,
    json: Lazy<String>,
): ToolPresentation? {
    fun s(key: String) = input?.str(key)
    return when (name) {
        "run_command" ->
            presentCommandTool(
                s("toolSummary") ?: s("description"),
                s("CommandLine") ?: s("command"),
            )
        "read_file" -> presentReadTool(s("Path") ?: s("file_path"))
        "write_file" -> presentWriteTool(s("Path") ?: s("file_path"), s("Content") ?: s("content"))
        "edit_file" ->
            presentEditTool(
                s("Path") ?: s("file_path"),
                s("Target") ?: s("old_string"),
                s("Replacement") ?: s("new_string"),
            )
        "grep_search" ->
            ToolPresentation(
                ZetaIcons.SearchText,
                Strings.chatToolGrep(firstLine(s("Query") ?: s("pattern"), 40)),
                json.value,
            )
        "find_by_name" ->
            ToolPresentation(
                ZetaIcons.SearchText,
                Strings.chatToolGlob(
                    firstLine(s("Pattern") ?: s("pattern") ?: baseName(s("SearchDirectory")), 40),
                ),
                json.value,
            )
        "search_web" -> presentWebSearchTool(s("query") ?: s("Query"), s("domain"))
        "invoke_subagent" ->
            presentSubagentTool(
                s("Role") ?: s("TypeName") ?: s("Prompt") ?: s("prompt"),
                s("Prompt") ?: s("prompt"),
                json,
            )
        "ask_question" -> ToolPresentation(MiuixIcons.ReplyAll, Strings.chatToolAsk(), json.value)
        "update_steps" -> presentUpdateStepsTool(input, json)
        "manage_task" -> presentManageTaskTool(s("Action"), s("TaskId"), json)
        else -> null
    }
}

private fun presentCommandTool(summary: String?, command: String?): ToolPresentation =
    ToolPresentation(
        ZetaIcons.Terminal,
        summary?.takeIf { it.isNotBlank() }
            ?: firstLine(command).ifEmpty { Strings.chatToolRunCommand() },
        command.orEmpty(),
    )

private fun presentReadTool(path: String?): ToolPresentation =
    ToolPresentation(
        MiuixIcons.File,
        Strings.chatToolRead(baseName(path)),
        path.orEmpty(),
    )

private fun presentWriteTool(path: String?, content: String?): ToolPresentation =
    ToolPresentation(
        MiuixIcons.Edit,
        Strings.chatToolWrite(baseName(path)),
        path.orEmpty() + "\n\n" + content.orEmpty(),
    )

private fun presentEditTool(path: String?, oldText: String?, newText: String?): ToolPresentation =
    ToolPresentation(
        MiuixIcons.Edit,
        Strings.chatToolEdit(baseName(path)),
        path.orEmpty() + (oldText?.let { "\n\n- $it\n+ ${newText.orEmpty()}" } ?: ""),
    )

private fun presentWebSearchTool(query: String?, domain: String?): ToolPresentation =
    ToolPresentation(
        ZetaIcons.Globe,
        Strings.chatToolWebSearch(firstLine(query, 50)),
        listOfNotNull(query?.takeIf { it.isNotBlank() }, domain?.takeIf { it.isNotBlank() })
            .joinToString("\n"),
    )

private fun presentSubagentTool(
    label: String?,
    prompt: String?,
    json: Lazy<String>,
): ToolPresentation =
    ToolPresentation(
        MiuixIcons.MindMap,
        Strings.chatToolAgent(firstLine(label, 50)),
        prompt.orEmpty().ifEmpty { json.value },
    )

private fun presentUpdateStepsTool(input: JsonObject?, json: Lazy<String>): ToolPresentation {
    val steps = input?.arr("steps")?.mapNotNull { it as? JsonObject }.orEmpty()
    val title =
        if (steps.isNotEmpty()) Strings.chatToolUpdateStepsCount(steps.size)
        else Strings.chatToolUpdateSteps()
    val detail =
        steps
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { step ->
                val mark =
                    when (step.str("status")) {
                        "done",
                        "completed" -> "[x]"
                        "in_progress" -> "[~]"
                        else -> "[ ]"
                    }
                "$mark ${step.str("title").orEmpty()}"
            } ?: json.value
    return ToolPresentation(MiuixIcons.SelectAll, title, detail)
}

private fun presentManageTaskTool(
    action: String?,
    taskId: String?,
    json: Lazy<String>,
): ToolPresentation {
    val summary =
        listOfNotNull(action?.takeIf { it.isNotBlank() }, taskId?.takeIf { it.isNotBlank() })
            .joinToString(" ")
    val title =
        if (summary.isNotEmpty()) Strings.chatToolManageTaskAction(summary)
        else Strings.chatToolManageTask()
    return ToolPresentation(MiuixIcons.Timer, title, json.value)
}

private fun presentMcpOrGenericTool(
    name: String,
    input: JsonObject?,
    json: String,
): ToolPresentation {
    phoneToolSuffix(name)?.let { tool ->
        return presentPhoneTool(tool, input, json)
    }
    if (name.startsWith("mcp__")) {
        val parts = name.removePrefix("mcp__").split("__", limit = 2)
        return ToolPresentation(ZetaIcons.Puzzle, parts.joinToString(" · "), json)
    }
    if (name.startsWith("mcp_")) {
        val parts = name.removePrefix("mcp_").split("_", limit = 2)
        return ToolPresentation(ZetaIcons.Puzzle, parts.joinToString(" · "), json)
    }
    return ToolPresentation(ZetaIcons.Wrench, name.ifBlank { Strings.chatToolGeneric() }, json)
}

/** 手机工具：标题用手机工具目录里那一句（[ChatPlatform.phoneToolTitle]，和顶部胶囊同一处），这里只挑图标。 */
private fun presentPhoneTool(tool: String, input: JsonObject?, json: String): ToolPresentation {
    val title =
        ChatPlatform.current.phoneToolTitle(tool, input ?: JsonObject(emptyMap()))
            ?: Strings.chatToolPhone(tool)
    val icon =
        when (tool) {
            "shell" -> ZetaIcons.Terminal
            "tap",
            "long_press",
            "swipe" -> ZetaIcons.Tap
            "scroll" -> ZetaIcons.Swipe
            "wait" -> MiuixIcons.Timer
            "open_app" -> if (input?.str("uri") != null) MiuixIcons.Link else ZetaIcons.Phone
            BROWSER_TOOL -> ZetaIcons.Globe
            else -> ZetaIcons.Phone
        }
    // 命令行展开后直接看命令，别的看整份参数
    val detail = if (tool == "shell") input?.str("command").orEmpty() else json
    return ToolPresentation(icon, title, detail)
}

fun ToolStep.presentation(): ToolPresentation = presentTool(name, input)

/** 一次工具调用的一行标题，和聊天列表里的一样（顶部状态胶囊也用）。 */
fun toolTitle(name: String, input: JsonObject?): String = presentTool(name, input).title

/** 手机的浏览器工具的名字（`mcp__phone__browser`），手机上的工具定义（app 的 phone/tools/BrowserTools.kt）也用它。 */
const val BROWSER_TOOL = "browser"
