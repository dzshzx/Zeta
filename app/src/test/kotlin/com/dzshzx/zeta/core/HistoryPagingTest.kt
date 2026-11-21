package com.dzshzx.zeta.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 往上翻载入更早记录的纯逻辑：读助手的输出、要页的参数、接上时和正在流式输出的新内容互不覆盖。 */
class HistoryPagingTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private val file = "/home/u/.claude/projects/-w/s.jsonl"

    /** 助手 history 的一页：第 40 条起，一轮提问、一次工具调用和结果、一段回答，前面还剩 40 条。 */
    private val page =
        listOf(
                """{"type":"zeta_history","skipped":40,"anchor":"row39","todo_seed":[{"type":"assistant","message":{"id":"m0","content":[{"type":"tool_use","id":"todo0","name":"TodoWrite","input":{"todos":[]}}]}}]}""",
                """{"type":"user","message":{"content":[{"type":"text","text":"更早的提问"}]},"uuid":"u40"}""",
                """{"type":"assistant","message":{"id":"m41","usage":{"input_tokens":10,"output_tokens":5},"content":[{"type":"tool_use","id":"tool41","name":"Bash","input":{"command":"ls"}}]}}""",
                """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool41","is_error":false,"content":"ok"}]}}""",
                """{"type":"assistant","message":{"id":"m43","content":[{"type":"text","text":"更早的回答"}]}}""",
            )
            .joinToString("\n")

    @Test
    fun readsAPageWithWhatIsLeftBeforeIt() {
        val reducer = TranscriptReducer("p1-")
        val read = HistoryPaging.read(page, file, reducer)
        assertFalse(read.stale)
        assertEquals(EarlierHistory(file, 40, "row39"), read.earlier)
        assertEquals(listOf("todo0"), read.todoSeed.map { it.id })
        val items = reducer.items
        assertEquals(3, items.size)
        val user = items[0] as UserItem
        assertEquals("更早的提问", user.text)
        assertEquals("u40", user.uuid)
        // 工具结果回填到了同一页里的调用上
        assertEquals(ToolStatus.Success, ((items[1] as WorkItem).steps.single() as ToolStep).status)
        assertEquals("更早的回答", (items[2] as AnswerItem).text)
        assertTrue(items.all { it.id.startsWith("p1-") || it.id.startsWith("w:") })
        assertEquals(1, read.usage.snapshot().turns.size)
    }

    @Test
    fun theFirstPageLeavesNothingEarlier() {
        val read =
            HistoryPaging.read(
                """{"type":"zeta_history","skipped":0,"anchor":null,"todo_seed":[]}""",
                file,
                TranscriptReducer(),
            )
        assertNull(read.earlier)
    }

    @Test
    fun aStalePageGivesNothing() {
        val reducer = TranscriptReducer()
        val read = HistoryPaging.read("""{"type":"zeta_history","stale":true}""", file, reducer)
        assertTrue(read.stale)
        assertTrue(reducer.items.isEmpty())
    }

    @Test
    fun argumentsCarryTheWindowToCheck() {
        val window = EarlierHistory(file, 120, null)
        assertEquals(listOf(file, "120", "-"), HistoryPaging.pageArguments(window))
        assertEquals(
            listOf(file, "120", "row119"),
            HistoryPaging.pageArguments(window.copy(anchor = "row119")),
        )
        assertEquals(
            listOf(file, "120", "-", "all"),
            HistoryPaging.pageArguments(window, rest = true),
        )
    }

    /**
     * 回答正流式写着时接上更早的一页：页插在最前面，之后的增量和终稿照样落在正在写的那段回答上， 新开的工作组接在末尾，不会落进页里（Conversation 就是这样把页接到同一个
     * reducer 的列表前面）。
     */
    @Test
    fun aPagePrependedDuringStreamingDoesNotDisturbTheLiveAnswer() {
        val live = TranscriptReducer()
        live.addUser("新的提问", uuid = "new")
        live.applyStream(json("""{"type":"message_start","message":{"id":"live"}}"""), 0)
        live.applyStream(
            json(
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            ),
            0,
        )
        live.applyStream(
            json(
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"写到一半"}}""",
            ),
            0,
        )

        val earlier = TranscriptReducer("p1-")
        HistoryPaging.read(page, file, earlier)
        live.items.addAll(0, earlier.items)

        live.applyStream(
            json(
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"，写完了"}}""",
            ),
            0,
        )
        live.applyStream(json("""{"type":"content_block_stop","index":0}"""), 0)
        live.applyAssistant(json("""{"id":"live","content":[{"type":"text","text":"写到一半，写完了"}]}"""))
        live.applyStream(json("""{"type":"message_start","message":{"id":"live2"}}"""), 0)
        live.applyStream(
            json(
                """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool-live","name":"Read"}}""",
            ),
            0,
        )
        live.applyUser(
            json(
                """{"content":[{"type":"tool_result","tool_use_id":"tool-live","content":"内容"}]}""",
            ),
            replay = false,
        )
        live.finishTurn()

        val items = live.items
        assertEquals(
            listOf("更早的提问", null, "更早的回答", "新的提问", "写到一半，写完了", null),
            items.map {
                when (it) {
                    is UserItem -> it.text
                    is AnswerItem -> it.text
                    else -> null
                }
            },
        )
        assertEquals(ToolStatus.Success, ((items[1] as WorkItem).steps.single() as ToolStep).status)
        val liveTool = (items.last() as WorkItem).steps.single() as ToolStep
        assertEquals("tool-live", liveTool.id)
        assertEquals(ToolStatus.Success, liveTool.status)
        assertEquals(items.size, items.map { it.id }.toSet().size)
    }
}
