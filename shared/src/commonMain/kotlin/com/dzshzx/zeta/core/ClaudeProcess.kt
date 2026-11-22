package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.diag.DiagEvents
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
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

/**
 * 托管进程的结局（[ClaudeProcess] 的 onExit）：退出码、标准错误输出的最后一段，和托管脚本报的会话去了别处的原因（[reason]，zeta_exit 的 reason）：
 * [RELEASED] 是电脑那边接回去了（zeta-helper 让出会话时结束了这边的进程），[HELD_ELSEWHERE] 是要接着的会话正被 Zeta 之外的 Claude
 * Code（比如终端里的）占着、托管脚本没起进程；别的结局是 null。
 */
class ProcessExit(val code: Int, val stderr: String, val reason: String? = null) {
    companion object {
        const val RELEASED = "released"
        const val HELD_ELSEWHERE = "held_elsewhere"
    }
}

/** 和托管进程之间连接的状态（远程主机才会断，见 [ClaudeProcess]）。 */
sealed interface ProcessLink {
    data object Connected : ProcessLink

    /** 断了，正在第 [attempt] 次重连；[reason] 是 ssh 说的原因（可能为空）。 */
    data class Reconnecting(val attempt: Int, val reason: String) : ProcessLink
}

/**
 * 与一个 `claude -p` 进程的连接，一行一个 JSON（stream-json）。进程在 [shell] 那台机器上（手机的 Debian 或远程主机）。
 *
 * 进程本身由 zeta_host.sh 托管、脱离本应用存活；这里只是接在它的输入管道和输出日志上。 本应用被回收时连接自然断开，进程继续运行，之后可以用 [Launch.Attach] 再接上。
 *
 * 远程主机的连接还会因为网络断掉（以 [RemoteShell.CONNECTION_FAILED] 退出）：接上过的进程这时不当它结束， 隔一会儿从读到的下一行重新接上，一直重试到接上或
 * [stop]（网络恢复后对话照原样继续，等批准的请求也还在）； 期间要发的消息留着、接上后再发，[onLink] 报告断开和接上。回调都在后台线程上。
 */
