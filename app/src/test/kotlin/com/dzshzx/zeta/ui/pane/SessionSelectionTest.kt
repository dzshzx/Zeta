package com.dzshzx.zeta.ui.pane

import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.chat.endConfirmSummary
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

    @Test
    fun archiveAsksOnlyWhenSomethingIsStillRunning() {
        val ended = session("d").copy(live = false)
        val idle = { _: String -> false }
        val ends = mutableListOf<List<SessionSummary>>()
        assertEquals(null, archivePrompt(listOf(ended), true, idle) { ends += it })
        assertEquals(null, archivePrompt(listOf(a), false, idle) { ends += it })

        val one = archivePrompt(listOf(a), true, idle) { ends += it }!!
        assertEquals(Strings.sidebarArchiveRunningTitle(), one.title)
        assertEquals(Strings.chatEndSummaryIdle(), one.summary)
        assertEquals(Strings.sidebarArchive(), one.confirmText)

        // 几个里只结束还在运行的
        val many = archivePrompt(listOf(a, ended), true, idle) { ends += it }!!
        assertEquals(Strings.sidebarArchiveMany(2), many.title)
        assertEquals(emptyList<List<SessionSummary>>(), ends) // 确认前什么也不做
        many.action()
        assertEquals(listOf(listOf(a)), ends)
    }

    @Test
    fun endSkipsEndedSessionsAndSaysWhatStops() {
        val ended = session("d").copy(live = false)
        val elsewhere = session("e", external = true)
        assertEquals(listOf(a, b), endable(listOf(a, ended, b)))

        val busy = setOf("b")::contains
        fun text(sessions: List<SessionSummary>) =
            endPrompt(sessions, busy) {}.let { Triple(it.title, it.summary, it.confirmText) }
        assertEquals(
            Triple(Strings.chatEndTitle(), Strings.chatEndSummaryIdle(), Strings.chatEnd()),
            text(listOf(a)),
        )
        assertEquals(Strings.chatEndSummaryActive(), text(listOf(b)).second)
        assertEquals(
            endConfirmSummary(external = true, occupant = null, busy = false),
            text(listOf(elsewhere)).second,
        )
        assertEquals(
            Triple(
                Strings.sidebarEndMany(2),
                Strings.sidebarEndManySummaryActive(),
                Strings.chatEnd(),
            ),
            text(listOf(a, b)),
        )
        assertEquals(Strings.sidebarEndManySummaryActive(), text(listOf(a, elsewhere)).second)
        assertEquals(Strings.sidebarEndManySummaryIdle(), text(listOf(a, c)).second)
    }
}
