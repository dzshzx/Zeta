package com.dzshzx.zeta.ui.chat

import androidx.compose.ui.graphics.vector.ImageVector
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.str
import com.dzshzx.zeta.phone.tools.BrowserTools
import com.dzshzx.zeta.phone.tools.PhoneToolGroups
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Edit
import io.github.suqi8.coui.kmp.icon.extended.File
import io.github.suqi8.coui.kmp.icon.extended.Link
import io.github.suqi8.coui.kmp.icon.extended.MindMap
import io.github.suqi8.coui.kmp.icon.extended.Paste
import io.github.suqi8.coui.kmp.icon.extended.ReplyAll
import io.github.suqi8.coui.kmp.icon.extended.SelectAll
import io.github.suqi8.coui.kmp.icon.extended.Timer
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
        "Bash" -> ToolPresentation(ZetaIcons.Terminal, s("description")?.takeIf { it.isNotBlank() } ?: firstLine(s("command")).ifEmpty { "执行命令" }, s("command").orEmpty())
        "Read" -> ToolPresentation(COUIIcons.File, "读取 " + baseName(s("file_path")), s("file_path").orEmpty())
        "Write" -> ToolPresentation(COUIIcons.Edit, "写入 " + baseName(s("file_path")), s("file_path").orEmpty() + "\n\n" + s("content").orEmpty())
        "Edit", "MultiEdit", "NotebookEdit" -> ToolPresentation(
            COUIIcons.Edit, "编辑 " + baseName(s("file_path") ?: s("notebook_path")),
            (s("file_path") ?: s("notebook_path")).orEmpty() + (s("old_string")?.let { "\n\n- $it\n+ ${s("new_string").orEmpty()}" } ?: ""),
        )
        "Grep" -> ToolPresentation(ZetaIcons.SearchText, "搜索 " + firstLine(s("pattern"), 40), json)
        "Glob" -> ToolPresentation(ZetaIcons.SearchText, "查找文件 " + firstLine(s("pattern"), 40), json)
        "WebSearch" -> ToolPresentation(ZetaIcons.Globe, "搜索网页：" + firstLine(s("query"), 50), s("query").orEmpty())
        "WebFetch" -> ToolPresentation(ZetaIcons.Globe, "读取网页 " + s("url").orEmpty().substringAfter("://").substringBefore('/'), s("url").orEmpty() + "\n\n" + s("prompt").orEmpty())
        "Task", "Agent" -> ToolPresentation(COUIIcons.MindMap, "子代理：" + firstLine(s("description") ?: s("prompt"), 50), s("prompt").orEmpty())
        "TodoWrite", "TaskCreate", "TaskUpdate", "TaskList", "TaskGet" -> ToolPresentation(COUIIcons.SelectAll, todoToolTitle(name, input), json)
        "AskUserQuestion" -> ToolPresentation(COUIIcons.ReplyAll, "向你提问", json)
        "ExitPlanMode" -> ToolPresentation(COUIIcons.SelectAll, "提交计划", s("plan") ?: json)
        "Skill" -> ToolPresentation(ZetaIcons.Sparkle, "技能 " + (s("skill") ?: s("command")).orEmpty(), json)
        else -> if (name.startsWith("mcp__phone__")) {
            presentPhoneTool(name.removePrefix("mcp__phone__"), input, json)
        } else if (name.startsWith("mcp__")) {
            val parts = name.removePrefix("mcp__").split("__", limit = 2)
            ToolPresentation(ZetaIcons.Puzzle, parts.joinToString(" · "), json)
        } else {
            ToolPresentation(ZetaIcons.Wrench, name.ifBlank { "工具" }, json)
        }
    }
}

