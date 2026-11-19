package com.dzshzx.zeta.phone.tools

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
    private val names = PhoneToolGroups.all.flatMap { group -> group.tools.map { (it as JsonObject)["name"]!!.jsonPrimitive.content } }

    /** 登记表按名字查组，重名会被后来的悄悄盖掉。 */
    @Test
    fun toolNamesAreUniqueAcrossGroups() {
        assertEquals(names.groupBy { it }.filterValues { it.size > 1 }.keys, emptySet<String>())
    }

    @Test
    fun everyToolIsFoundByItsName() {
        assertTrue(names.isNotEmpty())
        for (name in names) assertTrue(name, PhoneToolGroups.find(name) != null)
    }

    private fun text(result: JsonObject) = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
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
        val failed = toolCall { throw IllegalStateException("读取超时（30 秒）") }
        assertEquals("执行失败：读取超时（30 秒）", text(failed))
        assertTrue(isError(failed))
        assertEquals("执行失败：UnsupportedOperationException", text(toolCall { throw UnsupportedOperationException() }))
    }

    @Test
    fun wakesThroughAProviderInTheServicesProcess() {
        val providers = listOf(
            "com.miui.notes.remote" to "com.miui.notes:remote",
            null to "com.miui.notes",
            "com.miui.notes.AgenticNoteProvider;notes.agentic" to "com.miui.notes",
            "com.miui.notes.other" to "com.miui.notes",
        )
        assertEquals("com.miui.notes.AgenticNoteProvider", AppToolCall.pickAuthority("com.miui.notes", providers))
        assertEquals("com.miui.notes.remote", AppToolCall.pickAuthority("com.miui.notes:remote", providers))
        assertNull(AppToolCall.pickAuthority("com.other", providers))
        assertNull(AppToolCall.pickAuthority("com.miui.notes", emptyList()))
    }
}
