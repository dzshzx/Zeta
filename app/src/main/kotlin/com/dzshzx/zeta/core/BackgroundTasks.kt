package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 后台任务是什么：后台命令（Bash 的 run_in_background）、监视器（Monitor）、后台子代理，或别的种类。 */
enum class TaskKind { Command, Monitor, Agent, Other }

/** [Ended]：从清单里消失了（或进程没了），却没收到结局，不知道是怎么结束的。 */
enum class TaskState { Running, Completed, Failed, Stopped, Ended }

/**
 * Claude Code 在一个对话里起的一个后台任务。字段取自 stream-json 的几种 system 事件（2.1.328 实测，见 ClaudeTaskContractTest）：
 * background_tasks_changed（当前在跑的完整清单）、task_started、task_progress、task_updated、task_notification。
 */
@Immutable
data class BackgroundTask(
    val id: String,
    /** Claude Code 的 task_type：local_bash（后台命令和监视器）、local_agent（子代理）等。 */
    val type: String,
    val kind: TaskKind,
    val description: String,
    /** 命令（后台命令、监视器）或交给子代理的指令；不知道时为 null。 */
    val command: String?,
    /** 子代理的类型（general-purpose 等）。 */
    val agentType: String?,
    val toolUseId: String?,
    val startedAt: Long,
    val endedAt: Long? = null,
    val state: TaskState = TaskState.Running,
    /** 用户叫停了，还没等到结果。 */
    val stopping: Boolean = false,
    /** 子代理正在做的那一步（task_progress 的说明）。 */
    val progress: String? = null,
    val toolUses: Int? = null,
    /** 结束时的说明：后台命令是退出码那句，子代理是它交回的报告。和 [description] 一样时不留。 */
    val summary: String? = null,
    val error: String? = null,
) {
    val running: Boolean get() = state == TaskState.Running

    /** 能用 get_task_output 读输出的：后台命令和监视器（子代理读不了，看 [progress] 和 [summary]）。 */
    val readable: Boolean get() = kind == TaskKind.Command || kind == TaskKind.Monitor

    fun elapsedMs(now: Long): Long = ((endedAt ?: now) - startedAt).coerceAtLeast(0)
}

/** get_task_output 的应答：输出的最后至多 8 KiB。[error] 不为 null 时是没读成的原因。 */
@Immutable
data class TaskOutput(val text: String, val totalBytes: Long, val truncated: Boolean, val error: String? = null)

/**
 * 把 Claude Code 的事件归约成后台任务清单。纯逻辑，不碰 Android，在主线程（或测试里）调用。
 *
 * 面板列的是进过 background_tasks_changed 清单的任务：前台跑着的（比如没放后台的子代理）只记下它的 task_started，
 * 等它转到后台进了清单再列出来。结束了的留最近 [keepFinished] 个，好让用户看结局和输出。
 */
class TaskBoard(private val keepFinished: Int = 20) {
    private class Started(val event: JsonObject, val at: Long)

    private val entries = LinkedHashMap<String, BackgroundTask>()
    private val started = LinkedHashMap<String, Started>()

    /** 主对话最近发起的、可能成为后台任务的工具调用：tool_use id → 工具名和参数。命令要从这里取。 */
    private val calls = LinkedHashMap<String, Pair<String, JsonObject?>>()

    /** 在跑的排前面，各自按开始时间新的在前。 */
    val tasks: List<BackgroundTask>
        get() = entries.values.sortedWith(compareByDescending<BackgroundTask> { it.running }.thenByDescending { it.startedAt })

    /** 处理一条 stream-json 事件，返回清单有没有变。[now] 是收到它的时间（毫秒）。 */
    fun observe(event: JsonObject, now: Long): Boolean = when (event.str("type")) {
        "assistant" -> {
            rememberCalls(event)
            false
        }
        "system" -> when (event.str("subtype")) {
            "task_started" -> started(event, now)
            "background_tasks_changed" -> listed(event, now)
            "task_progress" -> progressed(event)
            "task_updated" -> patched(event)
            "task_notification" -> notified(event, now)
            else -> false
        }
        else -> false
    }

    /** 用户叫停 [id]（或叫停没成，[stopping] 为 false）。返回清单有没有变。 */
    fun markStopping(id: String, stopping: Boolean = true): Boolean {
        val task = entries[id]?.takeIf { it.running && it.stopping != stopping } ?: return false
        entries[id] = task.copy(stopping = stopping)
        return true
    }

    /** Claude Code 进程没了：它的后台任务也都跟着没了。 */
    fun processEnded(now: Long): Boolean {
        var changed = false
        for ((id, task) in entries) {
            if (task.running) {
                entries[id] = task.copy(state = TaskState.Ended, endedAt = now, stopping = false)
                changed = true
            }
        }
        started.clear()
        return changed
    }

    /** 清掉已结束的。 */
    fun clearFinished(): Boolean = entries.values.removeAll { !it.running }

