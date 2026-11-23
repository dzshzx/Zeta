package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import com.dzshzx.zeta.i18n.coreSaid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class TodoStatus {
    Pending,
    InProgress,
    Completed,
}

/** 任务清单里的一项。[activeForm] 是进行中时显示的说法（“正在运行测试”），没有时同 [content]。 */
@Immutable
data class TodoEntry(
    val key: String,
    val content: String,
    val activeForm: String,
    val status: TodoStatus,
    /** Claude 没给标题、[content] 是核心起的兜底标题时它的种类码（参数 id 就是 [key]）；Claude 给的标题为空。 */
    val contentCode: String = "",
) {
    /** 标题用界面语言说出来：兜底标题按种类码出字，Claude 给的照原文。 */
    fun contentSaid(): String =
        coreSaid(contentCode, JsonObject(mapOf("id" to JsonPrimitive(key))), content)

    /** 进行中的说法用界面语言说出来：它就是兜底标题时跟着 [contentSaid]。 */
    fun activeFormSaid(): String = if (activeForm == content) contentSaid() else activeForm
}

/** Claude 当前的任务清单（conv.state 的 todos，核心从清单工具调用归并好的）。 */
@Immutable
data class TodoList(val entries: List<TodoEntry>) {
    val total: Int
        get() = entries.size

    val done: Int
        get() = entries.count { it.status == TodoStatus.Completed }

    val allDone: Boolean
        get() = entries.all { it.status == TodoStatus.Completed }

    /** 正在做的那一项；没有时为 null。 */
    val current: TodoEntry?
        get() = entries.firstOrNull { it.status == TodoStatus.InProgress }

    /** 接下来要做的那一项。 */
    val next: TodoEntry?
        get() = entries.firstOrNull { it.status == TodoStatus.Pending }
}
