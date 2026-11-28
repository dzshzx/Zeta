package com.dzshzx.zeta.core

import com.dzshzx.zeta.i18n.UiLanguage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [HelperServe.call]：对着一个照协议应答的小脚本（不是真的 serve，好让它在要的时候不答、断开），看应答按请求号交回、参数和 id、method
 * 平级写出、断线和关闭时报错、在读线程上等应答被拦下。
 */
class HelperServeCallTest {
    private val problems = LinkedBlockingQueue<String>()
    private val messages = LinkedBlockingQueue<JsonObject>()
    private val serves = ArrayList<HelperServe>()

    @After
    fun cleanup() {
        serves.forEach { it.close() }
    }

    /**
     * 起一个假 serve：先打招呼，echo 把请求原样当 result，fail 答出错，drop 不答就退出，push 推一行，silent 不答、只推一行
     * {"type":"got","method":"silent"} 说收到了。
     */
    private fun fake(): HelperServe.Opened =
        HelperServe.Opened(ProcessBuilder("bash", "-c", SCRIPT).start()) { it.destroy() }

    private fun serve(
        device: String? = null,
        onMessage: (JsonObject) -> Unit = messages::put,
        open: () -> HelperServe.Opened = ::fake,
    ): HelperServe =
        HelperServe(
                name = "call-test",
                open = open,
                subscriptions = emptyList(),
                onMessage = onMessage,
                onProblem = { problems.put(it.orEmpty()) },
                device = device,
                backoff = { 0L },
            )
            .also { serves += it }

