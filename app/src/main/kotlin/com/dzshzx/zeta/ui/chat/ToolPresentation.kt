package com.dzshzx.zeta.ui.chat

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
        else -> if (name.startsWith("mcp__")) {
            val parts = name.removePrefix("mcp__").split("__", limit = 2)
            ToolPresentation(Icons.Rounded.Extension, parts.joinToString(" · "), json)
        } else {
            ToolPresentation(Icons.Rounded.Build, name.ifBlank { "工具" }, json)
        }
    }
}

internal fun ToolStep.presentation(): ToolPresentation = presentTool(name, input)
