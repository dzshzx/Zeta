package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable

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
)

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
