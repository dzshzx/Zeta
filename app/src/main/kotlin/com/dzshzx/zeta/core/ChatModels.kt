package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** 聊天列表里的一行。全部不可变，更新时整项替换。 */
@Immutable
sealed interface ChatItem {
    val id: String
}

/** [files] 是随消息送进 Linux 环境的附件路径。 */
@Immutable
data class UserItem(
    override val id: String,
    val text: String,
    val images: List<ImageRef> = emptyList(),
    val files: List<String> = emptyList(),
) : ChatItem

/** 聊天里的一张图片，显示时才载入；[key] 兼作缓存键。 */
@Immutable
sealed interface ImageRef {
    val key: String
}

/** 数据就在手边：刚发出的附件、实时收到的工具结果、data: 地址。 */
@Immutable
class InlineImage(override val key: String, val base64: String) : ImageRef

/** 存在会话记录里的图片，[ref] 是助手脚本给出的位置。 */
@Immutable
data class SessionImage(val file: String, val ref: String) : ImageRef {
    override val key: String get() = "s:$file:$ref"
}

/** Linux 环境里的一个图片文件（回答里用 Markdown 引用的）。 */
@Immutable
data class FileImage(val path: String) : ImageRef {
    override val key: String get() = "f:$path"
}

/** 回答里引用的网络图片。 */
@Immutable
data class RemoteImage(val url: String) : ImageRef {
    override val key: String get() = "u:$url"
}

/** Claude Code 报告的一个斜杠命令或技能。 */
@Immutable
data class SlashCommand(val name: String, val description: String, val argumentHint: String)

private const val AttachedPrefix = "[Attached file: "

/** 发送时把附件路径写成消息开头的几行，Claude 据此用工具去读。 */
internal fun withAttachedFiles(text: String, files: List<String>): String =
    if (files.isEmpty()) text else files.joinToString("\n") { "$AttachedPrefix$it]" } + if (text.isEmpty()) "" else "\n\n$text"

/** [withAttachedFiles] 的逆操作：从历史消息里拆出附件路径和正文。 */
internal fun splitAttachedFiles(text: String): Pair<List<String>, String> {
    if (!text.startsWith(AttachedPrefix)) return emptyList<String>() to text
    val files = ArrayList<String>()
    val lines = text.lines()
    var index = 0
    while (index < lines.size && lines[index].startsWith(AttachedPrefix) && lines[index].endsWith("]")) {
        files.add(lines[index].substring(AttachedPrefix.length, lines[index].length - 1))
        index++
    }
    return files to lines.drop(index).joinToString("\n").trim()
}

@Immutable
data class AnswerItem(override val id: String, val text: String, val streaming: Boolean) : ChatItem

/** 连续的思考与工具调用折叠成一组，对应 Eta 的“已完成 N 个步骤”。 */
@Immutable
data class WorkItem(override val id: String, val steps: List<WorkStep>) : ChatItem {
    val running: Boolean get() = steps.any { it.running }
}

@Immutable
data class NoticeItem(override val id: String, val text: String, val error: Boolean) : ChatItem

@Immutable
sealed interface WorkStep {
    val id: String
    val running: Boolean
}

@Immutable
data class ThinkingStep(
    override val id: String,
    val text: String,
    override val running: Boolean,
    val seconds: Int?,
) : WorkStep

enum class ToolStatus { Running, Success, Failed }

@Immutable
data class ToolStep(
    override val id: String,
    val name: String,
    val input: JsonObject?,
    val status: ToolStatus,
    val result: String?,
    /** 工具结果里带的图片（读图片文件、截图类工具）。 */
    val images: List<ImageRef> = emptyList(),
) : WorkStep {
    override val running: Boolean get() = status == ToolStatus.Running
}

@Immutable
data class QuestionOption(val label: String, val description: String)

@Immutable
data class Question(val question: String, val header: String, val options: List<QuestionOption>, val multiSelect: Boolean)

/** Claude Code 发来的 can_use_tool：等用户批准一次工具调用，或回答 AskUserQuestion。 */
@Immutable
data class PermissionRequest(
    val requestId: String,
    val toolName: String,
    val input: JsonObject,
    val questions: List<Question>,
)

@Immutable
data class ModelOption(val value: String, val displayName: String, val description: String)

/** 历史会话的概要（来自 ~/.claude/projects 下的记录文件）。 */
@Immutable
data class SessionSummary(
    val id: String,
    val file: String,
    val cwd: String,
    val title: String,
    val preview: String,
    val updated: Long,
    /** 正被别的 Claude Code 进程占用（比如终端里开着）。 */
    val external: Boolean = false,
    /** 已归档：不在主列表里显示。 */
    val archived: Boolean = false,
)

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** 工具结果的 content：字符串，或若干 text 块。 */
internal fun JsonElement?.flattenText(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonArray -> mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n")
    else -> ""
}