    private fun params(vararg pairs: Pair<String, Any>): JsonObject =
        JsonObject(
            pairs.associate { (k, v) ->
                k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())
            },
        )

    private fun failure(block: () -> Unit): ServeError {
        try {
            block()
        } catch (e: ServeError) {
            return e
        }
        fail("没报错")
        error("")
    }

    @Test
    fun replyCarriesTheResultAndParamsSitBesideIdAndMethod() {
        val s = serve()
        val result =
            s.call("echo", params("conv" to "k1", "n" to 2, "id" to "不该盖过请求号")) as JsonObject
        assertEquals("echo", result.str("method"))
        assertEquals("k1", result.str("conv"))
        assertEquals(2, result.int("n"))
        assertTrue(result["id"] is JsonPrimitive && !(result["id"] as JsonPrimitive).isString)
        assertNull(s.call("nothing", JsonObject(emptyMap())))
    }

    @Test
    fun refusedRequestsCarryTheReasonAndCode() {
        val s = serve()
        val e = failure { s.call("fail", JsonObject(emptyMap())) }
        assertEquals("不行", e.message)
        assertEquals("nope", e.code)
        // 不是 call 发的应答（订阅之类）答出错时照旧报给 onProblem
        assertEquals("", problems.poll(5, TimeUnit.SECONDS)) // 连上
        assertEquals("订阅被拒", problems.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun droppedConnectionFailsTheRequestAndLaterOnesWaitForTheReconnect() {
        val s = serve()
        val e = failure { s.call("drop", JsonObject(emptyMap())) }
        assertEquals("与 zeta-helper serve 的连接已断开，未收到应答", e.message)
        // 重连上以后照常
        assertEquals("echo", (s.call("echo", JsonObject(emptyMap())) as JsonObject).str("method"))
    }

    @Test
    fun closeFailsWaitingAndLaterRequests() {
        val s = serve()
        val waiting = LinkedBlockingQueue<String>()
        Thread {
            waiting.put(failure { s.call("silent", JsonObject(emptyMap())) }.message.orEmpty())
        }
            .start()
        // 请求已经发到 serve、在等应答了再关
        assertEquals("silent", messages.poll(5, TimeUnit.SECONDS)?.str("method"))
        s.close()
        assertEquals("与 zeta-helper serve 的连接已关闭", waiting.poll(5, TimeUnit.SECONDS))
        assertEquals(
            "与 zeta-helper serve 的连接已关闭",
            failure { s.call("echo", JsonObject(emptyMap())) }.message,
        )
    }

    @Test
    fun firstFailureIsTheReason() {
        val s = serve { error("还没有可用的 Linux 环境") }
        assertEquals("还没有可用的 Linux 环境", failure { s.call("echo", JsonObject(emptyMap())) }.message)
    }

    @Test
    fun timesOutWithoutAReply() {
        val s = serve()
        assertTrue(
            failure { s.call("silent", JsonObject(emptyMap()), 300) }.message!!.contains("未应答"),
        )
    }

    /** 连上先发 core.client 报设备名和界面语言，排在别的请求前面（假 serve 的 first 答回它收到的第一行）；它的应答不算问题。 */
    @Test
    fun reportsTheDeviceAndLanguageFirst() {
        val s = serve(device = "Xiaomi 17")
        val first = s.call("first", JsonObject(emptyMap())) as JsonObject
        assertEquals("core.client", first.str("method"))
        assertEquals("Xiaomi 17", first.str("device"))
        assertEquals(UiLanguage.SOURCE, first.str("lang"))
        assertEquals("", problems.poll(5, TimeUnit.SECONDS))
        assertNull(problems.poll())
    }

    /** 没有设备名的连接（会话列表这类）也报语言，只是不带 device：核心只更新语言，不算报了设备。 */
    @Test
    fun reportsTheLanguageWithoutADevice() {
        val first = serve().call("first", JsonObject(emptyMap())) as JsonObject
        assertEquals("core.client", first.str("method"))
        assertNull(first["device"])
        assertEquals(UiLanguage.SOURCE, first.str("lang"))
    }

    /** 换了界面语言：[HelperServe.introduceAll] 让开着的连接马上再报一次（假 serve 的 client 答回它收到的最后一个 core.client）。 */
    @Test
    fun introduceAllReportsTheNewLanguage() {
        val s = serve(device = "Xiaomi 17")
        assertEquals(
            UiLanguage.SOURCE,
            (s.call("client", JsonObject(emptyMap())) as JsonObject).str("lang"),
        )
        try {
            UiLanguage.current = "en"
            HelperServe.introduceAll()
            val again = s.call("client", JsonObject(emptyMap())) as JsonObject
            assertEquals("en", again.str("lang"))
            assertEquals("Xiaomi 17", again.str("device"))
        } finally {
            UiLanguage.current = UiLanguage.SOURCE
        }
    }

    @Test
    fun waitingForAReplyOnTheReaderThreadIsRefused() {
        val refused = LinkedBlockingQueue<Throwable>()
        lateinit var s: HelperServe
        s =
            serve(
                onMessage = {
                    refused.put(
                        runCatching { s.call("echo", JsonObject(emptyMap())) }.exceptionOrNull(),
                    )
                },
            )
        s.call("push", JsonObject(emptyMap()))
        assertTrue(refused.poll(5, TimeUnit.SECONDS) is IllegalStateException)
    }

    private companion object {
        val SCRIPT =
            """
            echo '{"type":"zeta_hello","protocol":${HelperServe.PROTOCOL}}'
            first=
            while IFS= read -r line; do
              id=${'$'}{line#*\"id\":}; id=${'$'}{id%%,*}
              [ -n "${'$'}first" ] || first=${'$'}line
              case ${'$'}line in *'"method":"core.client"'*) client=${'$'}line ;; esac
              case ${'$'}line in
                *'"method":"first"'*) printf '{"type":"zeta_reply","id":%s,"ok":true,"result":%s}\n' "${'$'}id" "${'$'}first" ;;
                *'"method":"client"'*) printf '{"type":"zeta_reply","id":%s,"ok":true,"result":%s}\n' "${'$'}id" "${'$'}client" ;;
                *'"method":"echo"'*) printf '{"type":"zeta_reply","id":%s,"ok":true,"result":%s}\n' "${'$'}id" "${'$'}line" ;;
                *'"method":"fail"'*)
                  printf '{"type":"zeta_reply","id":%s,"ok":false,"error":"不行","code":"nope"}\n' "${'$'}id"
                  printf '{"type":"zeta_reply","id":999,"ok":false,"error":"订阅被拒"}\n' ;;
                *'"method":"drop"'*) exit 0 ;;
                *'"method":"push"'*)
                  echo '{"type":"pushed"}'
                  printf '{"type":"zeta_reply","id":%s,"ok":true}\n' "${'$'}id" ;;
                *'"method":"silent"'*) echo '{"type":"got","method":"silent"}' ;;
                *'"method":"ping"'*) ;;
                *) printf '{"type":"zeta_reply","id":%s,"ok":true,"result":null}\n' "${'$'}id" ;;
              esac
            done
            """
                .trimIndent()
    }
}
