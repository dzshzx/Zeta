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
 * zeta_helper.py 的 history（分页）和 history-find：在临时 HOME 上真跑 python3，不碰任何人真实的 ~/.claude。
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
    fun throughLoadsDownToTheTurnHoldingThatMessage() {
        turns(0 until 500)
        save()
        // 第 102 条是第 25 轮的工具结果：这一页从第 25 轮的开头（第 100 条）开始
        val page = helper("history", file.path, "1200", "b299", "102")
        assertEquals(100, page.header.skipped())
        assertEquals("question 25", page.events.first().text())
        assertEquals(1100, page.events.size)
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
        assertEquals(JsonPrimitive(true), helper("history-find", file.path, tail.header.skipped().toString(), tail.header.anchor(), "question").header["stale"])
        // 核对用的那一行不对也不给
        assertEquals(JsonPrimitive(true), helper("history", file.path, "1000", "nope").header["stale"])
    }

    @Test
    fun findCountsSpokenTextBeforeTheLoadedPart() {
        user("u0", "[Attached file: /tmp/needle.txt]\nlook at this needle")
        toolUse("a0", "needle-tool")
        toolResult("r0", "needle-tool")
        answer("b0", "the **needle** is [here](https://needle.example) and needle again")
        user("u1", "no match")
        answer("b1", "nothing")
        user("u2", "needle in the loaded part")
        save()
        val found = helper("history-find", file.path, "6", "b1", "NEEDLE").header
        // 附件行、工具调用和结果、链接地址不算；不分大小写
        assertEquals("[[0,1],[3,2]]", found["matches"].toString())
        assertEquals(listOf(EarlierHits(0, 1), EarlierHits(3, 2)), HistoryPaging.hits(found.toString()))
    }

    /** 重新打开时恢复的模型和思考强度按整个对话算：最后一次真实回答在没载入的那段里也照样给。 */
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
}
