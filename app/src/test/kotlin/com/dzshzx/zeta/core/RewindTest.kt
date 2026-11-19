package com.dzshzx.zeta.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RewindTest {
    private val stopped = NoticeItem("n-stop", "已停止", error = false)

    /** 一段典型的对话：载入提示、三轮，第二轮停在工具步骤上，第三轮末尾是“已停止”。 */
    private val items = listOf(
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

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

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
        val failed = listOf(UserItem("u", "hi", uuid = "x"), NoticeItem("e", "出错了", error = true), stopped)
        assertEquals("e", turnActions(failed).single().lastItemId)
    }

    @Test
    fun messagesWithoutUuidCannotBeCutAt() {
        val mixed = listOf(
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
        assertEquals("uuid-1", lastSeenUser(mixed))
        assertNull(branchPlan(mixed, "a-1"))
    }

    @Test
    fun branchFromMiddleCutsTheNextTurn() {
        val plan = branchPlan(items, "a-1")!!
        assertEquals(listOf("n-0", "u-1", "a-1"), plan.items.map { it.id })
        assertEquals("uuid-2", plan.cut)
        assertEquals("uuid-3", plan.lastSeen)
    }

    @Test
    fun branchFromLastTurnCopiesEverything() {
        val plan = branchPlan(items, "a-3")!!
        assertEquals(items.map { it.id }, plan.items.map { it.id })
        assertNull(plan.cut)
        assertNull(branchPlan(items, "missing"))
        assertNull(branchPlan(items, "n-0"))
    }

    @Test
    fun truncateDropsTheTargetAndEverythingAfter() {
        val list = items.toMutableList()
        assertTrue(truncateBefore(list, "u-2"))
        assertEquals(listOf("n-0", "u-1", "a-1"), list.map { it.id })
        assertFalse(truncateBefore(list, "u-9"))
        assertEquals(3, list.size)
    }

    @Test
    fun rewindRequestNamesTargetAndLastSeen() {
        assertEquals(
            json("""{"subtype":"rewind_conversation","target_message_uuid":"t","last_seen_user_message_uuid":"s"}"""),
            rewindRequest("t", "s"),
        )
        assertEquals(json("""{"subtype":"rewind_conversation","target_message_uuid":"t"}"""), rewindRequest("t", null))
    }

    @Test
    fun rewindAnswersAreRead() {
        // 2.1.327 实测的应答形状
        assertNull(rewindFailure(json(
            """{"subtype":"success","request_id":"zeta-2","response":{"rewound":true,"targetMessageUuid":"t","prefillText":"hi","precedingAssistantUuid":null}}""",
        )))
        val stale = rewindFailure(json(
            """{"subtype":"success","request_id":"zeta-2","response":{"rewound":false,"prefillText":null,"precedingAssistantUuid":null,"error":"stale target","reason":"stale_target"}}""",
        ))
        assertEquals("这个会话在别处有了新内容，重新打开它再试", stale)
        assertEquals("Claude 还在处理消息，等这一轮结束再试", rewindFailure(json("""{"subtype":"success","response":{"rewound":false,"reason":"turn_running"}}""")))
        assertEquals("Claude Code 没有剪：odd", rewindFailure(json("""{"subtype":"success","response":{"rewound":false,"error":"odd","reason":"new_reason"}}""")))
        assertEquals(
            "Claude Code 拒绝了这次操作：rewind_conversation: target_message_uuid must be a string",
            rewindFailure(json("""{"subtype":"error","request_id":"zeta-2","error":"rewind_conversation: target_message_uuid must be a string"}""")),
        )
        assertNotNull(rewindFailure(null))
    }

    @Test
    fun launchResumesOrForks() {
        assertEquals(listOf("--resume", "s"), sessionArguments("s", "src", "T"))
        assertEquals(listOf("--resume", "src", "--fork-session", "--name", "分支 · T"), sessionArguments(null, "src", "分支 · T"))
        assertEquals(listOf("--resume", "src", "--fork-session"), sessionArguments(null, "src", " "))
        assertEquals(emptyList<String>(), sessionArguments(null, null, "T"))
    }

    @Test
    fun branchTitlesAreNumberedAndNotStacked() {
        assertEquals("分支 · 修 bug", branchTitle("修 bug", emptyList()))
        assertEquals("分支 2 · 修 bug", branchTitle("修 bug", listOf("分支 · 修 bug")))
        assertEquals("分支 3 · 修 bug", branchTitle("分支 · 修 bug", listOf("分支 · 修 bug", "分支 2 · 修 bug")))
        assertEquals("分支 2 · 修 bug", branchTitle("分支 4 · 修 bug", listOf("分支 · 修 bug")))
        assertEquals("分支 · 新对话", branchTitle("  ", emptyList()))
        assertEquals("分支 · 修 bug · 分支", branchTitle("修 bug · 分支", emptyList()))
    }
}
