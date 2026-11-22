package com.dzshzx.zeta.core

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 发给 serve 的请求没成：[message] 是给人看的原因（serve 或核心给的，或连接断了）；[code] 是 serve 给的错误代号，没给时为 null。 */
class ServeError(message: String, val code: String? = null) : IOException(message)

/** 经一条 serve 连接发请求、等应答：[HelperServe] 是它，测试里换成假的。 */
fun interface ServeRequests {
    /**
     * 发请求 [method]，[params] 的各项和 id、method 平级写出；阻塞到应答。ok 时返回 result（没有或为 null 时是 null），否则抛
     * [ServeError]。
     */
    fun call(method: String, params: JsonObject): JsonElement?

    /** 同 [call]，最多等 [timeoutMs]（比如载完整段历史这种慢的）；不分快慢的连接照 [call] 等。 */
    fun call(method: String, params: JsonObject, timeoutMs: Long): JsonElement? =
        call(method, params)
}

/**
 * 到一台主机上 `zeta-helper serve` 的常驻连接（协议见 `helper/serve.go`）：一条连接收那边推来的消息，不为每个操作各起一次命令。
 *
 * 构造后就在自己的线程上连：[open] 起 serve 进程，收到招呼后交给 [onHello]（每次连上都给，在读线程上调用），把 [subscriptions]
 * 里的方法各发一次，之后每行推来的消息交给 [onMessage] （在读线程上调用）。连上过以后断了（网络、那边的进程没了）按 [backoff] 隔一会儿重连，重连后照样再订阅一遍， 所以
 * [onMessage] 先收到的总是整份的；第一次就没连上的不重试，报了原因就停。每次连上或断开都经 [onProblem] 报一声：连上是 null， 断开、连不上是给人看的原因。 每
 * [pingMs] 发一次 ping，那边 90 秒收不到任何一行就自己退出（网络断了而 SSH 还没发觉时，不在主机上留下没人用的进程）。 [close] 以后不再重连、不再回调。
 *
 * 要应答的请求用 [call]，在别的线程上等；订阅的应答和 ping 的不交给它，订阅被拒时经 [onProblem] 报。
 */
