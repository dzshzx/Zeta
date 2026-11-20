package com.dzshzx.zeta.core

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf

/**
 * 一轮结束后浮在屏幕底部的结果卡片（照 Eta 的结果卡片）：这一轮用过手机工具、结束时 Zeta 不在前台，
 * 就把这一轮的回答（出错时是错误）浮在用户正在用的应用上，不用切回 Zeta 看。弹不弹、何时收起见 [ResultCardPolicy]；
 * 由 TurnAlerts 在一轮结束时建好（`hub.alerts.resultCard`），悬浮层（ui/overlay/ResultCardHost）照着画。
 *
 * 内容跟着对话的聊天记录走，可观察：一轮结束的那一刻，出错提示还没进聊天列表。
 */
@Stable
class TurnResult internal constructor(
    internal val conversation: Conversation,
    /** 卡片出现时已经在进行的别的对话：它们接着跑不算“新的一轮”。 */
    internal val busyBefore: Set<Conversation>,
) {
    val title: String get() = conversation.title

    private val outcome = derivedStateOf { ResultCardPolicy.outcome(conversation.items) }

    /** 这一轮的最终回答（Markdown）；出错结束时是那条错误。 */
    val text: String get() = outcome.value.text
    val failed: Boolean get() = outcome.value.failed

    /** “回到对话”：打开 Zeta 并定位到这个对话（和胶囊上点标题一样）。 */
    fun openInZeta() = conversation.overlay.openInZeta()
}

/** 结果卡片什么时候弹、什么时候收起、显示什么。都是纯函数，输入由 ConversationHub 从可观察的状态里取。 */
internal object ResultCardPolicy {
    /**
     * 一轮结束时弹不弹：这一轮用过手机工具（看屏或动手），不是用户叫停的，Zeta 不在前台（在前台时聊天页上就看得到），
     * 没锁屏、没息屏（无障碍浮层在锁屏界面之上，弹出来等于绕过锁屏），悬浮层用得上（无障碍服务连着），
     * 也没有别的对话正在用手机（卡片会挡住它在主屏上的操作）。
     */
    fun shows(
        usedPhone: Boolean,
        stopped: Boolean,
        zetaForeground: Boolean,
        locked: Boolean,
        overlayReady: Boolean,
        phoneInUse: Boolean,
    ): Boolean = usedPhone && !stopped && !zetaForeground && !locked && overlayReady && !phoneInUse

    /**
     * 显示着的卡片留不留（用户下滑、返回、点按钮、一段时间没碰之外）：Zeta 到了前台（回答在聊天页上看得到），
     * 锁屏或息屏，有对话开始了新的一轮（卡片出现时已经在跑的不算），或有对话用上了手机：都收起，不挡 Claude 的下一步操作。
     */
    fun keeps(zetaForeground: Boolean, locked: Boolean, newTurn: Boolean, phoneInUse: Boolean): Boolean =
        !zetaForeground && !locked && !newTurn && !phoneInUse

    /**
     * 多久没碰就自动收起：8 秒（照 Eta），回答长一点多给些读的时间，每 40 个字再加 1 秒，最多 20 秒。
     * 按着、滚动、拖动卡片时不算没碰，松手后重新计时。
     */
    fun idleDismissMs(text: String): Long = (IDLE_BASE_MS + text.length / CHARS_PER_SECOND * 1_000L).coerceAtMost(IDLE_MAX_MS)

    /** 卡片上显示的结果。 */
    data class Outcome(val text: String, val failed: Boolean)

    /**
     * 这一轮的结果：从最后往回找到这一轮开头（最后一条用户消息），最后一段非空的回答就是最终回答；
     * 它后面要是有出错提示（这一轮以出错告终），显示那条错误。都没有时说一句没有文字回答。
     */
    fun outcome(items: List<ChatItem>): Outcome {
        for (item in items.asReversed()) {
            when (item) {
                is UserItem -> break
                is NoticeItem -> if (item.error) return Outcome(item.text, failed = true)
                is AnswerItem -> if (item.text.isNotBlank()) return Outcome(item.text.trim(), failed = false)
                else -> Unit
            }
        }
        return Outcome(NO_ANSWER, failed = false)
    }

    const val NO_ANSWER = "这一轮没有文字回答。"
    private const val IDLE_BASE_MS = 8_000L
    private const val IDLE_MAX_MS = 20_000L
    private const val CHARS_PER_SECOND = 40
}
