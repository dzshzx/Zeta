package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import com.dzshzx.zeta.i18n.Strings

/*
 * 对话在回答时，它自己那条通知（照小爱：一个对话一条，执行中、等你、已完成都在这一条上更新，见 TurnAlerts）显示当前步骤
 * （和顶部胶囊同一句）并上小米超级岛（[FocusParam]），大岛左边是一个很短的词，有任务清单时带进度条。
 * 这里是不碰 Android 的部分：写什么字、多久更新一次。发通知在 AgentService 的 Notifier。
 */

/** 胶囊上那句当前步骤的类别。[short] 是超级岛大岛左边、息屏上的词，只放得下几个字。 */
enum class StepKind(private val shortText: () -> String) {
    FINISHED(Strings::coreStepShortFinished),
    CONTROLLING(Strings::coreStepShortControlling),
    PAUSED(Strings::coreStepShortPaused),
    ASKING(Strings::coreStepShortAsking),
    CONFIRMING(Strings::coreStepShortConfirming),

    /** 重试、压缩上下文这类临时状态。 */
    STATUS(Strings::coreStepShortStatus),
    ANSWERING(Strings::coreStepShortAnswering),

    /** 手机工具。 */
    PHONE(Strings::coreStepShortPhone),
    TOOL(Strings::coreStepShortTool),
    THINKING(Strings::coreStepShortThinking);

    /** 超级岛大岛左边、息屏上的词，只放得下几个字。 */
    val short: String
        get() = shortText()

    /** 停下来等用户（确认、回答、继续）：变成或不再是这种时马上更新通知，不节流。 */
    val needsUser: Boolean
        get() = this == CONTROLLING || this == PAUSED || this == ASKING || this == CONFIRMING
}

/** 胶囊上的一句当前步骤和它的类别。 */
@Immutable data class Step(val text: String, val kind: StepKind)

/** 任务清单的进度。 */
@Immutable data class TodoProgress(val done: Int, val total: Int)

/** 对话在回答时它那条通知上的内容。 */
@Immutable
data class LiveStatus(
    /** 点通知打开的对话，也是这条通知的 tag。 */
    val key: String,
    val title: String,
    /** 正文：胶囊上的那句当前步骤。 */
    val text: String,
    val kind: StepKind,
    /** 这一轮开始的时间（墙上时钟），通知上据此走秒。 */
    val startedAt: Long,
    /** 任务清单还没做完时的进度，进度条用；没有清单或已经做完时为 null。 */
    val progress: TodoProgress?,
) {
    /** 超级岛大岛左边、息屏上的词。 */
    val short: String
        get() = kind.short
}

internal object LiveUpdate {
    /** 进度条按清单一项一段；项数多到分不清时（系统也有上限）画成一整段。 */
    const val MAX_SEGMENTS = 12

    /** 对话 [key]（标题 [title]）这一轮从 [startedAt] 开始、当前步骤是 [step] 时通知上的样子；[todo] 是它的任务清单。 */
    fun status(key: String, title: String, step: Step, startedAt: Long, todo: TodoList?) =
        LiveStatus(
            key = key,
            title = title.ifBlank { Strings.coreUntitledConversation() },
            text = step.text,
            kind = step.kind,
            startedAt = startedAt,
            progress =
                todo
                    ?.takeIf { it.total > 0 && !it.allDone }
                    ?.let { TodoProgress(it.done, it.total) },
        )

    /** 进度条各段的长度：一项一段，太多时一整段。 */
    fun segments(total: Int): List<Int> =
        if (total <= MAX_SEGMENTS) List(total) { 1 } else listOf(total)

    /** 从 [old] 到 [new] 只是步骤文字、进度变了（同一个对话的同一轮、前后都不是在等用户）： 这种变化一轮里一秒好几次，要节流；其余的变化马上更新。 */
    fun minorChange(old: LiveStatus, new: LiveStatus): Boolean =
        old.key == new.key &&
            old.title == new.title &&
            old.startedAt == new.startedAt &&
            !old.kind.needsUser &&
            !new.kind.needsUser
}

/** 前台服务那条通知（只为保活）要显示的状态：有进程在就常驻，[busy] 时持有唤醒锁，[running] 是正在回答的对话。 */
internal data class StatusNotice(val active: Boolean, val busy: Boolean, val running: List<String>)

/** 通知更新的节流：小变化（[minor] 判断）离上次发出不到 [intervalMs] 时先记下，到点再发最新的那份； 其余的变化马上发，和已发出的一样就不发。只在一个线程上用。 */
internal class Throttle<T : Any>(
    private val intervalMs: Long,
    private val minor: (published: T, next: T) -> Boolean,
) {
    /** 最近发出的那份。 */
    var published: T? = null
        private set

    private var publishedAt = 0L
    private var waiting: T? = null

    /** 新的状态 [next]（时刻 [now]，毫秒）：返回现在就要发的那份，不用发或要等时返回 null。 */
    fun offer(next: T, now: Long): T? {
        val last = published
        waiting = null
        if (next == last) return null
        if (last == null || !minor(last, next) || now - publishedAt >= intervalMs)
            return publish(next, now)
        waiting = next
        return null
    }

    /** 记下的那份到点了就返回它（算作已发出），否则返回 null。 */
    fun poll(now: Long): T? {
        val next = waiting ?: return null
        if (now - publishedAt < intervalMs) return null
        return publish(next, now)
    }

    /** 记下的那份还要等多久（毫秒）；没有记下的时为 null。 */
    fun dueIn(now: Long): Long? = waiting?.let {
        (publishedAt + intervalMs - now).coerceAtLeast(0L)
    }

    private fun publish(value: T, now: Long): T {
        published = value
        publishedAt = now
        waiting = null
        return value
    }
}
