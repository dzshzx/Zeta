package io.github.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
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

/** [images] 是本次运行里发出的图片小图；从历史记录载入的只知道张数（[imageCount]）。 */
@Immutable
data class UserItem(
    override val id: String,
    val text: String,
    val images: List<ImageBitmap> = emptyList(),
    val imageCount: Int = images.size,
) : ChatItem

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
