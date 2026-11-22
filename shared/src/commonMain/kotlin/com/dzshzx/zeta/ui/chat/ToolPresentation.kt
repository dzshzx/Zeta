package com.dzshzx.zeta.ui.chat

import androidx.compose.ui.graphics.vector.ImageVector
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.str
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Edit
import io.github.suqi8.coui.kmp.icon.extended.File
import io.github.suqi8.coui.kmp.icon.extended.Link
import io.github.suqi8.coui.kmp.icon.extended.MindMap
import io.github.suqi8.coui.kmp.icon.extended.ReplyAll
import io.github.suqi8.coui.kmp.icon.extended.SelectAll
import io.github.suqi8.coui.kmp.icon.extended.Timer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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

/** 一次工具调用在列表里怎么显示：图标、一行标题、展开后的细节。 */
fun presentTool(name: String, input: JsonObject?): ToolPresentation {
    fun s(key: String) = input?.str(key)
    // 只有一部分工具的细节是整份输入；写文件这类的输入里带着整个文件，用不到就不格式化
    val json by lazy {
        input
            ?.let { runCatching { pretty.encodeToString(JsonObject.serializer(), it) }.getOrNull() }
            .orEmpty()
    }
    return when (name) {
        "Bash" ->
            ToolPresentation(
                ZetaIcons.Terminal,
                s("description")?.takeIf { it.isNotBlank() }
                    ?: firstLine(s("command")).ifEmpty { "执行命令" },
                s("command").orEmpty(),
            )
        "Read" ->
            ToolPresentation(
                COUIIcons.File,
                "读取 " + baseName(s("file_path")),
                s("file_path").orEmpty(),
            )
        "Write" ->
            ToolPresentation(
                COUIIcons.Edit,
                "写入 " + baseName(s("file_path")),
                s("file_path").orEmpty() + "\n\n" + s("content").orEmpty(),
            )
        "Edit",
        "MultiEdit",
        "NotebookEdit" ->
            ToolPresentation(
                COUIIcons.Edit,
                "编辑 " + baseName(s("file_path") ?: s("notebook_path")),
                (s("file_path") ?: s("notebook_path")).orEmpty() +
                    (s("old_string")?.let { "\n\n- $it\n+ ${s("new_string").orEmpty()}" } ?: ""),
            )
        "Grep" -> ToolPresentation(ZetaIcons.SearchText, "搜索 " + firstLine(s("pattern"), 40), json)
        "Glob" ->
            ToolPresentation(ZetaIcons.SearchText, "查找文件 " + firstLine(s("pattern"), 40), json)
        "WebSearch" ->
            ToolPresentation(
                ZetaIcons.Globe,
                "搜索网页：" + firstLine(s("query"), 50),
                s("query").orEmpty(),
            )
        "WebFetch" ->
            ToolPresentation(
                ZetaIcons.Globe,
                "读取网页 " + s("url").orEmpty().substringAfter("://").substringBefore('/'),
                s("url").orEmpty() + "\n\n" + s("prompt").orEmpty(),
            )
        "Task",
        "Agent" ->
            ToolPresentation(
                COUIIcons.MindMap,
                "子代理：" + firstLine(s("description") ?: s("prompt"), 50),
                s("prompt").orEmpty(),
            )
        "TodoWrite",
        "TaskCreate",
        "TaskUpdate",
        "TaskList",
        "TaskGet" -> ToolPresentation(COUIIcons.SelectAll, todoToolTitle(name, input), json)
        "AskUserQuestion" -> ToolPresentation(COUIIcons.ReplyAll, "向你提问", json)
        "ExitPlanMode" -> ToolPresentation(COUIIcons.SelectAll, "提交计划", s("plan") ?: json)
        "Skill" ->
            ToolPresentation(
                ZetaIcons.Sparkle,
                "技能 " + (s("skill") ?: s("command")).orEmpty(),
                json,
            )
        else ->
            if (name.startsWith("mcp__phone__")) {
                presentPhoneTool(name.removePrefix("mcp__phone__"), input, json)
            } else if (name.startsWith("mcp__")) {
                val parts = name.removePrefix("mcp__").split("__", limit = 2)
                ToolPresentation(ZetaIcons.Puzzle, parts.joinToString(" · "), json)
            } else {
                ToolPresentation(ZetaIcons.Wrench, name.ifBlank { "工具" }, json)
            }
    }
}

/** 手机工具：标题用手机工具目录里那一句（[ChatPlatform.phoneToolTitle]，和顶部胶囊同一处），这里只挑图标。 */
private fun presentPhoneTool(tool: String, input: JsonObject?, json: String): ToolPresentation {
    val title =
        ChatPlatform.current.phoneToolTitle(tool, input ?: JsonObject(emptyMap())) ?: "手机 · $tool"
    val icon =
        when (tool) {
            "shell" -> ZetaIcons.Terminal
            "tap",
            "long_press",
            "swipe" -> ZetaIcons.Tap
            "scroll" -> ZetaIcons.Swipe
            "wait" -> COUIIcons.Timer
            "open_app" -> if (input?.str("uri") != null) COUIIcons.Link else ZetaIcons.Phone
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