class HelperServe(
    private val name: String,
    private val open: () -> Opened,
    private val subscriptions: List<String>,
    private val onMessage: (JsonObject) -> Unit,
    private val onProblem: (String?) -> Unit,
    private val onHello: (JsonObject) -> Unit = {},
    private val backoff: (attempt: Int) -> Long = ::reconnectDelay,
    private val pingMs: Long = PING_MS,
) : ServeRequests {
    /** 起好的 serve 进程和结束它的办法（连同那边派生的进程）。 */
    class Opened(val process: Process, val kill: (Process) -> Unit)

    private val lock = Object()
    private var current: Opened? = null

    /** 收到招呼、能发请求的那个进程；还在连或断着时为 null。改它时 notifyAll，叫醒等着连上的 [call]。 */
    private var ready: Process? = null

    /** 不再重连了（[close] 了，或第一次就没连上）时是最后的原因，等着连上的 [call] 拿它报错。 */
    private var ended: String? = null

    /** 发出去、还没收到应答的 [call]，按请求号。 */
    private val pending = HashMap<Long, CompletableFuture<JsonElement?>>()
    @Volatile private var closed = false
    private var nextId = 0L

    /** 读线程：[onMessage]、[onProblem] 都在它上面调，应答也靠它读，所以它不能等应答。 */
    private val reader: Thread = thread(name = "zeta-serve-$name", isDaemon = true) { loop() }

    /** 不再要这条连接：结束那边的 serve（它读到输入结束也会自己退出），之后不再回调。等着应答的 [call] 都报错。 */
    fun close() {
        val opened =
            synchronized(lock) {
                closed = true
                if (ended == null) ended = "和 zeta-helper serve 的连接已关闭"
                ready = null
                failPending(ended!!)
                lock.notifyAll()
                current.also { current = null }
            } ?: return
        runCatching { opened.process.outputStream.close() }
        opened.kill(opened.process)
    }

    override fun call(method: String, params: JsonObject): JsonElement? =
        call(method, params, CALL_TIMEOUT_MS)

    /**
     * 发请求 [method] 并等它的 zeta_reply，[params] 的各项和 id、method 平级写出：ok 时返回 result（没有或为 null 时是 null），
     * 否则抛带 serve 给的原因的 [ServeError]。还在连（第一次或重连）时等连上；连接在应答之前断了也抛，不重发（请求可能已经生效）； 不再重连了（[close]
     * 了，或第一次就没连上）时直接报最后的原因。连上加应答一共最多等 [timeoutMs]。阻塞：不能在 [onMessage]、[onProblem]
     * 里调（应答要靠那条线程读），也别在主线程上调。
     */
    override fun call(method: String, params: JsonObject, timeoutMs: Long): JsonElement? {
        check(Thread.currentThread() !== reader) {
            "不能在 onMessage、onProblem 里等 serve 的应答：应答要靠这条线程读"
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val reply = CompletableFuture<JsonElement?>()
        val (process, id) = register(reply, deadline, timeoutMs)
        if (!write(process, request(id, method, params))) {
            synchronized(lock) { pending.remove(id) }
            throw ServeError(DROPPED)
        }
        try {
            return reply.get(remainingMs(deadline).coerceAtLeast(1), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            synchronized(lock) { pending.remove(id) }
            throw ServeError("等了 ${timeoutMs / 1000} 秒，zeta-helper serve 没有应答")
        } catch (e: ExecutionException) {
            throw e.cause as? ServeError ?: ServeError(e.cause?.message ?: DROPPED)
        }
    }

    /** 等到能发请求的那个进程，登记一个请求号。 */
    private fun register(
        reply: CompletableFuture<JsonElement?>,
        deadline: Long,
        timeoutMs: Long,
    ): Pair<Process, Long> =
        synchronized(lock) {
            while (ready == null) {
                ended?.let { throw ServeError(it) }
                val left = remainingMs(deadline)
                if (left <= 0) throw ServeError("等了 ${timeoutMs / 1000} 秒，还没连上 zeta-helper serve")
                lock.wait(left)
            }
            val id = ++nextId
            pending[id] = reply
            ready!! to id
        }

    private fun remainingMs(deadline: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())

    /** 拿着 [lock] 时调：等着应答的都以 [why] 报错。 */
    private fun failPending(why: String) {
        pending.values.forEach { it.completeExceptionally(ServeError(why)) }
        pending.clear()
    }

    private fun loop() {
        var attempt = 0
        var everConnected = false
        while (!closed) {
            if (attempt > 0) NetworkWake.sleep(backoff(attempt))
            if (closed) return
            var connected = false
            val reason = connectOnce { connected = true }
            if (closed) return
            everConnected = everConnected || connected
            // 一次都没连上（环境没就绪、主机不对）：报了原因就停，等调用方下次要时再新建
            if (!everConnected) end(reason)
            onProblem(reason)
            if (!everConnected) return
            attempt = if (connected) 1 else attempt + 1
        }
    }

    private fun end(why: String) =
        synchronized(lock) {
            if (ended == null) ended = why
            lock.notifyAll()
        }

    /** 连一次，读到断开为止，返回断开的原因。收到招呼、订阅发出去以后调 [connected]。 */
    private fun connectOnce(connected: () -> Unit): String {
        val opened =
            runCatching(open).getOrElse {
                return it.message ?: "zeta-helper serve 起不来"
            }
        synchronized(lock) {
            if (closed) {
                opened.kill(opened.process)
                return ""
            }
            current = opened
        }
        val process = opened.process
        val stderr = StringBuilder()
        thread(name = "zeta-serve-stderr", isDaemon = true) { collectTail(process, stderr) }
        var greeted = false
        var refused: String? = null
        runCatching {
            readLines(process.inputStream) { line ->
                val o = parse(line) ?: return@readLines
                when {
                    greeted -> handle(o)
                    o.str("type") == "zeta_hello" && o.int("protocol") == PROTOCOL -> {
                        greeted = true
                        onHello(o)
                        greet(process)
                        connected()
                        onProblem(null)
                    }
                    else -> {
                        refused = "zeta-helper serve 的协议对不上：$line".take(300)
                        opened.kill(process)
                    }
                }
            }
        }
        synchronized(lock) {
            if (current === opened) current = null
            if (ready === process) ready = null
            failPending(DROPPED)
            lock.notifyAll()
        }
        opened.kill(process)
        val code = runCatching { process.waitFor() }.getOrDefault(-1)
        return refused
            ?: synchronized(stderr) { stderr.toString().trim().takeLast(300) }
                .ifEmpty { "和 zeta-helper serve 的连接断了（退出码 $code）" }
                .let { if (code == RemoteShell.CONNECTION_FAILED) "连接断了：$it" else it }
    }

    /** 收到招呼：订阅、开始 ping，之后这个进程能接 [call] 的请求。 */
    private fun greet(process: Process) {
        subscriptions.forEach { send(process, it) }
        startPings(process)
        synchronized(lock) {
            if (!closed) ready = process
            lock.notifyAll()
        }
    }

    private fun handle(o: JsonObject) {
        if (o.str("type") != "zeta_reply") return onMessage(o)
        val reply = o.long("id")?.let { id -> synchronized(lock) { pending.remove(id) } }
        when {
            reply == null ->
                // 订阅、ping 的应答。只有订阅这类请求才会答 false：那边不认这个方法，是两边的版本对不上
                if (o.bool("ok") == false) onProblem(o.str("error") ?: "zeta-helper serve 拒绝了请求")
            o.bool("ok") == true -> reply.complete(o["result"]?.takeIf { it !is JsonNull })
            else ->
                reply.completeExceptionally(
                    ServeError(o.str("error") ?: "zeta-helper serve 拒绝了请求", o.str("code")),
                )
        }
    }

    private fun send(process: Process, method: String) {
        val id = synchronized(lock) { ++nextId }
        write(process, request(id, method, JsonObject(emptyMap())))
    }

    /** 整行写给 [process]；写不出去（连接断了）时返回 false。 */
    private fun write(process: Process, line: String): Boolean =
        synchronized(process) {
            runCatching {
                process.outputStream.write(line.toByteArray())
                process.outputStream.flush()
            }
                .isSuccess
        }

    private fun startPings(process: Process) {
        thread(name = "zeta-serve-ping", isDaemon = true) {
            while (!closed && process.isAlive) {
                runCatching { Thread.sleep(pingMs) }
                if (closed || !process.isAlive) return@thread
                send(process, "ping")
            }
        }
    }

    private fun collectTail(process: Process, tail: StringBuilder) {
        runCatching {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(tail) {
                    tail.append(line).append('\n')
                    if (tail.length > 2000) tail.delete(0, tail.length - 2000)
                }
            }
        }
    }

    companion object {
        /** 和 `helper/serve.go` 的 serveProtocol 一致。 */
        const val PROTOCOL = 1

        /** 多久发一次 ping：比那边的 90 秒短得多，丢一两次也不至于被当成走了。 */
        const val PING_MS = 30_000L

        /** [call] 默认最多等多久：conv.open 可能要先拉起核心、载入历史，要等 Claude Code 应答的 conv.* 请求在核心里最多等 30 秒。 */
        const val CALL_TIMEOUT_MS = 60_000L

        private const val DROPPED = "和 zeta-helper serve 的连接断了，没收到应答"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private fun parse(line: String): JsonObject? = runCatching {
            json.parseToJsonElement(line) as? JsonObject
        }
            .getOrNull()

        /** 一行请求：id、method 在前，[params] 的各项和它们平级（同名的以 id、method 为准）。 */
        private fun request(id: Long, method: String, params: JsonObject): String =
            JsonObject(
                    linkedMapOf<String, JsonElement>(
                        "id" to JsonPrimitive(id),
                        "method" to JsonPrimitive(method),
                    ) + params.filterKeys { it != "id" && it != "method" },
                )
                .toString() + "\n"

        /**
         * 在 [shell] 那台上起 serve：没装 zeta-helper 就先装上（[HelperProgram.ensure]）。装不上时抛出，原因先给 [problem]
         * 看（远程主机连不上之类给一句人话，见 [ConversationHub.linkProblem]），它不认就用标准错误。阻塞。
         */
        fun open(
            shell: LinuxShell,
            program: HelperProgram,
            problem: (CommandResult) -> String? = { null },
        ): Opened {
            val installed =
                program.ensure { command, stdin -> shell.run(command, stdin, 60_000, plain = true) }
                    ?: error("进不了 Linux 环境")
            if (installed.exitCode != 0)
                error(
                    problem(installed)
                        ?: installed.stderr.trim().takeLast(300).ifEmpty { "zeta-helper 没装上" },
                )
            return Opened(shell.startPlain(program.command("serve")), shell::kill)
        }
    }
}
