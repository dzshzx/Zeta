package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 便签内容都是编造的。 */
class MiuiNotesTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 11, 19, 10, 30, 0, 0, zone).toInstant().toEpochMilli()

    private fun ms(text: String) = PersonalQueries.parseTime(text, zone)!!

    private fun request(vararg pairs: Pair<String, Any>) = PersonalQueries.searchRequest(
        buildJsonObject {
            put("source", "notes")
            for ((k, v) in pairs) if (v is Number) put(k, v) else put(k, v.toString())
        },
        now, zone,
    )

    private fun row(vararg pairs: Pair<String, Any?>): Row = pairs.associate { (key, value) ->
        key to when (value) {
            null -> JsonNull
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        } as JsonElement
    }

    @Test
    fun searchesVisibleNotesByTitleTextOrTranscript() {
        val op = MiuiNotes.searchOp(request("query" to "购物_清单", "start" to "2026-11-01"))
        assertEquals(MiuiNotes.NOTES, op.str("uri"))
        assertEquals(
            "(${MiuiNotes.VISIBLE}) AND (title LIKE ? ESCAPE '\\' OR content LIKE ? ESCAPE '\\' OR transcriptText LIKE ? ESCAPE '\\') " +
                "AND (updatedAt >= CAST(? AS INTEGER))",
            op.str("selection"),
        )
        assertEquals(
            listOf("%购物\\_清单%", "%购物\\_清单%", "%购物\\_清单%", ms("2026-11-01").toString()),
            op.arr("args")!!.map { it.jsonPrimitive.content },
        )
        // 便签的 Provider 只认「列名 ASC/DESC」逗号连起来的排序
        assertEquals("updatedAt DESC, id DESC", op.str("sort"))
        assertEquals(MiuiNotes.LIST_COLUMNS, op.arr("projection")!!.map { it.jsonPrimitive.content })
        // 走 PersonalQueries.ops 也是同一个查询
        assertEquals(op, PersonalQueries.ops(request("query" to "购物_清单", "start" to "2026-11-01")).single())

        val byId = MiuiNotes.searchOp(request("id" to 12))
        assertEquals("(${MiuiNotes.VISIBLE}) AND (id = ?)", byId.str("selection"))
        assertEquals(listOf("12"), byId.arr("args")!!.map { it.jsonPrimitive.content })
        assertEquals(MiuiNotes.NOTE_COLUMNS, byId.arr("projection")!!.map { it.jsonPrimitive.content })
        assertEquals(1, byId.int("limit"))
    }

    @Test
    fun plainTextOnlyRewritesOldRichText() {
        val rich = "<new-format/><text indent=\"1\"><b>周末</b>清单</text>\n" +
            "<input type=\"checkbox\" indent=\"1\" level=\"3\" checked=\"true\" />牛奶\n" +
            "<input type=\"checkbox\" indent=\"1\" level=\"3\" />面包 &amp; 鸡蛋\n" +
            "<hr />\n☺ 1a2b3c\n<sound fileid=\"x\" />\n\n\n\n1 &lt; 2"
        assertEquals("周末清单\n[x] 牛奶\n[ ] 面包 & 鸡蛋\n---\n[图片]\n[录音]\n\n1 < 2", MiuiNotes.plainText(rich))
        // 纯文字原样：尖括号和转义写法都不动，只理换行
        assertEquals("a < b and &amp;\n\nc", MiuiNotes.plainText(" a < b and &amp;\r\n\r\n\r\n\r\nc "))
        assertEquals("", MiuiNotes.plainText(null))
    }

    @Test
    fun formatsListAndFullNote() {
        val rows = listOf(
            row("id" to 12, "title" to "周末", "content" to "清单\n牛奶", "updatedAt" to ms("2026-11-19T09:00"), "alertDate" to ms("2026-11-20T08:00"), "isPinned" to 1),
            row("id" to 11, "title" to "", "content" to "", "updatedAt" to ms("2026-11-18T09:00"), "alertDate" to null, "isPinned" to 0, "transcriptText" to "语音里说的"),
            row("id" to 10, "title" to null, "content" to null, "updatedAt" to ms("2026-11-17T09:00")),
        )
        val lines = MiuiNotes.formatList(rows, false, request(), zone, now).lines()
        assertEquals("便签 3 条（按修改时间新的在前；用 id 读全文，时间为 UTC+08:00）", lines[0])
        assertEquals("#12 2026-11-19 09:00 [置顶] [提醒 2026-11-20 08:00]: 周末 — 清单 牛奶", lines[1])
        assertEquals("#11 2026-11-18 09:00: 语音里说的", lines[2])
        assertEquals("#10 2026-11-17 09:00: (空)", lines[3])

        val note = MiuiNotes.formatNote(
            row(
                "id" to 12, "title" to "周末", "content" to "<text>第一段</text>\n第二段", "createdAt" to ms("2026-11-18T20:00"),
                "updatedAt" to ms("2026-11-19T09:00"), "alertDate" to 0, "isPinned" to 0, "transcriptText" to "转写的话",
            ),
            zone,
        )
        assertEquals("便签 #12\n标题：周末\n新建：2026-11-18 20:00\n修改：2026-11-19 09:00\n正文：\n第一段\n第二段\n转写：\n转写的话", note)
        assertEquals("便签 #3（置顶）\n正文：\n(空)", MiuiNotes.formatNote(row("id" to 3, "isPinned" to 1), zone))
    }
}
