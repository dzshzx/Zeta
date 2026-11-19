package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.diag.DiagEvents
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
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
    /** 还在等用户批准的 can_use_tool 请求（原样）。 */
    val pending: JsonObject?,
    /** 进程当前的权限模式（可能在运行中变过，比如计划获批后）。 */
    val permissionMode: String? = null,
    /** 还在跑的后台任务数；这一轮已经结束也要接上，任务跑完 Claude Code 还会接着汇报。 */
    val tasks: Int = 0,
    /** 还在跑的后台任务的事件（task_started、最后一条 background_tasks_changed、最近的 task_progress），接上时按序重放给任务清单。 */
    val taskEvents: List<JsonObject> = emptyList(),
)

/**
 * 与 Debian 里一个 `claude -p` 进程的连接，一行一个 JSON（stream-json）。
 *
 * 进程本身由 zeta_host.sh 托管、脱离本应用存活；这里只是接在它的输入管道和输出日志上。
 * 本应用被回收时连接自然断开，进程继续运行，之后可以用 [Launch.Attach] 再接上。回调都在后台线程上。
 */
class ClaudeProcess(
    private val config: LinuxEnvConfig,
    private val hostScript: String,
    val hostKey: String,
    launch: Launch,
    private val onEvent: (JsonObject) -> Unit,
    private val onExit: (exitCode: Int, stderrTail: String) -> Unit,
) {
    sealed interface Launch {
        /** [env] 是只给这个 Claude Code 进程的环境变量（比如自动压缩的设置），进程脱离出去后也一直带着。 */
        class Start(val cwd: String, val title: String, val arguments: List<String>, val env: Map<String, String> = emptyMap()) : Launch
        class Attach(val fromLine: Int) : Launch
    }

    private val process: Process = LinuxEnv.start(
        config,
        when (launch) {
            is Launch.Start -> hostCommand(listOf("start", hostKey, launch.cwd, launch.title) + launch.arguments, launch.env)
            is Launch.Attach -> hostCommand(listOf("attach", hostKey, launch.fromLine.toString()))
        },
    )
    private val outbox = LinkedBlockingQueue<String>()
    private val stderrTail = StringBuilder()

    @Volatile private var closed = false

    init {
        when (launch) {
            is Launch.Start -> DiagEvents.claudeLaunched(hostKey, attach = false, cwd = launch.cwd, arguments = launch.arguments)
            is Launch.Attach -> DiagEvents.claudeLaunched(hostKey, attach = true, cwd = null, arguments = emptyList())
        }
        thread(name = "claude-stdout", isDaemon = true) {
            var hostExit: JsonObject? = null
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (!line.startsWith("{")) return@forEachLine
                    val event = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@forEachLine
                    if (event.str("type") == "zeta_exit") hostExit = event else onEvent(event)
                }
            }
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            // 是这边 stop() 关的，还是那头自己断的
            val stopped = closed
            closed = true
            outbox.offer(POISON)
            val exit = hostExit
            val (exitCode, stderr) = if (exit != null) {
                (exit.int("code") ?: 1) to exit.str("stderr").orEmpty()
            } else {
                // 连接这一头自己断了（没拿到 root、脚本出错等）；进程也许还在，由调用方决定是否清理
                (if (code == 0) -1 else code) to synchronized(stderrTail) { stderrTail.toString() }
            }
            DiagEvents.claudeExited(hostKey, exitCode, stderr, stopped && exit == null)
            onExit(exitCode, stderr)
        }
        thread(name = "claude-stderr", isDaemon = true) {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(stderrTail) {
                        stderrTail.append(line).append('\n')
                        if (stderrTail.length > 4000) stderrTail.delete(0, stderrTail.length - 4000)
                    }
                }
            }
        }
        thread(name = "claude-stdin", isDaemon = true) {
            val stream: OutputStream = process.outputStream
            runCatching {
                while (true) {
                    val line = outbox.take()
                    if (line === POISON) break
                    stream.write(line.toByteArray())
                    stream.write('\n'.code)
                    stream.flush()
                }
            }
            runCatching { stream.close() }
        }
    }

    private fun hostCommand(arguments: List<String>, env: Map<String, String> = emptyMap()): String = hostCommandLine(hostScript, arguments, env)

    fun send(message: JsonObject) {
        if (!closed) outbox.offer(message.toString())
    }

    /** 结束进程并清掉它的托管目录。 */
    fun stop() {
        closed = true
        outbox.offer(POISON)
        thread(isDaemon = true) { runCatching { LinuxEnv.run(config, hostCommand(listOf("stop", hostKey)), timeoutMs = 20_000) } }
    }

    private companion object {
        val POISON = String(charArrayOf('\u0000'))
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

private val EnvName = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * 在 Debian 里执行 zeta_host.sh 的那行命令。[env] 经 `env` 加在最前面，zeta_host.sh 和它起的 Claude Code 都继承；
 * 名字不像环境变量的丢掉，值都加引号。
 */
internal fun hostCommandLine(hostScript: String, arguments: List<String>, env: Map<String, String> = emptyMap()): String {
    val assignments = env.filterKeys { EnvName.matches(it) }.map { (name, value) -> "$name=" + LinuxEnv.quote(value) }
    val prefix = if (assignments.isEmpty()) "" else "env " + assignments.joinToString(" ") + " "
    return "exec " + prefix + "bash -c " + LinuxEnv.quote(hostScript) + " zeta-host " + arguments.joinToString(" ") { LinuxEnv.quote(it) }
}
