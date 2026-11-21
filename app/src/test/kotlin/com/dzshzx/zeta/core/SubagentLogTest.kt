package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 子代理查看页的数据：zeta-helper subagent 在临时 HOME 上按偏移增量读子代理记录，[SubagentTranscript] 归约成聊天条目，
 * 输出流里的事件只补文件还没写到的部分。记录的格式照 Claude Code 2.1.328 的真实样子删减（真跑一遍的见 ClaudeTaskContractTest）。
 */
class SubagentLogTest {
    private lateinit var home: File
    private val session = "aaaaaaaa-1111-4222-8333-444444444444"
    private val dir
        get() = File(home, ".claude/projects/-workspace-test/$session/subagents")

    private val record
        get() = File(dir, "agent-a1b2c3.jsonl")

    private val meta
        get() = File(dir, "agent-a1b2c3.meta.json")

    @Before
    fun setUp() {
        home = Files.createTempDirectory("zeta-subagent-home").toFile().canonicalFile
        dir.mkdirs()
        File(home, ".claude/projects/-workspace-test/$session.jsonl")
            .writeText("{\"type\":\"user\"}\n")
        meta.writeText(
            """{"agentType":"general-purpose","description":"look around","toolUseId":"toolu_A","requestShape":"background","model":"claude-opus-5-5"}""",
        )
    }

    @After
    fun tearDown() {
        if (::home.isInitialized) home.deleteRecursively()
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val process =
            ProcessBuilder(listOf(TestHelper.path) + args)
                .apply { environment()["HOME"] = home.path }
                .start()
        process.outputStream.close()
        val out = process.inputStream.bufferedReader().readText()
        process.errorStream.readBytes()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        return process.exitValue() to out
    }

    /** 从 [t] 上次读到的地方接着读一次。 */
    private fun read(
        t: SubagentTranscript,
        hint: String = "-",
        use: String = t.toolUseId,
    ): Boolean {
        val (code, out) =
            run("subagent", t.offset.toString(), t.line.toString(), use, hint, session)
        assertEquals(out, 0, code)
        return t.applyRead(out)
    }

    private fun line(text: String) = text.trimIndent().replace("\n", "") + "\n"

    private val prompt =
        line(
            """{"type":"user","isSidechain":true,"agentId":"a1b2c3","message":{"role":"user","content":"SUBAGENT look around"},"uuid":"u0"}""",
        )
    private val attachment =
        line(
            """{"type":"attachment","isSidechain":true,"agentId":"a1b2c3","attachment":{"type":"skill_listing"},"uuid":"u1"}""",
        )
    private val requestBlob =
        line(
            """{"type":"api-request-blob","agentId":"a1b2c3","message":{"role":"user","content":[{"type":"text","text":"x"}]}}""",
        )
    private val thinkingText =
        line(
            """{"type":"assistant","isSidechain":true,"message":{"id":"msg_1","role":"assistant","content":[{"type":"text","text":"checking first"}]},"uuid":"u2"}""",
        )
    private val bashCall =
        line(
            """{"type":"assistant","isSidechain":true,"message":{"id":"msg_1","role":"assistant","content":[{"type":"tool_use","id":"toolu_s0","name":"Bash",
        "input":{"command":"echo hi","description":"sub step"}}]},"uuid":"u3"}""",
        )
    private val bashResult =
        line(
            """{"type":"user","isSidechain":true,"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_s0","content":"hi","is_error":false}]},
        "toolUseResult":{"stdout":"hi"},"uuid":"u4"}""",
        )
    private val report =
        line(
            """{"type":"assistant","isSidechain":true,"message":{"id":"msg_2","role":"assistant","content":[{"type":"text","text":"REPORT: all good"}]},"uuid":"u5"}""",
        )

