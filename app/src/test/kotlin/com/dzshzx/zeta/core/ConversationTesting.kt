package com.dzshzx.zeta.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 起进程时 [flag] 后面跟的值；没带这个参数时为 null。 */
internal fun Conversation.launchArgument(flag: String): String? =
    launchArguments().let { args -> args.indexOf(flag).takeIf { it >= 0 }?.let { args[it + 1] } }

/** 喂一条 Claude Code 的输出事件：和进程送来的一样走 [Conversation.receive]（drain 取出事件后调的那个），时间记作 0。 */
internal fun Conversation.receive(event: String) = receive(listOf(Json.parseToJsonElement(event) as JsonObject), 0L)

/**
 * 扮 Claude Code 答这个对话的进程正在等的、子类型为 [subtype] 的控制请求（最早的那个），[response] 是应答里的 response 一项。
 * 没有在等的就失败。
 */
internal fun Conversation.answer(subtype: String, response: String, success: Boolean = true) {
    val process = Conversation::class.java.getDeclaredField("process").apply { isAccessible = true }.get(this) as ClaudeProcess
    val id = checkNotNull(process.waitingFor(subtype)) { "没有在等的 $subtype" }
    receive(
        if (success) """{"type":"control_response","response":{"subtype":"success","request_id":"$id","response":$response}}"""
        else """{"type":"control_response","response":{"subtype":"error","request_id":"$id","error":"refused"}}""",
    )
}
