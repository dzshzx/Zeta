package com.dzshzx.zeta.core

import kotlinx.serialization.json.JsonObject

/**
 * 主机上核心报的一个会话眼下的样子（`conv.activity`，见 docs/core.md「对话」）：Zeta 的核心接着的对话，不管是哪个客户端开的。 [state] 是
 * waiting、answering、background、idle，进程没了时为 null；[turnEnd] 是最后一轮结束的时刻（毫秒），这个核心里还没结束过一轮时为 0。
 */
data class SessionActivity(val state: String?, val tasks: Int, val turnEnd: Long) {
    /** 还在进行：在回答、等确认，或者后台任务没做完。 */
    val active: Boolean
        get() = state == WAITING || state == ANSWERING || state == BACKGROUND

    companion object {
        const val WAITING = "waiting"
        const val ANSWERING = "answering"
        const val BACKGROUND = "background"
        const val IDLE = "idle"

        /** `conv.activity` 推送里的 sessions：会话号 → 样子；坏的项跳过。 */
        fun parse(push: JsonObject): Map<String, SessionActivity> =
            push
                .obj("sessions")
                .orEmpty()
                .mapNotNull { (session, value) ->
                    val o = value as? JsonObject ?: return@mapNotNull null
                    session to
                        SessionActivity(
                            o.str("activity"),
                            o.int("tasks") ?: 0,
                            o.long("turn_end") ?: 0,
                        )
                }
                .toMap()

        /** 从 [before] 到 [after] 之间结束了一轮的会话：两份里都有、结束时刻往后挪了的。第一次见的不算（不知道它是不是刚结束）。 */
        fun turnsEnded(
            before: Map<String, SessionActivity>,
            after: Map<String, SessionActivity>,
        ): List<String> = after.mapNotNull { (session, now) ->
            val was = before[session] ?: return@mapNotNull null
            session.takeIf { now.turnEnd > was.turnEnd }
        }
    }
}
