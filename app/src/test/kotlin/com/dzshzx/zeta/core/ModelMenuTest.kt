package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.ui.settings.effortRows
import org.junit.Assert.assertEquals
import org.junit.Test

/** Claude Code 2.1.329 的 initialize 实际报的模型清单（顺序照原样）；截图（ModelMenuShots）也用它。 */
internal val ReportedModels: List<ModelOption> = listOf(
    "default" to "claude-opus-5-5", "opus" to "claude-opus-5-5", "fable" to "claude-fable-5-2",
    "sonnet" to "claude-sonnet-5-5", "haiku" to "claude-haiku-5-5",
).map { (alias, resolved) ->
    ModelOption(alias, if (alias == Conversation.DEFAULT_MODEL) "Default (recommended)" else readableModelId(resolved), "", resolved, EffortLevels.map { it.first })
} + listOf(
    "claude-fable-5-1", "claude-haiku-4-5-20251001", "claude-sonnet-5", "claude-opus-5", "claude-fable-5",
    "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6", "claude-sonnet-4-6",
).map { ModelOption(it, readableModelId(it), "", it, EffortLevels.map { level -> level.first }) }

/** 模型菜单只直接列各系列最新的，旧版本收进「更多模型」（[modelMenu]）。 */
class ModelMenuTest {
    private fun option(value: String, resolved: String? = value) = ModelOption(value, readableModelId(value), "", resolved, EffortLevels.map { it.first })

    private val reported = ReportedModels

    @Test
    fun aliasesOnTopOlderVersionsByFamilyNewestFirst() {
        val menu = modelMenu(reported)
        assertEquals(listOf("default", "opus", "fable", "sonnet", "haiku"), menu.latest.map { it.value })
        assertEquals(
            listOf(
                "claude-opus-5", "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6",
                "claude-fable-5-1", "claude-fable-5",
                "claude-sonnet-5", "claude-sonnet-4-6",
                "claude-haiku-4-5-20251001",
            ),
            menu.older.map { it.value },
        )
    }

    /** 没有别名的系列，最新的完整 ID 放上层；和别名指向同一个模型的完整 ID 不重复列；没报完整 ID 的别名按名字认系列。 */
    @Test
    fun seriesWithoutAliasAndDuplicates() {
        val menu = modelMenu(
            listOf(
                option("default", "claude-opus-5-5"),
                option("opus", null),
                option("claude-opus-5-5"),
                option("claude-opus-5"),
                option("claude-lyric-1"),
                option("claude-lyric-1-2"),
                option("claude-opus-5-5[1m]"),
            ),
        )
        assertEquals(listOf("default", "opus", "claude-lyric-1-2", "claude-opus-5-5[1m]"), menu.latest.map { it.value })
        assertEquals(listOf("claude-opus-5", "claude-lyric-1"), menu.older.map { it.value })
    }

    /** 拿不到 Claude Code 的清单时只有几个别名：全在上层，没有「更多模型」。 */
    @Test
    fun fallbackHasNoOlder() {
        val menu = modelMenu(FallbackModels)
        assertEquals(FallbackModels, menu.latest)
        assertEquals(emptyList<ModelOption>(), menu.older)
    }

    /** 默认思考强度页：旧版本排在最新的后面；没设过的标成收起，设过的照常列出。 */
    @Test
    fun effortRowsFoldUnsetOlderModels() {
        val rows = effortRows(reported, ClaudeDefaults(null, null, mapOf("claude-opus-4-6" to "high"), null))
        assertEquals(
            listOf("claude-opus-5-5", "claude-fable-5-2", "claude-sonnet-5-5", "claude-haiku-5-5"),
            rows.filter { !it.older }.map { it.model }.take(4),
        )
        assertEquals(listOf("claude-opus-4-6"), rows.filter { !it.older }.drop(4).map { it.model })
        assertEquals(
            listOf("claude-opus-5", "claude-opus-4-8", "claude-opus-4-7", "claude-fable-5-1", "claude-fable-5", "claude-sonnet-5", "claude-sonnet-4-6", "claude-haiku-4-5-20251001"),
            rows.filter { it.older }.map { it.model },
        )
    }
}