    private fun rememberCalls(event: JsonObject) {
        // 子代理里的调用不是主对话的；它起的后台任务就只显示说明
        if (event["parent_tool_use_id"].let { it != null && it !is JsonNull }) return
        for (block in event.obj("message")?.arr("content").orEmpty()) {
            val use = block as? JsonObject ?: continue
            if (use.str("type") != "tool_use") continue
            val name = use.str("name") ?: continue
            if (name !in TASK_TOOLS) continue
            remember(use.str("id") ?: continue, name, use.obj("input"))
        }
    }

    private fun remember(id: String, name: String, input: JsonObject?) {
        calls.remove(id)
        calls[id] = name to input
        while (calls.size > MAX_CALLS) calls.remove(calls.keys.first())
    }

    private fun started(event: JsonObject, now: Long): Boolean {
        val id = event.str("task_id") ?: return false
        // 接回后台进程时，助手脚本把发起它的工具调用一并带来（zeta_tool），开始时间是那时的记录时间（zeta_at）
        event.obj("zeta_tool")?.let { tool ->
            val use = event.str("tool_use_id")
            if (use != null && use !in calls) remember(use, tool.str("name").orEmpty(), tool.obj("input"))
        }
        val at = event.long("zeta_at") ?: now
        val task = entries[id]
        if (task == null) {
            // 还没进后台清单（前台的，或者清单那条还没到）：先记着
            started.remove(id)
            started[id] = Started(event, at)
            while (started.size > MAX_CALLS) started.remove(started.keys.first())
            return false
        }
        entries[id] = enrich(task, event)
        return true
    }

    /** 用 task_started 补全：发起它的调用、命令、子代理类型。 */
    private fun enrich(task: BackgroundTask, start: JsonObject): BackgroundTask {
        val use = start.str("tool_use_id") ?: task.toolUseId
        val call = use?.let { calls[it] }
        val type = task.type.ifBlank { start.str("task_type").orEmpty() }
        return task.copy(
            type = type,
            kind = kindOf(type, call?.first),
            toolUseId = use,
            command = task.command ?: commandOf(call) ?: start.str("prompt"),
            agentType = task.agentType ?: start.str("subagent_type"),
            description = task.description.ifBlank { start.str("description").orEmpty() },
        )
    }

    private fun listed(event: JsonObject, now: Long): Boolean {
        val listed = HashSet<String>()
        var changed = false
        for (element in event.arr("tasks").orEmpty()) {
            val item = element as? JsonObject ?: continue
            val id = item.str("task_id") ?: continue
            listed += id
            val description = item.str("description").orEmpty()
            val task = entries[id]
            when {
                task == null -> {
                    val start = started.remove(id)
                    val type = item.str("task_type").orEmpty()
                    var created = BackgroundTask(
                        id = id, type = type, kind = kindOf(type, null), description = description, command = null,
                        agentType = item.str("subagent_type"), toolUseId = null, startedAt = start?.at ?: now,
                    )
                    if (start != null) created = enrich(created, start.event)
                    entries[id] = created
                    changed = true
                }
                // 结束了又回到清单（续跑的子代理）：重新算在跑
                !task.running -> {
                    entries[id] = task.copy(state = TaskState.Running, endedAt = null, stopping = false, summary = null, error = null)
                    changed = true
                }
                description.isNotBlank() && description != task.description -> {
                    entries[id] = task.copy(description = description)
                    changed = true
                }
            }
        }
        // 从清单里消失、又没收到结局的：结束了，但不知道怎么结束的
        for ((id, task) in entries) {
            if (task.running && id !in listed) {
                entries[id] = task.copy(state = TaskState.Ended, endedAt = task.endedAt ?: now, stopping = false)
                changed = true
            }
        }
        return trim() || changed
    }

    private fun progressed(event: JsonObject): Boolean {
        val id = event.str("task_id") ?: return false
        val task = entries[id] ?: return false
        val updated = task.copy(
            progress = event.str("description")?.takeIf { it.isNotBlank() } ?: task.progress,
            toolUses = event.obj("usage")?.int("tool_uses") ?: task.toolUses,
        )
        if (updated == task) return false
        entries[id] = updated
        return true
    }

    private fun patched(event: JsonObject): Boolean {
        val id = event.str("task_id") ?: return false
        val task = entries[id] ?: return false
        val patch = event.obj("patch") ?: return false
        val state = patch.str("status")?.let(::stateOf) ?: task.state
        val updated = task.copy(
            state = state,
            stopping = task.stopping && state == TaskState.Running,
            endedAt = if (state == TaskState.Running) null else patch.long("end_time") ?: task.endedAt,
            description = patch.str("description")?.takeIf { it.isNotBlank() } ?: task.description,
            error = patch.str("error") ?: task.error,
        )
        if (updated == task) return false
        entries[id] = updated
        return true
    }

    private fun notified(event: JsonObject, now: Long): Boolean {
        val id = event.str("task_id") ?: return false
        val task = entries[id] ?: return false
        val state = when (event.str("status")) {
            "completed" -> TaskState.Completed
            "failed" -> TaskState.Failed
            "stopped" -> TaskState.Stopped
            else -> task.state
        }
        val summary = event.str("summary")?.trim()?.takeIf { it.isNotEmpty() && it != task.description }
        entries[id] = task.copy(
            state = state,
            stopping = false,
            endedAt = if (state == TaskState.Running) null else task.endedAt ?: now,
            summary = summary ?: task.summary,
        )
        return trim() || entries[id] != task
    }

