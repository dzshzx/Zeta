package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import com.dzshzx.zeta.i18n.Strings

/*
 * 运行状态上超级岛：有对话在回答时，常驻通知显示那个对话的当前步骤（和顶部胶囊同一句）并上小米超级岛（[FocusParam]），
 * 大岛左边是一个很短的词，有任务清单时带进度条。
 * 这里是不碰 Android 的部分：挑哪个对话、写什么字、多久更新一次。发通知在 AgentService。
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

/** 挑常驻通知显示哪个对话时，一个正在回答的对话的样子。 */
internal data class LiveCandidate(
    /** 打开这个对话用的标识（会话号，新对话是它的 key）。 */
    val key: String,
    val title: String,
    val step: Step,
    /** 用户正在胶囊上接管它的后台屏幕。 */
    val controlling: Boolean,
    /** 胶囊会挑它：这一轮用过手机（看屏或动手），或暂停着、接管着。 */
    val engaged: Boolean,
    /** 最近一次用手机的时间。 */
    val engagedAt: Long,
    /** Zeta 里正显示着的那个对话。 */
    val current: Boolean,
    /** 这一轮开始的时间（墙上时钟），通知上据此走秒。 */
    val startedAt: Long,
    /** 停下来等用户回应的那个请求（批准工具、提问、MCP 表单），没有时为 null。 */
    val request: UserPrompt? = null,
)

/** 任务清单的进度。 */
@Immutable data class TodoProgress(val done: Int, val total: Int)

/** 常驻通知（超级岛）上的内容。 */
@Immutable
data class LiveStatus(
    /** 点通知打开的对话。 */
    val key: String,
    val title: String,
    /** 正文：胶囊上的那句当前步骤。 */
    val text: String,
    val kind: StepKind,
    val startedAt: Long,
    /** 任务清单还没做完时的进度，进度条用；没有清单或已经做完时为 null。 */
    val progress: TodoProgress?,
    /** 除了这个，还有几个对话在回答。 */
    val others: Int,
    /** 停下来等用户时：等的请求号和展开后的全文；按钮见 [replies]。没在等时为 null。 */
    val requestId: String? = null,
    val detail: String? = null,
    /** 通知上替用户回答的按钮（见 [AlertNotice.replies]），超级岛展开时也在；空的时候是“停止”“截屏”。 */
    val replies: List<AlertReply> = emptyList(),
    /** 超级岛右边那句：等用户时是等的是什么（[WaitingNotice.subject]），否则就是 [text]。 */
    val subject: String = text,
) {
    /** 超级岛大岛左边的词。 */
    val short: String
        get() = kind.short

    /** 几个对话同时在回答时，标题旁的一句；只有一个时为 null。 */
    val note: String?
        get() = if (others > 0) Strings.coreLiveOthers(others) else null
}

internal object LiveUpdate {
    /** 进度条按清单一项一段；项数多到分不清时（系统也有上限）画成一整段。 */
    const val MAX_SEGMENTS = 12

    /**
     * 显示哪个对话：顶部胶囊要显示的那个（[focusKey]）在回答就用它。胶囊显示着时不上岛，用到它的是胶囊退场
     * （锁屏、息屏）的时候：锁屏上的岛和通知接着说胶囊在说的那个对话。没有时照胶囊挑对话的规则排：接管着的优先，其次是停下来等用户的，再是用过手机的里最近用的，再是 Zeta
     * 里正显示着的，最后是最近开始的。
     */
    fun pick(candidates: List<LiveCandidate>, focusKey: String?): LiveCandidate? {
        focusKey
            ?.let { key -> candidates.firstOrNull { it.key == key } }
            ?.let {
                return it
            }
        return candidates.maxWithOrNull(
            compareBy<LiveCandidate> { it.controlling }
                .thenBy { it.step.kind.needsUser }
                .thenBy { it.engaged }
                .thenBy { if (it.engaged) it.engagedAt else 0L }
                .thenBy { it.current }
                .thenBy { it.startedAt },
        )
    }

    /** 选中的对话 [chosen] 在通知上的样子；一共有 [running] 个对话在回答，[todo] 是它的任务清单。 */
    fun status(chosen: LiveCandidate, running: Int, todo: TodoList?): LiveStatus {
        // 胶囊上那句是“需要你确认”“需要你回答”时，正文换成具体的命令、问题，带上回答的按钮（接管、暂停时不换）
        val request =
            chosen.request?.takeIf {
                chosen.step.kind == StepKind.CONFIRMING || chosen.step.kind == StepKind.ASKING
            }
        val waiting = request?.let(AlertNotice::waiting)
        return LiveStatus(
            key = chosen.key,
            title = chosen.title.ifBlank { Strings.coreUntitledConversation() },
            text = waiting?.text ?: chosen.step.text,
            kind = chosen.step.kind,
            startedAt = chosen.startedAt,
            progress =
                todo
                    ?.takeIf { it.total > 0 && !it.allDone }
                    ?.let { TodoProgress(it.done, it.total) },
            others = (running - 1).coerceAtLeast(0),
            requestId = request?.requestId,
            detail = waiting?.detail,
            replies = waiting?.replies.orEmpty(),
            subject = waiting?.subject ?: chosen.step.text,
        )
    }

    /** 进度条各段的长度：一项一段，太多时一整段。 */
    fun segments(total: Int): List<Int> =
        if (total <= MAX_SEGMENTS) List(total) { 1 } else listOf(total)

    /** 从 [old] 到 [new] 只是步骤文字、进度变了（同一个对话、几个对话在跑没变、前后都不是在等用户）： 这种变化一轮里一秒好几次，要节流；其余的变化马上更新。 */
    fun minorChange(old: LiveStatus?, new: LiveStatus?): Boolean {
        if (old == null || new == null) return false
        return old.key == new.key &&
            old.title == new.title &&
            old.others == new.others &&
            old.startedAt == new.startedAt &&
            !old.kind.needsUser &&
            !new.kind.needsUser
    }
}

/** 常驻通知要显示的状态：有进程在就常驻，[busy] 时持有唤醒锁，[running] 是正在回答的对话；[live] 不为 null 时显示当前步骤、上超级岛。 */
internal data class StatusNotice(
    val active: Boolean,
    val busy: Boolean,
    val running: List<String>,
    val live: LiveStatus?,
) {
    /** 和 [old] 比只是常驻通知上的步骤、进度变了，其余都一样：这种更新要节流。 */
    fun minorAfter(old: StatusNotice): Boolean =
        active == old.active &&
            busy == old.busy &&
            running == old.running &&
            LiveUpdate.minorChange(old.live, live)
}

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
