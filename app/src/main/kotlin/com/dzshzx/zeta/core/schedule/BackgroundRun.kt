package com.dzshzx.zeta.core.schedule

import android.content.Context

/**
 * 一项后台运行的运行记录：上次运行、跳过次数，以及接着同一个对话时用的会话。
 * [newSession] 只读，改了也不存。
 */
internal data class RunRecord(
    val lastRun: TaskRun?,
    val skips: Int,
    val lastSkip: Long?,
    val newSession: Boolean,
    val sessionId: String?,
)

/**
 * 后台运行从哪来：定时任务，或通知触发的规则。[ScheduleRunner] 经它把运行记录写回各自的清单。
 */
internal interface RunRecords {
    /** 来源的名字。进行中运行的键、通知的 tag 都是“[kind]:<id>”，两种来源的 id 撞不上。 */
    val kind: String

    /** 这一项现在的名字；已经删掉时为 null。 */
    fun name(context: Context, id: String): String?

    /** 改这一项的运行记录并存盘；已经删掉时什么都不做。 */
    fun update(context: Context, id: String, change: (RunRecord) -> RunRecord)
}

/** 这一次要跑什么。 */
internal data class RunSpec(
    val id: String,
    val name: String,
    /** 发给 Claude 的指令（通知触发的已经填好了通知内容）。 */
    val prompt: String,
    /** 工作目录；null 用运行那一刻的默认工作目录。 */
    val cwd: String?,
    /** null 跟随当前设置。 */
    val model: String?,
    val permissionMode: String?,
    val newSession: Boolean,
    /** 接着同一个对话时用的会话。 */
    val sessionId: String?,
    /** 加在系统提示词后面：告诉 Claude 这一轮是怎么来的、没人看着。 */
    val hint: String,
)

/** 定时任务的运行记录写在任务清单里。 */
internal object ScheduleRecords : RunRecords {
    override val kind = "schedule"

    override fun name(context: Context, id: String): String? = ScheduleStore.find(context, id)?.name

    override fun update(context: Context, id: String, change: (RunRecord) -> RunRecord) {
        ScheduleStore.updateTask(context, id) { task ->
            val next = change(RunRecord(task.lastRun, task.skips, task.lastSkip, task.newSession, task.sessionId))
            task.copy(lastRun = next.lastRun, skips = next.skips, lastSkip = next.lastSkip, sessionId = next.sessionId)
        }
    }

    fun spec(task: ScheduledTask) = RunSpec(
        id = task.id,
        name = task.name,
        prompt = task.prompt,
        cwd = task.cwd,
        model = task.model,
        permissionMode = task.permissionMode,
        newSession = task.newSession,
        sessionId = task.sessionId,
        hint = "Messages in this conversation may be sent automatically by the user's scheduled task \"${task.name}\" while nobody is watching. " +
            "The user first sees the opening of your final reply (about 200 characters) in a phone notification, so lead with the result.",
    )
}
