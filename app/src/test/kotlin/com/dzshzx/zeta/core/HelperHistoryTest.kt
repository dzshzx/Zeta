package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * zeta_helper.py 的 history（分页）：在临时 HOME 上真跑 python3，不碰任何人真实的 ~/.claude。
 * 记录照 Claude Code 的样子写（紧凑 JSON，一行一条；一轮是用户消息、工具调用、工具结果、回答）。
 */
class HelperHistoryTest {
    private lateinit var home: File
    private val helper = File("src/main/assets/zeta_helper.py")
    private val session = "aaaaaaaa-1111-4222-8333-444444444444"
    private val file get() = File(home, ".claude/projects/-workspace-test/$session.jsonl")
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        assumeTrue("要有 python3", runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false))
        home = Files.createTempDirectory("zeta-history-home").toFile()
        file.parentFile.mkdirs()
    }

    @After
    fun tearDown() {
        if (::home.isInitialized) home.deleteRecursively()
    }

    private fun helper(vararg args: String): List<JsonObject> {
        val process = ProcessBuilder(listOf("python3", "-I", "-") + args).apply { environment()["HOME"] = home.path }.start()
        process.outputStream.use { it.write(helper.readBytes()) }
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        assertEquals(err, 0, process.exitValue())
        return out.lines().filter { it.isNotBlank() }.map { json.parseToJsonElement(it) as JsonObject }
    }

    private val lines = ArrayList<String>()

    private fun write(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        lines += buildJsonObject(build).toString()
    }

    private fun user(uuid: String, text: String) = write {
        put("type", "user"); put("uuid", uuid)
        putJsonObject("message") { put("role", "user"); put("content", text) }
    }

    private fun toolUse(uuid: String, id: String) = write {
        put("type", "assistant"); put("uuid", uuid)
        putJsonObject("message") {
            put("id", "m-$uuid")
            putJsonArray("content") { add(buildJsonObject { put("type", "tool_use"); put("id", id); put("name", "Bash"); putJsonObject("input") { put("command", "ls") } }) }
        }
    }

    private fun toolResult(uuid: String, id: String) = write {
        put("type", "user"); put("uuid", uuid)
        putJsonObject("message") {
            putJsonArray("content") { add(buildJsonObject { put("type", "tool_result"); put("tool_use_id", id); put("content", "ok") }) }
        }
    }

    private fun answer(uuid: String, text: String, model: String? = null, effort: String? = null) = write {
        put("type", "assistant"); put("uuid", uuid)
        effort?.let { put("effort", it) }
        putJsonObject("message") {
            put("id", "m-$uuid")
            model?.let { put("model", it) }
            putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
        }
    }

    private fun rewind(cut: String, leaf: String?) = write {
        put("type", "last-prompt"); put("explicit", true); put("rewound", true); put("cutUuid", cut); put("leafUuid", leaf)
    }

    /** [turns] 轮，每轮 4 条：第 t 轮的用户消息 uuid 是 u<t>，回答是 b<t>。 */
    private fun turns(range: IntRange) {
        for (t in range) {
            user("u$t", "question $t")
            toolUse("a$t", "tool$t")
            toolResult("r$t", "tool$t")
            answer("b$t", "answer $t")
        }
    }

    private fun save() = file.writeText(lines.joinToString("\n", postfix = "\n"))

    private val List<JsonObject>.header get() = first()
    private val List<JsonObject>.events get() = drop(1)
    private fun JsonObject.skipped() = (this["skipped"] as JsonPrimitive).content.toInt()
    private fun JsonObject.anchor() = (this["anchor"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "-"
    private fun JsonObject.text(): String? = (this["message"] as? JsonObject)?.let { message ->
        (message["content"] as? JsonArray)?.firstNotNullOfOrNull { block -> ((block as JsonObject)["text"] as? JsonPrimitive)?.content }
    }

    /** 从最近的一段一页页往前翻到头，按先后拼起来。 */
    private fun readAll(): List<JsonObject> {
        var page = helper("history", file.path)
        val all = ArrayList(page.events)
        while (page.header.skipped() > 0) {
            page = helper("history", file.path, page.header.skipped().toString(), page.header.anchor())
            assertNull(page.header["stale"])
            all.addAll(0, page.events)
        }
        return all
    }

    @Test
    fun opensWithRecentTurnsAndPagesBackWithoutGapsOrRepeats() {
        turns(0 until 500)
        save()
        val tail = helper("history", file.path)
        // 2000 条里先给最近的 800 条，从一轮的开头开始
        assertEquals(1200, tail.header.skipped())
        assertEquals("b299", tail.header.anchor())
        assertEquals("question 300", tail.events.first().text())
        assertEquals(800, tail.events.size)
        val page = helper("history", file.path, "1200", "b299")
        assertEquals(800, page.header.skipped())
        assertEquals("question 200", page.events.first().text())
        assertEquals("answer 299", page.events.last().text())
        // 一页页翻到头：每一轮各出现一次，先后不乱
        val questions = readAll().mapNotNull { it.text()?.takeIf { text -> text.startsWith("question") } }
        assertEquals((0 until 500).map { "question $it" }, questions)
    }

    @Test
    fun pagesNeverSplitAToolCallFromItsResult() {
        // 一轮里一口气调了 600 次工具：找不到一轮的开头，只能在两次调用之间切
        user("u0", "do a lot")
        for (i in 0 until 600) {
            toolUse("a$i", "tool$i")
            toolResult("r$i", "tool$i")
        }
        answer("b0", "done")
        save()
        var page = helper("history", file.path)
        val seen = HashSet<String>()
        while (true) {
            val calls = page.events.flatMap { e -> contentOf(e).filter { it.str("type") == "tool_use" }.map { it.str("id")!! } }.toSet()
            val results = page.events.flatMap { e -> contentOf(e).filter { it.str("type") == "tool_result" }.map { it.str("tool_use_id")!! } }.toSet()
            assertEquals("一页里的工具调用和结果是成对的", calls, results)
            seen.addAll(calls)
            if (page.header.skipped() == 0) break
            page = helper("history", file.path, page.header.skipped().toString(), page.header.anchor())
        }
        assertEquals(600, seen.size)
    }

    private fun contentOf(event: JsonObject): List<JsonObject> =
        ((event["message"] as? JsonObject)?.get("content") as? JsonArray).orEmpty().map { it as JsonObject }

    @Test
    fun rewindMarksStillCutOutTheDroppedTurns() {
        turns(0 until 500)
        // 在已载入的那段里剪掉第 450 轮及以后，再接着聊
        rewind("u450", "b449")
        user("x1", "after rewind")
        answer("x2", "fresh answer")
        save()
        val all = readAll().mapNotNull { it.text() }
        assertTrue("question 449" in all)
        assertTrue(all.none { it == "question 450" || it == "answer 499" })
        assertEquals(listOf("after rewind", "fresh answer"), all.takeLast(2))
    }

    @Test
    fun anEarlierPageIsRefusedOnceTheRecordChangedBeforeIt() {
        turns(0 until 500)
        save()
        val tail = helper("history", file.path)
        // 新内容接在后面、在已载入的那段里剪：前面那页照样给
        rewind("u450", "b449")
        user("x1", "after rewind")
        save()
        assertEquals(800, helper("history", file.path, tail.header.skipped().toString(), tail.header.anchor()).header.skipped())
        // 在别处剪到了还没载入的地方：对不上，不给
        rewind("u250", "b249")
        save()
        assertEquals(JsonPrimitive(true), helper("history", file.path, tail.header.skipped().toString(), tail.header.anchor()).header["stale"])
        // 核对用的那一行不对也不给
        assertEquals(JsonPrimitive(true), helper("history", file.path, "1000", "nope").header["stale"])
    }

    /**
     * 重新打开时：思考强度照最后一次真实回答恢复，它的模型在 Claude Code 报来之前先显示。按整个对话算：
     * 最后一次真实回答在没载入的那段里也照样给。
     */
    @Test
    fun modelAndEffortComeFromTheWholeConversation() {
        user("u-first", "first")
        answer("b-first", "real answer", model = "claude-fable-5-2", effort = "max")
        turns(0 until 500)
        save()
        val tail = helper("history", file.path)
        assertTrue(tail.header.skipped() > 2)
        assertEquals("claude-fable-5-2", tail.header.str("model"))
        assertEquals("max", tail.header.str("effort"))
        val read = HistoryPaging.read(tail.joinToString("\n"), file.path, TranscriptReducer())
        assertEquals("claude-fable-5-2", read.model)
        assertEquals("max", read.effort)
    }

    /** 最后一次真实回答：跳过子代理和 Claude Code 合成的回答（出错提示等），照剪切记号去掉被剪的；老记录不带 effort。 */
    @Test
    fun lastRealAnswerSkipsSidechainsSyntheticAndCutTurns() {
        fun header(vararg raw: String): Pair<String?, String?> {
            lines.clear()
            lines.addAll(raw)
            save()
            val first = helper("history", file.path).header
            return first.str("model") to first.str("effort")
        }
        fun answerLine(uuid: String, model: String, effort: String?, sidechain: Boolean = false) =
            """{"type":"assistant","uuid":"$uuid",""" + (if (sidechain) """"isSidechain":true,""" else "") +
                (effort?.let { """"effort":"$it",""" } ?: "") +
                """"message":{"id":"m-$uuid","model":"$model","content":[{"type":"text","text":"答"}],"usage":{"input_tokens":3,"output_tokens":5}}}"""
        val u1 = """{"type":"user","uuid":"u1","message":{"role":"user","content":"第一问"}}"""
        val u2 = """{"type":"user","uuid":"u2","message":{"role":"user","content":"第二问"}}"""
        assertEquals(
            "claude-fable-5-2" to "xhigh",
            header(
                u1, answerLine("a1", "claude-opus-5-5", "high"), u2, answerLine("a2", "claude-fable-5-2", "xhigh"),
                answerLine("s1", "claude-haiku-5-5", "low", sidechain = true),
                """{"type":"assistant","uuid":"a3","isApiErrorMessage":true,"message":{"id":"m-a3","model":"<synthetic>","content":[{"type":"text","text":"API Error"}]}}""",
            ),
        )
        assertEquals(
            "claude-opus-5-5" to "high",
            header(
                u1, answerLine("a1", "claude-opus-5-5", "high"), u2, answerLine("a2", "claude-fable-5-2", "xhigh"),
                """{"type":"last-prompt","explicit":true,"rewound":true,"cutUuid":"u2","leafUuid":"a1"}""",
            ),
        )
        assertEquals("claude-sonnet-5-5" to null, header(u1, answerLine("a1", "claude-sonnet-5-5", null)))
        assertEquals(
            null to null,
            header(u1, """{"type":"assistant","uuid":"a1","message":{"id":"m","model":"<synthetic>","content":[{"type":"text","text":"Prompt is too long"}]}}"""),
        )
    }
}
