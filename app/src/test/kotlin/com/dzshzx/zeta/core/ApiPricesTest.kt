package com.dzshzx.zeta.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 按价目表估的费用：和 Claude Code 自己报的对得上，不认识的模型不估。 */
class ApiPricesTest {
    private fun usage(text: String) = Json.parseToJsonElement(text) as JsonObject

    private val small =
        usage(
            """{"input_tokens":2,"cache_creation_input_tokens":11329,"cache_read_input_tokens":13021,"output_tokens":4}""",
        )

    /** Claude Code 对同一份 usage 报的是 0.0933242 美元（result 行的 total_cost_usd）。 */
    @Test
    fun matchesWhatClaudeCodeReports() {
        assertEquals(0.0933242, ApiPrices.cost("claude-opus-5-5", small)!!, 1e-9)
    }

    @Test
    fun modelIdMayCarryADateOrAWindowMark() {
        val plain = ApiPrices.cost("claude-opus-5-5", small)!!
        assertEquals(plain, ApiPrices.cost("claude-opus-5-5[1m]", small)!!, 0.0)
        assertEquals(plain, ApiPrices.cost("claude-opus-5-5-20261001", small)!!, 0.0)
        // 更长的前缀先认：不被当成 claude-opus-5
        assertEquals(0.1199105, ApiPrices.cost("claude-opus-5", small)!!, 1e-9)
    }

    @Test
    fun unknownModelsAreNotPriced() {
        assertNull(ApiPrices.cost("claude-opus-5-9", small))
        assertNull(ApiPrices.cost("<synthetic>", small))
        assertNull(ApiPrices.cost(null, small))
        assertNull(ApiPrices.cost("claude-opus-5-5", JsonObject(emptyMap())))
    }

    @Test
    fun shortLivedCacheWritesAndFastModeHaveTheirOwnRates() {
        val base = """"input_tokens":0,"output_tokens":0,"cache_read_input_tokens":0"""
        val oneHour = usage("""{$base,"cache_creation_input_tokens":1000000}""")
        val fiveMinutes =
            usage(
                """{$base,"cache_creation_input_tokens":1000000,"cache_creation":{"ephemeral_5m_input_tokens":1000000,"ephemeral_1h_input_tokens":0}}""",
            )
        assertEquals(16.0, ApiPrices.cost("claude-fable-5-2", oneHour)!!, 1e-9)
        assertEquals(10.0, ApiPrices.cost("claude-fable-5-2", fiveMinutes)!!, 1e-9)
        val fast = usage("""{"input_tokens":1000000,"output_tokens":1000000,"speed":"fast"}""")
        assertEquals(96.0, ApiPrices.cost("claude-fable-5-2", fast)!!, 1e-9)
    }
}
