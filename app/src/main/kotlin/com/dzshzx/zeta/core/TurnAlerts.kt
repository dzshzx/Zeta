package com.dzshzx.zeta.core

import android.os.SystemClock
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.voice.ReadAloud
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.phone.AccessibilityGuard
import com.dzshzx.zeta.phone.OverlayBridge
import com.dzshzx.zeta.phone.OverlayFocus
import com.dzshzx.zeta.ui.overlay.ScreenLock

/**
 * 对话在 Zeta 之外怎么让用户知道：顶部状态胶囊显示哪个对话（[overlayFocus]）、一轮结束时的完成提醒和屏幕底部的结果卡片、
 * 需要确认的提醒、回答自动朗读，以及回答中的对话各自那条通知（[syncProgress]）。照小爱，一个对话只有一条通知，进行中、等你、已完成都在这一条上更新。 对话的事件由 [hub]
 * 转过来（一轮就要结束、结束了、等确认）；挑对话只读可观察的状态，在主线程上调用。
 */
class TurnAlerts internal constructor(private val hub: ConversationHub) : TurnEvents {
    private val app
        get() = hub.context

    private val main = MainHandler()

    /**
     * 顶部状态胶囊要显示的对话或外部客户端：正在用手机工具的那个（几个都在用时取最近操作的），没有时为 null。
     * 可观察（随对话、手机工具、外部客户端的调用、界面前后台的状态自动更新），在主线程上读。挑法见 [pickFocus]。
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
     * - 否则在进行中、这一轮用过手机（看屏或动手）的对话，暂停着、用户正接管着的对话，以及正在显示的外部客户端（[BridgeFocus.shown]）里， 照 [choose]
     *   挑：接管着的优先，其余取最近用手机的；
     * - Zeta 的聊天页正显示着这个对话、它又没有后台屏幕时不显示（聊天页上已经看得到进度，照 Eta）。 只读可观察的状态，由 [focusState] 缓存。
     */
    private fun pickFocus(): OverlayFocus? {
        fun visible(c: Conversation) = !(hub.isWatching(c) && c.phone.agentDisplay == null)
        linger
            ?.focus
            ?.takeIf { !it.conversation.busy && visible(it.conversation) }
            ?.let {
                return it
            }
        val conversations =
            hub.liveConversations
                .filter { c ->
                    val phone = c.phone
                    (phone.userControlling || phone.paused || (c.busy && phone.engagedThisTurn)) &&
                        visible(c)
                }
                .map { FocusCandidate(it.overlay, it.phone.engagedAt) }
        val clients = hub.bridgeFoci.filter { it.shown }.map { FocusCandidate(it, it.usedAt) }
        return choose(conversations + clients)
    }

    /** [focus] 正停留在“已完成”时的那一句，否则为 null。 */
    internal fun finishingText(focus: ConversationFocus): String? =
        linger?.takeIf { it.focus === focus && !focus.conversation.busy }?.text

    /** 对话的这一轮就要结束（还没清 busy 和手机工具的状态）。正显示在胶囊上的话，先停留一会儿显示 [text]（“已完成”；后台还有任务时见 [endText]）。 */
    override fun turnEnding(conversation: Conversation, text: String) {
        endingWithPhone = conversation.takeIf { it.phone.engagedThisTurn }
        val focus = conversation.overlay
        if (focusState.value !== focus) return
        linger = Linger(focus, endText(conversation, text))
        main.removeCallbacks(endLinger)
        main.postDelayed(endLinger, FINISHED_LINGER_MS)
    }

    /**
     * 一轮结束了：弹不弹结果卡片、要不要提醒、念不念回答。[text] 是结束的那一句（“已完成”“出错了”“已中断”），提醒上也写它。
     * [stopped]：这一轮是用户叫停的（聊天页或通知栏），不再提醒。
     */
    override fun turnFinished(conversation: Conversation, stopped: Boolean, text: String) {
        val showed =
            offerResultCard(
                conversation,
                usedPhone = endingWithPhone === conversation,
                stopped = stopped,
            )
        endingWithPhone = null
        val key = conversation.openKey
        progressShown.remove(key)
        // 后台运行起的对话由 ScheduleRunner 自己提醒（任务名和回答开头，点了打开这个对话）
        if (!stopped && !hub.isWatching(conversation) && conversation.unattended == null) {
            // 弹了结果卡片就不再响铃，免得打扰两次；后台还有任务时活还没干完，也不响铃（等它们做完、Claude Code 答完最后一轮再响）。
            // 通知照样留在通知栏里
            waitingShown.remove(key)
            Notifier.alert(
                app,
                key,
                title(conversation),
                endText(conversation, text),
                short = text,
                right = title(conversation),
                silent = showed || conversation.backgroundTasks > 0,
            )
        } else {
            // 不提醒的（叫停、看着、后台运行起的）：可能还留着的那条“进行中”撤掉。syncProgress 可能已经先看到它不在回答了，留给这里撤
            clearAlert(key)
        }
        if (!stopped) autoRead(conversation)
    }