class ClaudeProcess(
    private val shell: LinuxShell,
    private val hostScript: String,
    val hostKey: String,
    launch: Launch,
    private val onEvent: (JsonObject) -> Unit,
    private val onExit: (ProcessExit) -> Unit,
    private val onLink: (ProcessLink) -> Unit = {},
    /** 断了要不要重连：远程主机才会只断连接、不断进程。 */
    private val reconnect: Boolean = shell is RemoteShell,
    /** 第几次重连之前等多久（毫秒）；测试里改短。 */
    private val backoff: (attempt: Int) -> Long = ::reconnectDelay,
) {
    sealed interface Launch {
        /** [env] 是只给这个 Claude Code 进程的环境变量（比如自动压缩的设置），进程脱离出去后也一直带着。 */
        class Start(
            val cwd: String,
            val title: String,
            val arguments: List<String>,
            val env: Map<String, String> = emptyMap(),
        ) : Launch

        class Attach(val fromLine: Int) : Launch
    }

    private val outbox = LinkedBlockingDeque<String>()
    private val requests = ControlRequests()

    @Volatile private var closed = false
    private val finished = AtomicBoolean(false)

    /** 连接断了要重连：远程主机上已知存在的进程才重连（接过的，或接上一个已经在跑的）。 新起的进程第一次就没连上，说明那边根本没起来，照常报结束。 */
    @Volatile private var retrying: Boolean = reconnect && launch is Launch.Attach

    /** 输出日志里下一行的行号（从 1 起）：接上时从这一行读起，断了重连也从这里接着读。 */
    @Volatile private var nextLine: Int = (launch as? Launch.Attach)?.fromLine ?: 1

    init {
        when (launch) {
            is Launch.Start ->
                DiagEvents.claudeLaunched(
                    hostKey,
                    attach = false,
                    cwd = launch.cwd,
                    arguments = launch.arguments,
                )
            is Launch.Attach ->
                DiagEvents.claudeLaunched(
                    hostKey,
                    attach = true,
                    cwd = null,
                    arguments = emptyList(),
                )
        }
        connect(
            when (launch) {
                is Launch.Start ->
                    hostCommand(
                        listOf("start", hostKey, launch.cwd, launch.title) + launch.arguments,
                        launch.env,
                    )
                is Launch.Attach ->
                    hostCommand(listOf("attach", hostKey, launch.fromLine.toString()))
            },
            attempt = 0,
            login = launch is Launch.Start,
        )
    }

    /**
     * 接上一次：起 [command]，开读输出、读错误、写输入三个线程。连接断了由读输出的线程决定是结束还是重连。 [attempt] 是第几次重连（0
     * 是第一次接上），抛出的异常（su、ssh 起不来）第一次时交给调用方。 [login]：起 Claude Code 要登录 shell 的环境（PATH 里的
     * claude、代理等）；只接上的话托管脚本只用 bash 和 coreutils（[LinuxShell.startPlain]）。
     */
    private fun connect(command: String, attempt: Int, login: Boolean) {
        val process = if (login) shell.start(command) else shell.startPlain(command)
        val stderrTail = StringBuilder()
        // 托管脚本说接上了才开始写：没接上的连接（ssh 正要失败退出）收下的消息会跟着它一起丢掉
        val ready = CountDownLatch(1)
        val stderrReader =
            thread(name = "claude-stderr", isDaemon = true) { collectStderr(process, stderrTail) }
        // 连接断了时读输出的线程打断它：不再从发件箱里取消息，留给下一条连接
        val writer =
            thread(name = "claude-stdin", isDaemon = true) {
                writeOutbox(process.outputStream, ready)
            }
        thread(name = "claude-stdout", isDaemon = true) {
            val hostExit = readOutput(process, ready, attempt)
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            writer.interrupt()
            // 标准错误读完再看（ssh 连不上时原因在最后一行）
            stderrReader.join(2_000)
            val stderr = synchronized(stderrTail) { stderrTail.toString() }
            endConnection(hostExit, code, stderr, attempt)
        }
    }

    /** 读标准错误，只留最后约 4000 个字符。 */
    private fun collectStderr(process: Process, stderrTail: StringBuilder) {
        runCatching {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(stderrTail) {
                    stderrTail.append(line).append('\n')
                    if (stderrTail.length > 4000) stderrTail.delete(0, stderrTail.length - 4000)
                }
            }
        }
    }

    /** 接上后把发件箱里的消息一条一条写进 [stream]，直到收到 [POISON] 或连接断了。 */
    private fun writeOutbox(stream: OutputStream, ready: CountDownLatch) {
        runCatching {
            ready.await()
            while (true) {
                val line = outbox.take()
                if (line === POISON) break
                try {
                    stream.write(line.toByteArray())
                    stream.write('\n'.code)
                    stream.flush()
                } catch (error: Exception) {
                    // 连接断了（或者写到一半被打断）：这一条留给重连后的那条连接（已经关掉的话就丢了）
                    if (!closed) outbox.offerFirst(line)
                    throw error
                }
            }
        }
        runCatching { stream.close() }
    }

    /** 读标准输出直到连接断开，事件交给 [onEvent]；返回托管脚本报的结局（zeta_exit 那一行），没报就是 null。 */
    private fun readOutput(process: Process, ready: CountDownLatch, attempt: Int): JsonObject? {
        var hostExit: JsonObject? = null
        // 托管脚本接上后先打一行 zeta_attached，之后的每一行都是输出日志里的一行（直到 zeta_exit）；
        // 之前的是登录 shell 打的别的东西，不算行数
        var attached = false
        runCatching {
            readLines(process.inputStream) { line ->
                val event = parseEventLine(line)
                when (event?.str("type")) {
                    "zeta_attached" -> {
                        attached = true
                        onAttached(event, ready, attempt)
                    }
                    "zeta_exit" -> hostExit = event
                    else -> {
                        if (attached) nextLine++
                        event?.let(onEvent)
                    }
                }
            }
        }
        return hostExit
    }

    /** 输出的一行：是 JSON 对象就解析出来，别的（登录 shell 打的字、坏行）是 null。 */
    private fun parseEventLine(line: String): JsonObject? =
        if (line.startsWith("{"))
            runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        else null

    /** 托管脚本说接上了：从它报的行号接着数，放开写输入的线程；重连接上的报告一声。 */
    private fun onAttached(event: JsonObject, ready: CountDownLatch, attempt: Int) {
        event.int("from")?.let { nextLine = it }
        if (reconnect) retrying = true
        ready.countDown()
        if (attempt > 0) {
            DiagEvents.claudeLinkRestored(hostKey)
            onLink(ProcessLink.Connected)
        }
    }

    /** 连接断了以后：托管脚本报了结局就结束；远程连接断了、进程还在就重连；别的照退出码结束。 */
    private fun endConnection(hostExit: JsonObject?, code: Int, stderr: String, attempt: Int) {
        // 是这边 stop() 关的
        val stopped = closed
        when {
            hostExit != null ->
                finish(
                    ProcessExit(
                        hostExit.int("code") ?: 1,
                        hostExitStderr(hostExit),
                        reason = hostExit.str("reason"),
                    ),
                    stopped = false,
                )
            !stopped && retrying && code == RemoteShell.CONNECTION_FAILED ->
                retry(attempt + 1, stderr)
            // stop() 关的；或者连接这一头自己断了（没拿到 root、脚本出错等），进程也许还在，由调用方决定是否清理
            else -> finish(ProcessExit(if (code == 0) -1 else code, stderr), stopped)
        }
    }

    /** 连接断了、进程还在那边：等一会儿从 [nextLine] 起重新接上。 */
    private fun retry(attempt: Int, stderr: String) {
        val delay = backoff(attempt)
        DiagEvents.claudeLinkLost(hostKey, attempt, stderr)
        onLink(ProcessLink.Reconnecting(attempt, RemoteHosts.sshProblem(stderr)))
        thread(name = "claude-reconnect", isDaemon = true) {
            NetworkWake.sleep(delay)
            if (closed) {
                finish(ProcessExit(-1, stderr), stopped = true)
                return@thread
            }
            val started = runCatching {
                connect(
                    hostCommand(listOf("attach", hostKey, nextLine.toString())),
                    attempt,
                    login = false,
                )
            }
            if (started.isFailure) retry(attempt + 1, started.exceptionOrNull()?.message.orEmpty())
        }
    }

    /** 连接到此为止：只报一次。 */
    private fun finish(exit: ProcessExit, stopped: Boolean) {
        if (!finished.compareAndSet(false, true)) return
        closed = true
        outbox.offer(POISON)
        DiagEvents.claudeExited(hostKey, exit.code, exit.stderr, stopped)
        onExit(exit)
    }

    private fun hostCommand(
        arguments: List<String>,
        env: Map<String, String> = emptyMap(),
    ): String = hostCommandLine(hostScript, arguments, env)

    fun send(message: JsonObject) {
        if (!closed) outbox.offer(message.toString())
    }

    /**
     * 发一个控制请求，[body] 是其中的 request 一项（带 subtype）。要应答时给 [onResponse]：Claude Code 答了这个请求号， [answer]
     * 就把应答（control_response 里的 response 一项，成功、出错都给）交给它；进程结束时还没答的作废，不再调用。
     */
    fun request(body: JsonObject, onResponse: ((JsonObject) -> Unit)? = null) =
        send(requests.build(body, onResponse))

    /**
     * 这个进程的一条输出要是等着的某个控制请求的应答，交给那个请求的回调，返回 true。 在主线程上和别的事件按到达顺序调用（[Conversation] 的
     * handle），回调里改对话状态不用另外排队。
     */
    fun answer(event: JsonObject): Boolean = requests.answer(event)

    /** 还在等应答的、子类型为 [subtype] 的请求号；测试里扮 Claude Code 应答用。 */
    fun waitingFor(subtype: String): String? = requests.waitingFor(subtype)

    /** 结束会话并清掉它的托管目录：zeta_host.sh stop 发 end_session，由 Claude Code 自己退出（等价于终端里的 /exit），立即返回。 */
    fun stop() {
        closed = true
        requests.clear()
        outbox.offer(POISON)
        thread(isDaemon = true) {
            runCatching {
                shell.run(hostCommand(listOf("stop", hostKey)), timeoutMs = 20_000, plain = true)
            }
        }
    }

    private companion object {
        val POISON = String(charArrayOf('\u0000'))
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

/**
 * 第 [attempt] 次重连之前等多久：1、2、4、8、15 秒，之后十分钟里每 30 秒一次，再往后每分钟一次。 没网时 ssh 立刻失败，有网连不上时最多等
 * ConnectTimeout，所以一直重试也花不了多少。
 */
fun reconnectDelay(attempt: Int): Long =
    when {
        attempt <= 0 -> 0L
        attempt <= 4 -> 1_000L shl (attempt - 1)
        attempt == 5 -> 15_000L
        attempt <= 24 -> 30_000L
        else -> 60_000L
    }

/** 手机的网络变了（重新有网、换了网）：等着重连的连接提前醒来，不等满退避的时间。 */
object NetworkWake {
    private val lock = Object()
    private var generation = 0L

    fun signal() =
        synchronized(lock) {
            generation++
            lock.notifyAll()
        }

    /** 等 [ms] 毫秒，网络中途变了就提前返回。 */
    fun sleep(ms: Long) =
        synchronized(lock) {
            val start = generation
            val deadline = System.currentTimeMillis() + ms
            while (generation == start) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return@synchronized
                lock.wait(left)
            }
        }
}

