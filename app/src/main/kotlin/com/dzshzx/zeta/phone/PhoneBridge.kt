package com.dzshzx.zeta.phone

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 手机工具的外部入口：Zeta 之外的 MCP 客户端（工作站上的 Claude Code）经它用手机工具，和手机上的对话用的是同一套（[PhoneTools.handle]）。
 *
 * 抽象 Unix 套接字 [socketName]。连上后先发一行 `ZETA-BRIDGE <客户端标识>`，之后一行一条 MCP（JSON-RPC）消息。只认 root 连进来： 工作站
 * ssh 进手机的 Debian，`sudo zeta-helper phone-mcp <包名> <标识>` 把标准输入输出接到这里（见 helper/phonemcp.go）。 root
 * 本来就能操作整台手机，开这个口子不多给权限；别的身份（包括 Debian 里不带 sudo 的进程、其他应用）连上来马上断开。
 *
 * 每个客户端标识一份 [PhoneTools]，有自己的后台屏幕，和手机上的对话互不干扰。工作站上的命令一次调用连一次，同一个标识重连时接着用原来那份
 * （打开的应用、上次读屏的控件编号都还在）。连着时前台服务保活，进程不被冻结（[connected]，见 ConversationHub.onActivityChanged）。
 * 几个调用一起来时并行处理，应答按完成的先后写回，客户端按 id 对上。
 */
internal class PhoneBridge(
    private val socketName: String,
    /** 客户端 key 连上了，要它的手机工具（在主线程上调用；同一个 key 给同一份）。[busy] 是这个客户端有没有调用正在处理。 */
    private val open: (key: String, busy: () -> Boolean) -> PhoneTools,
    /** 一条连接断了（在主线程上调用）。 */
    private val closed: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val clients = AtomicInteger()

    /** 各客户端正在处理的调用数，跨连接留着：后台屏幕在调用之间照常休眠，调用进行中不休眠、不被腾掉。 */
    private val inFlight = ConcurrentHashMap<String, AtomicInteger>()

    /** 现在连着的客户端数。任何线程上都能读。 */
    val connected: Int
        get() = clients.get()

    /** 开始在套接字上等连接（另起线程）。名字被占（另一个进程开着同一个包）时记一笔就算了。 */
    fun start() {
        thread(name = "zeta-phone-bridge", isDaemon = true) {
            val server =
                runCatching { LocalServerSocket(socketName) }
                    .onFailure { Log.w(TAG, "开不了 $socketName：$it") }
                    .getOrNull() ?: return@thread
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: continue
                thread(name = "zeta-phone-bridge-client", isDaemon = true) { accept(socket) }
            }
        }
    }

    private fun accept(socket: LocalSocket) {
        socket.use {
            val uid = runCatching { it.peerCredentials.uid }.getOrDefault(-1)
            if (uid != ROOT_UID) {
                Log.w(TAG, "拒绝 uid $uid 的连接")
                return
            }
            val input = it.inputStream
            val key = clientKey(readLine(input))
            if (key == null) {
                Log.w(TAG, "开头不是 ZETA-BRIDGE <标识>，断开")
                return
            }
            val calls = inFlight.getOrPut(key) { AtomicInteger() }
            // 先记上再要手机工具：open 里会据此把前台服务开起来
            clients.incrementAndGet()
            try {
                val phone = onMain { open(key) { calls.get() > 0 } }
                serve(input, it.outputStream, calls) { message -> phone.handle(message) }
            } catch (e: Exception) {
                Log.w(TAG, "客户端 $key：$e")
            } finally {
                clients.decrementAndGet()
                main.post(closed)
            }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        main.post(task)
        return task.get()
    }

    companion object {
        private const val TAG = "ZetaPhoneBridge"
        private const val ROOT_UID = 0
        private val json = Json { ignoreUnknownKeys = true }

        private val KEY = Regex("[A-Za-z0-9_.-]{1,64}")

        /** 开头那一行 `ZETA-BRIDGE <标识>` 里的标识；不是这个样子返回 null。 */
        fun clientKey(header: String?): String? =
            header?.removePrefix("ZETA-BRIDGE ")?.takeIf { it != header && KEY.matches(it) }

        /** 按字节读一行，最多 200 字节（不经缓冲：后面的 MCP 消息留给 [serve] 读）。 */
        private fun readLine(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() <= 200) {
                when (val b = input.read()) {
                    -1 -> return null
                    '\n'.code -> return bytes.toString(Charsets.UTF_8.name())
                    else -> bytes.write(b)
                }
            }
            return null
        }

        /** 包 [packageName] 的入口名字（抽象命名空间，不带开头的 @）。 */
        fun socketName(packageName: String): String = "$packageName.phone"

        /**
         * 读 [input] 里一行一条的 MCP 消息，交给 [handle]，应答一行一条写到 [output]，直到对方关掉。 有 id 的请求各在一个线程里处理（读屏、等待要时间，
         * 不挡后面的调用），[inFlight] 记着正在处理的个数；通知（没有 id）不用应答，读不懂的行跳过。
         */
        fun serve(
            input: InputStream,
            output: OutputStream,
            inFlight: AtomicInteger,
            handle: (JsonObject) -> JsonObject,
        ) {
            val writer = output.bufferedWriter(Charsets.UTF_8)
            val workers = mutableListOf<Thread>()
            input.bufferedReader(Charsets.UTF_8).use { reader ->
                for (message in messages(reader)) {
                    if (message["id"] == null) continue
                    inFlight.incrementAndGet()
                    workers.removeAll { !it.isAlive }
                    workers +=
                        thread(name = "zeta-phone-bridge-call", isDaemon = true) {
                            val reply = runCatching {
                                handle(message)
                            }
                                .getOrElse { failed(message, it) }
                            inFlight.decrementAndGet()
                            write(writer, reply)
                        }
                }
            }
            // 对方关了输入（比如管道另一头写完了）：已经收下的调用照样答完再走
            workers.forEach(Thread::join)
        }

        private fun messages(reader: BufferedReader): Sequence<JsonObject> = generateSequence {
            runCatching { reader.readLine() }.getOrNull()
        }
            .mapNotNull { line ->
                runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
            }

        private fun write(writer: Writer, reply: JsonObject) {
            // 空对象是 handle 说“不用应答”
            if (reply.isEmpty()) return
            synchronized(writer) {
                try {
                    writer.write(reply.toString())
                    writer.write("\n")
                    writer.flush()
                } catch (_: IOException) {
                    // 对方已经断开，没人等这条应答了
                }
            }
        }

        private fun failed(message: JsonObject, error: Throwable): JsonObject = buildJsonObject {
            put("jsonrpc", "2.0")
            message["id"]?.let { put("id", it) }
            putJsonObject("error") {
                put("code", -32603)
                put("message", error.message ?: error.javaClass.simpleName)
            }
        }
    }
}
