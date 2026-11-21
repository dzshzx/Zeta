package com.dzshzx.zeta.core

import android.os.Looper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.robolectric.Shadows.shadowOf

/** 起进程时 [flag] 后面跟的值；没带这个参数时为 null。 */
internal fun Conversation.launchArgument(flag: String): String? =
    launchArguments().let { args -> args.indexOf(flag).takeIf { it >= 0 }?.let { args[it + 1] } }

/** 喂一条 Claude Code 的输出事件：和进程送来的一样走 [Conversation.receive]（drain 取出事件后调的那个），时间记作 0。 */
internal fun Conversation.receive(event: String) =
    receive(listOf(Json.parseToJsonElement(event) as JsonObject), 0L)

private val processField =
    Conversation::class.java.getDeclaredField("process").apply { isAccessible = true }

/**
 * 给对话挂上一个一直活着的进程，不经 [Conversation.prepare]：单元测试里的 su 总是失败，这个连接马上就断， 但退出回调是这里给的空函数，对话照样当它活着，直到对话自己
 * [Conversation.shutdown]。
 */
internal fun Conversation.attachLiveProcess(hub: ConversationHub) {
    val connection =
        ClaudeProcess(
            shell = checkNotNull(hub.shell(backend)),
            hostScript = hub.hostScript,
            hostKey = "test-live",
            launch = ClaudeProcess.Launch.Attach(fromLine = 1),
            onEvent = {},
            onExit = {},
            reconnect = false,
        )
    processField.set(this, connection)
}

/**
 * 扮 Claude Code 答这个对话的进程正在等的、子类型为 [subtype] 的控制请求（最早的那个），[response] 是应答里的 response 一项。 没有在等的就失败。
 */
internal fun Conversation.answer(subtype: String, response: String, success: Boolean = true) {
    val process = processField.get(this) as ClaudeProcess
    val id = checkNotNull(process.waitingFor(subtype)) { "没有在等的 $subtype" }
    receive(
        if (success)
            """{"type":"control_response","response":{"subtype":"success","request_id":"$id","response":$response}}"""
        else
            """{"type":"control_response","response":{"subtype":"error","request_id":"$id","error":"refused"}}""",
    )
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

/** 每 0.1 秒看一次 [condition]，最多等 [seconds] 秒；等到了返回 true。不执行主线程排队的消息，要等它们用 [awaitMain]。 */
internal fun waitUntil(seconds: Int, condition: () -> Boolean): Boolean {
    repeat(seconds * 10) {
        if (condition()) return true
        Thread.sleep(100)
    }
    return false
}
