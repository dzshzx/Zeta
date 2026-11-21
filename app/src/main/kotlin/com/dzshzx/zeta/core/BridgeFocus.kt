package com.dzshzx.zeta.core

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.phone.OverlayFocus
import com.dzshzx.zeta.phone.OverlayPhase
import com.dzshzx.zeta.phone.PhoneTools
import com.dzshzx.zeta.phone.TouchMark
import com.dzshzx.zeta.phone.VirtualScreen
import kotlinx.coroutines.flow.SharedFlow

/**
 * 顶部状态胶囊眼里的一个外部客户端（经 [com.dzshzx.zeta.phone.PhoneBridge] 用手机工具的工作站上的 Claude Code）。
 *
 * 外部客户端没有“一轮”：工作站上一次调用连一次，调用之间是那边模型在想。所以有工具调用在处理时显示，最后一个调用结束后再停留 [LINGER_MS]
 * （调用之间的空档里胶囊不一闪一闪），之后收起；暂停、接管着时一直显示（不然下一个调用卡在闸门前，用户看不到）。
 * 状态取自这个客户端的手机工具和调用的起止，主线程上可观察。随客户端第一次连上时在主线程上建好（同 [ConversationFocus]）。
 *
 * 它不碰手机工具的“一轮结束”（[PhoneTools.onTurnEnded] 会复位闸门）：同一个客户端随时还会再来调用。
 */
@Stable
internal class BridgeFocus(
    val key: String,
    private val phone: PhoneTools,
    private val main: Handler = Handler(Looper.getMainLooper()),
) : OverlayFocus {
    /** 正在处理的工具调用数。 */
    private var calls by mutableIntStateOf(0)

    /** 最后一个调用结束后还在停留。 */
    private var lingering by mutableStateOf(false)

    /** 最近一个调用开始的时间（墙上时钟），和对话一起在用手机时据此挑最近的那个。 */
    var usedAt by mutableLongStateOf(0L)
        private set

    private val endLinger = Runnable { if (calls == 0) lingering = false }

    /** 胶囊要不要显示它。 */
    val shown: Boolean
        get() = calls > 0 || lingering || phone.paused || phone.userControlling

    /** 一个工具调用开始了。在主线程上调用。 */
    fun callStarted() {
        calls++
        usedAt = System.currentTimeMillis()
    }

    /** 一个工具调用结束了：最后一个结束时开始停留，停留期间又有调用就接着显示，重新计时。在主线程上调用。 */
    fun callEnded() {
        if (calls > 0) calls--
        if (calls > 0) return
        lingering = true
        main.removeCallbacks(endLinger)
        main.postDelayed(endLinger, LINGER_MS)
    }

    override val title: String
        get() = TITLE

    override val phase: OverlayPhase
        get() =
            OverlayPhase.of(
                finishing = false,
                controlling = phone.userControlling,
                paused = phone.paused,
            )

    /** 调用进行中说正在做的那一步（[PhoneTools.currentCall]，和对话的说法一样），调用之间说在等下一步。 */
    override val step: String
        get() =
            when {
                phone.userControlling -> "你在接管"
                phone.paused -> "已暂停"
                calls > 0 -> phone.currentCall?.text ?: "操作手机"
                else -> "等待下一步"
            }

    /** 第二行：是谁在操作，有后台屏幕时带上它上面的应用（“电脑在操作 · 设置”）。 */
    override val thought: String
        get() = TITLE + (appName?.let { " · $it" } ?: "")

    override val screen: VirtualScreen?
        get() = phone.agentDisplay

    override val appName: String?
        get() = phone.agentDisplayApp?.takeIf { screen != null && it.isNotBlank() }

    override val actingOnMain: Boolean
        get() = phone.actingOnMain

    override val userControlling: Boolean
        get() = phone.userControlling

    override val touches: SharedFlow<TouchMark>
        get() = phone.touches

    /** 外部客户端不在 Zeta 里：没有对话可补充一句话、可打开，胶囊上不放这两样。 */
    override val inZeta: Boolean
        get() = false

    override fun takeOver() = phone.takeOverByUser()

    override fun resumeAuto() = phone.resumeByUser()

    override fun pause() = phone.pauseByUser()

    override fun resume() = phone.resumeByUser()

    /** 停止：在闸门前等着的调用作废返回，暂停、接管解除，胶囊随即收起（正在处理的调用照常做完）；客户端再来调用时再显示。 */
    override fun stop() {
        phone.cancelWaiting(STOPPED)
        main.removeCallbacks(endLinger)
        lingering = false
    }

    override fun supplement(text: String) = Unit

    override fun userTap(x: Float, y: Float, longPress: Boolean) = phone.userTap(x, y, longPress)

    override fun userSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Int,
    ) = phone.userSwipe(startX, startY, endX, endY, durationMs)

    override fun handoffToMain() = phone.handoffToMainByUser()

    override fun openInZeta() = Unit

    companion object {
        /** 最后一个调用结束后胶囊再停留多久：工作站上模型想下一步通常要十几秒。 */
        const val LINGER_MS = 30_000L

        const val TITLE = "电脑在操作"

        /** 用户在胶囊上点了停止时交给外部客户端的原因。 */
        const val STOPPED = "用户在手机上点了停止，这次手机操作没有执行：先别再操作手机，问用户要不要继续"
    }
}
