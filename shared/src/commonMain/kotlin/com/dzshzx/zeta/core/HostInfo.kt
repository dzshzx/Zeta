package com.dzshzx.zeta.core

import kotlinx.serialization.json.JsonObject

/** 一个仍在后台运行、可以重新接上的 Claude Code 进程（应用上次退出时留下的）。 */
class HostInfo(
    val key: String,
    val cwd: String,
    val title: String,
    val sessionId: String?,
    val historyFile: String?,
    /** 输出日志已经有多少行；接上时从下一行开始读。 */
    val lines: Int,
    val busy: Boolean,
    /** 还在等用户回应的请求（can_use_tool、elicitation 的 control_request 行，原样），按到达顺序。 */
    val pending: List<JsonObject> = emptyList(),
    /** 进程当前的权限模式（可能在运行中变过，比如计划获批后）。 */
    val permissionMode: String? = null,
    /** 还在跑的后台任务数；这一轮已经结束也要接上，任务跑完 Claude Code 还会接着汇报。 */
    val tasks: Int = 0,
    /** 还在跑的后台任务的事件（task_started、最后一条 background_tasks_changed、最近的 task_progress），接上时按序重放给任务清单。 */
    val taskEvents: List<JsonObject> = emptyList(),
) {
    /** 和 [Conversation.active] 同一口径：在回答、等确认或有后台任务。 */
    val active: Boolean
        get() = busy || pending.isNotEmpty() || tasks > 0
}

private val EnvName = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * 在 Linux 里执行 zeta_host.sh 的那行命令。[env] 经 `env` 加在最前面，zeta_host.sh 和它起的 Claude Code 都继承；
 * 名字不像环境变量的丢掉，值都加引号。
 */
fun hostCommandLine(
    hostScript: String,
    arguments: List<String>,
    env: Map<String, String> = emptyMap(),
): String {
    val assignments =
        env.filterKeys { EnvName.matches(it) }
            .map { (name, value) -> "$name=" + LinuxEnv.quote(value) }
    val prefix = if (assignments.isEmpty()) "" else "env " + assignments.joinToString(" ") + " "
    return "exec " +
        prefix +
        "bash -c " +
        LinuxEnv.quote(hostScript) +
        " zeta-host " +
        arguments.joinToString(" ") { LinuxEnv.quote(it) }
}
