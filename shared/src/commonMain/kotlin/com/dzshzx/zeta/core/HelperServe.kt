package com.dzshzx.zeta.core

import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 到一台主机上 `zeta-helper serve` 的常驻连接（协议见 `helper/serve.go`）：一条连接收那边推来的消息，不为每个操作各起一次命令。
 *
 * 构造后就在自己的线程上连：[open] 起 serve 进程，收到招呼后把 [subscriptions] 里的方法各发一次，之后每行推来的消息交给 [onMessage]
 * （在读线程上调用）。连上过以后断了（网络、那边的进程没了）按 [backoff] 隔一会儿重连，重连后照样再订阅一遍， 所以 [onMessage]
 * 先收到的总是整份的；第一次就没连上的不重试，报了原因就停。每次连上或断开都经 [onProblem] 报一声：连上是 null， 断开、连不上是给人看的原因。 每 [pingMs] 发一次
 * ping，那边 90 秒收不到任何一行就自己退出（网络断了而 SSH 还没发觉时，不在主机上留下没人用的进程）。 [close] 以后不再重连、不再回调。
 */
class HelperServe(
    private val name: String,
    private val open: () -> Opened,
    private val subscriptions: List<String>,
    private val onMessage: (JsonObject) -> Unit,
    private val onProblem: (String?) -> Unit,
    private val backoff: (attempt: Int) -> Long = ::reconnectDelay,
    private val pingMs: Long = PING_MS,
) {
    /** 起好的 serve 进程和结束它的办法（连同那边派生的进程）。 */
    class Opened(val process: Process, val kill: (Process) -> Unit)

    private val lock = Object()
    private var current: Opened? = null
    @Volatile private var closed = false
    private var nextId = 0L

    init {
        thread(name = "zeta-serve-$name", isDaemon = true) { loop() }
    }

    /** 不再要这条连接：结束那边的 serve（它读到输入结束也会自己退出），之后不再回调。 */
    fun close() {
        val opened =
            synchronized(lock) {
                closed = true
                current.also { current = null }
            } ?: return
        runCatching { opened.process.outputStream.close() }
        opened.kill(opened.process)
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
            onProblem(reason)
            everConnected = everConnected || connected
            // 一次都没连上（环境没就绪、主机不对）：报了原因就停，等调用方下次要时再新建
            if (!everConnected) return
            attempt = if (connected) 1 else attempt + 1
        }
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
                        subscriptions.forEach { send(process, it) }
                        startPings(process)
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
        synchronized(lock) { if (current === opened) current = null }
        opened.kill(process)
        val code = runCatching { process.waitFor() }.getOrDefault(-1)
        return refused
            ?: synchronized(stderr) { stderr.toString().trim().takeLast(300) }
                .ifEmpty { "和 zeta-helper serve 的连接断了（退出码 $code）" }
                .let { if (code == RemoteShell.CONNECTION_FAILED) "连接断了：$it" else it }
    }

    private fun handle(o: JsonObject) {
        if (o.str("type") != "zeta_reply") return onMessage(o)
        // 只有订阅这类请求才会答 false：那边不认这个方法，是两边的版本对不上
        if (o.bool("ok") == false) onProblem(o.str("error") ?: "zeta-helper serve 拒绝了请求")
    }

    private fun send(process: Process, method: String) {
        val id = synchronized(lock) { ++nextId }
        val line =
            buildJsonObject {
                put("id", JsonPrimitive(id))
                put("method", method)
            }
                .toString() + "\n"
        synchronized(process) {
            runCatching {
                process.outputStream.write(line.toByteArray())
                process.outputStream.flush()
            }
        }
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

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private fun parse(line: String): JsonObject? = runCatching {
            json.parseToJsonElement(line) as? JsonObject
        }
            .getOrNull()

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
