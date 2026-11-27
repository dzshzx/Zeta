package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import com.dzshzx.zeta.i18n.Strings
import java.util.Locale

/** 一次或几次模型请求的 token 数（helper/usage/tokens.go 的 Tokens）；[total] 是核心给的四项之和。 */
@Immutable
data class TokenCounts(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val total: Long = 0,
)

/**
 * 一轮（用户发一次、到 result 为止）的用量。[costUsd] 是按 API 标价折算的费用，算不出来时为 null；[costEstimated] 时它不是 Claude Code
 * 报的，是核心按 token 数和价目表估的：从会话记录恢复的含子代理；重新打开对话、接上还在跑的进程后的第一轮只有主对话的。
 */
@Immutable
data class TurnUsage(
    val tokens: TokenCounts,
    val costUsd: Double?,
    val durationMs: Long? = null,
    val costEstimated: Boolean = false,
)

enum class ContextLevel {
    Normal,
    Warning,
    Critical,
}

/** 界面要显示的一个对话的用量（conv.state 的 usage，helper/usage/snapshot.go 的 Usage）。各项都是核心算好的，这里照着显示。 */
@Immutable
data class ChatUsage(
    /** 主对话最近一次请求实测的上下文占用；还没有实测或刚压缩完时是 Claude Code 的估算（见 [contextEstimated]）。 */
    val contextTokens: Long? = null,
    val contextEstimated: Boolean = false,
    /** 上下文窗口。 */
    val contextWindow: Long? = null,
    /** 主对话实际用的模型（如 claude-opus-5-5）。 */
    val model: String? = null,
    val turns: List<TurnUsage> = emptyList(),
    val compactions: Int = 0,
    /** Claude Code 报告的、当前实际生效的思考强度。 */
    val appliedEffort: String? = null,
    /** 这个进程开没开自动压缩；还没问到是 null。 */
    val autoCompact: Boolean? = null,
    /** 自动压缩会在上下文到多少 token 时触发；关着或还不知道时是 null。 */
    val compactAt: Long? = null,
    /** 各轮 token 之和。 */
    val total: TokenCounts = TokenCounts(),
    /** 已知费用之和；一轮都算不出来时为 null。 */
    val totalCost: Double? = null,
    /** 有些轮次连估都估不出费用（模型不在价目表里）。 */
    val costPartial: Boolean = false,
    /** 有些轮次（从会话记录恢复的、接上后台进程后的第一轮）的费用是估的。 */
    val costEstimated: Boolean = false,
    /** 上下文占窗口的比例（0..1）；不知道时是 null。 */
    val fraction: Float? = null,
    /** 压缩点在窗口里的位置（0..1）；不知道压缩点时是 null。 */
    val compactFraction: Float? = null,
    /** 快满时提醒的档位。 */
    val level: ContextLevel = ContextLevel.Normal,
)

/**
 * Claude Code 支持的思考强度（`--effort`、`apply_flag_settings` 的 effortLevel）；null 表示不指定，沿用 Claude Code
 * 自己的设置。
 */
val EffortLevels: List<Pair<String, String>>
    get() =
        listOf(
            "low" to Strings.coreEffortLow(),
            "medium" to Strings.coreEffortMedium(),
            "high" to Strings.coreEffortHigh(),
            "xhigh" to Strings.coreEffortXHigh(),
            "max" to Strings.coreEffortMax(),
        )

fun effortLabel(effort: String?): String? = effort?.let { value ->
    EffortLevels.firstOrNull { it.first == value }?.second ?: value
}

/** 1234 → 1.2K，26618 → 26.6K，123456 → 123K，1234567 → 1.23M。十万以内留一位小数，压缩前后差几百也看得出来。 */
fun formatTokens(value: Long): String {
    val v = value.coerceAtLeast(0)
    return when {
        v >= 999_500 -> trimZeros(String.format(Locale.US, "%.2f", v / 1_000_000.0)) + "M"
        v >= 99_950 -> trimZeros(String.format(Locale.US, "%.0f", v / 1_000.0)) + "K"
        v >= 1_000 -> trimZeros(String.format(Locale.US, "%.1f", v / 1_000.0)) + "K"
        else -> v.toString()
    }
}

private fun trimZeros(text: String): String =
    if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text

/** 精确数，千位分隔。 */
fun formatExactTokens(value: Long): String = String.format(Locale.US, "%,d", value.coerceAtLeast(0))

fun formatCost(usd: Double): String =
    when {
        usd <= 0.0 -> "$0"
        usd < 1.0 -> "$" + String.format(Locale.US, "%.4f", usd)
        else -> "$" + String.format(Locale.US, "%.2f", usd)
    }
