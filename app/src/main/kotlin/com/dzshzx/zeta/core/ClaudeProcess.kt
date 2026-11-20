package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.diag.DiagEvents
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
) {
    /** 和 [Conversation.active] 同一口径：在回答、等确认或有后台任务。 */
    val active: Boolean get() = busy || pending != null || tasks > 0
}

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
    private val requests = ControlRequests()

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

    /**
     * 发一个控制请求，[body] 是其中的 request 一项（带 subtype）。要应答时给 [onResponse]：Claude Code 答了这个请求号，
     * [answer] 就把应答（control_response 里的 response 一项，成功、出错都给）交给它；进程结束时还没答的作废，不再调用。
     */
    fun request(body: JsonObject, onResponse: ((JsonObject) -> Unit)? = null) = send(requests.build(body, onResponse))

    /**
     * 这个进程的一条输出要是等着的某个控制请求的应答，交给那个请求的回调，返回 true。
     * 在主线程上和别的事件按到达顺序调用（[Conversation] 的 handle），回调里改对话状态不用另外排队。
     */
    fun answer(event: JsonObject): Boolean = requests.answer(event)

    /** 还在等应答的、子类型为 [subtype] 的请求号；测试里扮 Claude Code 应答用。 */
    internal fun waitingFor(subtype: String): String? = requests.waitingFor(subtype)

    /** 结束会话并清掉它的托管目录：zeta_host.sh stop 发 end_session，由 Claude Code 自己退出（等价于终端里的 /exit），立即返回。 */
    fun stop() {
        closed = true
        requests.clear()
        outbox.offer(POISON)
        thread(isDaemon = true) { runCatching { LinuxEnv.run(config, hostCommand(listOf("stop", hostKey)), timeoutMs = 20_000) } }
    }

    private companion object {
        val POISON = String(charArrayOf('\u0000'))
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/** 控制请求的 request 一项：[subtype] 加上 [fields]。 */
internal fun controlBody(subtype: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
    put("subtype", subtype)
    fields()
}

/**
 * 一个连接上发出、还在等应答的控制请求。应答只按请求号认，不看形状。请求号每个连接随机起头再递增：
 * 重新接上的进程的输出日志里可能还有上一个连接发的请求的应答，号不会撞上。
 */
internal class ControlRequests(private val prefix: String = "zeta-" + UUID.randomUUID().toString().take(8) + "-") {
    private class Waiting(val subtype: String?, val onResponse: (JsonObject) -> Unit)

    private val waiting = LinkedHashMap<String, Waiting>()
    private var seq = 0

    /** 给 [body] 编上号，组成整行 control_request；[onResponse] 不为 null 时记下，等这个号的应答。 */
    @Synchronized
    fun build(body: JsonObject, onResponse: ((JsonObject) -> Unit)?): JsonObject {
        val id = prefix + ++seq
        if (onResponse != null) waiting[id] = Waiting(body.str("subtype"), onResponse)
        return buildJsonObject {
            put("type", "control_request")
            put("request_id", id)
            put("request", body)
        }
    }

    /** [event] 是等着的请求的应答就交给它的回调（只调一次），返回 true。回调在锁外调用。 */
    fun answer(event: JsonObject): Boolean {
        if (event.str("type") != "control_response") return false
        val response = event.obj("response") ?: return false
        val id = response.str("request_id") ?: return false
        val entry = synchronized(this) { waiting.remove(id) } ?: return false
        entry.onResponse(response)
        return true
    }

    /** 连接断了：还没答的都不等了。 */
    @Synchronized
    fun clear() = waiting.clear()

    /** 还在等应答的、子类型为 [subtype] 的最早一个请求的号（测试里扮 Claude Code 应答用）。 */
    @Synchronized
    fun waitingFor(subtype: String): String? = waiting.entries.firstOrNull { it.value.subtype == subtype }?.key
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
