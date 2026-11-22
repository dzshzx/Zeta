package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 后台任务是什么：后台命令（Bash 的 run_in_background）、监视器（Monitor）、后台子代理，或别的种类。 */
enum class TaskKind {
    Command,
    Monitor,
    Agent,
    Other,
}

/** [Ended]：从清单里消失了（或进程没了），却没收到结局，不知道是怎么结束的。 */
enum class TaskState {
    Running,
    Completed,
    Failed,
    Stopped,
    Ended,
}

/** Claude Code 在一个对话里起的一个后台任务：核心归约好、经 conv.state 的 tasks 推来的一项（见 docs/core.md「对话」）。 */
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
    val running: Boolean
        get() = state == TaskState.Running

    /** 能用 get_task_output 读输出的：后台命令和监视器（子代理读不了，看 [progress] 和 [summary]）。 */
    val readable: Boolean
        get() = kind == TaskKind.Command || kind == TaskKind.Monitor

    fun elapsedMs(now: Long): Long = ((endedAt ?: now) - startedAt).coerceAtLeast(0)
}

/** conv.task_output 的结果：输出的最后至多 8 KiB。[error] 不为 null 时是没读成的原因。 */
@Immutable
data class TaskOutput(
    val text: String,
    val totalBytes: Long,
    val truncated: Boolean,
    val error: String? = null,
)

/** 终端输出转成能直接显示的纯文本：去掉颜色等转义序列；回车覆盖的进度行只留最后一版。 */
fun plainTerminalText(raw: String): String {
    val stripped = raw.replace(ANSI, "")
    return stripped.split('\n').joinToString("\n") { line ->
        val trimmed = line.removeSuffix("\r")
        trimmed.substringAfterLast('\r')
    }
}

private val ANSI =
    Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|[@-Z\\\\-_])")

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

/** 一个对话的后台任务（可观察）：清单是核心推来的（[source]），读到的输出按任务记在这里。「清除已结束」只是这边不再列出它们。 状态只在主线程上改。 */
class BackgroundTasks(private val source: () -> List<BackgroundTask>) {
    /** 用户清掉的已结束任务。 */
    private var cleared by mutableStateOf<Set<String>>(emptySet())

    /** 在跑的排前面（核心排好的）。 */
    val list: List<BackgroundTask>
        get() =
            source().let { all ->
                if (cleared.isEmpty()) all else all.filterNot { it.id in cleared }
            }

    /** 读到的输出，按任务 id。 */
    var outputs by mutableStateOf<Map<String, TaskOutput>>(emptyMap())
        private set

    val running: Int
        get() = list.count { it.running }

    /** conv.task_output 读到了任务 [taskId] 的输出。 */
    fun outputRead(taskId: String, output: TaskOutput) {
        outputs = outputs + (taskId to output)
    }

    fun clearFinished() {
        val finished = list.filterNot { it.running }.map { it.id }
        if (finished.isEmpty()) return
        cleared = cleared + finished
        outputs = outputs - finished.toSet()
    }
}
