package com.dzshzx.zeta.core.diag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagEventsTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun helperSubcommand() {
        assertEquals("list", DiagEvents.helperCommand("exec python3 - list"))
        assertEquals("history", DiagEvents.helperCommand("exec python3 - history '/home/x/.claude/projects/a/b.jsonl'"))
        assertEquals("hosts", DiagEvents.helperCommand("python3 - hosts"))
        assertNull(DiagEvents.helperCommand("exec bash -c 'x' zeta-host stop k"))
        assertNull(DiagEvents.helperCommand("exec python3 -c 'print(1)'"))
    }

    @Test
    fun launchArguments() {
        val args = listOf(
            "-p", "--input-format", "stream-json", "--resume", "0a1b2c3d-0000-4000-8000-000000000001", "--fork-session",
            "--model", "opus", "--permission-mode", "acceptEdits", "--append-system-prompt", "long hint", "--mcp-config", "{}",
        )
        assertEquals("续 0a1b2c3d 分支，模型 opus，权限 acceptEdits，手机工具", DiagEvents.describeArguments(args))
        assertEquals("", DiagEvents.describeArguments(listOf("-p", "--verbose")))
        // 参数在末尾、缺值的不出错
        assertEquals("", DiagEvents.describeArguments(listOf("--model")))
    }

    @Test
    fun toolErrors() {
        assertNull(DiagEvents.toolError(json("""{"content":[{"type":"text","text":"ok"}]}""")))
        assertNull(DiagEvents.toolError(json("""{"content":[{"type":"text","text":"ok"}],"isError":false}""")))
        assertEquals("找不到编号为 7 的屏幕", DiagEvents.toolError(json("""{"content":[{"type":"text","text":"找不到编号为 7 的屏幕"}],"isError":true}""")))
        assertEquals(
            "没找到「确定」",
            DiagEvents.toolError(json("""{"content":[{"type":"image","data":"x"},{"type":"text","text":"\n没找到「确定」\n主屏 1220×2656\n[0] 按钮"}],"isError":true}""")),
        )
        assertEquals("（没有说明）", DiagEvents.toolError(json("""{"content":[],"isError":true}""")))
    }

    @Test
    fun stderrTail() {
        val stderr = (1..10).joinToString("\n") { "line $it" } + "\n\n"
        assertEquals((5..10).joinToString("\n") { "line $it" }, DiagEvents.tail(stderr))
        assertEquals("", DiagEvents.tail("  \n "))
        assertEquals(600, DiagEvents.tail("x".repeat(2000)).length)
    }
}
