package com.dzshzx.zeta.core

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.voice.ReadAloud
import com.dzshzx.zeta.phone.AccessibilityGuard
import com.dzshzx.zeta.phone.OverlayBridge
import com.dzshzx.zeta.phone.OverlayFocus
import com.dzshzx.zeta.ui.overlay.ScreenLock

/**
 * 对话在 Zeta 之外怎么让用户知道：顶部状态胶囊显示哪个对话（[overlayFocus]）、一轮结束时的完成提醒和屏幕底部的结果卡片、
 * 需要确认的提醒、回答自动朗读，以及常驻通知提升成实时通知时显示哪个对话（[liveStatus]）。 对话的事件由 [hub]
 * 转过来（一轮就要结束、结束了、等确认）；挑对话只读可观察的状态，在主线程上调用。
 */
class TurnAlerts internal constructor(private val hub: ConversationHub) {
    private val app
        get() = hub.context

    private val main = Handler(Looper.getMainLooper())

    /**
     * 顶部状态胶囊要显示的对话：正在用手机工具的那个（几个都在用时取最近操作的），没有时为 null。 可观察（随对话、手机工具、界面前后台的状态自动更新），在主线程上读。挑法见
     * [pickFocus]。
     */
    val overlayFocus: OverlayFocus?
        get() = focusState.value

    private val focusState = derivedStateOf { pickFocus() }

    /** 刚结束一轮、胶囊还停留着显示“已完成”的那个，及那一句。 */
    private class Linger(val focus: ConversationFocus, val text: String)

    private var linger by mutableStateOf<Linger?>(null)
    private val endLinger = Runnable { linger = null }

    /**
     * 屏幕底部的结果卡片要显示的那一轮；没有时为 null。一轮结束时按 [ResultCardPolicy.shows] 建， 悬浮层在 [keepsResultCard]
     * 不成立或用户关掉时 [dismissResultCard]。可观察，在主线程上读写。
     */
    var resultCard by mutableStateOf<TurnResult?>(null)
        private set

    /** 正在结束的这一轮用过手机工具的那个对话（[turnEnding] 记下，紧接着的 [turnFinished] 取走：那时手机工具的状态已经清了）。 */
    private var endingWithPhone: Conversation? = null

    /** 最近一次结束时弹了结果卡片的对话的 [Conversation.openKey]：完成提醒据此不响铃（后台运行的也是）。只记 key，不留住对话。 */
    private var carded: String? = null

    /**
     * 胶囊显示谁：
     * - 刚结束一轮的那个先停留 [FINISHED_LINGER_MS]（显示“已完成”），期间它又开始了就照常显示；
     * - 否则在进行中、这一轮用过手机（看屏或动手）的对话，以及暂停着、用户正接管着的对话里， 接管着的优先，其余取最近用手机的；
     * - Zeta 的聊天页正显示着这个对话、它又没有后台屏幕时不显示（聊天页上已经看得到进度，照 Eta）。 只读可观察的状态，由 [focusState] 缓存。
     */
    private fun pickFocus(): ConversationFocus? {
        fun visible(c: Conversation) = !(hub.isWatching(c) && c.phone.agentDisplay == null)
        linger
            ?.focus
            ?.takeIf { !it.conversation.busy && visible(it.conversation) }
            ?.let {
                return it
            }
        return hub.liveConversations
            .filter { c ->
                val phone = c.phone
                (phone.userControlling || phone.paused || (c.busy && phone.engagedThisTurn)) &&
                    visible(c)
            }
            .maxWithOrNull(
                compareBy<Conversation> { it.phone.userControlling }.thenBy { it.phone.engagedAt },
            )
            ?.overlay
    }

    /** [focus] 正停留在“已完成”时的那一句，否则为 null。 */
    internal fun finishingText(focus: ConversationFocus): String? =
        linger?.takeIf { it.focus === focus && !focus.conversation.busy }?.text

    /** 对话的这一轮就要结束（还没清 busy 和手机工具的状态）。正显示在胶囊上的话，先停留一会儿显示 [text]（“已完成”）。 */
    internal fun turnEnding(conversation: Conversation, text: String) {
        endingWithPhone = conversation.takeIf { it.phone.engagedThisTurn }
        val focus = conversation.overlay
        if (focusState.value !== focus) return
        linger = Linger(focus, text)
        main.removeCallbacks(endLinger)
        main.postDelayed(endLinger, FINISHED_LINGER_MS)
    }

    /** 一轮结束了：弹不弹结果卡片、要不要提醒“已完成”、念不念回答。[stopped]：这一轮是用户叫停的（聊天页或通知栏），不再提醒。 */
    internal fun turnFinished(conversation: Conversation, stopped: Boolean) {
        val showed =
            offerResultCard(
                conversation,
                usedPhone = endingWithPhone === conversation,
                stopped = stopped,
            )
        endingWithPhone = null
        // 后台运行起的对话由 ScheduleRunner 自己提醒（任务名和回答开头，点了打开这个对话）
        if (!stopped && !hub.isWatching(conversation) && conversation.unattended == null) {
            // 弹了结果卡片就不再响铃，免得打扰两次；通知照样留在通知栏里
            alert(conversation, "已完成", silent = showed)
        }
        if (!stopped) autoRead(conversation)
    }

    /** 对话停下来等用户批准一次工具调用。 */
    internal fun attentionNeeded(conversation: Conversation) {
        if (!hub.isWatching(conversation) && conversation.unattended == null)
            alert(conversation, "需要你确认")
    }

