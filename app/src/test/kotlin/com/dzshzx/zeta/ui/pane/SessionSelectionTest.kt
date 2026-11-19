package com.dzshzx.zeta.ui.pane

import com.dzshzx.zeta.core.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSelectionTest {
    private fun session(id: String, archived: Boolean = false, external: Boolean = false) =
        SessionSummary(id, "/home/u/.claude/projects/-w/$id.jsonl", "/w", "t-$id", "", 0L, external = external, archived = archived)

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
    fun exportsAreJoinedWithRules() {
        val text = combineExports(listOf(ExportedMarkdown("f1", "一", "# 一\n\n## 用户\n\nhi\n"), ExportedMarkdown("f2", "二", "# 二\n")))
        assertEquals("# 一\n\n## 用户\n\nhi\n\n---\n\n# 二\n", text)
    }

    @Test
    fun batchFileNameCountsSessions() {
        assertTrue(Regex("Zeta-3个对话-\\d{8}-\\d{4}\\.md").matches(batchExportFileName(3, now = 0L)))
    }
}
