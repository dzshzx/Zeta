package com.dzshzx.zeta.core

import kotlinx.serialization.json.JsonObject

/**
 * 一个对话里的子代理跟踪：子代理查看页看过的子代理（[SubagentLog]，按发起它的 Agent 调用，只留最近几个）、 Agent 调用和子代理 id
 * 的对应、输出流里报了结局的子代理，以及 /clear 之前用过的会话号（那之前的子代理记录在那些会话的目录里）。 纯逻辑，状态只在主线程上改；读记录要调助手脚本，由
 * [Conversation.refreshSubagent] 做。
 */
internal class SubagentTracker {
    /** 查看页看过的子代理，按发起它的 Agent 调用；按访问顺序只留最近几个。 */
    private val logs =
        object : LinkedHashMap<String, SubagentLog>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SubagentLog>?) =
                size > MAX_LOGS
        }

    /** Agent 调用 → 子代理 id（task_started 的 task_id、工具结果里的 agentId）：读记录时不用逐个翻 meta。 */
    private val ids = LinkedHashMap<String, String>()

    /** 输出流里报了结局的子代理：子代理 id → 结局。 */
    private val outcomes = LinkedHashMap<String, TaskState>()

    /** /clear 之前用过的会话号。 */
    private val pastSessions = ArrayList<String>()

    /** 查看页要看的那个子代理：[toolUseId] 是发起它的 Agent 调用。 */
    fun log(toolUseId: String): SubagentLog = logs.getOrPut(toolUseId) { SubagentLog(toolUseId) }

    /** 开着查看页的子代理。 */
    val watched: List<SubagentLog>
        get() = logs.values.filter { it.watchers > 0 }

    /** [log] 的子代理 id：记录的 meta 里有就用它，没有时用输出流里见过的；都没有时为 null。 */
    fun agentId(log: SubagentLog): String? =
        log.transcript.meta?.agentId?.takeIf { it.isNotEmpty() } ?: ids[log.toolUseId]

    /** /clear 换了新会话：[previous] 里的子代理记录以后也要能找到。 */
    fun sessionCleared(previous: String) {
        pastSessions.add(previous)
    }

    /** 找子代理记录的会话：[current]（现在的会话、分支的来源）在前，再是 /clear 之前用过的。 */
    fun sessions(current: List<String>): List<String> = (current + pastSessions).distinct()

    /**
     * 更新查看页 [log] 的状态。结局以输出流里报的为准，没报过就看记录的 meta。 [active]
     * 是对话的进程活着、在回答或有后台任务：没有结局的子代理这时算在跑（前台的在回答里，后台的在后台任务里）。
     */
    fun publish(log: SubagentLog, active: Boolean) {
        val meta = log.transcript.meta
        val outcome =
            agentId(log)?.let(outcomes::get)
                ?: when {
                    meta?.stopped == true -> TaskState.Stopped
                    meta?.finished == true -> TaskState.Completed
                    else -> null
                }
        log.publish(outcome == null && active, outcome)
    }

    /**
     * 输出流里的一条事件（[parent] 是它的 parent_tool_use_id）：记下 Agent 调用和子代理 id 的对应、子代理的结局；
     * 子代理内部的事件交给开着的查看页。返回状态要刷新的查看页。
     */
    fun observe(event: JsonObject, parent: String?): List<SubagentLog> =
        when (event.str("type")) {
            "system" -> observeSystem(event)
            "assistant",
            "user" -> observeMessage(event, parent)
            else -> emptyList()
        }

    /** 子代理开始（记下 Agent 调用对应的子代理 id）和结局；返回要刷新的查看页。 */
    private fun observeSystem(event: JsonObject): List<SubagentLog> {
        when (event.str("subtype")) {
            "task_started" ->
                if (event.str("task_type") == "local_agent") {
                    val use = event.str("tool_use_id") ?: return emptyList()
                    putRecent(ids, use, event.str("task_id") ?: return emptyList())
                }
            "task_notification" -> {
                val id = event.str("task_id") ?: return emptyList()
                val state = notifiedState(event.str("status")) ?: return emptyList()
                putRecent(outcomes, id, state)
                return logs.values.filter {
                    it.watchers > 0 &&
                        (it.transcript.meta?.agentId == id || ids[it.toolUseId] == id)
                }
            }
        }
        return emptyList()
    }

    /** task_notification 的 status 对应的结局；不认识的是 null。 */
    private fun notifiedState(status: String?): TaskState? =
        when (status) {
            "completed" -> TaskState.Completed
            "failed" -> TaskState.Failed
            "stopped" -> TaskState.Stopped
            else -> null
        }

    /** assistant、user 事件：子代理内部的交给开着的查看页；主对话里 Agent 调用的结果带着子代理 id。 */
    private fun observeMessage(event: JsonObject, parent: String?): List<SubagentLog> {
        if (parent != null) {
            val log = logs[parent]?.takeIf { it.watchers > 0 } ?: return emptyList()
            return if (log.transcript.observeLive(event)) listOf(log) else emptyList()
        }
        if (event.str("type") != "user") return emptyList()
        // Agent 调用的结果里带着子代理 id（前台的做完时、后台的一起来就有）
        val agent = event.obj("tool_use_result")?.str("agentId") ?: return emptyList()
        val use =
            event.obj("message")?.arr("content")?.firstNotNullOfOrNull {
                (it as? JsonObject)?.str("tool_use_id")
            } ?: return emptyList()
        putRecent(ids, use, agent)
        return emptyList()
    }

    private fun <V> putRecent(map: LinkedHashMap<String, V>, key: String, value: V) {
        map.remove(key)
        map[key] = value
        while (map.size > MAX_IDS) map.remove(map.keys.first())
    }

    private companion object {
        const val MAX_LOGS = 8
        const val MAX_IDS = 128
    }
}
