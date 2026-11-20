package com.dzshzx.zeta.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 起进程时 [flag] 后面跟的值；没带这个参数时为 null。 */
internal fun Conversation.launchArgument(flag: String): String? =
    launchArguments().let { args -> args.indexOf(flag).takeIf { it >= 0 }?.let { args[it + 1] } }

/** 喂一条 Claude Code 的输出事件（和进程送来的一样走 handle）。 */
internal fun Conversation.receive(event: String) {
    Conversation::class.java.getDeclaredMethod("handle", JsonObject::class.java, Long::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(this, Json.parseToJsonElement(event) as JsonObject, 0L)
}
