package com.dzshzx.zeta.core

import android.os.Looper
import com.dzshzx.zeta.core.conv.ConvState
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.robolectric.Shadows.shadowOf

/**
 * 摆出核心推来的样子：当前状态经 [change] 改过后照到对话上（[Conversation.mirror]，和真推送走同一条路，hub 一样收到通知）。
 * 不连核心，只看界面、提醒这些怎么跟着变。
 */
internal fun Conversation.core(change: (ConvState) -> ConvState) =
    mirror(view.copy(state = change(view.state), connected = true))

/** 摆出核心推来的条目。 */
internal fun Conversation.coreItems(items: List<ChatItem>) =
    mirror(view.copy(items = items, connected = true))

/**
 * 假的核心连接（照 ConvClientTest 的做法）：装到 hub 上以后，对话的 conv.* 请求都到这里，记下来，按 [respond] 应答； 测试用 [push]
 * 喂录好的协议消息，[drop]、[reconnect] 模拟断线重连。默认的应答见 [defaultReply]：conv.open 推的整份状态里会话号照请求里的，没有就是
 * [newSession]。
 */
internal class FakeCore : ConvConnection {
    class Call(val method: String, val params: JsonObject) {
        val conv: String
            get() = (params["conv"] as JsonPrimitive).content

        fun str(key: String): String? = params.str(key)
    }

    val calls = LinkedBlockingQueue<Call>()

    /** 各方法的应答（JSON 文本）；没写的答 null。在发请求的线程上调，答完这个请求才进 [calls]。 */
    @Volatile var respond: (Call) -> String? = { call -> defaultReply(call) }

    /** 新对话打开时核心给的会话号；null 是还没有。 */
    @Volatile var newSession: String? = null

    @Volatile private var onMessage: ((JsonObject) -> Unit)? = null
    @Volatile private var onProblem: ((String?) -> Unit)? = null
    private var keys = 0

    /** 装到 [hub] 上：之后它的对话都连到这里。 */
    fun install(hub: ConversationHub): FakeCore {
        hub.convLinks.connect = { _, message, problem ->
            onMessage = message
            onProblem = problem
            problem(null)
            this
        }
        return this
    }

    override fun call(method: String, params: JsonObject): JsonElement? {
        val call = Call(method, params)
        // 答完（应答前的推送都推了）才交给测试：测试接着推的总在它们后面
        try {
            return respond(call)?.let(Json::parseToJsonElement)
        } finally {
            calls.put(call)
        }
    }

    override fun close() {}

    /** 推一行（核心推给客户端的消息）。 */
    fun push(json: String) {
        checkNotNull(onMessage) { "还没连上" }(Json.parseToJsonElement(json).jsonObject)
    }

    /** 推一份对话 [conv] 的状态变化（conv.state，[fields] 是 JSON 对象里的几项）。 */
    fun state(conv: String, fields: String) =
        push("""{"type":"conv.state","conv":"$conv",$fields}""")

    /** 连接断了。 */
    fun drop(why: String = "连接断了") = checkNotNull(onProblem)(why)

    /** 重新连上（新的 serve 进程）：打开过的对话重新 conv.open。 */
    fun reconnect() = checkNotNull(onProblem)(null)

    /** 等下一个 [method] 请求（跳过别的），5 秒内没有就失败。 */
    fun next(method: String): Call {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            val left = deadline - System.currentTimeMillis()
            check(left > 0) { "5 秒内没有 $method" }
            val call = calls.poll(left, TimeUnit.MILLISECONDS) ?: continue
            if (call.method == method) return call
        }
    }

    /** 默认的应答（都答 null）：conv.open 先推整份的状态和条目；conv.model、conv.effort、conv.mode 像核心那样先推改了的那一项。 */
    fun defaultReply(call: Call): String? {
        val value = call.params["value"]?.toString() ?: "null"
        when (call.method) {
            "conv.open" -> opened(call)
            "conv.model" -> push(stateOf(call, """"model":$value"""))
            "conv.effort" -> push(stateOf(call, """"effort":$value"""))
            "conv.mode" ->
                push(stateOf(call, """"mode":{"user":$value,"process":"","shown":$value}"""))
        }
        return null
    }

    private fun stateOf(call: Call, fields: String) =
        """{"type":"conv.state","conv":"${call.conv}",$fields}"""

    private fun opened(call: Call) {
        val session = call.str("session") ?: newSession
        keys++
        val sessionField = session?.let { ",\"session\":\"$it\"" }.orEmpty()
        push(
            """{"type":"conv.state","conv":"${call.conv}","full":true,"key":"c$keys"$sessionField,""" +
                """"cwd":"${call.str("cwd").orEmpty()}","title":"${call.str("title").orEmpty()}"}""",
        )
        push(
            """{"type":"conv.items","conv":"${call.conv}","full":true,"at":0,"remove":0,"items":[]}""",
        )
    }
}

/**
 * 不连核心：截图和只看界面的测试用（[com.dzshzx.zeta.shots.Fixtures] 的 hub 默认装它）。打开对话马上失败，不去碰本机的 Linux 环境， 对话的样子由测试经
 * [core]、[coreItems] 摆出来。
 */
internal val NoCore: (String, (JsonObject) -> Unit, (String?) -> Unit) -> ConvConnection =
    { _, _, _ ->
        object : ConvConnection {
            override fun call(method: String, params: JsonObject): JsonElement? =
                throw ServeError("测试里不连核心")

            override fun close() {}
        }
    }

/**
 * Robolectric 测试里等后台线程投回主线程的结果（Handler.post、读会话、跑 helper）。测试线程就是主线程，排队的消息要测试自己执行， Thread.sleep 和
 * Compose 的 waitUntil 都不执行它们：结果晚于上一次执行就一直等不到。这里每轮先执行一遍主线程排队的消息再看 [condition]，[timeoutMs] 毫秒内等不到就失败。
 */
internal fun awaitMain(timeoutMs: Long = 20_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        if (System.currentTimeMillis() > deadline)
            throw AssertionError("等了 ${timeoutMs / 1000} 秒还没等到")
        Thread.sleep(10)
    }
}

/** 同 [awaitMain]，每轮另把主线程的时钟往前拨一点：核心推来的变化要攒一会儿（延后投递）才照到对话上。 */
internal fun awaitCore(timeoutMs: Long = 20_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
        if (condition()) return
        if (System.currentTimeMillis() > deadline)
            throw AssertionError("等了 ${timeoutMs / 1000} 秒还没等到")
        Thread.sleep(10)
    }
}

/**
 * 每 0.1 秒看一次 [condition]，最多等 [seconds] 秒；等到了返回 true。不执行主线程排队的消息，要等它们用 [awaitMain]。 默认的 60
 * 秒只防卡死：条件一成立就返回，不拿它量快慢，机器忙时也不会误判。
 */
internal fun waitUntil(seconds: Int = 60, condition: () -> Boolean): Boolean {
    repeat(seconds * 10) {
        if (condition()) return true
        Thread.sleep(100)
    }
    return false
}
