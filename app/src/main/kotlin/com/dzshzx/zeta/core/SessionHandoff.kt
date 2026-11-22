package com.dzshzx.zeta.core

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 终端里开着的 Claude Code（比如 tmux 里的 claude）占着会话时，它现在的样子（zeta-helper occupant）。 [status] 是 Claude Code
 * 登记的忙闲：idle、waiting（在等确认，[waitingFor] 是在等什么）、busy（正在跑一轮）、shell（在跑 ! 命令）；旧版不报时是 unknown。
 * [handsOver]：现在能交给 Zeta（两轮之间），由 zeta-helper 判断（handoff.go 的 handsOver）。正跑着的一轮在那边的进程里，交接会丢掉它。
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
    }
}

/** 一次接过来（zeta-helper handoff）的结果。 */
sealed interface HandoffResult {
    /** 那边退出了（或本来就没人占着），可以在 Zeta 里接着。 */
    data object Handed : HandoffResult

    /** 那边这时正忙，没动它。 */
    data class Busy(val occupant: Occupant) : HandoffResult

    /** 发了退出信号，那边过了一会儿还没退。 */
    data object Stuck : HandoffResult

    /** 没跑成（连不上等），[reason] 给人看。 */
    data class Failed(val reason: String) : HandoffResult
}

/** zeta-helper 交接命令输出的解析：纯函数，单元测试直接测。 */
internal object HandoffOutput {
    private val json = Json { ignoreUnknownKeys = true }

    private fun line(output: String): JsonObject? =
        output
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("{") }
            ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    /** occupant 的输出：没人占着是 null。解析不了的当作看不出忙闲（还占着）。 */
    fun occupant(output: String): Occupant? {
        val o = line(output) ?: return Occupant(Occupant.UNKNOWN)
        val status = o.str("status") ?: return null
        return Occupant(status, o.str("waiting_for"), handsOver = o.bool("hands_over") != false)
    }

    fun handoff(output: String): HandoffResult {
        val o = line(output) ?: return HandoffResult.Failed("zeta-helper 的输出看不懂")
        return when {
            o.bool("handed") == true -> HandoffResult.Handed
            o.str("status") == "stuck" -> HandoffResult.Stuck
            else ->
                HandoffResult.Busy(Occupant(o.str("status") ?: Occupant.UNKNOWN, handsOver = false))
        }
    }
}

/**
 * 一个对话的会话正被终端里的 Claude Code 占着时，聊天页「别处运行」卡片背后的状态：那边闲不闲（[occupant]，卡片开着时定时 [refresh]），
 * 和把会话接过来（[handOver]：那边闲着时让它照 /exit 那样退出）。那边不再占着了（自己退出或接过来了）就调 [onFreed]。 在主线程上调用，跑命令在后台线程。
 */
class SessionHandoff(
    private val hub: ConversationHub,
    private val backend: String,
    private val sessionId: () -> String?,
    private val onFreed: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

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

    /** 查一次那边的样子。 */
    fun refresh() {
        val id = sessionId() ?: return
        if (refreshing || handing) return
        refreshing = true
        thread(name = "zeta-occupant", isDaemon = true) {
            val result = hub.helper("occupant", listOf(id), 15_000, backend)
            main.post {
                refreshing = false
                val output = result.outputOrNull ?: return@post
                val now = HandoffOutput.occupant(output)
                if (now == null) onFreed() else occupant = now
            }
        }
    }

    /** 接过来；没成的原因记在 [problem]，那边正忙时 [occupant] 也跟着更新。 */
    fun handOver() {
        val id = sessionId() ?: return
        if (handing) return
        handing = true
        problem = null
        thread(name = "zeta-handoff", isDaemon = true) {
            val result =
                when (val run = hub.helper("handoff", listOf(id), 40_000, backend)) {
                    is HelperResult.Ok -> HandoffOutput.handoff(run.output)
                    is HelperResult.Failed -> HandoffResult.Failed(run.reason)
                }
            main.post {
                handing = false
                when (result) {
                    HandoffResult.Handed -> {
                        occupant = null
                        onFreed()
                    }
                    is HandoffResult.Busy -> occupant = result.occupant
                    HandoffResult.Stuck -> problem = "对方的 Claude Code 没有退出。如需强制结束，请使用右上角菜单中的「结束对话」。"
                    is HandoffResult.Failed -> problem = "接管失败：${result.reason}"
                }
            }
        }
    }
}

/**
 * 远程主机上「Zeta」tmux 会话里的窗口（zeta-helper desk-open、desk-close）：Zeta 起的对话每个一个窗口，回到电脑前 `tmux attach -t
 * zeta` 回车就在终端里接着。手机本机不开：人就在手机上。没有 tmux 的主机 desk-open 什么也不做。都在后台跑，不等结果。
 */
internal object DeskWindows {
    /** 开窗口（已经有了就不动）。窗口名由窗口里的程序按会话记录里的标题改。 */
    fun open(hub: ConversationHub, backend: String, sessionId: String, cwd: String) =
        fire(hub, backend, "desk-open", listOf(sessionId, cwd))

    /** 关掉这些会话的窗口（删除、归档时）。 */
    fun close(hub: ConversationHub, backend: String, sessionIds: Collection<String>) {
        if (sessionIds.isNotEmpty()) fire(hub, backend, "desk-close", sessionIds.toList())
    }

    private fun fire(
        hub: ConversationHub,
        backend: String,
        command: String,
        arguments: List<String>,
    ) {
        if (backend == Backends.LOCAL) return
        thread(name = "zeta-desk", isDaemon = true) {
            hub.helper(command, arguments, 15_000, backend)
        }
    }
}
