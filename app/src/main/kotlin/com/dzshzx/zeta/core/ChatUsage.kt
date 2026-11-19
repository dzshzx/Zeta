package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import java.util.Locale
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** 一次或几次模型请求的 token 数。 */
@Immutable
data class TokenCounts(val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0) {
    val total: Long get() = input + output + cacheRead + cacheWrite

    operator fun plus(other: TokenCounts) =
        TokenCounts(input + other.input, output + other.output, cacheRead + other.cacheRead, cacheWrite + other.cacheWrite)

    /** 每一项都不小于 [other]：累计数只会涨，变小说明计数从头开始了。 */
    fun covers(other: TokenCounts): Boolean =
        input >= other.input && output >= other.output && cacheRead >= other.cacheRead && cacheWrite >= other.cacheWrite

    operator fun minus(other: TokenCounts) = TokenCounts(
        (input - other.input).coerceAtLeast(0),
        (output - other.output).coerceAtLeast(0),
        (cacheRead - other.cacheRead).coerceAtLeast(0),
        (cacheWrite - other.cacheWrite).coerceAtLeast(0),
    )

    companion object {
        val Zero = TokenCounts()

        /** API 的 usage 形状（assistant 消息、stream_event、result 行都是它）；四项都没有时返回 null。 */
        fun ofApi(usage: JsonObject?): TokenCounts? {
            usage ?: return null
            val input = usage.long("input_tokens")
            val output = usage.long("output_tokens")
            val read = usage.long("cache_read_input_tokens")
            val write = usage.long("cache_creation_input_tokens")
            if (input == null && output == null && read == null && write == null) return null
            return TokenCounts(input ?: 0, output ?: 0, read ?: 0, write ?: 0)
        }

        /** result 行的 modelUsage：这个进程启动以来各模型的累计，加在一起。 */
        fun ofModelUsage(modelUsage: JsonObject?): TokenCounts? {
            val models = modelUsage?.values?.filterIsInstance<JsonObject>()?.takeIf { it.isNotEmpty() } ?: return null
            return models.fold(Zero) { sum, m ->
                sum + TokenCounts(
                    m.long("inputTokens") ?: 0,
                    m.long("outputTokens") ?: 0,
                    m.long("cacheReadInputTokens") ?: 0,
                    m.long("cacheCreationInputTokens") ?: 0,
                )
            }
        }
    }
}

/** 一轮（用户发一次、到 result 为止）的用量。[costUsd] 是按 API 标价折算的费用，算不出来时为 null。 */
@Immutable
data class TurnUsage(val tokens: TokenCounts, val costUsd: Double?, val durationMs: Long? = null)

enum class ContextLevel { Normal, Warning, Critical }

/** 界面要显示的一个对话的用量。 */
@Immutable
data class ChatUsage(
    /** 主对话最近一次请求实测的上下文占用；还没有实测或刚压缩完时是 Claude Code 的估算（见 [contextEstimated]）。 */
    val contextTokens: Long? = null,
    val contextEstimated: Boolean = false,
    /** 上下文窗口：优先用 Claude Code 自己算的（会按 autoCompactWindow 设置缩小），其次是模型本身的窗口。 */
    val contextWindow: Long? = null,
    /** 主对话实际用的模型（如 claude-opus-5-5）。 */
    val model: String? = null,
    val turns: List<TurnUsage> = emptyList(),
    val compactions: Int = 0,
    /** Claude Code 报告的、当前实际生效的思考强度。 */
    val appliedEffort: String? = null,
) {
    val total: TokenCounts get() = turns.fold(TokenCounts.Zero) { sum, turn -> sum + turn.tokens }

    /** 已知费用之和；一轮都算不出来时为 null。 */
    val totalCost: Double? get() = turns.mapNotNull { it.costUsd }.takeIf { it.isNotEmpty() }?.sum()

    /** 有些轮次（从会话记录恢复的、接上后台进程后的第一轮）没有费用数据。 */
    val costPartial: Boolean get() = turns.any { it.costUsd == null }

    val fraction: Float?
        get() {
            val used = contextTokens ?: return null
            val window = contextWindow?.takeIf { it > 0 } ?: return null
            return (used.toDouble() / window).toFloat().coerceIn(0f, 1f)
        }

    /** 快满时提醒：Claude Code 在窗口减去约 3.3 万的缓冲处自动压缩，92% 左右就到了。 */
    val level: ContextLevel
        get() {
            val f = fraction ?: return ContextLevel.Normal
            return when {
                f >= 0.92f -> ContextLevel.Critical
                f >= 0.75f -> ContextLevel.Warning
                else -> ContextLevel.Normal
            }
        }

    val known: Boolean get() = contextTokens != null || turns.isNotEmpty()
}

