package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.core.obj
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** 便签内容都是编造的。 */
class MiuiNoteWritesTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val ms = PersonalQueries.parseTime("2026-11-19T09:00", zone)!!

    private fun input(vararg pairs: Pair<String, Any?>) = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            null -> put(k, JsonNull)
            is Number -> put(k, v)
            else -> put(k, v.toString())
        }
    }

    private fun row(vararg pairs: Pair<String, Any?>): Row = pairs.associate { (key, value) ->
        key to when (value) {
            null -> JsonNull
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        } as JsonElement
    }

    private fun bad(message: String, block: () -> Unit) {
        val e = assertThrows(BadInput::class.java) { block() }
        assertTrue(e.message, e.message!!.startsWith(message))
    }

    private fun failure(message: String, block: () -> Unit) {
        val e = assertThrows(IllegalStateException::class.java) { block() }
        assertTrue(e.message, e.message!!.startsWith(message))
    }

    private fun arguments(op: JsonObject): JsonObject {
        assertEquals("appTool", op.str("op"))
        assertEquals(MiuiNoteWrites.SERVICE, op.str("component"))
        val request = Json.parseToJsonElement(op.str("request")!!).jsonObject
        assertEquals("tools/call", request.str("method"))
        return request.obj("params")!!
    }

    @Test
    fun createNeedsATitle() {
        assertEquals(
            input("title" to "买菜", "content" to "鸡蛋\n牛奶"),
            MiuiNoteWrites.createArgs(input("title" to "  买菜 ", "content" to "鸡蛋\r\n牛奶\n")),
        )
        assertEquals(input("title" to "空的", "content" to ""), MiuiNoteWrites.createArgs(input("title" to "空的")))
        bad("create 需要 title") { MiuiNoteWrites.createArgs(input("content" to "鸡蛋")) }
        bad("create 需要 title") { MiuiNoteWrites.createArgs(input("title" to "  ")) }
        bad("title 最多 200 字") { MiuiNoteWrites.createArgs(input("title" to "字".repeat(201))) }
        bad("content 最多 8000 字") { MiuiNoteWrites.createArgs(input("title" to "t", "content" to "字".repeat(8_001))) }
    }

    @Test
    fun updateChangesOnlyWhatIsGiven() {
        val note = row("id" to 7, "title" to "旧标题", "content" to "旧正文")
        assertEquals(input("noteId" to 7, "title" to "新标题"), MiuiNoteWrites.updateArgs(7, input("title" to "新标题"), note))
        assertEquals(input("noteId" to 7, "content" to "新正文"), MiuiNoteWrites.updateArgs(7, input("content" to " 新正文 "), note))
        // 给空正文就是清空
        assertEquals(input("noteId" to 7, "content" to ""), MiuiNoteWrites.updateArgs(7, input("content" to ""), note))
        bad("update 要给 title 或 content") { MiuiNoteWrites.updateArgs(7, input("note_id" to 7), note) }
        bad("title 不能为空") { MiuiNoteWrites.updateArgs(7, input("title" to " "), note) }
    }

    @Test
    fun refusesToReplaceBodiesTheReaderCannotShowWhole() {
        val rich = row("content" to "<text>周末</text>\n<input type=\"checkbox\" checked=\"true\" />牛奶\n☺ 1a2b3c")
        bad("便签 #7 是旧版富文本") { MiuiNoteWrites.updateArgs(7, input("content" to "牛奶"), rich) }
        // 只改标题可以
        assertEquals(input("noteId" to 7, "title" to "周末"), MiuiNoteWrites.updateArgs(7, input("title" to "周末"), rich))
        val long = row("content" to "字".repeat(PersonalQueries.ITEM_MAX + 1))
        bad("便签 #7 的正文超过 ${PersonalQueries.ITEM_MAX} 字") { MiuiNoteWrites.updateArgs(7, input("content" to "短"), long) }
        // 新版便签是 Markdown，图片是一行链接，读出来就是原文，可以整篇替换
        val markdown = row("content" to "清单\n![](https://com.miui.notes/image/abc)")
        assertEquals("新的", MiuiNoteWrites.updateArgs(7, input("content" to "新的"), markdown).str("content"))
    }

    @Test
    fun callsTheNotesToolService() {
        val create = arguments(MiuiNoteWrites.createOp(MiuiNoteWrites.createArgs(input("title" to "买菜", "content" to "鸡蛋"))))
        assertEquals("note_create", create.str("name"))
        assertEquals(input("title" to "买菜", "content" to "鸡蛋"), create["arguments"])

        val update = arguments(MiuiNoteWrites.updateOp(input("noteId" to 7, "title" to "新")))
        assertEquals("note_update", update.str("name"))
        assertEquals(input("noteId" to 7, "title" to "新"), update["arguments"])

        // 读回：和 search_personal 按 id 读的同一个查询（只读得到列表里看得到的便签）
        assertEquals(MiuiNotes.searchOp(SearchRequest(PersonalSource.Notes, null, null, null, 7, 1, 0)), MiuiNotes.readOp(7))
    }

    @Test
    fun readsServiceReplies() {
        val created = MiuiNoteWrites.result(AppToolCall.Reply("{\"success\":true,\"noteId\":123,\"_context_hint\":\"新笔记已创建，ID 为 123\"}", false))
        assertEquals(123L, MiuiNoteWrites.createdId(created))
        assertNull(MiuiNoteWrites.createdId(input("success" to true, "noteId" to -1)))
        failure("便签报错：title=\"新笔记\" 是占位符") {
            MiuiNoteWrites.result(AppToolCall.Reply("title=\"新笔记\" 是占位符,不允许使用。", true))
        }
        failure("便签没做成：笔记不存在") {
            MiuiNoteWrites.result(AppToolCall.Reply("{\"success\":false,\"message\":\"笔记不存在\"}", false))
        }
        failure("便签的回答读不懂") { MiuiNoteWrites.result(AppToolCall.Reply("好的", false)) }
    }

    @Test
    fun checksTheReadBack() {
        val args = MiuiNoteWrites.createArgs(input("title" to "买菜", "content" to "鸡蛋\n牛奶"))
        assertNull(MiuiNoteWrites.mismatch(row("title" to "买菜", "content" to "鸡蛋\r\n牛奶\n"), args))
        assertEquals("正文", MiuiNoteWrites.mismatch(row("title" to "买菜", "content" to "鸡蛋"), args))
        assertEquals("标题和正文", MiuiNoteWrites.mismatch(row("title" to "买", "content" to null), args))
        // 便签存正文时把字面的 \n 换成换行
        assertEquals("a\nb\nc", MiuiNoteWrites.stored("a\\nb\\r\\nc"))
        assertNull(MiuiNoteWrites.mismatch(row("title" to "t", "content" to "a\nb"), MiuiNoteWrites.createArgs(input("title" to "t", "content" to "a\\nb"))))
        // 只改标题时不核对正文
        assertNull(MiuiNoteWrites.mismatch(row("title" to "新", "content" to "原来的"), input("noteId" to 1, "title" to "新")))
    }

    @Test
    fun repliesWithTheNoteAsReadBack() {
        val note = row("id" to 123, "title" to "买菜", "content" to "鸡蛋", "createdAt" to ms, "updatedAt" to ms, "alertDate" to 0, "isPinned" to 0)
        assertEquals(
            "已新建，读回核对一致。\n便签 #123\n标题：买菜\n新建：2026-11-19 09:00\n修改：2026-11-19 09:00\n正文：\n鸡蛋",
            MiuiNoteWrites.saved("新建", note, MiuiNoteWrites.createArgs(input("title" to "买菜", "content" to "鸡蛋")), zone),
        )
        assertTrue(
            MiuiNoteWrites.saved("修改", note, input("noteId" to 123, "content" to "鸭蛋"), zone)
                .startsWith("已修改，但读回的正文和写入的不一样（未确认）：\n便签 #123\n"),
        )
    }
}

