package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

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

/** Claude 当前的任务清单。 */
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

/**
 * 从对话里的工具调用还原 Claude 的任务清单。纯函数，不依赖 Android。
 *
 * Claude Code 2.1.327 有两套清单工具，同一时间只开其中一套：
 * - 任务工具（默认）：TaskCreate（`subject`、`activeForm`，结果文字 `Task #<id> created successfully: …` 给出编号）
 *   逐项新建，TaskUpdate（`taskId`，可选 `status`、`subject`、`activeForm`；`status` 为 `deleted` 时删掉）逐项改；
 * - TodoWrite（设了 CLAUDE_CODE_ENABLE_TASKS=false 时）：每次给出整份清单 `todos:
 *   [{content, status, activeForm}]`。
 *
 * 归并方式照 Claude Code 自己给远程会话还原清单的做法：任务工具的清单不空就用它，否则用最后一次 TodoWrite。
 * 另加一条：旧清单全部做完后又新建任务，算开了一份新清单，旧的那些不再显示。
 */
object TodoBoard {
    val ToolNames = setOf("TodoWrite", "TaskCreate", "TaskUpdate")

    private val CreatedId = Regex("^Task #(\\S+) created successfully")

    /** 聊天列表（加上没载入的更早历史里的清单工具调用 [seed]）里最新的清单；没有清单时返回 null。 */
    fun from(items: List<ChatItem>, seed: List<ToolStep> = emptyList()): TodoList? {
        val board = Board()
        seed.forEach(board::apply)
        for (item in items) {
            if (item !is WorkItem) continue
            for (step in item.steps) if (step is ToolStep && step.name in ToolNames)
                board.apply(step)
        }
        return board.list()
    }

    /**
     * 把核心推来的 todo_seed（没载入的历史里的清单工具调用，原样的 assistant、user 事件）解成工具步骤：assistant 里的 tool_use 是一步，user
     * 里同 id 的 tool_result 给出结果和成败。
     */
    fun seedSteps(events: JsonArray?): List<ToolStep> {
        if (events.isNullOrEmpty()) return emptyList()
        val steps = LinkedHashMap<String, ToolStep>()
        for (element in events) {
            val event = element as? JsonObject ?: continue
            val type = event.str("type")
            for (block in event.obj("message")?.arr("content").orEmpty()) {
                val b = block as? JsonObject ?: continue
                when {
                    type == "assistant" && b.str("type") == "tool_use" -> {
                        val id = b.str("id") ?: continue
                        steps[id] =
                            ToolStep(
                                id,
                                b.str("name").orEmpty(),
                                b.obj("input"),
                                ToolStatus.Running,
                                null,
                            )
                    }
                    type == "user" && b.str("type") == "tool_result" -> {
                        val id = b.str("tool_use_id") ?: continue
                        val step = steps[id] ?: continue
                        steps[id] =
                            step.copy(
                                status =
                                    if (b.bool("is_error") == true) ToolStatus.Failed
                                    else ToolStatus.Success,
                                result = b["content"].flattenText(),
                            )
                    }
                }
            }
        }
        return steps.values.toList()
    }

    private class Board {
        /** 已拿到编号的任务，按新建的先后。 */
        val tasks = LinkedHashMap<String, TodoEntry>()

        /** 已经发出、还没拿到编号的 TaskCreate（键是工具调用的 id）。 */
        val creating = LinkedHashMap<String, TodoEntry>()
        var written: List<TodoEntry>? = null

        fun apply(step: ToolStep) {
            if (step.status == ToolStatus.Failed) return
            val input = step.input ?: return
            when (step.name) {
                "TaskCreate" -> create(step, input)
                "TaskUpdate" -> update(input)
                "TodoWrite" ->
                    written =
                        (input["todos"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element
                            ->
                            val todo = element as? JsonObject ?: return@mapIndexedNotNull null
                            val content =
                                todo.str("content")?.takeIf { it.isNotBlank() }
                                    ?: return@mapIndexedNotNull null
                            TodoEntry(
                                "todo-$index",
                                content,
                                todo.str("activeForm")?.takeIf { it.isNotBlank() } ?: content,
                                status(todo.str("status")) ?: TodoStatus.Pending,
                            )
                        }
            }
        }

        private fun create(step: ToolStep, input: JsonObject) {
            val subject =
                (input.str("subject") ?: input.str("title") ?: input.str("name"))?.takeIf {
                    it.isNotBlank()
                } ?: return
            val activeForm =
                (input.str("activeForm") ?: input.str("active_form"))?.takeIf { it.isNotBlank() }
                    ?: subject
            // 上一份清单已经全部做完：这是一份新清单
            if (
                creating.isEmpty() &&
                    tasks.isNotEmpty() &&
                    tasks.values.all { it.status == TodoStatus.Completed }
            )
                tasks.clear()
            val id = step.result?.let { CreatedId.find(it.trim())?.groupValues?.get(1) }
            if (step.status == ToolStatus.Success && id != null) {
                if (id !in tasks) tasks[id] = TodoEntry(id, subject, activeForm, TodoStatus.Pending)
            } else {
                creating[step.id] = TodoEntry(step.id, subject, activeForm, TodoStatus.Pending)
            }
        }

        private fun update(input: JsonObject) {
            val id =
                (input.str("taskId") ?: input.str("task_id") ?: input.str("id"))?.takeIf {
                    it.isNotBlank()
                } ?: return
            val requested = input.str("status")
            if (requested == "deleted") {
                tasks.remove(id)
                return
            }
            val existing = tasks[id]
            val subject = input.str("subject")?.takeIf { it.isNotBlank() }
            val content = subject ?: existing?.content ?: "任务 #$id"
            tasks[id] =
                TodoEntry(
                    key = id,
                    content = content,
                    activeForm =
                        (input.str("activeForm") ?: input.str("active_form"))?.takeIf {
                            it.isNotBlank()
                        }
                            ?: existing?.activeForm?.takeUnless {
                                subject != null && it == existing.content
                            }
                            ?: content,
                    status = status(requested) ?: existing?.status ?: TodoStatus.Pending,
                )
        }

        fun list(): TodoList? {
            val entries = (tasks.values + creating.values).ifEmpty { written.orEmpty() }
            return if (entries.isEmpty()) null else TodoList(entries)
        }

        private fun status(value: String?): TodoStatus? =
            when (value) {
                "pending" -> TodoStatus.Pending
                "in_progress" -> TodoStatus.InProgress
                "completed" -> TodoStatus.Completed
                else -> null
            }
    }
}
