package com.dzshzx.zeta.core

import kotlinx.serialization.json.JsonObject

/**
 * Anthropic API 的标价，用来给没有费用数据的轮次估个数（会话记录里只有 token；Claude Code 实时报的费用以它报的为准）。 价格是每百万 token
 * 的美元数，照官方价目表手工维护：出了新模型或调了价要改这张表，表里没有的模型算不出来。
 *
 * 缓存写入按存多久分两档：5 分钟的是输入价的 1.25 倍，1 小时的是 2 倍；usage 里没分档时按 1 小时算（Claude Code 写的是这一档）。 快速模式（usage.speed
 * 为 fast）整体按 2 倍。Haiku 5.5 提示词超过 100K 时的加价不算。
 */
object ApiPrices {
    private class Price(val input: Double, val output: Double, val cacheRead: Double)

    /** 不带日期、不带窗口标记的模型号 → 价格。 */
    private val table: Map<String, Price> =
        mapOf(
            "claude-fable-5-2" to Price(8.0, 40.0, 0.20),
            "claude-mythos-5-2" to Price(8.0, 40.0, 0.20),
            "claude-fable-5-1" to Price(10.0, 50.0, 0.25),
            "claude-mythos-5-1" to Price(10.0, 50.0, 0.25),
            "claude-fable-5" to Price(10.0, 50.0, 1.0),
            "claude-mythos-5" to Price(10.0, 50.0, 1.0),
            "claude-opus-5-5" to Price(4.0, 20.0, 0.20),
            "claude-opus-5" to Price(5.0, 25.0, 0.5),
            "claude-opus-4-8" to Price(5.0, 25.0, 0.5),
            "claude-opus-4-7" to Price(5.0, 25.0, 0.5),
            "claude-opus-4-6" to Price(5.0, 25.0, 0.5),
            "claude-sonnet-5-5" to Price(2.0, 10.0, 0.10),
            "claude-sonnet-5" to Price(2.0, 10.0, 0.2),
            "claude-sonnet-4-6" to Price(3.0, 15.0, 0.3),
            "claude-haiku-5-5" to Price(0.1, 0.5, 0.01),
            "claude-haiku-4-5" to Price(1.0, 5.0, 0.1),
        )

    private val dated = Regex("""-\d{8}$""")

    /** [model] 是表里的某个模型号，后面至多带日期（-20261001）或方括号里的窗口标记（[1m]）。 */
    private fun priceOf(model: String): Price? =
        table[model.substringBefore('[').replace(dated, "")]

    /** 模型 [model] 的一次请求按标价要多少美元；[usage] 是 API 的 usage 形状。模型不在表里、usage 是空的时返回 null。 */
    fun cost(model: String?, usage: JsonObject?): Double? {
        val price = priceOf(model ?: return null) ?: return null
        val tokens = TokenCounts.ofApi(usage) ?: return null
        val shortLived = usage?.obj("cache_creation")?.long("ephemeral_5m_input_tokens") ?: 0
        val write5m = shortLived.coerceIn(0, tokens.cacheWrite)
        val write1h = tokens.cacheWrite - write5m
        val standard =
            tokens.input * price.input +
                tokens.output * price.output +
                tokens.cacheRead * price.cacheRead +
                write5m * price.input * 1.25 +
                write1h * price.input * 2
        val speed = if (usage?.str("speed") == "fast") 2 else 1
        return standard * speed / 1_000_000
    }
}
