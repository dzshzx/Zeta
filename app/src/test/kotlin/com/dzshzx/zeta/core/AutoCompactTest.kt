package com.dzshzx.zeta.core

import org.junit.Assert.assertEquals
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
}
