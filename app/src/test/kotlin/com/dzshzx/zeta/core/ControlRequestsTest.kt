package com.dzshzx.zeta.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 控制请求的应答只按请求号认：形状像也不算，换了连接的旧号不算，一个号只答一次。 */
class ControlRequestsTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun answer(id: String, body: String = "{}", subtype: String = "success") =
        json("""{"type":"control_response","response":{"subtype":"$subtype","request_id":"$id","response":$body}}""")

    @Test
    fun answersGoToTheRequestWithThatId() {
        val requests = ControlRequests()
        val got = ArrayList<String>()
        val first = requests.build(controlBody("get_settings")) { got += "settings:" + it.str("request_id") }
        val second = requests.build(controlBody("get_context_usage") { put("x", 1) }) { got += "usage:" + it.str("request_id") }
        val quiet = requests.build(controlBody("interrupt"), null)
        assertEquals("control_request", first.str("type"))
        assertEquals("get_context_usage", second.obj("request")?.str("subtype"))
        val ids = listOf(first, second, quiet).map { it.str("request_id")!! }
        assertEquals(3, ids.toSet().size)

        // 先答后发的那个：顺序无所谓
        assertTrue(requests.answer(answer(ids[1])))
        assertTrue(requests.answer(answer(ids[0], subtype = "error")))
        assertEquals(listOf("usage:${ids[1]}", "settings:${ids[0]}"), got)
        // 一个号只答一次；不要应答的、不认识的号、别的事件都不算
        assertFalse(requests.answer(answer(ids[0])))
        assertFalse(requests.answer(answer(ids[2])))
        assertFalse(requests.answer(answer("zeta-1", """{"models":[],"commands":[],"current_permission_mode":"plan"}""")))
        assertFalse(requests.answer(json("""{"type":"result","subtype":"success"}""")))
    }

    @Test
    fun connectionsDoNotShareIdsAndDroppedRequestsStayQuiet() {
        val old = ControlRequests()
        val fresh = ControlRequests()
        val oldId = old.build(controlBody("initialize")) { throw AssertionError("旧连接的请求") }.str("request_id")!!
        val freshId = fresh.build(controlBody("initialize")) {}.str("request_id")!!
        assertNotEquals(oldId, freshId)
        assertFalse("上一个连接的应答不能认到新连接的请求上", fresh.answer(answer(oldId)))

        assertEquals(oldId, old.waitingFor("initialize"))
        old.clear()
        assertNull(old.waitingFor("initialize"))
        assertFalse(old.answer(answer(oldId)))
    }
}
