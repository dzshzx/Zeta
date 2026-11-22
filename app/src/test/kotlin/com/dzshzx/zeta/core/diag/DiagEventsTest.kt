package com.dzshzx.zeta.core.diag

import com.dzshzx.zeta.core.HelperProgram
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagEventsTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun helperSubcommand() {
        val program = HelperProgram({ ByteArray(0) }, "zeta")
        // 应用里拼 helper 命令行的那一处，诊断日志要认得出子命令
        assertEquals(
            "storage",
            DiagEvents.helperCommand(program.command("storage", listOf("it's", "1"))),
        )
        assertEquals("list", DiagEvents.helperCommand(program.command("list")))
        assertEquals(
            "history",
            DiagEvents.helperCommand(
                program.command("history", listOf("/home/x/.claude/projects/a/b.jsonl")),
            ),
        )
        assertEquals("install", DiagEvents.helperCommand(program.installCommand(byteArrayOf(1, 2))))
        assertNull(DiagEvents.helperCommand("exec bash -c 'x' zeta-host stop k"))
        assertNull(DiagEvents.helperCommand("exec python3 -c 'print(1)'"))
    }

    @Test
    fun toolErrors() {
        assertNull(DiagEvents.toolError(json("""{"content":[{"type":"text","text":"ok"}]}""")))
        assertNull(
            DiagEvents.toolError(
                json("""{"content":[{"type":"text","text":"ok"}],"isError":false}"""),
            ),
        )
        assertEquals(
            "找不到编号为 7 的屏幕",
            DiagEvents.toolError(
                json("""{"content":[{"type":"text","text":"找不到编号为 7 的屏幕"}],"isError":true}"""),
            ),
        )
        assertEquals(
            "没找到「确定」",
            DiagEvents.toolError(
                json(
                    """{"content":[{"type":"image","data":"x"},{"type":"text","text":"\n没找到「确定」\n主屏 1220×2656\n[0] 按钮"}],"isError":true}""",
                ),
            ),
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
