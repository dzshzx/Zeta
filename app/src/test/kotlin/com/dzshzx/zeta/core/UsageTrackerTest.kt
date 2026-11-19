package com.dzshzx.zeta.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 样本取自 Claude Code 2.1.327 在手机上的一次真实 stream-json 输出（三轮：两条短消息、一次 /compact），
 * 只留与用量有关的字段，会话号等换成了假的。
 */
class UsageTrackerTest {
    private fun j(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val start1 = j(
        """{"type":"stream_event","event":{"type":"message_start","message":{"model":"claude-opus-5-5","id":"msg_1","usage":{"input_tokens":2,"cache_creation_input_tokens":11329,"cache_read_input_tokens":13021,"output_tokens":4}}},"parent_tool_use_id":null}""",
    )
    private val delta1 = j(
        """{"type":"stream_event","event":{"type":"message_delta","usage":{"input_tokens":2,"cache_creation_input_tokens":11329,"cache_read_input_tokens":13021,"output_tokens":4}},"parent_tool_use_id":null}""",
    )

    // result 行的键序不固定：type 在后面
    private val result1 = j(
        """{"duration_api_ms":1468,"duration_ms":2100,"total_cost_usd":0.09332420000000001,"usage":{"input_tokens":2,"cache_creation_input_tokens":11329,"cache_read_input_tokens":13021,"output_tokens":4},"modelUsage":{"claude-opus-5-5":{"inputTokens":2,"outputTokens":4,"cacheReadInputTokens":13021,"cacheCreationInputTokens":11329,"costUSD":0.09332420000000001,"contextWindow":1000000}},"type":"result","subtype":"success"}""",
    )
    private val delta2 = j(
        """{"type":"stream_event","event":{"type":"message_delta","usage":{"input_tokens":2,"cache_creation_input_tokens":11482,"cache_read_input_tokens":15364,"output_tokens":4}},"parent_tool_use_id":null}""",
    )
    private val result2 = j(
        """{"total_cost_usd":0.188341,"usage":{"input_tokens":2,"cache_creation_input_tokens":11482,"cache_read_input_tokens":15364,"output_tokens":4},"modelUsage":{"claude-opus-5-5":{"inputTokens":4,"outputTokens":8,"cacheReadInputTokens":28385,"cacheCreationInputTokens":22811,"costUSD":0.188341,"contextWindow":1000000}},"type":"result","subtype":"success"}""",
    )
    private val boundary = j(
        """{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"manual","pre_tokens":26852,"post_tokens":7459,"cumulative_dropped_tokens":19393,"duration_ms":7070}}""",
    )

    // /compact 这一轮：result.usage 全是 0，压缩请求的花费只体现在累计里
    private val result3 = j(
        """{"is_error":false,"num_turns":0,"total_cost_usd":0.23370600000000002,"usage":{"input_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":0},"modelUsage":{"claude-opus-5-5":{"inputTokens":2164,"outputTokens":607,"cacheReadInputTokens":52750,"cacheCreationInputTokens":25295,"costUSD":0.23370600000000002,"contextWindow":1000000}},"subtype":"success","result":"","type":"result"}""",
    )

    @Test
    fun contextIsTheLastRequestAndCompactionShrinksIt() {
        val meter = UsageTracker()
        meter.processStarted(fresh = true)
        meter.observe(start1)
        assertEquals(24356L, meter.snapshot().contextTokens)
        meter.observe(delta1)
        meter.observe(result1)
        meter.observe(delta2)
        // 和压缩时 Claude Code 报的 pre_tokens 一致
        assertEquals(26852L, meter.snapshot().contextTokens)
        meter.observe(result2)
        meter.observe(boundary)
        val after = meter.snapshot()
        assertEquals(7459L, after.contextTokens)
        assertEquals(1, after.compactions)
        assertEquals("claude-opus-5-5", after.model)
        // 没问过 Claude Code 时用模型本身的窗口
        assertEquals(1_000_000L, after.contextWindow)
        // post_tokens 不含系统提示和工具：压缩后 Claude Code 的估算到了就换上
        assertTrue(meter.snapshot().contextEstimated)
        meter.observe(contextUsage(totalTokens = 27040, maxTokens = 400_000))
        assertEquals(27040L, meter.snapshot().contextTokens)
        assertTrue(meter.snapshot().contextEstimated)
        assertEquals(400_000L, meter.snapshot().contextWindow)
        // 有实测数之后，估算不再覆盖它
        meter.observe(delta2)
        meter.observe(contextUsage(totalTokens = 30000, maxTokens = 400_000))
        assertEquals(26852L, meter.snapshot().contextTokens)
        assertFalse(meter.snapshot().contextEstimated)
    }

    private fun contextUsage(totalTokens: Long, maxTokens: Long) = j(
        """{"type":"control_response","response":{"subtype":"success","request_id":"zeta-9","response":{"totalTokens":$totalTokens,"maxTokens":$maxTokens,"model":"claude-opus-5-5"}}}""",
    )

    @Test
    fun turnsAreDeltasOfTheProcessTotals() {
        val meter = UsageTracker()
        meter.processStarted(fresh = true)
        listOf(start1, delta1, result1, delta2, result2, boundary, result3).forEach { meter.observe(it) }
        val usage = meter.snapshot()
        assertEquals(3, usage.turns.size)
        assertEquals(TokenCounts(2, 4, 13021, 11329), usage.turns[0].tokens)
        assertEquals(0.0933242, usage.turns[0].costUsd!!, 1e-9)
        assertEquals(2100L, usage.turns[0].durationMs)
        assertEquals(TokenCounts(2, 4, 15364, 11482), usage.turns[1].tokens)
        assertEquals(0.188341 - 0.0933242, usage.turns[1].costUsd!!, 1e-9)
        // 压缩那一轮的 token 和费用来自累计之差
        assertEquals(TokenCounts(2160, 599, 24365, 2484), usage.turns[2].tokens)
        assertEquals(0.233706 - 0.188341, usage.turns[2].costUsd!!, 1e-9)
        assertEquals(0.233706, usage.totalCost!!, 1e-9)
        assertEquals(TokenCounts(2164, 607, 52750, 25295), usage.total)
        assertFalse(usage.costPartial)
    }

    @Test
    fun resumedProcessCarriesOldTotalsSoFirstCostIsUnknown() {
        // --resume 之后 Claude Code 把上次进程的累计带了过来：0.2337 + 这一轮
        val resumed = j(
            """{"type":"result","subtype":"success","total_cost_usd":0.33235440000000005,"usage":{"input_tokens":2,"output_tokens":3,"cache_read_input_tokens":15102,"cache_creation_input_tokens":11945},"modelUsage":{"claude-opus-5-5":{"inputTokens":2166,"outputTokens":610,"cacheReadInputTokens":67852,"cacheCreationInputTokens":37240,"costUSD":0.33235440000000005,"contextWindow":1000000}}}""",
        )
        val meter = UsageTracker()
        meter.processStarted(fresh = false)
        meter.observe(resumed)
        val first = meter.snapshot().turns.single()
        assertEquals(TokenCounts(2, 3, 15102, 11945), first.tokens)
        assertNull(first.costUsd)
        // 之后的一轮又能按差值算
        val next = j(
            """{"type":"result","total_cost_usd":0.40,"usage":{"input_tokens":1,"output_tokens":1,"cache_read_input_tokens":1,"cache_creation_input_tokens":1},"modelUsage":{"claude-opus-5-5":{"inputTokens":2167,"outputTokens":620,"cacheReadInputTokens":67900,"cacheCreationInputTokens":37300}}}""",
        )
        meter.observe(next)
        val second = meter.snapshot().turns[1]
        assertEquals(TokenCounts(1, 10, 48, 60), second.tokens)
        assertEquals(0.40 - 0.3323544, second.costUsd!!, 1e-9)
        assertTrue(meter.snapshot().costPartial)
    }

    @Test
    fun resumedProcessWithoutCarriedTotalsKeepsCost() {
        val meter = UsageTracker()
        meter.processStarted(fresh = false)
        meter.observe(result1)
        assertEquals(0.0933242, meter.snapshot().turns.single().costUsd!!, 1e-9)
    }

    @Test
    fun countersThatStartOverDoNotGiveNegativeTurns() {
        val meter = UsageTracker()
        meter.processStarted(fresh = true)
        meter.observe(result1)
        meter.observe(result2)
        meter.observe(j("""{"type":"conversation_reset","new_conversation_id":"s2"}"""))
        assertNull(meter.snapshot().contextTokens)
        // /clear 之后累计从零开始：这一轮按 result.usage 算，费用与累计对得上
        val afterClear = j(
            """{"type":"result","total_cost_usd":0.01,"usage":{"input_tokens":3,"output_tokens":5,"cache_read_input_tokens":0,"cache_creation_input_tokens":900},"modelUsage":{"claude-opus-5-5":{"inputTokens":3,"outputTokens":5,"cacheReadInputTokens":0,"cacheCreationInputTokens":900}}}""",
        )
        meter.observe(afterClear)
        val turn = meter.snapshot().turns.last()
        assertEquals(TokenCounts(3, 5, 0, 900), turn.tokens)
        assertEquals(0.01, turn.costUsd!!, 1e-9)
    }

    @Test
    fun subagentMessagesDoNotMoveTheContext() {
        val meter = UsageTracker()
        meter.observe(delta1)
        meter.observe(
            j("""{"type":"stream_event","event":{"type":"message_delta","usage":{"input_tokens":5,"cache_read_input_tokens":90000,"output_tokens":10}},"parent_tool_use_id":"toolu_1"}"""),
        )
        assertEquals(24356L, meter.snapshot().contextTokens)
    }

    @Test
    fun claudeWindowWinsAndIsRemembered() {
        val meter = UsageTracker()
        meter.observe(result1)
        meter.observe(
            j("""{"type":"control_response","response":{"subtype":"success","request_id":"zeta-2","response":{"totalTokens":12410,"maxTokens":400000,"rawMaxTokens":400000,"model":"claude-opus-5-5"}}}"""),
        )
        val usage = meter.snapshot()
        assertEquals(400_000L, usage.contextWindow)
        assertEquals("claude-opus-5-5" to 400_000L, meter.learnedWindow)
        // result1 之前没有消息事件，上下文先用 Claude Code 的估算
        assertEquals(12410L, usage.contextTokens)
        meter.observe(
            j("""{"type":"control_response","response":{"subtype":"success","request_id":"zeta-3","response":{"effective":{},"applied":{"model":"claude-opus-5-5","effort":"xhigh"}}}}"""),
        )
        assertEquals("xhigh", meter.snapshot().appliedEffort)
    }

    @Test
    fun historyRestoresContextAndTurns() {
        val lines = listOf(
            """{"type":"user","message":{"content":[{"type":"text","text":"a"}]}}""",
            // 同一条消息按内容块拆成两行，usage 只算一次
            """{"type":"assistant","message":{"id":"m1","model":"claude-opus-5-5","usage":{"input_tokens":2,"output_tokens":40,"cache_read_input_tokens":100,"cache_creation_input_tokens":10},"content":[{"type":"thinking","thinking":"x"}]}}""",
            """{"type":"assistant","message":{"id":"m1","model":"claude-opus-5-5","usage":{"input_tokens":2,"output_tokens":40,"cache_read_input_tokens":100,"cache_creation_input_tokens":10},"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{}}]}}""",
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}""",
            """{"type":"assistant","message":{"id":"m2","model":"claude-opus-5-5","usage":{"input_tokens":1,"output_tokens":5,"cache_read_input_tokens":150,"cache_creation_input_tokens":20},"content":[{"type":"text","text":"done"}]}}""",
            """{"type":"user","message":{"content":[{"type":"text","text":"b"}]}}""",
            """{"type":"assistant","message":{"id":"m3","model":"claude-opus-5-5","usage":{"input_tokens":1,"output_tokens":2,"cache_read_input_tokens":170,"cache_creation_input_tokens":8},"content":[{"type":"text","text":"ok"}]}}""",
        )
        val history = UsageTracker()
        lines.forEach { history.observeHistory(j(it)) }
        history.finishHistory()
        val restored = history.snapshot(knownWindows = mapOf("claude-opus-5-5" to 400_000L))
        assertEquals(181L, restored.contextTokens)
        assertEquals(400_000L, restored.contextWindow)
        assertEquals(2, restored.turns.size)
        assertEquals(TokenCounts(3, 45, 250, 30), restored.turns[0].tokens)
        assertNull(restored.totalCost)
        assertTrue(restored.costPartial)

        // 压缩之后记录里只有压缩提示，占用取压缩后的大小
        val compacted = UsageTracker()
        (lines + """{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"manual","pre_tokens":181,"post_tokens":60}}""")
            .forEach { compacted.observeHistory(j(it)) }
        compacted.finishHistory()
        assertEquals(60L, compacted.snapshot().contextTokens)
        assertEquals(1, compacted.snapshot().compactions)
        // 重新打开后起了进程：压缩后的数是估算，换成 Claude Code 报的
        val reopened = UsageTracker()
        reopened.prependHistory(compacted)
        reopened.observe(contextUsage(totalTokens = 24400, maxTokens = 400_000))
        assertEquals(24400L, reopened.snapshot().contextTokens)

        // 历史接在实时数据前面
        val live = UsageTracker()
        live.processStarted(fresh = false)
        live.observe(result1)
        live.prependHistory(history)
        val merged = live.snapshot()
        assertEquals(3, merged.turns.size)
        assertEquals(TokenCounts(3, 45, 250, 30), merged.turns[0].tokens)
        assertEquals(181L, merged.contextTokens)
    }

    @Test
    fun levelsAndFormatting() {
        assertEquals(ContextLevel.Normal, ChatUsage(contextTokens = 100, contextWindow = 1000).level)
        assertEquals(ContextLevel.Warning, ChatUsage(contextTokens = 800, contextWindow = 1000).level)
        assertEquals(ContextLevel.Critical, ChatUsage(contextTokens = 950, contextWindow = 1000).level)
        assertEquals(ContextLevel.Normal, ChatUsage(contextTokens = 950).level)
        assertEquals("26.9K", formatTokens(26852))
        assertEquals("26.6K", formatTokens(26618))
        assertEquals("123K", formatTokens(123_456))
        assertEquals("100K", formatTokens(99_960))
        assertEquals("7.5K", formatTokens(7459))
        assertEquals("1K", formatTokens(1000))
        assertEquals("400K", formatTokens(400_000))
        assertEquals("1M", formatTokens(1_000_000))
        assertEquals("1.25M", formatTokens(1_250_000))
        assertEquals("812", formatTokens(812))
        assertEquals("26,852", formatExactTokens(26852))
        assertEquals("$0.0933", formatCost(0.0933242))
        assertEquals("$1.50", formatCost(1.5))
        assertEquals("上下文已压缩（压缩前 26.9K）", compactNotice(boundary))
        assertEquals("上下文已自动压缩", compactNotice(j("""{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"auto"}}""")))
    }
}
