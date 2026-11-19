package com.dzshzx.zeta.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * 长对话往上翻时载入更早的记录。打开对话时 zeta_helper.py history 只给最近的一段，往上翻到顶附近时再要那之前的一页，接到列表前面。
 * 助手每次都从头读整份记录（照剪切记号去掉被剪的部分），按条数切页；剪切只去掉界面已经载入的某条用户消息之后的内容，
 * 新内容只接在后面，所以还没载入的那段不会变。要页时报上 [EarlierHistory.anchor] 给助手核对，对不上（比如在终端里剪到了更早的地方）
 * 它就不给（stale），界面让用户重新打开对话。
 *
 * 这里是不碰 Android 的部分：读助手的输出、一页能不能接上去。
 */

/** 会话记录 [file] 里还没载入的前 [skipped] 条。[anchor] 是第 skipped-1 条出自记录里的哪一行（uuid），要下一页时给助手核对。 */
data class EarlierHistory(val file: String, val skipped: Int, val anchor: String?)

/** 往上翻载入更早记录的状态。 */
sealed interface EarlierState {
    data object Idle : EarlierState

    data object Loading : EarlierState

    /** 没读到。[retry] 为 false 时重试也没用（记录在已载入的那段之前被改过，要重新打开对话）。 */
    data class Failed(val message: String, val retry: Boolean) : EarlierState
}

/**
 * 读出来的一段历史：条目已经归并进调用方给的 [TranscriptReducer]。[earlier] 是这段之前还剩的部分（读完了是 null），
 * [todoSeed] 是那部分里的清单工具调用，[usage] 是这段的用量，[itemAt] 是第几条消息变成了哪一项（只记用户消息和回答），
 * 查找跳到更早的命中、载入后要据此找到那一项。[stale] 表示助手核对窗口没对上，什么都没给。
 * [model]、[effort] 是整个对话主线上最后一次真实回答用的模型和思考强度（助手按整份记录算，不只是这一段），重新打开对话时照它恢复。
 */
class HistoryRead(
    val earlier: EarlierHistory?,
    val todoSeed: List<ToolStep>,
    val usage: UsageTracker,
    val itemAt: Map<Int, String>,
    val stale: Boolean,
    val model: String? = null,
    val effort: String? = null,
)

/** 还没载入的那部分里有查找词的一条消息：第 [event] 条，有 [count] 处。 */
data class EarlierHits(val event: Int, val count: Int)

internal object HistoryPaging {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 把助手 history 的输出（第一行 zeta_history，之后一行一条消息）归并进 [into]。[file] 是这份记录，历史里的图片要靠它去取。
     * [keepRunningTools] 用于载入后还要接着跟读进程的情况：最后那几步其实还在跑。
     */
    fun read(stdout: String, file: String, into: TranscriptReducer, keepRunningTools: Boolean = false): HistoryRead {
        val usage = UsageTracker()
        val itemAt = HashMap<Int, String>()
        var earlier: EarlierHistory? = null
        var seed = emptyList<ToolStep>()
        var stale = false
        var model: String? = null
        var effort: String? = null
        var index = -1
        for (line in stdout.lineSequence()) {
            val event = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
            if (event.str("type") == "zeta_history") {
                stale = event.bool("stale") == true
                val skipped = event.int("skipped") ?: 0
                index = skipped
                earlier = if (skipped > 0) EarlierHistory(file, skipped, event.str("anchor")) else null
                seed = TodoBoard.seedSteps(event.arr("todo_seed"))
                model = event.str("model")
                effort = event.str("effort")
                continue
            }
            usage.observeHistory(event)
            val before = into.items.size
            when (event.str("type")) {
                "system" -> if (event.str("subtype") == "compact_boundary") into.addNotice(compactNotice(event), error = false)
                "assistant" -> event.obj("message")?.let { into.applyAssistant(it, history = true) }
                "user" -> event.obj("message")?.let {
                    into.applyUser(it, replay = true, sessionFile = file, uuid = event.str("uuid"), toolUseResult = event.obj("tool_use_result"))
                }
            }
            if (index >= 0) {
                into.items.subList(before.coerceAtMost(into.items.size), into.items.size)
                    .firstOrNull { it is UserItem || it is AnswerItem }?.let { itemAt[index] = it.id }
                index++
            }
        }
        into.finishTurn(keepRunningTools)
        usage.finishHistory()
        return HistoryRead(earlier, seed, usage, itemAt, stale, model, effort)
    }

    /** 助手 history-find 的输出；窗口没对上或读不懂时是 null。 */
    fun hits(stdout: String): List<EarlierHits>? {
        val line = stdout.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val event = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
        if (event.str("type") != "zeta_history_find" || event.bool("stale") == true) return null
        return event.arr("matches").orEmpty().mapNotNull { element ->
            val pair = element as? JsonArray ?: return@mapNotNull null
            val at = (pair.getOrNull(0) as? JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
            val count = (pair.getOrNull(1) as? JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
            EarlierHits(at, count).takeIf { at >= 0 && count > 0 }
        }
    }

    /** 要 [window] 之前的一页时给助手的参数；[through] 是这一页至少要载到的那条（查找跳过去用）。 */
    fun pageArguments(window: EarlierHistory, through: Int? = null): List<String> =
        listOf(window.file, window.skipped.toString(), window.anchor ?: "-") + listOfNotNull(through?.takeIf { it in 0 until window.skipped }?.toString())

    /** 在 [window] 里查 [query] 时给助手的参数。 */
    fun findArguments(window: EarlierHistory, query: String): List<String> =
        listOf(window.file, window.skipped.toString(), window.anchor ?: "-", query)

    /**
     * 一页读回来时能不能接到列表前面：发出请求后没有作废过（[ticket] 还是最新的），要的那段也还是现在没载入的那段。
     * 照 Eta 修过的竞态：作废了的旧请求回来时不能再覆盖列表。
     */
    fun accepts(ticket: Int, latestTicket: Int, requested: EarlierHistory, current: EarlierHistory?): Boolean =
        ticket == latestTicket && requested == current
}
