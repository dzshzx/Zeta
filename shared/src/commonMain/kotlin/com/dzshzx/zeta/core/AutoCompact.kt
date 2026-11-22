package com.dzshzx.zeta.core

import kotlin.math.floor

/**
 * Zeta 起的对话的自动压缩开关和压缩时机（上下文窗口是各台主机 settings.json 的 autoCompactWindow，在主机页改，见
 * [com.dzshzx.zeta.core.claude.ClaudeDefaults.compactWindow]）。只经环境变量交给 Zeta 起的 Claude Code 进程，不改
 * `~/.claude.json` 和 `~/.claude/settings.json`， 所以只影响 Zeta
 * 起的对话；环境变量只在进程启动时读，改了设置以后，已经在跑的进程照旧，下次起进程时生效。
 *
 * Claude Code 的做法（2.1.328 实测，见 AutoCompactContractTest）：
 * - 开关：`DISABLE_AUTO_COMPACT` 非空且不是 0/false 时关掉自动压缩，优先于 settings.json 的 `autoCompactEnabled`；手动
 *   /compact 照常可用。 这时 get_context_usage 的 `isAutoCompactEnabled` 是 false，不给
 *   `autoCompactThreshold`，`maxTokens` 回到模型本身的窗口。
 * - 阈值：有效窗口 E = 压缩窗口（settings.json 的 autoCompactWindow，没设时按模型）− min(模型最大输出, 20000)； 默认在 E − 13000
 *   处压缩，也就是 get_context_usage 报的 `autoCompactThreshold`。 `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`=p（0 < p
 *   ≤ 100）把阈值压到 min(⌊E × p/100⌋, E − 13000)，只会更早、不会更晚； 但 get_context_usage 报的
 *   `autoCompactThreshold` 不含这个百分比，所以界面上的压缩点由 [threshold] 自己算。
 *
 * 选百分比而不选 `CLAUDE_CODE_AUTO_COMPACT_WINDOW`：后者会盖过用户在 Claude Code
 * 里设的压缩窗口（/autocompact、autoCompactWindow）， 百分比则叠在那个窗口上面，两边的设置都算数。
 */
object AutoCompact {
    const val DISABLE_ENV = "DISABLE_AUTO_COMPACT"
    const val PERCENT_ENV = "CLAUDE_AUTOCOMPACT_PCT_OVERRIDE"

    /** Claude Code 在有效窗口里留给压缩摘要的余量。 */
    const val SUMMARY_BUFFER = 13_000L

    /** 设置里给的几档（有效窗口的百分比）；null 是 Claude Code 自己的默认点。 */
    val Percents: List<Int> = listOf(50, 60, 70, 80, 90)

    /** 起进程时要加的环境变量。都按默认时什么都不加，用户自己在环境里设的照常生效。 */
    fun env(enabled: Boolean, percent: Int?): Map<String, String> =
        when {
            !enabled -> mapOf(DISABLE_ENV to "1")
            percent != null && percent in 1..100 -> mapOf(PERCENT_ENV to percent.toString())
            else -> emptyMap()
        }

    /**
     * 实际的压缩点。[reported] 是 get_context_usage 的 `autoCompactThreshold`（不含百分比），[percent] 是起进程时给的百分比。 照
     * Claude Code 的算法用双精度算（JS 的 Math.floor(e * (p / 100))），整数算法在个别数上会差 1（比如 10 万的 29%）。
     */
    fun threshold(reported: Long?, percent: Int?): Long? {
        reported ?: return null
        if (percent == null || percent !in 1..100) return reported
        val effective = reported + SUMMARY_BUFFER
        return minOf(floor(effective * (percent / 100.0)).toLong(), reported)
    }
}
