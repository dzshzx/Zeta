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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 照片、文字、地点都是编造的。 */
class PhotoContentTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 11, 19, 10, 30, 0, 0, zone).toInstant().toEpochMilli()

    private fun ms(text: String) = PersonalQueries.parseTime(text, zone)!!

    private fun request(vararg pairs: Pair<String, Any>) = PersonalQueries.searchRequest(
        buildJsonObject {
            put("source", "photo_content")
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
    fun needsAQueryAndTakesNoId() {
        assertThrows(BadInput::class.java) { PhotoContent.searchOps(request()) }
        assertThrows(BadInput::class.java) { request("id" to 5) }
        assertThrows(BadInput::class.java) { PersonalQueries.ops(request("query" to " ")) }
    }

    @Test
    fun searchesTextTagsAndPlaceWithBoundEscapedArgs() {
        val r = request("query" to "50%_off", "start" to "2026-11-01", "end" to "2026-11-10", "limit" to 5, "offset" to 10)
        val (gallery, tags, probe) = PhotoContent.searchOps(r)
        assertEquals(PhotoContent.searchOps(r), PersonalQueries.ops(r))

        assertEquals(PhotoContent.GALLERY, gallery.str("uri"))
        assertEquals(PhotoContent.GALLERY_COLUMNS, gallery.arr("projection")!!.map { it.jsonPrimitive.content })
        val selection = gallery.str("selection")!!
        // 分页在子查询里：多取一条；Provider 不认 offset，辅助进程那边不再跳
        assertTrue(selection, selection.endsWith("ORDER BY timestamp DESC, long_id DESC LIMIT 6 OFFSET 10))"))
        assertEquals(5, gallery.int("limit"))
        assertNull(gallery["offset"])
        assertEquals("timestamp DESC, long_id DESC", gallery.str("sort"))
        assertEquals(PhotoContent.OCR_CHARS, gallery.int("maxChars"))
        val like = "%50\\%\\_off%"
        assertEquals(
            listOf(like, "50%_off", "50%_off", like, like, like, ms("2026-11-01").toString(), ms("2026-11-10").toString()),
            gallery.arr("args")!!.map { it.jsonPrimitive.content },
        )
        // 每个 ? 都有参数
        assertEquals(selection.count { it == '?' }, gallery.arr("args")!!.size)

        assertEquals(PhotoContent.SCENE_TAG, tags.str("uri"))
        assertEquals(listOf("50%_off", "50%_off", like), tags.arr("args")!!.map { it.jsonPrimitive.content })
        assertEquals(3, tags.str("selection")!!.count { it == '?' })

        assertEquals(PhotoContent.GALLERY, probe.str("uri"))
        assertEquals(1, probe.int("limit"))
        assertEquals(0, probe.arr("args")!!.size)
    }

    @Test
    fun mediaLookupByPath() {
        val op = PhotoContent.mediaOp(listOf("/storage/emulated/0/DCIM/Camera/a.jpg", "/storage/emulated/0/Pictures/b.png"))
        assertEquals(PersonalQueries.FILES, op.str("uri"))
        assertEquals("(_data IN (?, ?))", op.str("selection"))
        assertEquals(2, op.int("limit"))
    }

    @Test
    fun snippetsKeepContextAroundTheFirstHit() {
        assertEquals("车票 G1234 次", PhotoContent.snippet("车票\n G1234   次", "g1234", 40))
        val long = "甲".repeat(100) + "订单号 778899" + "乙".repeat(100)
        val s = PhotoContent.snippet(long, "订单号", 30)!!
        assertTrue(s, s.startsWith("…") && s.endsWith("…") && "订单号" in s)
        assertEquals(30 + 2, s.length)
        // 前文约三分之一
        assertEquals(1 + (30 - 3) / 3, s.indexOf("订单号"))
        assertEquals("订单号 7…", PhotoContent.snippet("订单号 778899", "订单号", 5))
        assertNull(PhotoContent.snippet("没有", "发票", 30))
    }

    @Test
    fun matchedTagsComeFromTheImagesOwnTags() {
        val matched = mapOf(109L to "猫", 900L to "熊猫")
        assertEquals(listOf("猫"), PhotoContent.matchedTags("2216,109,5", matched))
        assertEquals(emptyList<String>(), PhotoContent.matchedTags(null, matched))
        assertEquals(emptyList<String>(), PhotoContent.matchedTags("2216", matched))
    }

    private val day = ms("2026-11-03T14:22")

    private fun media(id: Long, path: String, type: Long = 1, duration: Long? = null) = row(
        "_id" to id, "_data" to "/storage/emulated/0/$path", "datetaken" to day, "date_added" to day / 1000, "media_type" to type,
        "duration" to duration, "relative_path" to path.substringBeforeLast('/') + "/", "_display_name" to path.substringAfterLast('/'),
    )

    @Test
    fun formatsHitsWithMediaStoreIdsAndSkipsDeletedPhotos() {
        val r = request("query" to "猫", "limit" to 4, "offset" to 8)
        val gallery = listOf(
            row("long_id" to "gallery_1", "timestamp" to day, "file_path" to "/storage/emulated/0/DCIM/Camera/a.jpg", "tags" to "2216,109", "ocr" to null),
            row("long_id" to "gallery_2", "timestamp" to day, "file_path" to "/storage/emulated/0/DCIM/Gone/old.jpg", "tags" to "109"),
            row(
                "long_id" to "gallery_3", "timestamp" to day, "file_path" to "/storage/emulated/0/Pictures/Screenshots/s.png", "tags" to "0",
                "ocr" to "领养启事\n橘猫一只，两岁", "formatLocation" to "某市", "detailLocation" to "某市猫咪街",
            ),
            row("long_id" to "gallery_4", "timestamp" to day, "file_path" to "/storage/emulated/0/DCIM/Camera/v.mp4", "tags" to "109,2216"),
        )
        val tags = listOf(row("tag_id" to 109, "scene_tag" to "cat"), row("tag_id" to 109, "scene_tag" to "猫"), row("tag_id" to 109, "scene_tag" to "Katze"))
        val media = listOf(
            media(41, "DCIM/Camera/a.jpg"),
            media(43, "Pictures/Screenshots/s.png"),
            media(44, "DCIM/Camera/v.mp4", type = 3, duration = 65_000),
        )
        val text = PhotoContent.format(gallery, true, tags, media, false, r, zone, now)
        val lines = text.lines()
        assertEquals(
            "照片内容 3 条（文字、标签、地点里找「猫」，按拍摄时间新的在前，时间为 UTC+08:00；用 view_media 看画面，视频加 kind: video）" +
                "；另有 1 张在索引里但照片已删除，没有列出；还有更多，用 offset=12 继续",
            lines[0],
        )
        assertEquals("#41 2026-11-03 14:22 DCIM/Camera/a.jpg — 标签 猫", lines[1])
        assertEquals("#43 2026-11-03 14:22 Pictures/Screenshots/s.png — 文字「领养启事 橘猫一只，两岁」；地点 某市猫咪街", lines[2])
        assertEquals("#44 2026-11-03 14:22 DCIM/Camera/v.mp4 [视频 1m05s] — 标签 猫", lines[3])
        assertEquals(4, lines.size)
    }

    @Test
    fun saysWhenTheHitIsBeyondTheFetchedText() {
        val r = request("query" to "合同编号")
        val gallery = listOf(row("long_id" to "gallery_1", "timestamp" to day, "file_path" to "/storage/emulated/0/DCIM/Camera/a.jpg", "ocr" to "开头"))
        val text = PhotoContent.format(gallery, false, emptyList(), listOf(media(41, "DCIM/Camera/a.jpg")), false, r, zone, now)
        assertTrue(text, text.lines()[1].endsWith("— 文字（命中处在取到的前 ${PhotoContent.OCR_CHARS} 字之外）"))
    }

    @Test
    fun emptyResultsExplainWhy() {
        val r = request("query" to "发票")
        assertEquals(PhotoContent.INDEX_EMPTY, PhotoContent.format(emptyList(), false, emptyList(), emptyList(), true, r, zone, now))
        val none = PhotoContent.format(emptyList(), false, emptyList(), emptyList(), false, r, zone, now)
        assertTrue(none, none.startsWith("照片内容里没有找到「发票」"))
        // 这一页的照片都已删除：仍给出继续翻页的提示
        val gone = PhotoContent.format(
            listOf(row("long_id" to "gallery_9", "timestamp" to day, "file_path" to "/x.jpg", "ocr" to "发票")), true, emptyList(), emptyList(), false,
            request("query" to "发票", "limit" to 1), zone, now,
        )
        assertTrue(gone, gone.startsWith("照片内容 0 条") && gone.contains("另有 1 张在索引里但照片已删除") && gone.endsWith("用 offset=1 继续"))
    }

    @Test
    fun titleNamesTheSource() {
        assertEquals("在照片内容里找「猫」", PersonalTools.title("search_personal", buildJsonObject { put("source", "photo_content"); put("query", "猫") }))
    }
}