/**
 * 从 Claude Code 的 stream-json 事件（以及 zeta_helper.py history 给出的会话记录）累计一个对话的用量。纯逻辑，不依赖 Android。
 *
 * - 上下文占用：主对话最近一次请求的 usage 四项之和（提示词含缓存读写，加上输出）。这正是 Claude Code 判断自动压缩用的数，
 *   压缩前的 pre_tokens 与它一致。压缩后的 post_tokens 只算消息、不含系统提示和工具定义（实测压缩后下一次请求比它多两万多），
 *   所以压缩后先显示它，再用 get_context_usage 的估算（含这些固定开销）替换，下一次请求的实测数到了再以实测为准。
 * - 每轮用量：result 行的 modelUsage 和 total_cost_usd 是这个进程启动以来的累计（含子代理和压缩请求），相邻两个 result 相减就是这一轮。
 *   全新会话的进程从零开始累计；用 `--resume` 续上旧会话时 Claude Code 会把上次进程的累计带过来，接上后台进程时也不知道之前读到哪，
 *   这两种情况第一轮的基线未知：token 改用 result.usage（只含这一轮），费用只在累计与这一轮完全对得上时才算。
 * - 会话记录里没有费用，从记录恢复的轮次只有 token。
 */
class UsageTracker {
    private var contextTokens: Long? = null

    /** [contextTokens] 不是请求实测的（还没有、或是压缩后的数），Claude Code 的估算到了就换上。 */
    private var estimated = true
    private var claudeWindow: Long? = null
    private var modelWindow: Long? = null
    private var model: String? = null
    private val turns = ArrayList<TurnUsage>()
    private var compactions = 0
    private var appliedEffort: String? = null
    private var baseTokens: TokenCounts? = null
    private var baseCost: Double? = null

    /** 从会话记录读到的、还没结束的一轮：消息 id → 这条消息的 usage（一条消息按内容块拆成几行，每行都带同一份 usage）。 */
    private val historyTurn = LinkedHashMap<String, TokenCounts>()

    /** 每次有变化加一，调用方据此决定要不要刷新界面。 */
    var version = 0
        private set

    /** Claude Code 告诉我们的窗口（模型 → 窗口），调用方记下来，给还没起进程的旧会话用。 */
    var learnedWindow: Pair<String, Long>? = null
        private set

    private fun changed(): Boolean {
        version++
        return true
    }

    /** 起了一个新进程。[fresh] 表示全新会话（没带 --resume），它的累计从零开始。 */
    fun processStarted(fresh: Boolean) {
        baseTokens = if (fresh) TokenCounts.Zero else null
        baseCost = if (fresh) 0.0 else null
    }

    /** 一条实时事件；返回用量有没有变化。 */
    fun observe(event: JsonObject): Boolean {
        val main = event["parent_tool_use_id"].let { it == null || it is JsonNull }
        return when (event.str("type")) {
            "stream_event" -> main && observeStream(event.obj("event"))
            "assistant" -> main && observeMessage(event.obj("message"))
            "result" -> observeResult(event)
            "system" -> event.str("subtype") == "compact_boundary" && compacted(event.obj("compact_metadata")?.long("post_tokens"))
            // /clear 换了新会话，之前的上下文都不在了；Claude Code 的累计也可能跟着清零，下一轮的基线算未知
            "conversation_reset" -> {
                contextTokens = null
                estimated = true
                baseTokens = null
                baseCost = null
                changed()
            }
            "control_response" -> observeControl(event.obj("response")?.obj("response"))
            else -> false
        }
    }