    /** 正显示着“在等你”的提醒：会话的 openKey → 请求号。同一个请求不重发；提醒撤掉或换成结束的那句时去掉。 */
    private val waitingShown = HashMap<String, String>()

    /** 对话等着的请求变了（来了一个、处理掉了、Claude Code 撤回了、进程没了）。来了就照 [syncWaiting] 提醒，没了就撤掉这个对话的提醒。 */
    override fun pendingChanged(conversation: Conversation) {
        if (conversation.pending != null) {
            syncWaiting(conversation)
            return
        }
        Notifier.forgetRings(
            hub.liveConversations.mapNotNullTo(HashSet()) { it.pending?.requestId },
        )
        clearAlert(conversation.openKey)
    }

    /** 用户看着的对话变了（回到前台、切到后台、切了对话）：看着的那个的提醒不必留着；没看着、还在等用户的对话照 [syncWaiting] 提醒。 */
    override fun watchChanged() {
        if (hub.foreground) clearAlert(hub.current.openKey)
        syncAll()
    }

    /** 对话的状态变了（[ConversationHub.onActivityChanged]）：各对话的等你提醒跟着对一遍。 */
    internal fun syncAll() = hub.liveConversations.forEach(::syncWaiting)

    /**
     * 对话在等用户、用户又没看着它时怎么提醒：它的那条通知（进行中的话就是那一条）换成在等什么，能在通知上回答的带上按钮 （见
     * [AlertNotice.replies]），超级岛弹出来。同一个请求只响一次。 后台运行的由 ScheduleRunner 提醒。
     */
    private fun syncWaiting(conversation: Conversation) {
        val request = conversation.pending ?: return
        if (hub.isWatching(conversation) || conversation.unattended != null) return
        val key = conversation.openKey
        if (waitingShown[key] == request.requestId) return
        waitingShown[key] = request.requestId
        progressShown.remove(key)
        val notice = AlertNotice.waiting(request)
        Notifier.alert(
            app,
            key,
            title(conversation),
            notice.text,
            short = AlertNotice.waitingStep(request).kind.short,
            right = notice.subject,
            detail = notice.detail,
            actions =
                notice.replies.mapIndexed { index, reply ->
                    AlertReplyReceiver.action(app, key, request.requestId, index, reply)
                },
            silent = !Notifier.firstRing(request.requestId),
        )
    }

    private fun clearAlert(key: String) {
        waitingShown.remove(key)
        progressShown.remove(key)
        Notifier.clearAlert(app, key)
    }

    /**
     * 对话 [key] 的提醒上第 [index] 个按钮按下了（[AlertReplyReceiver]），按钮是为请求 [requestId] 发的。那个请求还在等就照按钮回答，
     * 回答后请求没了，[pendingChanged] 随即撤掉提醒；已经处理掉的不再回答，留着的过时提醒一并撤掉。
     */
    internal fun reply(key: String, requestId: String, index: Int) {
        val conversation = hub.findConversation(key)
        val pending = conversation?.pending
        if (conversation == null || pending == null) {
            clearAlert(key)
            return
        }
        if (pending.requestId != requestId) return
        val reply = AlertNotice.replies(pending).getOrNull(index) ?: return
        if (pending is PermissionRequest)
            conversation.reply(pending, AlertNotice.permissionReply(pending, reply))
    }

    /**
     * 一轮结束时说的那句：这一轮答完了、后台任务（子代理、后台命令）还在跑时，活还没干完，不说 [text]（“已完成”），说还有几个在跑。 它们做完后 Claude Code
     * 会接着答一轮，那一轮结束时才是“已完成”。
     */
    private fun endText(conversation: Conversation, text: String): String {
        val tasks = conversation.backgroundTasks
        return if (tasks > 0 && text == Strings.coreTurnDone()) Strings.coreTurnDoneWithTasks(tasks)
        else text
    }

    /** 提醒的标题：对话标题。 */
    private fun title(conversation: Conversation) = conversation.title.ifBlank { "Zeta" }

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

    /** 正在回答的对话这一轮从什么时候开始（墙上时钟），通知上走秒用。 */
    private val turnStarts = HashMap<Conversation, Long>()

    /** 正显示着“进行中”的对话：openKey → 它那条通知的节流（一轮里步骤一秒变好几次）。换成等你、已完成或撤掉时去掉。 */
    private val progressShown = HashMap<String, Throttle<LiveStatus>>()

