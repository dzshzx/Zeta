package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.phone.PhoneToolCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolGroupsTest {
    private val names = PhoneToolGroups.all.flatMap { group -> group.tools.map { it.name } }

    /**
     * 交给 Claude 的整份清单（屏幕工具和各工具组，含浏览器、定时任务、系统应用工具）里名字都不重复： 调用按名字分派（先看 PhoneTools
     * 自己处理的几个，再按名字查组，组的登记表里后来的盖掉先来的），重名的只有一个调得到。
     */
    @Test
    fun toolNamesAreUniqueInTheWholeList() {
        val all =
            PhoneToolCatalog.definitions.map { (it as JsonObject)["name"]!!.jsonPrimitive.content }
        assertTrue(all.containsAll(names) && "screen" in all)
        assertEquals(emptySet<String>(), all.groupBy { it }.filterValues { it.size > 1 }.keys)
    }

    /**
     * 每个交给 Claude 的手机工具都有中文名字（工具权限页）和这一步做了什么的一句中文（聊天、胶囊）。 什么参数都不给时也要说得出来，而且不是退回工具名：组里的 title
     * 漏了这个工具的分支时就是工具名。
     */
    @Test
    fun everyToolHasALabelAndATitle() {
        val tools = PhoneToolCatalog.tools
        assertTrue(tools.size > 20)
        val empty = JsonObject(emptyMap())
        for (tool in tools) {
            assertTrue(tool.name, tool.label.isNotBlank() && tool.label.any { it.code > 127 })
            val title = PhoneToolCatalog.title(tool.name, empty)
            assertTrue(
                "${tool.name}: $title",
                !title.isNullOrBlank() && title != tool.name && title.any { it.code > 127 },
            )
        }
        assertEquals(tools.map { it.name to it.label }, PhoneToolCatalog.labels)
        assertNull(PhoneToolCatalog.title("no_such_tool", empty))
    }

    @Test
    fun everyToolIsFoundByItsName() {
        assertTrue(names.isNotEmpty())
        for (name in names) assertTrue(name, PhoneToolGroups.find(name) != null)
    }

    private fun text(result: JsonObject) =
        result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content

    private fun isError(result: JsonObject) = result["isError"]?.jsonPrimitive?.boolean == true

    @Test
    fun toolCallTurnsExceptionsIntoFailures() {
        val ok = toolCall { toolText("好了") }
        assertEquals("好了", text(ok))
        assertFalse(isError(ok))
        // 参数不对：原话交给 Claude
        val bad = toolCall { throw BadInput("limit 在 1 到 50 之间") }
        assertEquals("limit 在 1 到 50 之间", text(bad))
        assertTrue(isError(bad))
        // 设备命令报的失败原因同样原话交出，不再叠一层「执行失败：」
        assertEquals("执行失败：权限不够", text(toolCall { throw ToolError("执行失败：权限不够") }))
        val failed = toolCall { throw IllegalStateException("读取超时（30 秒）") }
        assertEquals("执行失败：读取超时（30 秒）", text(failed))
        assertTrue(isError(failed))
        assertEquals(
            "执行失败：UnsupportedOperationException",
            text(toolCall { throw UnsupportedOperationException() }),
        )
    }

    @Test
    fun wakesThroughAProviderInTheServicesProcess() {
        val providers =
            listOf(
                "com.miui.notes.remote" to "com.miui.notes:remote",
                null to "com.miui.notes",
                "com.miui.notes.AgenticNoteProvider;notes.agentic" to "com.miui.notes",
                "com.miui.notes.other" to "com.miui.notes",
            )
        assertEquals(
            "com.miui.notes.AgenticNoteProvider",
            AppToolCall.pickAuthority("com.miui.notes", providers),
        )
        assertEquals(
            "com.miui.notes.remote",
            AppToolCall.pickAuthority("com.miui.notes:remote", providers),
        )
        assertNull(AppToolCall.pickAuthority("com.other", providers))
        assertNull(AppToolCall.pickAuthority("com.miui.notes", emptyList()))
    }
}