    @Test
    fun recordIsFoundByItsAgentCallAndReadIncrementally() {
        // 结果那一行还没写完：这次只交出前面的整行
        record.writeText(
            prompt + attachment + requestBlob + thinkingText + bashCall + bashResult.dropLast(20),
        )
        val t = SubagentTranscript("toolu_A")
        assertTrue(read(t))
        assertEquals("SUBAGENT look around", t.prompt)
        assertEquals(
            SubagentMeta(
                "a1b2c3",
                "general-purpose",
                "look around",
                "claude-opus-5-5",
                finished = false,
                stopped = false,
                background = true,
            ),
            t.meta,
        )
        assertEquals(record.canonicalPath, t.file)
        assertEquals(
            (prompt + attachment + requestBlob + thinkingText + bashCall)
                .toByteArray()
                .size
                .toLong(),
            t.offset,
        )
        assertEquals(5, t.line)
        assertFalse(t.more)
        val running = t.items(running = true)
        assertEquals("checking first", (running[0] as AnswerItem).text)
        val step = (running[1] as WorkItem).steps.single() as ToolStep
        assertEquals("toolu_s0", step.id)
        assertTrue(step.running)
        assertEquals(1, t.toolUses())

        // 写完了：从上次的位置接着读，结果补上，报告接在后面
        record.appendText(bashResult.takeLast(20) + report)
        meta.writeText(
            """{"agentType":"general-purpose","description":"look around","toolUseId":"toolu_A","requestShape":"background","finished":true}""",
        )
        assertTrue(read(t))
        assertTrue(t.meta!!.finished && t.meta!!.ended)
        val done = t.items(running = false)
        assertEquals(3, done.size)
        assertEquals(ToolStatus.Success, ((done[1] as WorkItem).steps.single() as ToolStep).status)
        assertEquals("hi", ((done[1] as WorkItem).steps.single() as ToolStep).result)
        assertEquals("REPORT: all good", (done[2] as AnswerItem).text)
        assertEquals(record.length(), t.offset)
        // 没有新内容
        assertFalse(read(t))
    }

    @Test
    fun knownAgentIdIsUsedDirectlyAndAWrongCallIsMissing() {
        record.writeText(prompt + bashCall)
        val t = SubagentTranscript("toolu_A")
        assertTrue(read(t, hint = "a1b2c3"))
        assertEquals("a1b2c3", t.meta!!.agentId)
        // 给的 agent id 的 meta 指着别的调用：不算，照 toolUseId 找也找不到
        val other = SubagentTranscript("toolu_B")
        assertTrue(read(other, hint = "a1b2c3"))
        assertTrue(other.missing)
        assertNull(other.meta)
        // 会话里还没有子代理目录
        val (_, out) =
            run("subagent", "0", "0", "toolu_A", "-", "bbbbbbbb-1111-4222-8333-444444444444")
        assertTrue(out, out.contains("\"missing\":true"))
        // 参数不对的不执行
        assertNotEquals(0, run("subagent", "0", "0", "../x", "-", session).first)
        assertNotEquals(0, run("subagent", "0", "0", "toolu_A", "-", "../../etc").first)
        assertNotEquals(0, run("subagent", "-1", "0", "toolu_A", "-", session).first)
    }

    @Test
    fun stoppedAndShrunkRecords() {
        record.writeText(prompt + thinkingText + bashCall)
        val t = SubagentTranscript("toolu_A")
        read(t)
        assertEquals(2, t.items(running = true).size)
        // 被停下：meta 记 stoppedByUser；还没结果的那一步收口成已中断
        meta.writeText("""{"toolUseId":"toolu_A","stoppedByUser":true}""")
        read(t)
        assertTrue(t.meta!!.stopped && t.meta!!.ended)
        assertEquals(
            ToolStatus.Failed,
            ((t.items(running = false)[1] as WorkItem).steps.single() as ToolStep).status,
        )
        // 记录变短了（换了文件）：从头重读，不接着旧的偏移
        record.writeText(prompt + report)
        assertTrue(read(t))
        assertEquals(
            listOf("REPORT: all good"),
            t.items(running = false).map { (it as AnswerItem).text },
        )
    }

    @Test
    fun imagesInResultsPointAtTheirRecordLine() {
        val shot =
            line(
                """{"type":"user","isSidechain":true,"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_s0","content":[
            {"type":"text","text":"screen"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGk="}}]}]},"uuid":"u4"}""",
            )
        record.writeText(prompt + bashCall + shot)
        val t = SubagentTranscript("toolu_A")
        read(t)
        val step = (t.items(running = false).single() as WorkItem).steps.single() as ToolStep
        val image = step.images.single() as SessionImage
        assertEquals(record.canonicalPath, image.file)
        assertEquals("2.0.1", image.ref)
        // 查看页要显示它时，用 image 命令照这个位置取回
        val (code, out) = run("image", image.file, image.ref)
        assertEquals(0, code)
        assertTrue(out, out.contains("\"data\":\"aGk=\""))
    }

    @Test
    fun aLineLongerThanOneReadIsSkipped() {
        val huge =
            line(
                """{"type":"user","isSidechain":true,"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_s0","content":"${"x".repeat(4_300_000)}"}]}}""",
            )
        record.writeText(prompt + bashCall + huge + report)
        val t = SubagentTranscript("toolu_A")
        read(t)
        assertTrue(t.more)
        while (t.more) read(t)
        val items = t.items(running = false)
        assertEquals("REPORT: all good", (items.last() as AnswerItem).text)
        assertEquals(4, t.line)
        assertEquals(record.length(), t.offset)
    }