    /** 有对话在回答（不全是在等用户）时每秒看一次当前步骤：步骤变了不会调 [ConversationHub.onActivityChanged]。 */
    private val liveTick = Runnable { hub.onActivityChanged() }

    /** 节流记下的那几份到点了就发。 */
    private val flushProgress = Runnable { flush(SystemClock.elapsedRealtime()) }

    /**
     * 回答中的对话（[answering]）各自那条通知上写“进行中”：标题是对话标题，正文是当前步骤（和顶部胶囊同一句），从这一轮开始走秒，
     * 有没做完的任务清单时带进度条，上超级岛（[FocusParam.of]）。照小爱，一个对话只有一条通知：等你确认、已完成也发在这一条上（同一个 tag）， 所以停下来等请求的交给
     * [syncWaiting]，结束的交给 [turnFinished]。用户正看着的、后台运行起的（和提醒一样）不发；顶部胶囊正显示着的那个也不发 （照
     * Eta，不让顶部同时出现两个说同一件事的胶囊）， 胶囊照样每秒再看一次，它一退场（比如锁屏）下一秒就发。在主线程上调用。
     */
    internal fun syncProgress(answering: List<Conversation>) {
        val now = System.currentTimeMillis()
        turnStarts.keys.retainAll(answering.toSet())
        answering.forEach { turnStarts.getOrPut(it) { now } }
        main.removeCallbacks(liveTick)
        val capsule = OverlayBridge.capsuleBounds != null
        // 都停下来等用户时步骤不会自己变（请求来了、没了另有 onActivityChanged），不必每秒再看；胶囊显示着时照看，好在它退场时接上
        if (answering.isNotEmpty() && (capsule || answering.any { it.pending == null }))
            main.postDelayed(liveTick, LIVE_TICK_MS)
        val capsuleKey =
            (overlayFocus as? ConversationFocus)?.conversation?.openKey?.takeIf { capsule }
        val shown = answering.filter {
            it.pending == null &&
                !hub.isWatching(it) &&
                it.unattended == null &&
                it.openKey != capsuleKey
        }
        val keys = shown.mapTo(HashSet()) { it.openKey }
        progressShown.keys
            .filter { it !in keys }
            .forEach { key ->
                progressShown.remove(key)
                // 停下来等请求的，那条通知马上换成等什么（syncWaiting）；结束了的由 turnFinished 换成结束的那句。其余（看着了、胶囊接过去了、对话没了）撤掉
                val conversation = answering.firstOrNull { it.openKey == key }
                val replaced =
                    conversation?.pending != null ||
                        (conversation == null && hub.findConversation(key) != null)
                if (!replaced) Notifier.clearAlert(app, key)
            }
        val clock = SystemClock.elapsedRealtime()
        shown.forEach { conversation ->
            val status =
                LiveUpdate.status(
                    conversation.openKey,
                    conversation.title,
                    conversation.overlay.stepInfo,
                    turnStarts.getValue(conversation),
                    conversation.todos,
                )
            val throttle =
                progressShown.getOrPut(conversation.openKey) {
                    Throttle(LIVE_INTERVAL_MS, LiveUpdate::minorChange)
                }
            throttle.offer(status, clock)?.let { Notifier.progress(app, it) }
        }
        scheduleFlush(clock)
    }

    private fun flush(now: Long) {
        progressShown.values.forEach { throttle ->
            throttle.poll(now)?.let { Notifier.progress(app, it) }
        }
        scheduleFlush(now)
    }

    private fun scheduleFlush(now: Long) {
        main.removeCallbacks(flushProgress)
        progressShown.values
            .mapNotNull { it.dueIn(now) }
            .minOrNull()
            ?.let { main.postDelayed(flushProgress, it) }
    }

    /** 胶囊的一个候选：[usedAt] 是它最近一次用手机的时间（墙上时钟）。 */
    internal class FocusCandidate(val focus: OverlayFocus, val usedAt: Long)

    internal companion object {
        /** 几个候选里胶囊显示哪个：接管着的优先，其余取最近用手机的；没有候选时为 null。 */
        fun choose(candidates: List<FocusCandidate>): OverlayFocus? =
            candidates
                .maxWithOrNull(
                    compareBy<FocusCandidate> { it.focus.userControlling }.thenBy { it.usedAt },
                )
                ?.focus

        /** 对话在回答时多久看一次当前步骤（发不发通知另由 [LIVE_INTERVAL_MS] 节流）。 */
        private const val LIVE_TICK_MS = 1_000L

        /** 同一个对话的通知上只是步骤、进度变了时，两次更新至少隔这么久。 */
        private const val LIVE_INTERVAL_MS = 2_000L

        /** 一轮结束后胶囊停留显示“已完成”多久。 */
        private const val FINISHED_LINGER_MS = 2_000L
    }
}
