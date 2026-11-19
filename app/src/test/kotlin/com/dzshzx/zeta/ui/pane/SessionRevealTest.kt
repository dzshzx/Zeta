package com.dzshzx.zeta.ui.pane

import com.dzshzx.zeta.core.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionRevealTest {
    private fun s(id: String) = SessionSummary(id, "/p/$id.jsonl", "/w", id, id, 0)

    @Test
    fun indexCountsHeadersOfNonEmptyGroups() {
        val pinned = listOf(s("p1"))
        val today = emptyList<SessionSummary>()
        val earlier = listOf(s("e1"), s("e2"))
        // 置顶标题、p1、更早标题、e1、e2
        assertEquals(1, revealIndex(listOf(pinned, today, earlier), "p1"))
        assertEquals(4, revealIndex(listOf(pinned, today, earlier), "e2"))
        assertEquals(2, revealIndex(listOf(today, today, earlier), "e2"))
        assertNull(revealIndex(listOf(pinned, today, earlier), "x"))
    }
}
