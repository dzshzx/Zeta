package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.conv.CoreConversation
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Zeta 之外占着会话的 Claude Code（终端里的，或另一个包托管的）现在的样子：核心 conv.state 的 occupant（docs/core.md「对话」，blocked
 * 时核心自己每 3 秒查一次，变了才推）。 [status] 是 Claude Code 登记的忙闲：idle、waiting（在等确认，[waitingFor]
 * 是在等什么）、busy（正在跑一轮）、shell（在跑 ! 命令）；旧版不报时是 unknown。 [handsOver]：现在能交给
 * Zeta（两轮之间），由核心判断。正跑着的一轮在那边的进程里，交接会丢掉它。
 */
@Immutable
data class Occupant(
    val status: String,
    val waitingFor: String? = null,
    val handsOver: Boolean = true,
) {
    companion object {
        const val IDLE = "idle"
        const val WAITING = "waiting"
        const val UNKNOWN = "unknown"
        const val SHELL = "shell"

        /**
         * conv.state 里的 occupant（`{status, waiting_for, hands_over}`）：`status` 为 null 是没人占着；没有
         * `status` 的当作还占着、看不出忙闲。
         */
        fun of(o: JsonObject): Occupant? {
            if (o["status"] is JsonNull) return null
            val status = o.str("status") ?: return Occupant(UNKNOWN)
            return Occupant(status, o.str("waiting_for"), handsOver = o.bool("hands_over") != false)
        }
    }
}

/** 一次接过来（核心的 conv.handoff）的结果。 */
sealed interface HandoffResult {
    /** 那边退出了（或本来就没人占着），核心解开了 blocked。 */
    data object Handed : HandoffResult

    /** 那边这时正忙，没动它。 */
    data object Busy : HandoffResult

    /** 发了退出信号，那边过了一会儿还没退。 */
    data object Stuck : HandoffResult

    /** 没做成（连不上等），[reason] 给人看。 */
    data class Failed(val reason: String) : HandoffResult

    companion object {
        /** conv.handoff 的 result（`{handed, status}`）。 */
        fun of(result: JsonElement?): HandoffResult {
            val o = result as? JsonObject ?: return Failed("核心的应答看不懂")
            return when {
                o.bool("handed") == true -> Handed
                o.str("status") == STUCK -> Stuck
                else -> Busy
            }
        }

        private const val STUCK = "stuck"
    }
}

/**
 * 对话的会话正被 Zeta 之外的 Claude Code 占着（核心的 conv.state 里 blocked 为真）时，用户点「接管」把会话接过来（[handOver]，经
 * conv.handoff：那边闲着时让它照 /exit 那样退出）。 那边的样子由核心盯着、推在 conv.state 的 occupant
 * 里（[Conversation.occupant]）， 接过来或那边自己退出后核心解开 blocked 并推 conv.state，卡片随之收起；这里只管点了之后的进行中和没接成的原因。
 * [core] 是对话现在打开着的核心对话。 在主线程上调用，请求在后台线程上发。
 */
class SessionHandoff(private val core: () -> CoreConversation?) {
    private val main = MainHandler()

    /** 正在接过来。 */
    var handing by mutableStateOf(false)
        private set

    /** 上一次没接过来的原因，给卡片显示；再点接管、或核心推来的占用变了（[occupantChanged]）时清掉。 */
    var problem by mutableStateOf<String?>(null)
        private set

    /** 接过来；没成的原因记在 [problem]。 */
    fun handOver() {
        val target = core() ?: return
        if (handing) return
        handing = true
        problem = null
        thread(name = "zeta-handoff", isDaemon = true) {
            val result =
                try {
                    target.handoff()
                } catch (e: Exception) {
                    HandoffResult.Failed(e.message ?: "没有做成")
                }
            main.post {
                handing = false
                problem =
                    when (result) {
                        HandoffResult.Handed -> null
                        HandoffResult.Busy -> "对方的 Claude Code 正忙，本轮结束后可以接管。"
                        HandoffResult.Stuck -> "对方的 Claude Code 没有退出。如需强制结束，请使用右上角菜单中的「结束对话」。"
                        is HandoffResult.Failed -> "接管失败：${result.reason}"
                    }
            }
        }
    }

    /** 核心推来的占用变了（那边换了状态、退出了）：上次没接成的原因不再作数。 */
    fun occupantChanged() {
        problem = null
    }
}

/**
 * 远程主机上「Zeta」tmux 会话里的窗口：Zeta 跑过的对话每个一个窗口，回到电脑前 `tmux attach -t zeta` 回车就在终端里接着。 开窗口由核心做（对话选项
 * desk）；这里只在删除、归档会话时关掉它们的窗口（zeta-helper desk-close），在后台跑，不等结果。
 */
object DeskWindows {
    /** 关掉这些会话的窗口（删除、归档时）。 */
    fun close(hub: ConversationHub, backend: String, sessionIds: Collection<String>) {
        if (sessionIds.isNotEmpty()) fire(hub, backend, "desk-close", sessionIds.toList())
    }

    /** 在主机 backend 上跑 helper 子命令 command：平时在后台线程上跑、不等结果；单元测试换成记下发了什么。 */
    var run:
        (hub: ConversationHub, backend: String, command: String, arguments: List<String>) -> Unit =
        { hub, backend, command, arguments ->
            thread(name = "zeta-desk", isDaemon = true) {
                hub.helper(command, arguments, 15_000, backend)
            }
        }

    private fun fire(
        hub: ConversationHub,
        backend: String,
        command: String,
        arguments: List<String>,
    ) {
        if (backend != Backends.LOCAL) run(hub, backend, command, arguments)
    }
}