    /** 通用的提醒：标题是对话标题。 */
    private fun alert(conversation: Conversation, text: String, silent: Boolean = false) =
        Notifier.alert(app, conversation.title.ifBlank { "Zeta" }, text, silent = silent)

    /** 一轮结束时要不要弹结果卡片（见 [ResultCardPolicy.shows]），要的话换上这一轮的。返回弹了没有。 */
    private fun offerResultCard(
        conversation: Conversation,
        usedPhone: Boolean,
        stopped: Boolean,
    ): Boolean {
        val others = hub.liveConversations.filter { it !== conversation }
        // 后台运行上报了没有变化：跑完不提醒，卡片也不弹
        val show =
            conversation.unattended?.quiet != true &&
                ResultCardPolicy.shows(
                    usedPhone = usedPhone,
                    stopped = stopped,
                    zetaForeground = hub.foreground,
                    locked = ScreenLock.hides(hub.settings.phoneDisplay),
                    overlayReady = AccessibilityGuard.connected,
                    phoneInUse = others.any { it.busy && it.phone.engagedThisTurn },
                )
        carded = conversation.openKey.takeIf { show }
        if (show) resultCard = TurnResult(conversation, others.filterTo(HashSet()) { it.busy })
        return show
    }

    /** 显示着的结果卡片还留不留（见 [ResultCardPolicy.keeps]）。只读可观察的状态，悬浮层据此收起；[locked] 是锁屏或息屏。 */
    internal fun keepsResultCard(card: TurnResult, locked: Boolean): Boolean {
        val others = hub.liveConversations.filter { it !== card.conversation }
        return ResultCardPolicy.keeps(
            zetaForeground = hub.foreground,
            locked = locked,
            newTurn = card.conversation.busy || others.any { it.busy && it !in card.busyBefore },
            phoneInUse = others.any { it.busy && it.phone.engagedThisTurn },
        )
    }

    /** 收起结果卡片（用户关掉、超时，或不再留）。 */
    fun dismissResultCard(card: TurnResult) {
        if (resultCard === card) resultCard = null
    }

    /** [conversation] 最近一次结束时弹了结果卡片：它的完成提醒不必再响铃。 */
    internal fun showedResultCard(conversation: Conversation): Boolean =
        carded == conversation.openKey

    /** 开了“回答自动朗读”时，正显示着的对话这一轮以回答收尾就念出来（出错、停在工具步骤上的不念）。 定时任务起的对话不念：用户多半不在跟前。 */
    private fun autoRead(conversation: Conversation) {
        if (
            !hub.settings.autoReadAnswers ||
                conversation !== hub.current ||
                conversation.unattended != null
        )
            return
        val answer = conversation.items.lastOrNull() as? AnswerItem ?: return
        if (answer.text.isNotBlank()) ReadAloud.speak(app, answer.id, answer.text)
    }

    /** 正在回答的对话这一轮从什么时候开始（墙上时钟），实时通知上走秒用。 */
    private val turnStarts = HashMap<Conversation, Long>()

    /** 有对话在回答、系统允许实时通知时每秒看一次当前步骤：步骤变了不会调 [ConversationHub.onActivityChanged]。 */
    private val liveTick = Runnable { hub.onActivityChanged() }

    /**
     * 实时通知上显示什么（挑法见 [LiveUpdate.pick]）；没有对话在回答（空闲保持着的不算）、系统不允许提升，
     * 或顶部胶囊正显示着（[OverlayBridge.capsuleBounds] 不为 null；照 Eta，不让顶部同时出现两个说同一件事的胶囊）时为 null，
     * 通知照旧。胶囊显示着也照样每秒再看一次，胶囊一退场（比如锁屏）下一秒就提升。
     */
    internal fun liveStatus(answering: List<Conversation>): LiveStatus? {
        val now = System.currentTimeMillis()
        turnStarts.keys.retainAll(answering.toSet())
        answering.forEach { turnStarts.getOrPut(it) { now } }
        main.removeCallbacks(liveTick)
        if (answering.isEmpty() || !AgentService.canPromote(app)) return null
        main.postDelayed(liveTick, LIVE_TICK_MS)
        if (OverlayBridge.capsuleBounds != null) return null
        val focusKey = (overlayFocus as? ConversationFocus)?.conversation?.openKey
        val candidates = answering.map { conversation ->
            val phone = conversation.phone
            conversation to
                LiveCandidate(
                    key = conversation.openKey,
                    title = conversation.title,
                    step = conversation.overlay.stepInfo,
                    controlling = phone.userControlling,
                    engaged = phone.userControlling || phone.paused || phone.engagedThisTurn,
                    engagedAt = phone.engagedAt,
                    current = conversation === hub.current,
                    startedAt = turnStarts.getValue(conversation),
                )
        }
        val chosen = LiveUpdate.pick(candidates.map { it.second }, focusKey) ?: return null
        val conversation = candidates.first { it.second === chosen }.first
        return LiveUpdate.status(
            chosen,
            answering.size,
            TodoBoard.from(conversation.items, conversation.todoSeed),
        )
    }

    private companion object {
        /** 对话在回答时多久看一次当前步骤（发不发通知另由 AgentService 节流）。 */
        const val LIVE_TICK_MS = 1_000L

        /** 一轮结束后胶囊停留显示“已完成”多久。 */
        const val FINISHED_LINGER_MS = 2_000L
    }
}
