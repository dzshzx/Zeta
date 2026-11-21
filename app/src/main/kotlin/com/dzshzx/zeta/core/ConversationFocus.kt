package com.dzshzx.zeta.core

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import com.dzshzx.zeta.phone.OverlayFocus
import com.dzshzx.zeta.phone.OverlayPhase
import com.dzshzx.zeta.phone.PhoneTools
import com.dzshzx.zeta.phone.TouchMark
import com.dzshzx.zeta.phone.VirtualScreen
import com.dzshzx.zeta.ui.MainActivity
import com.dzshzx.zeta.ui.chat.toolTitle
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.SharedFlow

/**
 * 顶部状态胶囊眼里的一个对话（[OverlayFocus]）：状态全取自对话和它的手机工具，自己不另存，所以都可观察。
 * 每个对话一个，随对话在主线程上建好（不能等到组合里才建：快照里新建的状态在别处读会出错）。
 */
@Stable
internal class ConversationFocus(private val hub: ConversationHub, val conversation: Conversation) :
    OverlayFocus {
    private val phone: PhoneTools
        get() = conversation.phone

    /** 这一轮刚结束、胶囊还停留着时的那句（“已完成”）；其余时候为 null。 */
    private val finishing: String?
        get() = hub.alerts.finishingText(this)

    override val title: String
        get() = conversation.title

    override val phase: OverlayPhase
        get() =
            OverlayPhase.of(
                finishing = finishing != null,
                controlling = phone.userControlling,
                paused = phone.paused,
            )

    // 聊天记录在回答时每秒变二十几次，这两句只在结果真的变了时才让胶囊重组
    private val stepState = derivedStateOf { currentStep() }
    private val thoughtState = derivedStateOf { currentThought() }

    override val step: String
        get() = stepState.value.text

    /** [step] 和它的类别（实时通知的状态栏小胶囊据此写一个词）。 */
    val stepInfo: Step
        get() = stepState.value

    override val thought: String
        get() = thoughtState.value

    override val screen: VirtualScreen?
        get() = phone.agentDisplay

    override val appName: String?
        get() = phone.agentDisplayApp

    override val actingOnMain: Boolean
        get() = phone.actingOnMain

    override val userControlling: Boolean
        get() = phone.userControlling

    override val touches: SharedFlow<TouchMark>
        get() = phone.touches

    override fun takeOver() = phone.takeOverByUser()

    override fun resumeAuto() = phone.resumeByUser()

    override fun pause() = phone.pauseByUser()

    override fun resume() = phone.resumeByUser()

    /** 和聊天页的停止键一样；闸门（暂停、接管）在 interrupt 里一并放开，一轮已经结束时也放开。 */
    override fun stop() = conversation.interrupt()

    /** 回答进行中发出的消息由 Claude Code 排队，在下一个工具调用的间隙并进这一轮；已经结束就另起一轮。 */
    override fun supplement(text: String) {
        conversation.send(text)
        // 暂停或接管着的话，手机工具调用卡在闸门前，等不到下一个间隙：让它作废返回，补充随即交给 Claude。
        // 先发消息再放开：两者走同一条有序的通道，Claude Code 先排上这句话、再收到工具结果
        if (conversation.busy && (phone.paused || phone.userControlling))
            phone.cancelWaiting(PhoneTools.SUPPLEMENTED)
    }

    override fun userTap(x: Float, y: Float, longPress: Boolean) = phone.userTap(x, y, longPress)

    override fun userSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Int,
    ) = phone.userSwipe(startX, startY, endX, endY, durationMs)

    override fun handoffToMain() = phone.handoffToMainByUser()

    /**
     * 有 MIUI 的“后台弹出界面”权限（或 Zeta 本来就在前台）就直接用 PendingIntent；确定没有就直接经 root 打开，
     * 不先试一次、等它被悄悄拦下；读不到这项权限时才先试 PendingIntent，等一会儿还没到前台再经 root。
     */
    override fun openInZeta() {
        val key = conversation.openKey
        val display = hub.settings.phoneDisplay
        val context = hub.context
        val route =
            OpenRoute.choose(
                hub.foreground,
                BackgroundStart.allowed(context),
                root = hub.environment.setup != SetupState.NoRoot,
            )
        if (route != OpenRoute.Root) MainActivity.openConversation(context, key, display)
        if (route == OpenRoute.PendingIntent) return
        thread(name = "zeta-open", isDaemon = true) {
            if (route == OpenRoute.PendingIntentThenRoot) {
                Thread.sleep(OPEN_FALLBACK_MS)
                if (hub.foreground) return@thread
            }
            MainActivity.openConversationAsRoot(context, key, display)
        }
    }

    /** 胶囊上的一句当前步骤。优先级：结束 > 接管 / 暂停 > 等确认 > 重试、压缩 > 聊天记录里正在进行的那一步。 */
    private fun currentStep(): Step {
        finishing?.let {
            return Step(it, StepKind.FINISHED)
        }
        if (phone.userControlling) return Step("你在接管", StepKind.CONTROLLING)
        if (phone.paused) return Step("已暂停", StepKind.PAUSED)
        conversation.pending?.let {
            return AlertNotice.waitingStep(it)
        }
        conversation.status?.let {
            return Step(it, StepKind.STATUS)
        }
        return when (val last = conversation.items.lastOrNull()) {
            is AnswerItem -> if (last.streaming) Step("正在回答", StepKind.ANSWERING) else THINKING
            is WorkItem -> {
                // 一起发出的几个手机操作排着队一个个做：说正在做的那一个，而不是排在最后的
                val running = last.steps.filter { it.running }
                val call = phone.currentCall
                val active =
                    call?.let { c -> running.lastOrNull { it is ToolStep && isCurrent(it, c) } }
                        ?: running.lastOrNull()
                if (active is ToolStep) toolStep(active) else THINKING
            }
            // 刚发出消息、工具刚返回还没开始下一块：模型在想
            else -> THINKING
        }
    }

    /** 正在跑的工具：手机工具优先用 PhoneTools 给的说法（能说出点的是哪个控件），对不上就用聊天里的标题。 */
    private fun toolStep(step: ToolStep): Step {
        if (step.name.startsWith(PHONE_PREFIX)) {
            val call = phone.currentCall
            if (call != null && isCurrent(step, call)) return Step(call.text, StepKind.PHONE)
            return Step(
                phone.describeCall(step.name.removePrefix(PHONE_PREFIX), step.input),
                StepKind.PHONE,
            )
        }
        return Step(toolTitle(step.name, step.input), StepKind.TOOL)
    }

    private fun isCurrent(step: ToolStep, call: PhoneTools.CallInfo): Boolean =
        call.toolUseId?.let { it == step.id } ?: (PHONE_PREFIX + call.tool == step.name)

    /** 思考尾句：正在思考时最近一小段可见推理，合并空白、去掉 Markdown 记号。 */
    private fun currentThought(): String {
        if (
            finishing != null ||
                phone.userControlling ||
                phone.paused ||
                conversation.pending != null
        )
            return ""
        val work = conversation.items.lastOrNull() as? WorkItem ?: return ""
        val thinking = work.steps.lastOrNull { it.running } as? ThinkingStep ?: return ""
        return thinking.text
            .takeLast(THOUGHT_CHARS * 4)
            .replace(MARKUP, "")
            .replace(SPACES, " ")
            .trim()
            .takeLast(THOUGHT_CHARS)
            .trimStart()
    }

    private companion object {
        val THINKING = Step("正在思考", StepKind.THINKING)
        const val OPEN_FALLBACK_MS = 400L
        const val THOUGHT_CHARS = 40
        val PHONE_PREFIX = "mcp__${PhoneTools.SERVER}__"
        val SPACES = Regex("\\s+")
        val MARKUP = Regex("[*`#]+")
    }
}