/** 手机工具：用人话说它在手机上做了什么。 */
private fun presentPhoneTool(tool: String, input: JsonObject?, json: String): ToolPresentation {
    fun s(key: String) = input?.str(key)
    fun n(key: String) = input?.int(key)
    // 按文字、id 找的控件显示要找的文字或 id
    val where = n("node")?.let { "控件 $it" } ?: s("match")?.let { "「" + firstLine(it, 20) + "」" } ?: s("id")?.let { "id=$it" }
        ?: n("x")?.let { "($it, ${n("y") ?: 0})" }.orEmpty()
    return when (tool) {
        "shell" -> ToolPresentation(ZetaIcons.Terminal, "Android：" + firstLine(s("command")).ifEmpty { "执行命令" }, s("command").orEmpty())
        "screen" -> ToolPresentation(ZetaIcons.Phone, if (input?.bool("screenshot") == true) "看屏幕（带截图）" else "看屏幕", json)
        "tap" -> ToolPresentation(ZetaIcons.Tap, "点按 $where".trim(), json)
        "long_press" -> ToolPresentation(ZetaIcons.Tap, "长按 $where".trim(), json)
        "swipe" -> ToolPresentation(ZetaIcons.Tap, "滑动", json)
        "scroll" -> ToolPresentation(
            ZetaIcons.Swipe,
            when (s("direction")) {
                "up" -> "向上滚动"
                "left" -> "向左滚动"
                "right" -> "向右滚动"
                else -> "向下滚动"
            } + (if (where.isNotEmpty()) " $where" else ""),
            json,
        )
        "wait" -> ToolPresentation(
            COUIIcons.Timer,
            when {
                s("match") != null || s("id") != null -> "等 $where" + if (input?.bool("gone") == true) "消失" else "出现"
                s("app") != null -> "等 " + s("app") + " 打开"
                else -> "等待 " + "%.1f".format((n("duration_ms") ?: 1000) / 1000.0).removeSuffix(".0") + " 秒"
            },
            json,
        )
        "clipboard" -> ToolPresentation(
            COUIIcons.Paste,
            when (s("text")) {
                null -> "读取剪贴板"
                "" -> "清空剪贴板"
                else -> "写入剪贴板“" + firstLine(s("text"), 30) + "”"
            },
            json,
        )
        "type_text" -> ToolPresentation(
            ZetaIcons.Phone,
            if (s("text").isNullOrEmpty()) "清空输入框" else "输入“" + firstLine(s("text"), 30) + "”",
            json,
        )
        "press_key" -> ToolPresentation(
            ZetaIcons.Phone,
            "按" + when (s("key")) {
                "back" -> "返回键"
                "home" -> "主屏幕键"
                "recents" -> "最近任务键"
                "enter" -> "回车"
                "delete" -> "退格"
                "paste" -> "粘贴"
                "wake" -> "亮屏"
                "notifications" -> "下拉通知栏"
                "quick_settings" -> "下拉控制中心"
                else -> s("key").orEmpty()
            },
            json,
        )
        "open_app" -> when {
            s("uri") != null -> ToolPresentation(COUIIcons.Link, "打开 " + firstLine(s("uri"), 50), json)
            s("action") != null -> ToolPresentation(ZetaIcons.Phone, "打开 " + s("action").orEmpty().substringAfterLast('.'), json)
            else -> ToolPresentation(ZetaIcons.Phone, "打开 " + s("app").orEmpty(), json)
        }
        "find_apps" -> ToolPresentation(ZetaIcons.Phone, "查找应用 " + s("query").orEmpty(), json)
        BrowserTools.NAME -> ToolPresentation(ZetaIcons.Globe, BrowserTools.title(tool, input ?: JsonObject(emptyMap())), json)
        else -> ToolPresentation(ZetaIcons.Phone, PhoneToolGroups.find(tool)?.title(tool, input ?: JsonObject(emptyMap())) ?: "手机 · $tool", json)
    }
}

internal fun ToolStep.presentation(): ToolPresentation = presentTool(name, input)

/** 一次工具调用的一行标题，和聊天列表里的一样（顶部状态胶囊也用）。 */
internal fun toolTitle(name: String, input: JsonObject?): String = presentTool(name, input).title
