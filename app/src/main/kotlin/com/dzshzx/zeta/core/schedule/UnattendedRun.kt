package com.dzshzx.zeta.core.schedule

import android.content.Context
import com.dzshzx.zeta.core.Unattended
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.phone.tools.ScheduleTools

/** 一项后台运行的运行记录：上次运行、跳过次数，以及接着同一个对话时用的会话。 */
data class RunRecord(
    val lastRun: TaskRun?,
    val skips: Int,
    val lastSkip: Long?,
    val sessionId: String?,
)

/** 后台运行从哪来：定时任务，或通知触发的规则。[ScheduleRunner] 经它把运行记录写回各自的清单。 */
interface RunRecords {
    /** 来源的名字。进行中运行的键、通知的 tag 都是“[kind]:<id>”，两种来源的 id 撞不上。 */
    val kind: String

    /** 这一项已经删掉、对话也还没有标题时，通知和日志里用的名字。 */
    val fallbackName: String

    /** 这一项现在的名字；已经删掉时为 null。 */
    fun name(context: Context, id: String): String?

    /** 改这一项的运行记录并存盘；已经删掉时什么都不做。 */
    fun update(context: Context, id: String, change: (RunRecord) -> RunRecord)
}

/** 这一次要跑什么。 */
data class RunSpec(
    val id: String,
    val name: String,
    /** 发给 Claude 的指令（通知触发的已经填好了通知内容）。 */
    val prompt: String,
    /** 在哪台主机上跑（[com.dzshzx.zeta.core.Backends]）。 */
    val backend: String,
    /** 工作目录；null 用运行那一刻那台主机的默认工作目录。 */
    val cwd: String?,
    /** null 用 Claude Code 的默认模型。 */
    val model: String?,
    /** 这次运行发的指令用的权限模式，在任务上明确定，不跟随别处的默认值。 */
    val permissionMode: String,
    val newSession: Boolean,
    /** 接着同一个对话时用的会话。 */
    val sessionId: String?,
    /** 加在系统提示词后面：告诉 Claude 这一轮是怎么来的、没人看着。 */
    val hint: String,
    /** 只在有变化时提醒（见 [Schedules.settle]）。 */
    val onlyOnChange: Boolean = false,
)

/**
 * 一次无人值守的后台运行，从开始到跑完挂在跑它的对话上（Conversation.unattended，手机工具上同步挂一份给工具调用用）。 它就是这个对话的来源标记：挂着时完成和确认提醒由
 * [ScheduleRunner] 发（带任务名），通用的那条不发，回答也不念（见 TurnAlerts）。 这次运行的状态都记在这里：只在有变化时提醒的运行里 Claude 上报的结论
 * [report]（工具线程写，主线程跑完时读）， 为哪个确认请求提醒过（[notifiedRequest]）。跑完怎么算、提不提醒见 [settle]。
 * 管理定时任务的工具在后台运行里拦两道：Claude Code 的权限规则让模型调不到（见 ScheduleTools.unattendedRules）， 漏过来的调用再由
 * ScheduleTools.refusal 看它拒掉。
 */
class UnattendedRun(val onlyOnChange: Boolean) : Unattended {
    @Volatile var report: RunReport? = null

    /** 最近一次发过「需要你确认」提醒的确认请求的请求号；没停下来等过确认时为 null。在主线程上读写。 */
    var notifiedRequest: String? = null

    /** 这次停下来等过用户确认：跑完照常提醒。 */
    val asked: Boolean
        get() = notifiedRequest != null

    /** 跑完的结局（见 [Schedules.settle]）：[raw]、[answer] 是从对话里看出来的（[Schedules.outcomeOf]），再按这次的上报和确认定。 */
    fun settle(raw: RunOutcome, answer: String): Settled =
        Schedules.settle(raw, answer, onlyOnChange, report, asked)

    /** 这一轮跑完不提醒（Claude 上报了没有变化）：屏幕底部也不弹结果卡片。 */
    override val quiet: Boolean
        get() = !settle(RunOutcome.Done, "").notify
}

/**
 * 后台运行加在系统提示词后面的说明（接着开着的进程时附在指令后面）：[intro] 说这一轮是怎么来的，接着说结果先看通知里那一段；
 * 只在有变化时提醒的再说怎么上报（手机工具关着时没有上报的工具，照常提醒）。定时任务和通知触发共用。
 */
fun unattendedHint(intro: String, onlyOnChange: Boolean): String =
    "$intro The user first sees the opening of your final reply (about ${Schedules.MAX_SUMMARY} characters) in a phone notification, " +
        "so lead with the result." +
        if (onlyOnChange) " $ONLY_ON_CHANGE_HINT" else ""

private const val ONLY_ON_CHANGE_HINT =
    "This automation notifies the user only when there is something worth telling them. Before ending each automatic run, " +
        "call the phone tool ${ScheduleTools.REPORT}: outcome \"no_change\" when nothing is worth telling the user " +
        "(for example nothing new since the last run); no notification is sent and the run is only logged. " +
        "Otherwise outcome \"deliver\" with a one- or two-sentence summary, which becomes the notification text. " +
        "If you do not report, the user is notified as usual. Messages the user types themselves need no report."

/** 定时任务的运行记录写在任务清单里。 */
object ScheduleRecords : RunRecords {
    override val kind = "schedule"
    override val fallbackName: String
        get() = Strings.scheduleFallbackName()

    override fun name(context: Context, id: String): String? = ScheduleStore.find(context, id)?.name

    override fun update(context: Context, id: String, change: (RunRecord) -> RunRecord) {
        ScheduleStore.updateTask(context, id) { task ->
            val next = change(RunRecord(task.lastRun, task.skips, task.lastSkip, task.sessionId))
            task.copy(
                lastRun = next.lastRun,
                skips = next.skips,
                lastSkip = next.lastSkip,
                sessionId = next.sessionId,
            )
        }
    }

    fun spec(task: ScheduledTask) =
        RunSpec(
            id = task.id,
            name = task.name,
            prompt = task.prompt,
            backend = task.backend,
            cwd = task.cwd,
            model = task.model,
            permissionMode = task.permissionMode,
            newSession = task.newSession,
            sessionId = task.sessionId,
            hint =
                unattendedHint(
                    "Messages in this conversation may be sent automatically by the user's scheduled task \"${task.name}\" while nobody is watching.",
                    task.onlyOnChange,
                ),
            onlyOnChange = task.onlyOnChange,
        )
}
