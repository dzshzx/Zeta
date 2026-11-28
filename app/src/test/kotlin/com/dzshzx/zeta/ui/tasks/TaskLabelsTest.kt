package com.dzshzx.zeta.ui.tasks

import com.dzshzx.zeta.core.BackgroundTask
import com.dzshzx.zeta.core.TaskKind
import com.dzshzx.zeta.core.TaskState
import com.dzshzx.zeta.core.formatElapsed
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskLabelsTest {
    private fun task(
        kind: TaskKind,
        description: String = "跑测试",
        command: String? = "make test",
        state: TaskState = TaskState.Running,
        agent: String? = null,
    ) =
        BackgroundTask(
            id = "b1",
            type = if (kind == TaskKind.Agent) "local_agent" else "local_bash",
            kind = kind,
            description = description,
            command = command,
            agentType = agent,
            toolUseId = null,
            startedAt = 0,
            endedAt = if (state == TaskState.Running) null else 6_000,
            state = state,
        )

    @Test
    fun rowText() {
        assertEquals("后台命令 · 已运行 2 分 05 秒", TaskLabels.summary(task(TaskKind.Command), 125_000))
        assertEquals(
            "子代理 · general-purpose · 已完成 · 用时 6 秒",
            TaskLabels.summary(
                task(TaskKind.Agent, state = TaskState.Completed, agent = "general-purpose"),
                99_000,
            ),
        )
        assertEquals(
            "监视器 · 正在停止 · 已运行 1 秒",
            TaskLabels.summary(task(TaskKind.Monitor).copy(stopping = true), 1_000),
        )
        assertEquals(
            "后台命令 · 已停止 · 用时 6 秒",
            TaskLabels.summary(task(TaskKind.Command, state = TaskState.Stopped), 99_000),
        )
        // 没有说明就用命令的第一行
        assertEquals(
            "make test",
            TaskLabels.title(
                task(TaskKind.Command, description = "", command = "\nmake test\nmake lint"),
            ),
        )
        assertEquals(
            "后台命令",
            TaskLabels.title(task(TaskKind.Command, description = "", command = null)),
        )
    }

    @Test
    fun conversationState() {
        assertEquals(
            "等待确认",
            TaskLabels.conversation(busy = true, waiting = true, tasks = 0, schedule = null),
        )
        assertEquals(
            "正在回答",
            TaskLabels.conversation(busy = true, waiting = false, tasks = 2, schedule = null),
        )
        assertEquals(
            "2 个后台任务运行中",
            TaskLabels.conversation(busy = false, waiting = false, tasks = 2, schedule = null),
        )
        assertEquals(
            "定时任务「早报」 · 正在回答",
            TaskLabels.conversation(busy = true, waiting = false, tasks = 0, schedule = "早报"),
        )
    }

    @Test
    fun outputHeader() {
        assertEquals("输出", TaskLabels.outputHeader(0, false))
        assertEquals("输出 · 21 B", TaskLabels.outputHeader(21, false))
        assertEquals("输出 · 共 1.5 MB，只显示最后 8 KB", TaskLabels.outputHeader(1_500_000, true))
    }

    @Test
    fun elapsed() {
        assertEquals("3 秒", formatElapsed(3_400))
        assertEquals("2 分 05 秒", formatElapsed(125_000))
        assertEquals("1 小时 03 分", formatElapsed(3_780_000))
    }
}
