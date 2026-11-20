package com.dzshzx.zeta.core

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTitleTest {
    private val turn = listOf(
        UserItem("u1", "[Attached file: /tmp/a.png]\n帮我看看这张截图里的报错"),
        WorkItem("w1", emptyList()),
        AnswerItem("a1", "这是空指针异常，出在 MainActivity。", streaming = false),
        NoticeItem("n1", "已完成", error = false),
    )

    private fun response(id: String, title: String?, subtype: String = "success") = buildJsonObject {
        put("subtype", subtype)
        put("request_id", id)
        putJsonObject("response") { put("title", title?.let(::JsonPrimitive) ?: JsonNull) }
    }

    @Test
    fun describesQuestionsAndAnswers() {
        assertEquals("帮我看看这张截图里的报错\n\n这是空指针异常，出在 MainActivity。", AutoTitle.describe(turn))
        // 斜杠命令整条跳过，附件行去掉
        assertEquals("答", AutoTitle.describe(listOf(UserItem("u", "/compact"), AnswerItem("a", "答", false))))
        val long = AutoTitle.describe(listOf(UserItem("u", "长".repeat(5000))))
        assertEquals(AutoTitle.MAX_DESCRIPTION, long.length)
    }

    @Test
    fun requestsOnceAndAppliesTheTitle() {
        val auto = AutoTitle()
        // 没 arm（打开的历史会话、分支）不起
        assertNull(auto.next(true, "帮我看看", turn))
        auto.arm("帮我看看")
        val request = auto.next(true, "帮我看看", turn)!!
        val body = request.body
        assertEquals("generate_session_title", body["subtype"]!!.jsonPrimitive.content)
        assertEquals(true, body["persist"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(AutoTitle.describe(turn), body["description"]!!.jsonPrimitive.content)
        // 还没回来不重发
        assertNull(auto.next(true, "帮我看看", turn))
        var applied: String? = null
        assertTrue(auto.onResponse(request, response("t1", "截图报错排查"), "帮我看看") { applied = it })
        assertEquals("截图报错排查", applied)
        // 起成了就不再起
        assertNull(auto.next(true, "截图报错排查", turn))
    }

    /** 重新 arm（/clear 之后）以前发的请求，应答晚到了也不用。 */
    @Test
    fun answerToAnEarlierArmingIsIgnored() {
        val auto = AutoTitle()
        auto.arm("帮我看看")
        val stale = auto.next(true, "帮我看看", turn)!!
        auto.arm("帮我看看")
        assertFalse(auto.onResponse(stale, response("t1", "旧的"), "帮我看看") { throw AssertionError() })
        val fresh = auto.next(true, "帮我看看", turn)!!
        var applied: String? = null
        assertTrue(auto.onResponse(fresh, response("t2", "新的"), "帮我看看") { applied = it })
        assertEquals("新的", applied)
    }

    @Test
    fun retriesWhenTooShortThenGivesUp() {
        val auto = AutoTitle()
        auto.arm("hi")
        // 内容不到 10 个字：先不请求
        assertNull(auto.next(true, "hi", listOf(UserItem("u", "hi"))))
        repeat(AutoTitle.MAX_ATTEMPTS) { i ->
            val request = auto.next(true, "hi", turn)
            assertNotNull(request)
            // Claude Code 嫌内容不够，返回 null：保留原标题，下一轮再试
            assertTrue(auto.onResponse(request!!, response("s${i + 1}", null), "hi") { throw AssertionError() })
        }
        assertNull(auto.next(true, "hi", turn))
    }

    @Test
    fun respectsRenamesErrorsAndTheSwitch() {
        val auto = AutoTitle()
        auto.arm("原句")
        // 关着开关
        assertNull(auto.next(false, "原句", turn))
        assertNull(auto.next(true, "原句", turn))

        auto.arm("原句")
        // 用户已经改了名
        assertNull(auto.next(true, "我的名字", turn))

        auto.arm("原句")
        val renamed = auto.next(true, "原句", turn)!!
        // 应答回来前用户改了名：不用它
        assertTrue(auto.onResponse(renamed, response("x4", "自动标题"), "我的名字") { throw AssertionError() })

        auto.arm("原句")
        val failed = auto.next(true, "原句", turn)!!
        // 出错：不提示，不再试
        assertTrue(auto.onResponse(failed, response("x5", null, subtype = "error"), "原句") { throw AssertionError() })
        assertNull(auto.next(true, "原句", turn))
    }

    /** zeta_helper.py 读标题：改过的名字压过自动标题，不看先后（Claude Code 补写元数据时两行会按 custom、ai 的顺序重写一遍）。 */
    @Test
    fun helperPrefersCustomTitles() {
        val helper = File("src/main/assets/zeta_helper.py")
        assertTrue(helper.path, helper.isFile)
        val home = java.nio.file.Files.createTempDirectory("zeta-helper").toFile()
        try {
            val dir = File(home, ".claude/projects/-p").apply { mkdirs() }
            val user = """{"type":"user","cwd":"/p","message":{"role":"user","content":"帮我看看这张截图里的报错"}}"""
            File(dir, "s1.jsonl").writeText(user + "\n" + """{"type":"ai-title","aiTitle":"截图报错排查","sessionId":"s1"}""" + "\n")
            File(dir, "s2.jsonl").writeText(
                listOf(
                    user,
                    """{"type":"ai-title","aiTitle":"旧的自动标题","sessionId":"s2"}""",
                    """{"type":"custom-title","customTitle":"我改的名字","sessionId":"s2"}""",
                    """{"type":"ai-title","aiTitle":"旧的自动标题","sessionId":"s2"}""",
                ).joinToString("\n", postfix = "\n"),
            )
            File(dir, "s3.jsonl").writeText(user + "\n")
            val process = ProcessBuilder("python3", "-I", "-", "list").directory(home).apply {
                environment().clear()
                environment()["HOME"] = home.path
                environment()["PATH"] = System.getenv("PATH") ?: "/usr/bin:/bin"
                environment()["LANG"] = "C.UTF-8"
            }.start()
            process.outputStream.use { it.write(helper.readBytes()) }
            val out = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            val titles = out.lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it) as JsonObject }
                .associate { it["id"]!!.jsonPrimitive.content to it["title"]!!.jsonPrimitive.content }
            assertEquals(mapOf("s1" to "截图报错排查", "s2" to "我改的名字", "s3" to ""), titles)
        } finally {
            home.deleteRecursively()
        }
    }
}