class NoteToolsTest {
    @Test
    fun oneSmallTool() {
        val json = kotlinx.serialization.json.JsonArray(NoteTools.tools).toString()
        println("note tools definition: ${json.length} chars")
        assertTrue("definition is ${json.length} chars", json.length <= 1_500)
        assertEquals(listOf("edit_note"), NoteTools.tools.map { (it as JsonObject).str("name") })
    }

    @Test
    fun titlesAreChinese() {
        fun title(vararg pairs: Pair<String, String>) = NoteTools.title("edit_note", buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } })
        assertEquals("新建便签「Zeta 测试」", title("action" to "create", "title" to " Zeta 测试 "))
        assertEquals("新建便签「一二三四五六七八九十一二…」", title("action" to "create", "title" to "一二三四五六七八九十一二三"))
        assertEquals("新建便签", title("action" to "create"))
        assertEquals("修改便签", title("action" to "update"))
        assertEquals("改便签", title("action" to "delete"))
    }
}

class AppToolCallTest {
    @Test
    fun speaksJsonRpcToolsCall() {
        val request = Json.parseToJsonElement(AppToolCall.request("time_now", buildJsonObject { put("x", 1) })).jsonObject
        assertEquals(
            Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"time_now","arguments":{"x":1}}}"""),
            request,
        )
        assertEquals(
            AppToolCall.Reply("{\"now\":1}", false),
            AppToolCall.parse("""{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\"now\":1}"}],"isError":false}}"""),
        )
        assertEquals(
            AppToolCall.Reply("笔记不存在: 9", true),
            AppToolCall.parse("""{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"笔记不存在: 9"}],"isError":true}}"""),
        )
    }

    @Test
    fun protocolErrorsThrow() {
        fun failure(message: String, block: () -> Unit) {
            val e = assertThrows(IllegalStateException::class.java) { block() }
            assertTrue(e.message, e.message!!.startsWith(message))
        }
        // 调用方不是系统应用时服务这样答
        failure("服务拒绝了请求：Caller not authorized: requires system signature") {
            AppToolCall.parse("""{"jsonrpc":"2.0","id":0,"error":{"code":-32600,"message":"Caller not authorized: requires system signature"}}""")
        }
        failure("服务的回答不是 JSON") { AppToolCall.parse("oops") }
        failure("服务的回答里没有 result") { AppToolCall.parse("""{"jsonrpc":"2.0","id":1}""") }
        failure("service not bound") { AppToolCall.reply(buildJsonObject { put("ok", false); put("error", "service not bound: x/y") }) }
        failure("服务没有回答") { AppToolCall.reply(buildJsonObject { put("ok", true) }) }
    }
}
