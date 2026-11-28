package com.dzshzx.zeta.core

/** 对话背后的命令行工具标识：Claude Code（[CLAUDE]）或 Antigravity CLI（[AGY]）。 */
object Harnesses {
    const val CLAUDE = "claude"
    const val AGY = "agy"

    val ALL: List<String> = listOf(CLAUDE, AGY)

    fun normalize(value: String?): String = if (value == AGY) AGY else CLAUDE

    fun title(harness: String): String =
        if (normalize(harness) == AGY) "Antigravity" else "Claude Code"

    fun shortTitle(harness: String): String =
        if (normalize(harness) == AGY) "Antigravity" else "Claude"

    fun cliName(harness: String): String =
        if (normalize(harness) == AGY) "Antigravity CLI" else "Claude Code"

    fun badge(harness: String): String? = if (normalize(harness) == AGY) AGY else null
}