/** 从 [input] 一行行读到结束，每读到完整的一行（以换行结尾）交给 [onLine]，不带换行。 连接断在一行中间时，剩下的半行不交出去（也就不算进行数），重连后从这一行读起。 */
fun readLines(input: InputStream, onLine: (String) -> Unit) {
    val buffer = ByteArray(64 * 1024)
    val pending = ByteArrayOutputStream()
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        var from = 0
        for (i in 0 until n) {
            if (buffer[i] != '\n'.code.toByte()) continue
            pending.write(buffer, from, i - from)
            onLine(pending.toString(Charsets.UTF_8.name()))
            pending.reset()
            from = i + 1
        }
        if (from < n) pending.write(buffer, from, n - from)
    }
}

/** 控制请求的 request 一项：[subtype] 加上 [fields]。 */
fun controlBody(subtype: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonObject =
    buildJsonObject {
        put("subtype", subtype)
        fields()
    }

/** 一个连接上发出、还在等应答的控制请求。应答只按请求号认，不看形状。请求号每个连接随机起头再递增： 重新接上的进程的输出日志里可能还有上一个连接发的请求的应答，号不会撞上。 */
class ControlRequests(
    private val prefix: String = "zeta-" + UUID.randomUUID().toString().take(8) + "-",
) {
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
    @Synchronized fun clear() = waiting.clear()

    /** 还在等应答的、子类型为 [subtype] 的最早一个请求的号（测试里扮 Claude Code 应答用）。 */
    @Synchronized
    fun waitingFor(subtype: String): String? =
        waiting.entries.firstOrNull { it.value.subtype == subtype }?.key
}

private val EnvName = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** zeta_host.sh 退出报告（zeta_exit）里的标准错误输出：最后约 2000 字节的 base64。截在半个字上的开头几个字节去掉， 别的不合法的字节换成替换字符。 */
fun hostExitStderr(report: JsonObject): String {
    val bytes =
        runCatching { java.util.Base64.getDecoder().decode(report.str("stderr_base64").orEmpty()) }
            .getOrNull() ?: return ""
    val start =
        bytes.indexOfFirst { (it.toInt() and 0xC0) != 0x80 }.let { if (it < 0) bytes.size else it }
    return String(bytes, start, bytes.size - start, Charsets.UTF_8)
}

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