    private fun observeStream(event: JsonObject?): Boolean = when (event?.str("type")) {
        "message_start" -> observeMessage(event.obj("message"))
        // 消息结束时的 usage 是最终数（含完整的输出）
        "message_delta" -> TokenCounts.ofApi(event.obj("usage"))?.let { setContext(it.total) } ?: false
        else -> false
    }

    private fun observeMessage(message: JsonObject?): Boolean {
        message ?: return false
        message.str("model")?.let { model = it }
        return TokenCounts.ofApi(message.obj("usage"))?.let { setContext(it.total) } ?: false
    }

    private fun setContext(tokens: Long): Boolean {
        if (tokens <= 0) return false
        estimated = false
        if (tokens == contextTokens) return false
        contextTokens = tokens
        return changed()
    }

    private fun compacted(postTokens: Long?): Boolean {
        compactions++
        contextTokens = postTokens
        estimated = true
        return changed()
    }

    private fun observeResult(event: JsonObject): Boolean {
        val modelUsage = event.obj("modelUsage")
        val cumulative = TokenCounts.ofModelUsage(modelUsage)
        val cost = event.double("total_cost_usd")
        val own = TokenCounts.ofApi(event.obj("usage")) ?: TokenCounts.Zero
        val base = baseTokens?.takeIf { cumulative != null && cumulative.covers(it) }
        val tokens = if (cumulative != null && base != null) cumulative - base else own
        val turnCost = when {
            cost == null -> null
            base != null -> baseCost?.takeIf { cost >= it }?.let { cost - it }
            cumulative == own -> cost
            else -> null
        }
        if (cumulative != null) baseTokens = cumulative
        if (cost != null) baseCost = cost
        windowOf(modelUsage)?.let { modelWindow = it }
        // 斜杠命令（/context 之类）也会出一个 result，但没有请求模型
        if (tokens.total > 0 || (turnCost ?: 0.0) > 0.0) turns.add(TurnUsage(tokens, turnCost, event.long("duration_ms")))
        return changed()
    }

    private fun windowOf(modelUsage: JsonObject?): Long? {
        modelUsage ?: return null
        model?.let { name -> modelUsage.obj(name)?.long("contextWindow")?.let { return it } }
        return modelUsage.values.mapNotNull { (it as? JsonObject)?.long("contextWindow") }.maxOrNull()
    }

    /** get_context_usage 和 get_settings 的应答。 */
    private fun observeControl(response: JsonObject?): Boolean {
        response ?: return false
        var any = false
        response.long("maxTokens")?.takeIf { it > 0 }?.let { window ->
            claudeWindow = window
            response.str("model")?.let { name ->
                model = name
                learnedWindow = name to window
            }
            // 还没有实测数时（刚起的新会话、刚压缩完）用 Claude Code 的估算，它含系统提示、工具定义这些固定开销
            if (estimated) response.long("totalTokens")?.takeIf { it > 0 }?.let { contextTokens = it }
            any = true
        }
        response.obj("applied")?.let { applied ->
            val effort = applied.str("effort")
            if (effort != appliedEffort) {
                appliedEffort = effort
                any = true
            }
        }
        return any && changed()
    }

    /** 会话记录里的一条（形状同 stream-json）。读完后调 [finishHistory]。 */
    fun observeHistory(event: JsonObject) {
        when (event.str("type")) {
            // 用户亲手发的消息（不是工具结果）开始新的一轮
            "user" -> if (event.obj("message")?.arr("content").orEmpty().any { (it as? JsonObject)?.str("type") in setOf("text", "image") }) {
                flushHistoryTurn()
            }
            "assistant" -> {
                val message = event.obj("message") ?: return
                message.str("model")?.let { model = it }
                val usage = TokenCounts.ofApi(message.obj("usage")) ?: return
                historyTurn[message.str("id") ?: "m${historyTurn.size}"] = usage
                if (usage.total > 0) {
                    contextTokens = usage.total
                    estimated = false
                }
            }
            "system" -> if (event.str("subtype") == "compact_boundary") {
                compactions++
                contextTokens = event.obj("compact_metadata")?.long("post_tokens")
                estimated = true
            }
        }
    }

