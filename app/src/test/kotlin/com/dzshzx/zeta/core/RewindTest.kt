package com.dzshzx.zeta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RewindTest {
    private val stopped = NoticeItem("n-stop", "已停止", error = false)

    /** 一段典型的对话：载入提示、三轮，第二轮停在工具步骤上，第三轮末尾是“已停止”。 */
    private val items =
        listOf(
            NoticeItem("n-0", "上下文已压缩", error = false),
            UserItem("u-1", "first", uuid = "uuid-1"),
            AnswerItem("a-1", "one", streaming = false),
            UserItem("u-2", "second", uuid = "uuid-2"),
            AnswerItem("a-2", "two", streaming = false),
            WorkItem("w-2", emptyList()),
            UserItem("u-3", "third", uuid = "uuid-3"),
            AnswerItem("a-3", "three", streaming = false),
            stopped,
        )

    @Test
    fun turnsStartAtUserMessages() {
        assertEquals(listOf(Turn(1, 3), Turn(3, 6), Turn(6, 9)), turnsOf(items))
        assertEquals(emptyList<Turn>(), turnsOf(listOf(NoticeItem("n", "x", error = false))))
    }

    @Test
    fun actionsHangUnderTheLastAnswerOrStep() {
        val turns = turnActions(items)
        assertEquals(listOf("a-1", "w-2", "a-3"), turns.map { it.lastItemId })
        assertEquals(listOf(2, 1, 0), turns.map { it.laterTurns })
        assertEquals(listOf("uuid-2", "uuid-3", null), turns.map { it.branchCut })
        assertTrue(turns.all { it.rewindable && it.branchable })
    }

    @Test
    fun turnWithOnlyPlainNoticesHasNoActionRow() {
        val only = listOf(UserItem("u", "hi", uuid = "x"), stopped)
        assertNull(turnActions(only).single().lastItemId)
        val failed =
            listOf(UserItem("u", "hi", uuid = "x"), NoticeItem("e", "出错了", error = true), stopped)
        assertEquals("e", turnActions(failed).single().lastItemId)
    }

    @Test
    fun messagesWithoutUuidCannotBeCutAt() {
        val mixed =
            listOf(
                UserItem("u-1", "first", uuid = "uuid-1"),
                AnswerItem("a-1", "one", streaming = false),
                UserItem("u-2", "queued"),
                AnswerItem("a-2", "two", streaming = false),
            )
        val turns = turnActions(mixed)
        assertTrue(turns[0].rewindable)
        assertFalse(turns[0].branchable)
        assertFalse(turns[1].rewindable)
        assertTrue(turns[1].branchable)
    }
}
