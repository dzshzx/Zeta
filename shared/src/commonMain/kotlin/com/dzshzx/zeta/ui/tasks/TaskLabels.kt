package com.dzshzx.zeta.ui.tasks

import com.dzshzx.zeta.core.BackgroundTask
import com.dzshzx.zeta.core.TaskKind
import com.dzshzx.zeta.core.TaskState
import com.dzshzx.zeta.core.formatBytes
import com.dzshzx.zeta.core.formatElapsed
import com.dzshzx.zeta.i18n.Strings

/** 后台任务面板上的说法。纯函数，单元测试在 TaskLabelsTest。 */
object TaskLabels {
    fun kind(task: BackgroundTask): String =
        when (task.kind) {
            TaskKind.Command -> Strings.tasksKindCommand()
            TaskKind.Monitor -> Strings.tasksKindMonitor()
            TaskKind.Agent ->
                task.agentType?.takeIf { it.isNotBlank() }?.let { Strings.tasksKindAgentType(it) }
                    ?: Strings.tasksKindAgent()
            TaskKind.Other -> task.type.ifBlank { Strings.tasksKindOther() }
        }

    /** 详情页的大标题：只说种类，子代理的类型另起一行，免得被截断。 */
    fun page(task: BackgroundTask): String =
        if (task.kind == TaskKind.Agent) Strings.tasksKindAgent() else kind(task)

    /** 一行标题：任务说明；没有说明就用命令的第一行。 */
    fun title(task: BackgroundTask): String =
        task.description
            .ifBlank {
                task.command?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            }
            .ifBlank { kind(task) }

    fun state(task: BackgroundTask): String =
        if (task.stopping) Strings.tasksStopping() else state(task.state)

    /** 任务状态的说法；子代理查看页在跑和结局也照它说（SubagentScreen）。 */
    fun state(state: TaskState): String =
        when (state) {
            TaskState.Running -> Strings.tasksStateRunning()
            TaskState.Completed -> Strings.tasksStateCompleted()
            TaskState.Failed -> Strings.tasksStateFailed()
            TaskState.Stopped -> Strings.tasksStateStopped()
            TaskState.Ended -> Strings.tasksStateEnded()
        }

    /** 列表里的说明行：“后台命令 · 已运行 2 分 05 秒”“子代理 · 已完成 · 用时 6 秒”。 */
    fun summary(task: BackgroundTask, now: Long): String {
        val time = formatElapsed(task.elapsedMs(now))
        return kind(task) +
            " · " +
            when {
                task.stopping -> Strings.tasksSummaryStopping(time)
                task.running -> Strings.tasksSummaryRunning(time)
                else -> Strings.tasksSummaryEnded(state(task), time)
            }
    }

    /** 侧栏入口和后台对话一行的状态：在回答、等确认、后台任务在跑，都没有就是快要收起了。 */
    fun conversation(busy: Boolean, waiting: Boolean, tasks: Int, schedule: String?): String {
        val state =
            when {
                waiting -> Strings.tasksConvWaiting()
                busy -> Strings.tasksConvAnswering()
                tasks > 0 -> Strings.tasksConvTasks(tasks)
                else -> Strings.tasksConvIdle()
            }
        return if (schedule == null) state else Strings.tasksConvSchedule(schedule, state)
    }

    /** 输出区上方的一句：读到多少、是不是只显示了最后一段。 */
    fun outputHeader(totalBytes: Long, truncated: Boolean): String =
        when {
            totalBytes <= 0 -> Strings.tasksOutput()
            truncated -> Strings.tasksOutputTruncated(formatBytes(totalBytes))
            else -> Strings.tasksOutputSize(formatBytes(totalBytes))
        }
}