    fun finishHistory() {
        flushHistoryTurn()
        changed()
    }

    private fun flushHistoryTurn() {
        if (historyTurn.isEmpty()) return
        val tokens = historyTurn.values.fold(TokenCounts.Zero) { sum, it -> sum + it }
        historyTurn.clear()
        if (tokens.total > 0) turns.add(TurnUsage(tokens, costUsd = null))
    }

    /** 把载入的历史接到前面：历史的轮次在前；上下文占用、模型以已经收到的实时数据为准。 */
    fun prependHistory(history: UsageTracker) {
        turns.addAll(0, history.turns)
        if (contextTokens == null) {
            contextTokens = history.contextTokens
            estimated = history.estimated
        }
        if (model == null) model = history.model
        compactions += history.compactions
        changed()
    }

    /** [knownWindows] 是以前从 Claude Code 得知的各模型窗口，这个对话自己还没拿到窗口时用。 */
    fun snapshot(knownWindows: Map<String, Long> = emptyMap()): ChatUsage = ChatUsage(
        contextTokens = contextTokens,
        contextEstimated = estimated && contextTokens != null,
        contextWindow = claudeWindow ?: model?.let { knownWindows[it] } ?: modelWindow,
        model = model,
        turns = turns.toList(),
        compactions = compactions,
        appliedEffort = appliedEffort,
    )
}

/**
 * compact_boundary 在聊天里显示的提示。只写压缩前的大小：它是实测数；post_tokens 不含系统提示和工具，
 * 写成「→ 7.6K」会让人以为上下文只剩这么多。压缩后的占用看用量弹窗。
 */
internal fun compactNotice(event: JsonObject): String {
    val metadata = event.obj("compact_metadata")
    val label = if (metadata?.str("trigger") == "auto") "上下文已自动压缩" else "上下文已压缩"
    return metadata?.long("pre_tokens")?.let { "$label（压缩前 ${formatTokens(it)}）" } ?: label
}

/** Claude Code 支持的思考强度（`--effort`、`apply_flag_settings` 的 effortLevel）；null 表示不指定，沿用 Claude Code 自己的设置。 */
internal val EffortLevels: List<Pair<String, String>> = listOf(
    "low" to "低",
    "medium" to "中",
    "high" to "高",
    "xhigh" to "超高",
    "max" to "最高",
)

internal fun effortLabel(effort: String?): String? = effort?.let { value -> EffortLevels.firstOrNull { it.first == value }?.second ?: value }

/** 1234 → 1.2K，26618 → 26.6K，123456 → 123K，1234567 → 1.23M。十万以内留一位小数，压缩前后差几百也看得出来。 */
internal fun formatTokens(value: Long): String {
    val v = value.coerceAtLeast(0)
    return when {
        v >= 999_500 -> trimZeros(String.format(Locale.US, "%.2f", v / 1_000_000.0)) + "M"
        v >= 99_950 -> trimZeros(String.format(Locale.US, "%.0f", v / 1_000.0)) + "K"
        v >= 1_000 -> trimZeros(String.format(Locale.US, "%.1f", v / 1_000.0)) + "K"
        else -> v.toString()
    }
}

private fun trimZeros(text: String): String = if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text

/** 精确数，千位分隔。 */
internal fun formatExactTokens(value: Long): String = String.format(Locale.US, "%,d", value.coerceAtLeast(0))

internal fun formatCost(usd: Double): String = when {
    usd <= 0.0 -> "$0"
    usd < 1.0 -> "$" + String.format(Locale.US, "%.4f", usd)
    else -> "$" + String.format(Locale.US, "%.2f", usd)
}

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
