package com.dzshzx.zeta.phone.tools

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 剪贴板内容都是编造的。 */
class ClipboardHistoryTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 11, 19, 23, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun ms(text: String) = PersonalQueries.parseTime(text, zone)!!

    private fun request(vararg pairs: Pair<String, Any>) =
        PersonalQueries.searchRequest(
            buildJsonObject {
                put("source", "clipboard")
                for ((k, v) in pairs) if (v is Number) put(k, v) else put(k, v.toString())
            },
            now,
            zone,
        )

    /** 系统键盘存的样子：ClipboardContentModel 的 JSON 数组，content 本身又是一段 JSON 字符串。 */
    private fun miuiList(): String = buildJsonArray {
        addJsonObject {
            put("time", ms("2026-11-19T22:52"))
            put("type", 10)
            put(
                "content",
                buildJsonArray {
                    addJsonObject {
                        put("text", "取件码 8-2-1234")
                        put("dataSize", 13)
                    }
                }
                    .toString(),
            )
            put("isAcrossDevices", false)
            put("deviceName", "")
        }
        addJsonObject {
            put("time", ms("2026-11-19T21:00"))
            put("type", 10)
            put(
                "content",
                buildJsonArray { addJsonObject { put("htmlText", "<b>周会</b> &amp; 复盘") } }
                    .toString(),
            )
        }
        addJsonObject {
            put("time", ms("2026-11-19T20:00"))
            put("type", 13)
            put(
                "content",
                buildJsonArray {
                    addJsonObject {
                        put("uriFileName", "报价单.pdf")
                        put("fileUri", "content://x/1")
                    }
                }
                    .toString(),
            )
        }
        addJsonObject {
            put("time", ms("2026-11-19T19:00"))
            put("type", 10)
            put("content", buildJsonArray { addJsonObject { put("text", "平板上复制的") } }.toString())
            put("isAcrossDevices", true)
            put("deviceName", "Xiaomi Pad")
        }
    }
        .toString()

    private fun prefs(list: String, wrap: Boolean = false): String {
        val encoded =
            if (wrap)
                Base64.getMimeEncoder().encodeToString(list.toByteArray()).replace("\r\n", "&#10;")
            else Base64.getEncoder().encodeToString(list.toByteArray())
        // _temp 那一项是别的设备暂存的，不读；放在前面看会不会认错
        return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
            "    <string name=\"clipboard_cipher_list_temp\">${Base64.getEncoder().encodeToString("[]".toByteArray())}</string>\n" +
            "    <string name=\"clipboard_cipher_list\">$encoded</string>\n</map>\n"
    }

    @Test
    fun parsesTheSystemKeyboardList() {
        val entries = ClipboardHistory.parseMiui(prefs(miuiList()))
        assertEquals(
            listOf(
                ClipEntry(ms("2026-11-19T22:52"), "取件码 8-2-1234"),
                ClipEntry(ms("2026-11-19T21:00"), "周会 & 复盘"),
                ClipEntry(ms("2026-11-19T20:00"), "报价单.pdf", "文件或图片"),
                ClipEntry(ms("2026-11-19T19:00"), "平板上复制的", null, "Xiaomi Pad"),
            ),
            entries,
        )
        // 换行的 base64（换行写成字符实体）也认
        assertEquals(entries, ClipboardHistory.parseMiui(prefs(miuiList(), wrap = true)))
        // 没有这一项、或是空的
        assertEquals(emptyList<ClipEntry>(), ClipboardHistory.parseMiui("<map />"))
        assertEquals(
            emptyList<ClipEntry>(),
            ClipboardHistory.parseMiui(
                "<map><string name=\"clipboard_cipher_list\"></string></map>",
            ),
        )
    }

    @Test
    fun wetypeImagesKeepTheirPath() {
        assertEquals(ClipEntry(5, "随便一段"), ClipboardHistory.wetypeEntry(5, 0, "随便一段", ""))
        assertEquals(
            ClipEntry(6, "/data/user/0/com.tencent.wetype/files/clip/1.png", "图片"),
            ClipboardHistory.wetypeEntry(
                6,
                1,
                "",
                "/data/user/0/com.tencent.wetype/files/clip/1.png",
            ),
        )
    }

    @Test
    fun mergesNewestFirstWithoutDuplicates() {
        val miui = listOf(ClipEntry(300, "同一段 "), ClipEntry(100, "只在系统键盘"))
        val wetype = listOf(ClipEntry(290, "同一段"), ClipEntry(200, "只在微信输入法"), ClipEntry(50, "同一段"))
        assertEquals(
            listOf(ClipEntry(300, "同一段 "), ClipEntry(200, "只在微信输入法"), ClipEntry(100, "只在系统键盘")),
            ClipboardHistory.merge(miui, wetype),
        )
    }

    private val sample =
        listOf(
            ClipEntry(ms("2026-11-19T22:52"), "取件码 8-2-1234"),
            ClipEntry(ms("2026-11-19T20:00"), "报价单.pdf", "文件或图片"),
            ClipEntry(
                ms("2026-11-19T19:00"),
                "Meeting LINK https://example.com/m/1",
                null,
                "Xiaomi Pad",
            ),
            ClipEntry(ms("2026-11-18T09:00"), "昨天的一段\n两行"),
        )

    @Test
    fun filtersByKeywordAndWindow() {
        assertEquals(
            listOf(sample[2]),
            ClipboardHistory.filter(sample, request("query" to "meeting link")),
        )
        assertEquals(
            sample.take(3),
            ClipboardHistory.filter(sample, request("start" to "2026-11-19")),
        )
        assertEquals(
            listOf(sample[3]),
            ClipboardHistory.filter(sample, request("end" to "2026-11-19")),
        )
    }

    @Test
    fun formatsPagesAndFullEntries() {
        val text =
            ClipboardHistory.format(
                sample,
                request("limit" to 2),
                zone,
                now,
                listOf("微信输入法的剪贴板没读到：不存在"),
            )
        val lines = text.lines()
        assertTrue(lines[0], lines[0].startsWith("剪贴板历史 2 条（新的在前；"))
        assertTrue(lines[0], lines[0].endsWith("；还有更多，用 offset=2 继续"))
        assertEquals("#${ms("2026-11-19T22:52")} 2026-11-19 22:52: 取件码 8-2-1234", lines[1])
        assertEquals("#${ms("2026-11-19T20:00")} 2026-11-19 20:00 [文件或图片]: 报价单.pdf", lines[2])
        // 有一处没读到时照实说，不当成空
        assertEquals("（微信输入法的剪贴板没读到：不存在）", lines[3])

        val second =
            ClipboardHistory.format(sample, request("limit" to 2, "offset" to 2), zone, now).lines()
        assertEquals(
            "#${ms("2026-11-19T19:00")} 2026-11-19 19:00 [来自 Xiaomi Pad]: Meeting LINK https://example.com/m/1",
            second[1],
        )
        assertEquals("#${ms("2026-11-18T09:00")} 2026-11-18 09:00: 昨天的一段 两行", second[2])
        assertTrue(second[0], !second[0].contains("还有更多"))

        assertEquals(
            "没有符合条件的剪贴板记录。",
            ClipboardHistory.format(sample, request("query" to "不存在的词"), zone, now),
        )

        val full =
            ClipboardHistory.format(sample, request("id" to ms("2026-11-18T09:00")), zone, now)
        assertEquals("剪贴板记录 #${ms("2026-11-18T09:00")}\n复制于：2026-11-18 09:00\n内容：\n昨天的一段\n两行", full)
        assertTrue(
            ClipboardHistory.format(sample, request("id" to 12), zone, now)
                .startsWith("没有编号为 12 的剪贴板记录"),
        )
    }

    @Test
    fun registeredAsASource() {
        assertEquals(PersonalSource.Clipboard, PersonalSource.of("clipboard"))
        val title =
            PersonalTools.title(
                "search_personal",
                buildJsonObject {
                    put("source", JsonPrimitive("clipboard"))
                    put("query", "取件码")
                },
            )
        assertEquals("在剪贴板历史中搜索「取件码」", title)
    }
}
