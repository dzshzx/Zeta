package com.dzshzx.zeta.phone

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 外部入口一行一条的 MCP 转发（[PhoneBridge.serve]）：真套接字和 root 身份要在设备上验（tools/phone-tests/phone-bridge.sh）。 */
@RunWith(RobolectricTestRunner::class)
class PhoneBridgeTest {
    private fun reply(message: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", message["id"]!!)
        put("result", message["method"]!!)
    }

    private fun serve(input: String, handle: (JsonObject) -> JsonObject): List<JsonObject> {
        val out = ByteArrayOutputStream()
        val inFlight = AtomicInteger()
        PhoneBridge.serve(ByteArrayInputStream(input.toByteArray()), out, inFlight, handle)
        assertEquals("答完才返回，正在处理的归零", 0, inFlight.get())
        return out.toString(Charsets.UTF_8)
            .lines()
            .filter { it.isNotBlank() }
            .map {
                Json.parseToJsonElement(it).jsonObject
            }
    }

    @Test
    fun answersRequestsSkipsNotificationsAndJunk() {
        val replies =
            serve(
                """
                {"jsonrpc":"2.0","id":1,"method":"initialize"}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                not json
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """
                    .trimIndent(),
                ::reply,
            )
        assertEquals(setOf(1, 2), replies.map { it["id"]!!.jsonPrimitive.int }.toSet())
    }

    /** 慢的调用不挡后面的：两个调用同时在处理，先完成的先写回。 */
    @Test
    fun callsRunConcurrently() {
        val both = CountDownLatch(2)
        val replies =
            serve(
                """
                {"jsonrpc":"2.0","id":1,"method":"a"}
                {"jsonrpc":"2.0","id":2,"method":"b"}
                """
                    .trimIndent(),
            ) {
                both.countDown()
                assertTrue("两个调用要同时在处理", both.await(5, TimeUnit.SECONDS))
                reply(it)
            }
        assertEquals(2, replies.size)
    }

    @Test
    fun failureBecomesErrorReply() {
        val replies = serve("""{"jsonrpc":"2.0","id":"x","method":"tools/call"}""") { error("坏了") }
        val only = replies.single()
        assertEquals("x", only["id"]!!.jsonPrimitive.content)
        assertEquals("坏了", only["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun emptyReplyIsNotWritten() {
        assertEquals(emptyList<JsonObject>(), serve("""{"id":3}""") { JsonObject(emptyMap()) })
    }

    @Test
    fun headerNamesTheClient() {
        assertEquals("desk-1", PhoneBridge.clientKey("ZETA-BRIDGE desk-1"))
        for (bad in listOf(null, "", "desk-1", "ZETA-BRIDGE ", "ZETA-BRIDGE a b", """{"id":1}""")) {
            assertEquals(bad, null, PhoneBridge.clientKey(bad))
        }
    }

    @Test
    fun socketNameFollowsPackage() {
        assertEquals("com.dzshzx.zeta.dev.phone", PhoneBridge.socketName("com.dzshzx.zeta.dev"))
    }
}
