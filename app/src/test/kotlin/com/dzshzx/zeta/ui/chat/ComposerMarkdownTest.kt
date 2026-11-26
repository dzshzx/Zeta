package com.dzshzx.zeta.ui.chat

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入框的 Markdown 扫描和桌面版（Go 的 `client/mdmarks`）跑同一份用例：两边认的写法一样。 */
class ComposerMarkdownTest {
    private val order =
        compareBy<Pair<String, Pair<Int, Int>>>(
            { it.second.first },
            { it.second.second },
            { it.first },
        )

    @Test
    fun sharedCases() {
        val cases =
            Json.parseToJsonElement(File("../client/mdmarks/testdata/cases.json").readText())
                .jsonArray
        assertTrue("cases.json 里没有用例", cases.isNotEmpty())
        for (case in cases) {
            val obj = case.jsonObject
            val text = obj["text"]!!.jsonPrimitive.content
            // 用例按码位数算，这边按 UTF-16 下标算
            val index = { cp: Int -> text.offsetByCodePoints(0, cp) }
            val want =
                obj["spans"]!!.jsonArray.map {
                    val s = it.jsonObject
                    val at = s["at"]!!.jsonPrimitive.int
                    val part = s["text"]!!.jsonPrimitive.content
                    val start = index(at)
                    assertEquals(
                        "$text：用例的 at 对不上原文",
                        part,
                        text.substring(start, start + part.length),
                    )
                    s["kind"]!!.jsonPrimitive.content to (start to start + part.length)
                }
            // 两边都不排序，按起点、终点、样式名排好再比；样式名是枚举名首字母小写（同 Go 的 Kind）
            val got =
                composerMarkdownSpans(text).map {
                    it.mark.name.replaceFirstChar(Char::lowercaseChar) to (it.start to it.end)
                }
            assertEquals(text, want.sortedWith(order), got.sortedWith(order))
        }
    }
}
