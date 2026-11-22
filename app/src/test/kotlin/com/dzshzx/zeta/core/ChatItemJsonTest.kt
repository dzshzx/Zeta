package com.dzshzx.zeta.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目的 JSON（[chatItemJson]、[chatItemOf]）：归约器对照用例的期望结果（helper/testdata/transcript 的
 * <名字>.items.json）读回来再写出去， 和原文件按值相同；Go 核心省掉零值的写法读出来也一样。
 */
class ChatItemJsonTest {
    private val dir = File("../helper/testdata/transcript")

    private fun cases(): List<File> =
        dir.listFiles { f -> f.name.endsWith(".items.json") }
            .orEmpty()
            .sortedBy { it.name }
            .also { assertTrue("${dir.absolutePath} 下没有 *.items.json", it.isNotEmpty()) }

    @Test
    fun recordedItemsRoundTrip() {
        for (case in cases()) {
            val recorded = Json.parseToJsonElement(case.readText()).jsonArray
            val again = JsonArray(recorded.map { chatItemJson(chatItemOf(it.jsonObject)) })
            assertEquals(case.name, recorded, again)
        }
    }

    @Test
    fun readsTheGoFormWithZeroValuesLeftOut() {
        for (case in cases()) {
            val recorded = Json.parseToJsonElement(case.readText()).jsonArray
            val goForm = recorded.map { dropZero(it) as JsonObject }
            val read = goForm.map(::chatItemOf)
            // 比写出来的 JSON：InlineImage 不是 data class，条目本身按引用比
            assertEquals(case.name, recorded, JsonArray(read.map(::chatItemJson)))
            assertEquals(case.name, goForm, read.map { dropZero(chatItemJson(it)) })
        }
    }

    @Test
    fun unknownKindsKeepTheirPlace() {
        val items =
            Json.parseToJsonElement(
                    """[{"kind":"user","id":"a","text":"你好"},{"kind":"later","id":"b","text":"新种类"},""" +
                        """{"kind":"work","id":"c","steps":[{"kind":"later","id":"x"},{"kind":"thinking","id":"t","text":"想"}]}]""",
                )
                .jsonArray
                .map { chatItemOf(it.jsonObject) }
        assertEquals(listOf("a", "b", "c"), items.map { it.id })
        assertEquals(NoticeItem("b", "新种类", false), items[1])
        assertEquals(listOf(ThinkingStep("t", "想", false, null)), (items[2] as WorkItem).steps)
    }

    /**
     * Go 核心写出的样子：对象里值为 null、false、""、[] 的键省掉（同 helper/transcript/parity_test.go 的 dropZero）。 工具的参数
     * input 是原样转来的，里面不动。
     */
    private fun dropZero(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject ->
                JsonObject(
                    element
                        .filterValues { !isZero(it) }
                        .mapValues { (k, v) -> if (k == "input") v else dropZero(v) },
                )
            is JsonArray -> JsonArray(element.map(::dropZero))
            else -> element
        }

    private fun isZero(v: JsonElement): Boolean =
        v is JsonNull ||
            v is JsonArray && v.isEmpty() ||
            v is JsonPrimitive &&
                (v.isString && v.content.isEmpty() || !v.isString && v.booleanOrNull == false)
}