    /** 已结束的只留最近 [keepFinished] 个。 */
    private fun trim(): Boolean {
        val finished = entries.values.filter { !it.running }
        if (finished.size <= keepFinished) return false
        finished.sortedByDescending { it.endedAt ?: it.startedAt }.drop(keepFinished).forEach { entries.remove(it.id) }
        return true
    }

    companion object {
        /** 会起后台任务的工具。Task 是 Agent 的旧名。 */
        private val TASK_TOOLS = setOf("Bash", "Monitor", "Agent", "Task")
        private const val MAX_CALLS = 64

        internal fun kindOf(type: String, tool: String?): TaskKind = when {
            type == "local_bash" -> if (tool == "Monitor") TaskKind.Monitor else TaskKind.Command
            type == "local_agent" -> TaskKind.Agent
            else -> TaskKind.Other
        }

        private fun commandOf(call: Pair<String, JsonObject?>?): String? {
            val input = call?.second ?: return null
            return when (call.first) {
                "Bash" -> input.str("command")
                "Monitor" -> input.str("command") ?: input.obj("ws")?.str("url")
                else -> input.str("prompt")
            }?.takeIf { it.isNotBlank() }
        }

        private fun stateOf(status: String): TaskState = when (status) {
            "completed" -> TaskState.Completed
            "failed" -> TaskState.Failed
            "killed" -> TaskState.Stopped
            else -> TaskState.Running // pending、running、paused
        }
    }
}

/** get_task_output 应答（control_response 里的 response）。 */
internal fun parseTaskOutput(response: JsonObject): TaskOutput {
    if (response.str("subtype") == "error") return TaskOutput("", 0, false, error = response.str("error") ?: "没有读到输出")
    val body = response.obj("response") ?: JsonObject(emptyMap())
    return TaskOutput(
        text = body.str("output").orEmpty(),
        totalBytes = (body["total_bytes"] as? JsonPrimitive)?.longOrNull ?: 0,
        truncated = body.bool("truncated") == true,
    )
}

/** 终端输出转成能直接显示的纯文本：去掉颜色等转义序列；回车覆盖的进度行只留最后一版。 */
fun plainTerminalText(raw: String): String {
    val stripped = raw.replace(ANSI, "")
    return stripped.split('\n').joinToString("\n") { line ->
        val trimmed = line.removeSuffix("\r")
        trimmed.substringAfterLast('\r')
    }
}

private val ANSI = Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|[@-Z\\\\-_])")

/** “3 秒”“2 分 05 秒”“1 小时 03 分”。 */
fun formatElapsed(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return when {
        h > 0 -> "$h 小时 %02d 分".format(m)
        m > 0 -> "$m 分 %02d 秒".format(s)
        else -> "$s 秒"
    }
}

/**
 * 一个对话的后台任务（可观察）：清单、读到的输出。状态只在主线程上改。
 * 发控制请求由 [Conversation] 做，这里记下请求号，应答回来时认领。
 */
class BackgroundTasks {
    private val board = TaskBoard()

    var list by mutableStateOf<List<BackgroundTask>>(emptyList())
        private set

    /** 读到的输出，按任务 id。 */
    var outputs by mutableStateOf<Map<String, TaskOutput>>(emptyMap())
        private set

    private val outputRequests = HashMap<String, String>()
    private val stopRequests = HashMap<String, String>()

    val running: Int get() = list.count { it.running }

    fun observe(event: JsonObject, now: Long) {
        if (board.observe(event, now)) publish()
    }

    internal fun outputRequested(requestId: String, taskId: String) {
        outputRequests[requestId] = taskId
    }

    internal fun stopRequested(requestId: String, taskId: String) {
        stopRequests[requestId] = taskId
        if (board.markStopping(taskId)) publish()
    }

    /** 一条 control_response 是不是这里发的请求的应答；是就收下。 */
    internal fun onResponse(response: JsonObject?): Boolean {
        val id = response?.str("request_id") ?: return false
        outputRequests.remove(id)?.let { task ->
            outputs = outputs + (task to parseTaskOutput(response))
            return true
        }
        stopRequests.remove(id)?.let { task ->
            // stop_task 对不认识的任务也答 success；真停没停看随后的 task_updated。答错时把“正在停止”撤掉
            if (response.str("subtype") == "error" && board.markStopping(task, stopping = false)) publish()
            return true
        }
        return false
    }

    internal fun processEnded(now: Long) {
        outputRequests.clear()
        stopRequests.clear()
        if (board.processEnded(now)) publish()
    }

    fun clearFinished() {
        if (board.clearFinished()) {
            val kept = board.tasks.mapTo(HashSet()) { it.id }
            outputs = outputs.filterKeys { it in kept }
            publish()
        }
    }

    private fun publish() {
        list = board.tasks
    }
}
