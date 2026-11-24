package com.dzshzx.zeta.core

import com.dzshzx.zeta.i18n.UiLanguage
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
 * 条目的 JSON（[chatItemJson]、[chatItemOf]）：Go 归约器对照用例的期望结果（helper/testdata/transcript 的
 * <名字>.items.json，Go 的 parity_test 录下）读回来再写出去，和原文件按值相同；比之前两边都去掉零值（Go 写出时省掉 null、false、空串和空数组，Kotlin
 * 全写）。
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
            assertEquals(case.name, dropZero(recorded), dropZero(again))
        }
    }

    @Test
    fun readsTheGoFormWithZeroValuesLeftOut() {
        for (case in cases()) {
            val recorded = Json.parseToJsonElement(case.readText()).jsonArray
            val goForm = recorded.map { dropZero(it) as JsonObject }
            val read = goForm.map(::chatItemOf)
            // 比写出来的 JSON：InlineImage 不是 data class，条目本身按引用比
            assertEquals(case.name, goForm, read.map { dropZero(chatItemJson(it)) })
        }
    }

    /** 只带键的实时图（核心按 image_refs 推来的）读成 [CoreImage]，记下对话在核心里的标识；带着数据的、不知道标识的照旧是 [InlineImage]。 */
    @Test
    fun inlineImagesWithoutDataNeedTheCore() {
        val o =
            Json.parseToJsonElement(
                    """{"kind":"user","id":"u","images":[{"kind":"inline","key":"a:1:0"},""" +
                        """{"kind":"inline","key":"a:1:1","data":"AAAA"}]}""",
                )
                .jsonObject
        val held = (chatItemOf(o, conv = "c3") as UserItem).images
        assertEquals(CoreImage("c3", "a:1:0"), held[0])
        assertEquals("a:1:0", held[0].key)
        assertEquals("AAAA", (held[1] as InlineImage).base64)
        assertTrue((chatItemOf(o) as UserItem).images.all { it is InlineImage })

        val step =
            Json.parseToJsonElement(
                    """{"kind":"work","id":"w","steps":[{"kind":"tool","id":"t","name":"Read",""" +
                        """"images":[{"kind":"inline","key":"t:0"}]}]}""",
                )
                .jsonObject
        val tool = (chatItemOf(step, conv = "c3") as WorkItem).steps.single() as ToolStep
        assertEquals(listOf(CoreImage("c3", "t:0")), tool.images)
    }

    /** 图片文件的缩小图带着 original（它是哪个附件的）：三种用户消息里的图都读得出、写得回。 */
    @Test
    fun imagesKeepTheirOriginal() {
        val o =
            Json.parseToJsonElement(
                    """{"kind":"user","id":"u","files":["/w/a.jpg","/w/b.png","/w/c.gif"],"images":[""" +
                        """{"kind":"inline","key":"a:1:0","original":"/w/a.jpg"},""" +
                        """{"kind":"inline","key":"a:1:1","data":"AAAA","original":"/w/b.png"},""" +
                        """{"kind":"session","file":"/s.jsonl","ref":"3.1","original":"/w/c.gif"},""" +
                        """{"kind":"inline","key":"a:1:2","data":"BBBB"}]}""",
                )
                .jsonObject
        val item = chatItemOf(o, conv = "c3") as UserItem
        assertEquals(
            listOf("/w/a.jpg", "/w/b.png", "/w/c.gif", null),
            item.images.map { it.original },
        )
        assertEquals(
            listOf("/w/a.jpg", "/w/b.png", "/w/c.gif", null),
            (chatItemOf(chatItemJson(item), conv = "c3") as UserItem).images.map { it.original },
        )
    }

    /** 核心自己说的提示带种类码和参数（docs/i18n.md）：读出来、写回去不丢，按界面语言出字；别的种类的条目不读这两项。 */
    @Test
    fun noticesKeepTheirCodeAndArgs() {
        val o =
            Json.parseToJsonElement(
                    """{"kind":"notice","id":"n-1","text":"已停止","code":"noticeStopped","args":{"n":2}}""",
                )
                .jsonObject
        val notice = chatItemOf(o) as NoticeItem
        assertEquals("noticeStopped", notice.code)
        assertEquals(o["args"], notice.args)
        assertEquals("已停止", notice.said())
        assertEquals(o, dropZero(chatItemJson(notice)))
        val unknown =
            Json.parseToJsonElement(
                    """{"kind":"later","id":"x","text":"t","code":"noticeStopped"}""",
                )
                .jsonObject
        assertEquals("", (chatItemOf(unknown) as NoticeItem).code)
    }

    /** 工具步骤的结果是核心写的话时带种类码和参数：读出来、写回去不丢，按界面语言出字；工具自己的结果照原样。 */
    @Test
    fun toolStepsKeepTheirResultCodeAndArgs() {
        val o =
            Json.parseToJsonElement(
                    """{"kind":"work","id":"w","steps":[{"kind":"tool","id":"t","name":"Bash","status":"failed",""" +
                        """"result":"（没有输出）\n退出码 2","result_code":"stepShellNoOutputExit","result_args":{"code":2}},""" +
                        """{"kind":"tool","id":"t2","name":"Read","status":"success","result":"内容"}]}""",
                )
                .jsonObject
        val work = chatItemOf(o) as WorkItem
        val (shell, read) = work.steps.map { it as ToolStep }
        assertEquals("stepShellNoOutputExit", shell.resultCode)
        assertEquals(o["steps"]!!.jsonArray[0].jsonObject["result_args"], shell.resultArgs)
        assertEquals(dropZero(o), dropZero(chatItemJson(work)))
        UiLanguage.current = UiLanguage.ENGLISH
        try {
            assertEquals("(No output)\nExit code 2", shell.resultSaid())
            assertEquals("内容", read.resultSaid())
        } finally {
            UiLanguage.current = UiLanguage.SOURCE
        }
    }

    /** 另一个会话发来的消息（会话互通）：读出发信会话的名字，写回去还是同一个 peer；空串和没写都是自己发的。 */
    @Test
    fun peerMessagesKeepTheSender() {
        val o =
            Json.parseToJsonElement(
                    """{"kind":"user","id":"u","text":"测试完了，结果在 /tmp/r.txt","peer":"home-repo-e8"}""",
                )
                .jsonObject
        val item = chatItemOf(o) as UserItem
        assertEquals("home-repo-e8", item.peer)
        assertEquals("测试完了，结果在 /tmp/r.txt", item.text)
        val written = chatItemJson(item)
        assertEquals(JsonPrimitive("home-repo-e8"), written["peer"])
        assertEquals(item, chatItemOf(written))
        assertEquals(o, dropZero(written))

        val own =
            chatItemOf(
                Json.parseToJsonElement("""{"kind":"user","id":"v","text":"你好","peer":""}""")
                    .jsonObject,
            )
                as UserItem
        assertEquals(null, own.peer)
        assertEquals(JsonNull, chatItemJson(own)["peer"])
        assertEquals(null, (chatItemOf(dropZero(chatItemJson(own)) as JsonObject) as UserItem).peer)
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
