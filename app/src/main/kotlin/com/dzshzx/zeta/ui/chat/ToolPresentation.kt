package com.dzshzx.zeta.ui.chat

import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.int
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class ToolPresentation(val icon: ImageVector, val title: String, val detail: String)

private val pretty = Json { prettyPrint = true }

private fun baseName(path: String?): String = path?.trimEnd('/')?.substringAfterLast('/').orEmpty()

private fun firstLine(text: String?, limit: Int = 80): String =
    text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.let { if (it.length > limit) it.take(limit) + "…" else it }.orEmpty()

/** 一次工具调用在列表里怎么显示：图标、一行标题、展开后的细节。 */
internal fun presentTool(name: String, input: JsonObject?): ToolPresentation {
    fun s(key: String) = input?.str(key)
    val json = input?.let { runCatching { pretty.encodeToString(JsonObject.serializer(), it) }.getOrNull() }.orEmpty()
    return when (name) {
        "Bash" -> ToolPresentation(Icons.Rounded.Terminal, s("description")?.takeIf { it.isNotBlank() } ?: firstLine(s("command")).ifEmpty { "执行命令" }, s("command").orEmpty())
        "Read" -> ToolPresentation(Icons.Rounded.Description, "读取 " + baseName(s("file_path")), s("file_path").orEmpty())
        "Write" -> ToolPresentation(Icons.Rounded.EditNote, "写入 " + baseName(s("file_path")), s("file_path").orEmpty() + "\n\n" + s("content").orEmpty())
        "Edit", "MultiEdit", "NotebookEdit" -> ToolPresentation(
            Icons.Rounded.EditNote, "编辑 " + baseName(s("file_path") ?: s("notebook_path")),
            (s("file_path") ?: s("notebook_path")).orEmpty() + (s("old_string")?.let { "\n\n- $it\n+ ${s("new_string").orEmpty()}" } ?: ""),
        )
        "Grep" -> ToolPresentation(Icons.AutoMirrored.Rounded.ManageSearch, "搜索 " + firstLine(s("pattern"), 40), json)
        "Glob" -> ToolPresentation(Icons.AutoMirrored.Rounded.ManageSearch, "查找文件 " + firstLine(s("pattern"), 40), json)
        "WebSearch" -> ToolPresentation(Icons.Rounded.Language, "搜索网页：" + firstLine(s("query"), 50), s("query").orEmpty())
        "WebFetch" -> ToolPresentation(Icons.Rounded.Language, "读取网页 " + s("url").orEmpty().substringAfter("://").substringBefore('/'), s("url").orEmpty() + "\n\n" + s("prompt").orEmpty())
        "Task", "Agent" -> ToolPresentation(Icons.Rounded.AccountTree, "子代理：" + firstLine(s("description") ?: s("prompt"), 50), s("prompt").orEmpty())
        "TodoWrite", "TaskCreate", "TaskUpdate" -> ToolPresentation(Icons.Rounded.Checklist, "更新待办", json)
        "AskUserQuestion" -> ToolPresentation(Icons.Rounded.QuestionAnswer, "向你提问", json)
        "ExitPlanMode" -> ToolPresentation(Icons.Rounded.Checklist, "提交计划", s("plan") ?: json)
        "Skill" -> ToolPresentation(Icons.Rounded.AutoAwesome, "技能 " + (s("skill") ?: s("command")).orEmpty(), json)
        else -> if (name.startsWith("mcp__phone__")) {
            presentPhoneTool(name.removePrefix("mcp__phone__"), input, json)
        } else if (name.startsWith("mcp__")) {
            val parts = name.removePrefix("mcp__").split("__", limit = 2)
            ToolPresentation(Icons.Rounded.Extension, parts.joinToString(" · "), json)
        } else {
            ToolPresentation(Icons.Rounded.Build, name.ifBlank { "工具" }, json)
        }
    }
}

/** 手机工具：用人话说它在手机上做了什么。 */
private fun presentPhoneTool(tool: String, input: JsonObject?, json: String): ToolPresentation {
    fun s(key: String) = input?.str(key)
    fun n(key: String) = input?.int(key)
    val where = n("node")?.let { "控件 $it" } ?: n("x")?.let { "($it, ${n("y") ?: 0})" }.orEmpty()
    return when (tool) {
        "shell" -> ToolPresentation(Icons.Rounded.Terminal, "Android：" + firstLine(s("command")).ifEmpty { "执行命令" }, s("command").orEmpty())
        "screen" -> ToolPresentation(Icons.Rounded.Smartphone, if (input?.bool("screenshot") == true) "看屏幕（带截图）" else "看屏幕", json)
        "tap" -> ToolPresentation(Icons.Rounded.TouchApp, "点按 $where".trim(), json)
        "long_press" -> ToolPresentation(Icons.Rounded.TouchApp, "长按 $where".trim(), json)
        "swipe" -> ToolPresentation(Icons.Rounded.TouchApp, "滑动", json)
        "type_text" -> ToolPresentation(Icons.Rounded.Smartphone, "输入“" + firstLine(s("text"), 30) + "”", json)
        "press_key" -> ToolPresentation(
            Icons.Rounded.Smartphone,
            "按" + when (s("key")) {
                "back" -> "返回键"
                "home" -> "主屏幕键"
                "recents" -> "最近任务键"
                "enter" -> "回车"
                "delete" -> "退格"
                "wake" -> "亮屏"
                "notifications" -> "下拉通知栏"
                "quick_settings" -> "下拉控制中心"
                else -> s("key").orEmpty()
            },
            json,
        )
        "open_app" -> ToolPresentation(Icons.Rounded.Smartphone, "打开 " + s("app").orEmpty(), json)
        "find_apps" -> ToolPresentation(Icons.Rounded.Smartphone, "查找应用 " + s("query").orEmpty(), json)
        else -> ToolPresentation(Icons.Rounded.Smartphone, "手机 · $tool", json)
    }
}

internal fun ToolStep.presentation(): ToolPresentation = presentTool(name, input)

/** 一次工具调用的一行标题，和聊天列表里的一样（顶部状态胶囊也用）。 */
internal fun toolTitle(name: String, input: JsonObject?): String = presentTool(name, input).title
