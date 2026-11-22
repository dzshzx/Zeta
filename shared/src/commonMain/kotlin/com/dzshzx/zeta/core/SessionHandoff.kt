package com.dzshzx.zeta.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.conv.CoreConversation
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Zeta 之外占着会话的 Claude Code（终端里的，或另一个包托管的）现在的样子：核心 conv.occupant 的 result（docs/core.md「对话」）。
 * [status] 是 Claude Code 登记的忙闲：idle、waiting（在等确认，[waitingFor] 是在等什么）、busy（正在跑一轮）、shell（在跑 !
 * 命令）；旧版不报时是 unknown。 [handsOver]：现在能交给 Zeta（两轮之间），由核心判断。正跑着的一轮在那边的进程里，交接会丢掉它。
 */
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

        /** conv.occupant 的 result：`status` 为 null 是没人占着；看不懂的当作还占着、看不出忙闲（没人占着时卡片会去接管）。 */
        fun of(result: JsonElement?): Occupant? {
            val o = result as? JsonObject ?: return Occupant(UNKNOWN)
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
    data class Busy(val occupant: Occupant) : HandoffResult

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
                else -> Busy(Occupant(o.str("status") ?: Occupant.UNKNOWN, handsOver = false))
            }
        }

        private const val STUCK = "stuck"
    }
}

/**
 * 对话的会话正被 Zeta 之外的 Claude Code 占着（核心的 conv.state 里 blocked
 * 为真）时，聊天页「别处运行」卡片背后的状态：那边闲不闲（[occupant]，卡片开着时定时 [refresh]，经 conv.occupant），和把会话接过来（[handOver]，经
 * conv.handoff：那边闲着时让它照 /exit 那样退出）。 接过来以后核心解开 blocked 并推 conv.state，卡片随之收起；那边自己退出了（查到没人占着）也经
 * conv.handoff 让核心解开。 [core] 是对话现在打开着的核心对话。在主线程上调用，请求在后台线程上发。
 */
class SessionHandoff(private val core: () -> CoreConversation?) {
    private val main = MainHandler()

    /** 最近一次查到的占用；还没查过是 null。 */
    var occupant by mutableStateOf<Occupant?>(null)
        private set

    /** 正在接过来。 */
    var handing by mutableStateOf(false)
        private set

    /** 上一次没接过来的原因，给卡片显示；接成或重新查到状态时清掉。 */
    var problem by mutableStateOf<String?>(null)
        private set

    private var refreshing = false

    /** 查一次那边的样子；没人占着了就请核心解开。 */
    fun refresh() {
        val target = core() ?: return
        if (refreshing || handing) return
        refreshing = true
        thread(name = "zeta-occupant", isDaemon = true) {
            val result = runCatching { target.occupant() }
            main.post {
                refreshing = false
                result.onSuccess { now -> if (now == null) handOver() else occupant = now }
            }
        }
    }

    /** 接过来；没成的原因记在 [problem]，那边正忙时 [occupant] 也跟着更新。 */
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
                when (result) {
                    HandoffResult.Handed -> occupant = null
                    is HandoffResult.Busy -> occupant = result.occupant
                    HandoffResult.Stuck -> problem = "对方的 Claude Code 没有退出。如需强制结束，请使用右上角菜单中的「结束对话」。"
                    is HandoffResult.Failed -> problem = "接管失败：${result.reason}"
                }
            }
        }
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
