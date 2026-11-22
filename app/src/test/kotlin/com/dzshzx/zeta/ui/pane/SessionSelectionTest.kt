package com.dzshzx.zeta.ui.pane

import com.dzshzx.zeta.core.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSelectionTest {
    private fun session(id: String, archived: Boolean = false, external: Boolean = false) =
        SessionSummary(
            id,
            "/home/u/.claude/projects/-w/$id.jsonl",
            "/w",
            "t-$id",
            "",
            0L,
            external = external,
            archived = archived,
        )

    private val a = session("a")
    private val b = session("b")
    private val c = session("c", archived = true)

    @Test
    fun selectAllTogglesOnlyTheVisibleList() {
        // 搜索前选了 c，搜索结果是 a、b：全选只加 a、b，再点一次只去掉 a、b，c 还在
        val all = toggleAll(setOf("c"), listOf(a, b))
        assertEquals(setOf("a", "b", "c"), all)
        assertTrue(allSelected(all, listOf(a, b)))
        assertEquals(setOf("c"), toggleAll(all, listOf(a, b)))
        assertFalse(allSelected(emptySet(), emptyList()))
        assertEquals(setOf("a", "b"), toggleAll(setOf("a"), listOf(a, b)))
    }

    @Test
    fun archiveButtonUnarchivesOnlyWhenAllAreArchived() {
        assertTrue(archiveAction(listOf(a, c)))
        assertFalse(archiveAction(listOf(c)))
        assertTrue(archiveAction(emptyList()))
    }

    @Test
    fun activeAndExternalSessionsAreNotDeleted() {
        val external = session("d", external = true)
        val removable = deletable(listOf(a, b, c, external)) { it == "b" }
        assertEquals(listOf(a, c), removable)
    }

    @Test
    fun deletePromptIsFixedWhenOpened() {
        // 打开时正在进行：只提示。之后对话结束了，点那个按钮也不删
        var running = true
        val blocked = deletePrompt(a) { running && it == "a" }
        assertEquals(DeletePrompt.Blocked(a), blocked)
        running = false
        assertNull(confirmedDeletion(blocked) { running && it == "a" })
        // 别处的 Claude Code 开着的同样只提示
        assertTrue(deletePrompt(session("d", external = true)) { false } is DeletePrompt.Blocked)
    }

    @Test
    fun confirmedDeletionRechecksActivity() {
        val prompt = deletePrompt(a) { false }
        assertEquals(DeletePrompt.Confirm(a), prompt)
        assertEquals(a, confirmedDeletion(prompt) { false })
        // 确认框开着时它开始进行了（定时任务、通知触发）：不删
        assertNull(confirmedDeletion(prompt) { it == "a" })
        assertNull(confirmedDeletion(null) { false })
    }

    @Test
    fun exportsAreJoinedWithRules() {
        val text =
            combineExports(
                listOf(
                    ExportedMarkdown("一", "# 一\n\n## 用户\n\nhi\n", "Zeta-一.md"),
                    ExportedMarkdown("二", "# 二\n", "Zeta-二.md"),
                ),
            )
        assertEquals("# 一\n\n## 用户\n\nhi\n\n---\n\n# 二\n", text)
    }

    @Test
    fun batchFileNameCountsSessions() {
        assertTrue(Regex("Zeta-3个对话-\\d{8}-\\d{4}\\.md").matches(exportFileName("3个对话", now = 0L)))
        assertTrue(
            Regex("Zeta-设置-\\d{8}-\\d{4}\\.json")
                .matches(exportFileName("设置", now = 0L, extension = "json")),
        )
    }
}
