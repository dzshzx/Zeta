package com.dzshzx.zeta.core

import com.dzshzx.zeta.ui.chat.TurnIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 各轮的操作：能做什么、挂在哪一项照核心标在用户消息上的（conv.items 的 rewindable、forkable、round_last），之后还有几轮由客户端数。 */
class RewindTest {
    /** 核心推来的一段：载入提示、三轮；第二轮停在工具步骤上，第三轮的用户消息 Claude Code 还不认得、只有灰色提示。 */
    private val items =
        Json.parseToJsonElement(
                """[{"kind":"notice","id":"n-0","text":"上下文已压缩"},
                {"kind":"user","id":"u-1","text":"first","uuid":"uuid-1","rewindable":true,"forkable":true,"round_last":"a-1"},
                {"kind":"answer","id":"a-1","text":"one"},
                {"kind":"user","id":"u-2","text":"second","uuid":"uuid-2","rewindable":true,"forkable":true,"round_last":"w-2"},
                {"kind":"answer","id":"a-2","text":"two"},
                {"kind":"work","id":"w-2","steps":[]},
                {"kind":"user","id":"u-3","text":"third","forkable":true},
                {"kind":"notice","id":"n-3","text":"已停止"}]""",
            )
            .jsonArray
            .map { chatItemOf(it.jsonObject) }

    @Test
    fun readsWhatTheCoreMarkedOnUserMessages() {
        val users = items.filterIsInstance<UserItem>()
        assertEquals(listOf(true, true, false), users.map { it.rewindable })
        assertTrue(users.all { it.forkable })
        assertEquals(listOf("a-1", "w-2", null), users.map { it.roundLast })
        // 没标的是零值：核心省掉了 false 和空串
        val bare = chatItemOf(Json.parseToJsonElement("""{"kind":"user","id":"u"}""").jsonObject)
        assertEquals(UserItem("u", ""), bare)
    }

    @Test
    fun turnsCountTheRoundsAfterThem() {
        val turns = userTurns(items)
        assertEquals(listOf("u-1", "u-2", "u-3"), turns.map { it.user.id })
        assertEquals(listOf(2, 1, 0), turns.map { it.laterTurns })
        assertEquals(emptyList<Turn>(), userTurns(listOf(NoticeItem("n", "x", error = false))))
    }

    @Test
    fun actionRowHangsWhereTheCoreSaid() {
        val index = TurnIndex(userTurns(items))
        assertEquals(setOf("a-1", "w-2"), index.byLast.keys)
        assertEquals("u-2", index.byLast.getValue("w-2").user.id)
        assertSame(index.byUser.getValue("u-3"), index.last)
        assertNull(index.byLast["n-3"])
        assertFalse(index.last!!.user.rewindable)
    }
}
