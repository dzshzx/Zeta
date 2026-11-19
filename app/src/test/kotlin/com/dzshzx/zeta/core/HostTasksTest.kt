package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 接回后台进程时，zeta_helper.py hosts 从输出日志里摘出还在跑的后台任务的事件（task_events），
 * 重放给 [TaskBoard] 后和实时收到时一样：命令、开始时间、子代理的进展都在。
 */
class HostTasksTest {
    private lateinit var home: File
    private var holder: Process? = null
    private val helper = File("src/main/assets/zeta_helper.py").absoluteFile

    @Before
    fun setUp() {
        assumeTrue("要 Linux 的 /proc 和 python3", File("/proc/self/cmdline").exists())
        home = Files.createTempDirectory("zeta-host-tasks").toFile().canonicalFile
    }

    @After
    fun tearDown() {
        holder?.destroyForcibly()
        if (::home.isInitialized) home.deleteRecursively()
    }

    @Test
    fun runningTasksAreReplayedWithTheirCallAndStartTime() {
        // 助手脚本认 /proc/<pid>/cmdline 里有 claude 的才算活着
        val process = ProcessBuilder("bash", "-c", "exec -a claude-fake sleep 60").start().also { holder = it }
        val dir = File(home, ".cache/zeta/hosts/k1").apply { mkdirs() }
        File(dir, "pid").writeText("${process.pid()}\n")
        File(dir, "out.jsonl").writeText(
            listOf(
                """{"type":"system","subtype":"init","session_id":"s1","permissionMode":"default"}""",
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_0","name":"Bash","input":{"command":"make test","run_in_background":true}}]},"parent_tool_use_id":null,"timestamp":"2026-11-19T02:13:29.893Z"}""",
                """{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"b1","task_type":"local_bash","description":"run tests"}]}""",
                """{"type":"system","subtype":"task_started","task_id":"b1","tool_use_id":"toolu_0","description":"run tests","task_type":"local_bash"}""",
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_1","name":"Agent","input":{"prompt":"review it","subagent_type":"general-purpose"}},{"type":"tool_use","id":"toolu_2","name":"Bash","input":{"command":"sleep 1"}}]},"parent_tool_use_id":null,"timestamp":"2026-11-19T02:14:00.000Z"}""",
                """{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"b1","task_type":"local_bash","description":"run tests"},{"task_id":"a1","task_type":"local_agent","subagent_type":"general-purpose","description":"review"}]}""",
                """{"type":"system","subtype":"task_started","task_id":"a1","tool_use_id":"toolu_1","description":"review","task_type":"local_agent","prompt":"review it"}""",
                """{"type":"system","subtype":"task_progress","task_id":"a1","description":"Reading files","usage":{"tool_uses":1}}""",
                """{"type":"system","subtype":"task_progress","task_id":"a1","description":"Running checks","usage":{"tool_uses":3}}""",
                // 前台跑完的不进最后的清单
                """{"type":"system","subtype":"task_started","task_id":"b2","tool_use_id":"toolu_2","description":"sleep","task_type":"local_bash","is_backgrounded":false}""",
                """{"type":"result","subtype":"success","is_error":false}""",
            ).joinToString("\n", postfix = "\n"),
        )
        val builder = ProcessBuilder("python3", "-I", helper.path, "hosts").directory(home)
        builder.environment().apply {
            clear()
            put("HOME", home.path)
            put("PATH", System.getenv("PATH"))
            put("LANG", "C.UTF-8")
        }
        val run = builder.start()
        run.outputStream.close()
        val out = run.inputStream.bufferedReader().readText()
        assertTrue(run.waitFor(30, TimeUnit.SECONDS))
        val host = out.lineSequence().filter { it.startsWith("{") }.map { Json.parseToJsonElement(it).jsonObject }.single()
        assertEquals(2, host.int("tasks"))
        val events = host.arr("task_events").orEmpty().map { it as JsonObject }

        val board = TaskBoard()
        events.forEach { board.observe(it, 9_999_999_999_999) }
        val tasks = board.tasks.associateBy { it.id }
        assertEquals(setOf("b1", "a1"), tasks.keys)
        assertEquals("make test", tasks["b1"]!!.command)
        assertEquals(1795054409893, tasks["b1"]!!.startedAt)
        assertEquals("review it", tasks["a1"]!!.command)
        assertEquals(1795054440000, tasks["a1"]!!.startedAt)
        assertEquals("Running checks", tasks["a1"]!!.progress)
        assertEquals(3, tasks["a1"]!!.toolUses)
        assertTrue(tasks.values.all { it.running })
    }
}
