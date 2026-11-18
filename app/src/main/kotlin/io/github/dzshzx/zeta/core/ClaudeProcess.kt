package io.github.dzshzx.zeta.core

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
        class Start(val cwd: String, val title: String, val arguments: List<String>) : Launch
        class Attach(val fromLine: Int) : Launch
    }

    private val process: Process = LinuxEnv.start(
        config,
        hostCommand(
            when (launch) {
                is Launch.Start -> listOf("start", hostKey, launch.cwd, launch.title) + launch.arguments
                is Launch.Attach -> listOf("attach", hostKey, launch.fromLine.toString())
            },
        ),
    )
    private val outbox = LinkedBlockingQueue<String>()
    private val stderrTail = StringBuilder()

    @Volatile private var closed = false

    init {
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
            closed = true
            outbox.offer(POISON)
            val exit = hostExit
            if (exit != null) {
                onExit(exit.int("code") ?: 1, exit.str("stderr").orEmpty())
            } else {
                // 连接这一头自己断了（没拿到 root、脚本出错等）；进程也许还在，由调用方决定是否清理
                onExit(if (code == 0) -1 else code, synchronized(stderrTail) { stderrTail.toString() })
            }
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

    private fun hostCommand(arguments: List<String>): String =
        "exec bash -c " + LinuxEnv.quote(hostScript) + " zeta-host " + arguments.joinToString(" ") { LinuxEnv.quote(it) }

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
