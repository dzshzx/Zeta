package com.dzshzx.zeta.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCompactTest {
    @Test
    fun envOnlyWhenSomethingDiffersFromClaudeCode() {
        assertEquals(emptyMap<String, String>(), AutoCompact.env(enabled = true, percent = null))
        assertEquals(
            mapOf("CLAUDE_AUTOCOMPACT_PCT_OVERRIDE" to "70"),
            AutoCompact.env(enabled = true, percent = 70),
        )
        // 关着时百分比没有意义，只给开关
        assertEquals(
            mapOf("DISABLE_AUTO_COMPACT" to "1"),
            AutoCompact.env(enabled = false, percent = 70),
        )
        assertEquals(emptyMap<String, String>(), AutoCompact.env(enabled = true, percent = 0))
        assertEquals(emptyMap<String, String>(), AutoCompact.env(enabled = true, percent = 101))
    }

    @Test
    fun thresholdFollowsClaudeCodeFormula() {
        assertNull(AutoCompact.threshold(null, 60))
        assertEquals(367_000L, AutoCompact.threshold(367_000, null))
        // 40 万窗口：有效窗口 38 万
        assertEquals(190_000L, AutoCompact.threshold(367_000, 50))
        assertEquals(266_000L, AutoCompact.threshold(367_000, 70))
        assertEquals(342_000L, AutoCompact.threshold(367_000, 90))
        // 只会更早：100% 也不超过默认点
        assertEquals(367_000L, AutoCompact.threshold(367_000, 100))
        // 100 万窗口
        assertEquals(784_000L, AutoCompact.threshold(967_000, 80))
        assertEquals(48_000L, AutoCompact.threshold(67_000, 60))
    }

    @Test
    fun envGoesInFrontOfTheHostScript() {
        val plain = hostCommandLine("echo hi", listOf("start", "k", "/w", "t", "-p"))
        assertEquals("exec bash -c 'echo hi' zeta-host 'start' 'k' '/w' 't' '-p'", plain)
        val withEnv =
            hostCommandLine(
                "echo hi",
                listOf("start"),
                mapOf("DISABLE_AUTO_COMPACT" to "1", "bad name" to "x", "A" to "it's"),
            )
        assertEquals(
            "exec env DISABLE_AUTO_COMPACT='1' A='it'\\''s' bash -c 'echo hi' zeta-host 'start'",
            withEnv,
        )
    }

    private fun j(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun contextUsage(extra: String) =
        j(
            """{"subtype":"success","request_id":"zeta-1","response":""" +
                """{"totalTokens":20000,"maxTokens":400000,"model":"claude-opus-5-5"$extra}}""",
        )

    @Test
    fun usageCarriesTheCompactionPoint() {
        val meter = UsageTracker()
        meter.processStarted(fresh = true, compactPercent = 70)
        meter.contextUsageAnswered(
            contextUsage(""","autoCompactThreshold":367000,"isAutoCompactEnabled":true"""),
        )
        val usage = meter.snapshot()
        assertEquals(true, usage.autoCompact)
        assertEquals(266_000L, usage.compactAt)
        assertEquals(0.665f, usage.compactFraction!!, 0.0001f)

        // 关着：没有压缩点，窗口回到模型本身的
        val off = UsageTracker()
        off.processStarted(fresh = true, compactPercent = null)
        off.contextUsageAnswered(
            j(
                """{"subtype":"success","request_id":"zeta-1","response":""" +
                    """{"totalTokens":20000,"maxTokens":1000000,"isAutoCompactEnabled":false,"model":"claude-opus-5-5"}}""",
            ),
        )
        assertEquals(false, off.snapshot().autoCompact)
        assertNull(off.snapshot().compactAt)
        assertNull(off.snapshot().compactFraction)

        // 新进程：要等它自己报
        meter.processStarted(fresh = false, compactPercent = null)
        assertNull(meter.snapshot().compactAt)
        assertNull(meter.snapshot().autoCompact)
    }

    @Test
    fun levelFollowsTheCompactionPointWhenKnown() {
        // 压缩点 26.6 万：到 21.3 万（80%）提醒，到 25.3 万（95%）就是马上要压缩
        fun level(used: Long) =
            ChatUsage(
                    contextTokens = used,
                    contextWindow = 400_000,
                    autoCompact = true,
                    compactAt = 266_000,
                )
                .level
        assertEquals(ContextLevel.Normal, level(200_000))
        assertEquals(ContextLevel.Warning, level(215_000))
        assertEquals(ContextLevel.Critical, level(255_000))
        // 不知道压缩点时照旧按窗口算
        assertEquals(
            ContextLevel.Normal,
            ChatUsage(contextTokens = 255_000, contextWindow = 400_000).level,
        )
        assertTrue(
            ChatUsage(contextTokens = 380_000, contextWindow = 400_000, autoCompact = false)
                .level == ContextLevel.Critical,
        )
    }
}