    private fun e(text: String): JsonObject =
        Json.parseToJsonElement(text.trimIndent().replace("\n", "")).jsonObject

    /** 前台子代理：输出流先送来工具调用和结果，文件随后追上；追上之前补上，追上之后不重复。 */
    @Test
    fun liveEventsFillTheGapUntilTheFileCatchesUp() {
        val t = SubagentTranscript("toolu_A")
        t.applyRead(
            """
            {"type":"zeta_subagent","file":"/f.jsonl","agent_id":"a1","meta":{"toolUseId":"toolu_A"},"offset":10,"line":2,"more":false,"skipped":0,"reset":false}
                        {"type":"user","prompt":true,"message":{"content":[{"type":"text","text":"go"}]}}
                        {"type":"assistant","message":{"id":"msg_1","content":[{"type":"tool_use","id":"toolu_s0","name":"Bash","input":{"command":"a"}}]}}
            """
                .trimIndent(),
        )
        // 指令那条（输出流里也有）不算新内容
        assertFalse(
            t.observeLive(
                e(
                    """{"type":"user","parent_tool_use_id":"toolu_A","message":{"content":[{"type":"text","text":"go"}]}}""",
                ),
            ),
        )
        // 文件里已经有的工具调用不再要
        assertFalse(
            t.observeLive(
                e(
                    """{"type":"assistant","parent_tool_use_id":"toolu_A","message":{"id":"msg_1","content":[{"type":"tool_use","id":"toolu_s0","name":"Bash","input":{"command":"a"}}]}}""",
                ),
            ),
        )
        assertTrue(
            t.observeLive(
                e(
                    """{"type":"user","parent_tool_use_id":"toolu_A","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_s0","content":"ok"}]}}""",
                ),
            ),
        )
        assertTrue(
            t.observeLive(
                e(
                    """{"type":"assistant","parent_tool_use_id":"toolu_A","message":{"id":"msg_2","content":[{"type":"tool_use","id":"toolu_s1","name":"Read","input":{"file_path":"/x"}}]}}""",
                ),
            ),
        )
        val live = (t.items(running = true).single() as WorkItem).steps.map { it as ToolStep }
        assertEquals(
            listOf("toolu_s0" to ToolStatus.Success, "toolu_s1" to ToolStatus.Running),
            live.map { it.id to it.status },
        )
        assertEquals(2, t.toolUses())

        // 文件追上：同样的内容只出现一次
        t.applyRead(
            """
            {"type":"zeta_subagent","file":"/f.jsonl","agent_id":"a1","meta":{"toolUseId":"toolu_A"},"offset":20,"line":5,"more":false,"skipped":0,"reset":false}
                        {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_s0","content":"ok"}]}}
                        {"type":"assistant","message":{"id":"msg_2","content":[{"type":"text","text":"reading"}]}}
                        {"type":"assistant","message":{"id":"msg_2","content":[{"type":"tool_use","id":"toolu_s1","name":"Read","input":{"file_path":"/x"}}]}}
            """
                .trimIndent(),
        )
        val items = t.items(running = true)
        assertEquals(
            listOf("toolu_s0", "toolu_s1"),
            items.filterIsInstance<WorkItem>().flatMap { it.steps }.map { it.id },
        )
        assertEquals("reading", items.filterIsInstance<AnswerItem>().single().text)
        assertEquals(2, t.toolUses())
        // 同一条消息里文件已有文字的，输出流的文字不再补
        assertFalse(
            t.observeLive(
                e(
                    """{"type":"assistant","parent_tool_use_id":"toolu_A","message":{"id":"msg_2","content":[{"type":"text","text":"reading"}]}}""",
                ),
            ),
        )
    }

    @Test
    fun onlyTheLatestEventsAreKept() {
        val t = SubagentTranscript("toolu_A", keep = 2)
        val events =
            (0 until 5).joinToString("\n") {
                """{"type":"assistant","message":{"id":"m$it","content":[{"type":"text","text":"t$it"}]}}"""
            }
        t.applyRead(
            """{"type":"zeta_subagent","file":"/f.jsonl","agent_id":"a1","meta":{},"offset":1,"line":5,"more":false,"skipped":1,"reset":false}""" +
                "\n" +
                events,
        )
        assertEquals(4, t.skipped)
        assertEquals(listOf("t3", "t4"), t.items(running = false).map { (it as AnswerItem).text })
    }
}
