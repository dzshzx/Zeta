package com.dzshzx.zeta.phone.tools

import android.database.sqlite.SQLiteDatabase
import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.str
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 把 [PhotoContent.searchOps] 拼出的条件放到和小米澎湃 AI 引擎同样结构的表上真跑一遍（SQLite，内存库），照它的 AiSearchDataProvider
 * 的拼法：`SELECT 列 FROM 表 WHERE (条件) ORDER BY 排序`，参数绑定。表里的照片、文字、地点都是编造的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PhotoContentSqlTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 11, 19, 10, 30, 0, 0, zone).toInstant().toEpochMilli()
    private val day = 86_400_000L
    private lateinit var db: SQLiteDatabase

    @Before
    fun tables() {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE gallery (long_id TEXT NOT NULL PRIMARY KEY, uri_id INTEGER NOT NULL, timestamp INTEGER, file_path TEXT, " +
                "formatLocation TEXT, detailLocation TEXT)",
        )
        db.execSQL(
            "CREATE TABLE gallery_ocr (lucene_id TEXT NOT NULL PRIMARY KEY, parent_id TEXT, content TEXT, section TEXT)",
        )
        db.execSQL(
            "CREATE TABLE gallery_scene (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, lucene_id TEXT, parent_id TEXT, tag INTEGER NOT NULL, " +
                "confidence REAL)",
        )
        db.execSQL(
            "CREATE TABLE scene_tag (id INTEGER PRIMARY KEY, tag_id INTEGER, scene_tag TEXT, language TEXT, parent_tag_id INTEGER, threshold REAL)",
        )
        listOf(
                arrayOf<Any?>(109, "猫", "chinese", 2216, 0.5),
                arrayOf<Any?>(109, "cat", "english", 2216, 0.5),
                arrayOf<Any?>(2216, "动物", "chinese", null, 0.0),
                arrayOf<Any?>(900, "熊猫", "chinese", 2216, 0.0),
                arrayOf<Any?>(0, "文档", "chinese", null, null),
            )
            .forEach {
                db.execSQL(
                    "INSERT INTO scene_tag (tag_id, scene_tag, language, parent_tag_id, threshold) VALUES (?, ?, ?, ?, ?)",
                    it,
                )
            }
        photo(1, ocr = listOf("Invoice No. 123 Zeta"))
        photo(2, tags = listOf(109 to 0.9, 2216 to null), place = "杭州市" to "杭州市西湖区")
        photo(3, tags = listOf(109 to 0.2))
        photo(4, tags = listOf(900 to 0.95))
        photo(5, ocr = listOf("折扣 5x0 Zeta"))
        photo(6, ocr = listOf("第一段", "第二张发票 zeta"))
    }

    @After fun close() = db.close()

    /** 第 [n] 张：越大越早拍。 */
    private fun photo(
        n: Int,
        ocr: List<String> = emptyList(),
        tags: List<Pair<Int, Double?>> = emptyList(),
        place: Pair<String, String>? = null,
    ) {
        db.execSQL(
            "INSERT INTO gallery VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                "gallery_$n",
                n,
                now - n * day,
                "/storage/emulated/0/DCIM/Camera/$n.jpg",
                place?.first,
                place?.second,
            ),
        )
        ocr.forEachIndexed { i, text ->
            db.execSQL(
                "INSERT INTO gallery_ocr VALUES (?, ?, ?, ?)",
                arrayOf<Any?>("gallery_${n}_$i", "gallery_$n", text, "0 1"),
            )
        }
        tags.forEach { (tag, confidence) ->
            db.execSQL(
                "INSERT INTO gallery_scene (parent_id, tag, confidence) VALUES (?, ?, ?)",
                arrayOf<Any?>("gallery_$n", tag, confidence),
            )
        }
    }

    private fun request(query: String, vararg pairs: Pair<String, Any>) =
        PersonalQueries.searchRequest(
            buildJsonObject {
                put("source", "photo_content")
                put("query", query)
                for ((k, v) in pairs) if (v is Number) put(k, v) else put(k, v.toString())
            },
            now,
            zone,
        )

    /** 照 Provider 的拼法执行 [op]，返回第一列；tags、ocr 是它另外拼的列，这里不取。 */
    private fun run(op: JsonObject, table: String): List<String> {
        val columns =
            op.arr("projection")!!
                .map { it.jsonPrimitive.content }
                .filter { it != "tags" && it != "ocr" }
        val sql =
            "SELECT " +
                columns.joinToString(", ") +
                " FROM " +
                table +
                (op.str("selection")?.let { " WHERE ($it)" } ?: "") +
                (op.str("sort")?.let { " ORDER BY $it" } ?: "")
        val args = op.arr("args")?.map { it.jsonPrimitive.content }?.toTypedArray()
        return db.rawQuery(sql, args).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    private fun photos(query: String, vararg pairs: Pair<String, Any>) =
        run(PhotoContent.searchOps(request(query, *pairs))[0], "gallery")

    private fun tags(query: String) = run(PhotoContent.searchOps(request(query))[1], "scene_tag")

    @Test
    fun exactTagWinsAndRespectsItsThreshold() {
        // 有叫「猫」的标签：不退到「熊猫」；第 3 张的置信度低于这个标签的门槛
        assertEquals(listOf("gallery_2"), photos("猫"))
        assertEquals(listOf("109"), tags("猫"))
        assertEquals(listOf("gallery_2"), photos("CAT"))
        // 置信度为空的标签照样算
        assertEquals(listOf("gallery_2"), photos("动物"))
    }

    @Test
    fun fallsBackToTagsContainingTheWord() {
        assertEquals(listOf("gallery_4"), photos("熊"))
        assertEquals(listOf("900"), tags("熊"))
    }

    @Test
    fun matchesTextAndPlace() {
        assertEquals(listOf("gallery_1"), photos("invoice"))
        assertEquals(listOf("gallery_6"), photos("发票"))
        assertEquals(listOf("gallery_2"), photos("西湖"))
        // LIKE 的通配符按原样找
        assertEquals(emptyList<String>(), photos("5_0"))
        assertEquals(emptyList<String>(), photos("长城"))
    }

    @Test
    fun pagesNewestFirstWithOneExtraRow() {
        assertEquals(listOf("gallery_1", "gallery_5"), photos("zeta", "limit" to 1))
        assertEquals(listOf("gallery_5", "gallery_6"), photos("zeta", "limit" to 1, "offset" to 1))
        assertEquals(listOf("gallery_6"), photos("zeta", "limit" to 1, "offset" to 2))
        // 时间窗口：第 6 张拍在窗口之前
        val start = PersonalQueries.formatTime(now - 5 * day - 1, zone).replace(' ', 'T')
        assertEquals(listOf("gallery_1", "gallery_5"), photos("zeta", "start" to start))
    }

    @Test
    fun probeTellsAnEmptyIndex() {
        val probe = PhotoContent.searchOps(request("x"))[2]
        assertEquals(listOf("gallery_1"), run(probe, "gallery").take(1))
        db.execSQL("DELETE FROM gallery")
        assertEquals(emptyList<String>(), run(probe, "gallery"))
    }
}
