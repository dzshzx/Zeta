package com.dzshzx.zeta.ui.tasks

import com.dzshzx.zeta.core.BackgroundTask
import com.dzshzx.zeta.core.TaskKind
import com.dzshzx.zeta.core.TaskState
import com.dzshzx.zeta.core.formatBytes
import com.dzshzx.zeta.core.formatElapsed

/** 后台任务面板上的说法。纯函数，单元测试在 TaskLabelsTest。 */
internal object TaskLabels {
    fun kind(task: BackgroundTask): String = when (task.kind) {
        TaskKind.Command -> "后台命令"
        TaskKind.Monitor -> "监视器"
        TaskKind.Agent -> task.agentType?.takeIf { it.isNotBlank() }?.let { "子代理 · $it" } ?: "子代理"
        TaskKind.Other -> task.type.ifBlank { "后台任务" }
    }

    /** 详情页的大标题：只说种类，子代理的类型另起一行，免得被截断。 */
    fun page(task: BackgroundTask): String = if (task.kind == TaskKind.Agent) "子代理" else kind(task)

    /** 一行标题：任务说明；没有说明就用命令的第一行。 */
    fun title(task: BackgroundTask): String =
        task.description.ifBlank { task.command?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty() }.ifBlank { kind(task) }

    fun state(task: BackgroundTask): String = when {
        task.stopping -> "正在停止"
        else -> when (task.state) {
            TaskState.Running -> "运行中"
            TaskState.Completed -> "已完成"
            TaskState.Failed -> "失败"
            TaskState.Stopped -> "已停止"
            TaskState.Ended -> "已结束"
        }
    }

    /** 列表里的说明行：“后台命令 · 已运行 2 分 05 秒”“子代理 · 已完成 · 用时 6 秒”。 */
    fun summary(task: BackgroundTask, now: Long): String {
        val time = formatElapsed(task.elapsedMs(now))
        return kind(task) + " · " + when {
            task.stopping -> "正在停止 · 已运行 $time"
            task.running -> "已运行 $time"
            else -> state(task) + " · 用时 " + time
        }
    }

    /** 侧栏入口和后台对话一行的状态：在回答、等确认、后台任务在跑，都没有就是快要收起了。 */
    fun conversation(busy: Boolean, waiting: Boolean, tasks: Int, schedule: String?): String {
        val state = when {
            waiting -> "等你确认"
            busy -> "正在回答"
            tasks > 0 -> "$tasks 个后台任务在跑"
            else -> "已结束，稍后收起"
        }
        return if (schedule == null) state else "定时任务「$schedule」 · $state"
    }

    /** 输出区上方的一句：读到多少、是不是只显示了最后一段。 */
    fun outputHeader(totalBytes: Long, truncated: Boolean): String = when {
        totalBytes <= 0 -> "输出"
        truncated -> "输出 · 共 ${formatBytes(totalBytes)}，只显示最后 8 KB"
        else -> "输出 · ${formatBytes(totalBytes)}"
    }
}
