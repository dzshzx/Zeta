package com.dzshzx.zeta.core.ssh

import com.dzshzx.zeta.core.CommandResult
import com.dzshzx.zeta.core.RemoteHost
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Zeta 直连远程主机的 SSH（[RemoteHost.Via.Direct]）：每台主机一条连接，每个命令开一个通道，和 OpenSSH 的 ControlMaster 一样省握手。
 * 连接断了，上面的命令都以 [CONNECTION_FAILED] 结束，下一个命令重新连；最后一个通道关掉一分钟后断开（[idleMs] 不是 null 时照它，测试里改短）。
 * 主机指纹在加主机时记下（[probe]），以后连接只认这一个，变了就拒绝。
 *
 * 连接、通道、闲置断开、主机指纹的核对和出错的说法都在随 APK 带的 Go 程序 zeta-ssh 里（仓根 client/cmd/zeta-ssh），这里只做协议转换和 Android
 * 原生的几件事。协议见 docs/core.md「Android 直连（zeta-ssh）」。
 * - 第一次要用时起 zeta-ssh（[program] 是它的路径，手机上在应用的 nativeLibraryDir 里），整个应用进程一个；它退出了，上面的命令都以
 *   [CONNECTION_FAILED] 结束，下一次要用时再起。
 * - 它请应用做的：取 Zeta 密钥（[key]）的公钥、用 Keystore 里的私钥签名（[SshKey.sign]，签名不出 Keystore）、经系统解析域名（Go 在 Android
 *   上自己解析不了）。
 * - 每个远程命令是一个 [Process]：输出按读掉多少确认多少（zeta-ssh 只发窗口里的），输入每写一段等它写进通道，写不进时抛 IOException。
 */
class SshLinks(
    ownKey: () -> SshKey,
    private val idleMs: Long? = null,
    private val program: () -> File,
) {
    /** Zeta 自己的密钥（第一次要时才取，阻塞：要进 Keystore）：登录用它，设置页上给人复制它的公钥。 */
    val key: SshKey by lazy(ownKey)

    private val lock = Any()
    private var child: Child? = null
    private val ids = AtomicLong()

    /** 回调（签名、解析域名）在这里做，不挡读 zeta-ssh 输出的那条线程。 */
    private val calls = Executors.newCachedThreadPool { r ->
        Thread(r, "zeta-ssh-call").apply { isDaemon = true }
    }

    /**
     * 在主机 [host] 上跑 [command]：[login] 时交给那边的登录 shell，否则只用 bash（见
     * [com.dzshzx.zeta.core.LinuxShell.startPlain]）； 带上 Zeta 在那边的目录名 [linuxName] 和时区
     * [timeZone]。马上返回，连接在后台建：连不上的进程以 [CONNECTION_FAILED] 结束， 原因以 [PROBLEM_PREFIX] 开头写进标准错误。结束进程用
     * [Process.destroy]（关掉那个通道）。
     */
    fun start(
        host: RemoteHost,
        command: String,
        login: Boolean,
        linuxName: String,
        timeZone: String,
    ): Process {
        val process = GoProcess(ids.incrementAndGet())
        val child =
            running().getOrElse {
                process.exited(CONNECTION_FAILED, it.message)
                return process
            }
        process.attach(child)
        val sent =
            child.send(
                buildJsonObject {
                    put("method", "start")
                    put("p", process.id)
                    putJsonObject("host") {
                        put("id", host.id)
                        put("target", host.target)
                        put("host_key", host.hostKey.orEmpty())
                    }
                    put("command", command)
                    put("login", login)
                    put("name", linuxName)
                    put("tz", timeZone)
                },
            )
        if (!sent) child.lost()
        return process
    }

    /**
     * 连一次 [target] 在登录 shell 里跑 [command]（加主机、重新检查时用）：认 [expectedHostKey]（null
     * 时谁都认），返回对方的主机公钥和命令的结果。连接用完就断开，不进连接池。阻塞，别在主线程上调用。
     */
    fun probe(
        target: String,
        expectedHostKey: ByteArray?,
        command: String,
        linuxName: String,
        timeZone: String,
        timeoutMs: Long = 45_000,
    ): ProbeResult {
        val child =
            running().getOrElse {
                return ProbeResult(null, null, it.message)
            }
        val id = ids.incrementAndGet()
        val reply = CompletableFuture<JsonObject>()
        child.probes[id] = reply
        if (!child.alive) child.probes.remove(id)?.completeExceptionally(IOException(child.reason))
        child.send(
            buildJsonObject {
                put("method", "probe")
                put("id", id)
                put("target", target)
                put("host_key", expectedHostKey?.let(Base64.getEncoder()::encodeToString).orEmpty())
                put("command", command)
                put("name", linuxName)
                put("tz", timeZone)
                put("timeout_ms", timeoutMs)
            },
        )
        // zeta-ssh 自己到点结束命令；多等的是连接（最多十几秒）
        val answer = runCatching {
            reply.get(timeoutMs + 60_000, TimeUnit.MILLISECONDS)
        }
            .getOrElse {
                child.probes.remove(id)
                return ProbeResult(null, null, it.cause?.message ?: "zeta-ssh 没有回应")
            }
        val seen = answer.string("host_key")?.let(Base64.getDecoder()::decode)
        val problem = answer.string("problem")
        if (problem != null) {
            return ProbeResult(seen, null, problem, answer.flag("changed"))
        }
        val output =
            if (answer.flag("timed_out"))
                CommandResult(-1, answer.string("stdout").orEmpty(), CommandResult.TIMEOUT)
            else
                CommandResult(
                    answer["code"]?.jsonPrimitive?.int ?: CONNECTION_FAILED,
                    answer.string("stdout").orEmpty(),
                    answer.string("stderr").orEmpty(),
                )
        return ProbeResult(seen, output, null)
    }

    /** 只留下 [hostIds] 这几台的连接，别的断开（主机删了），上面的命令都以 [CONNECTION_FAILED] 结束。 */
    fun retain(hostIds: Set<String>) {
        current()
            ?.send(
                buildJsonObject {
                    put("method", "retain")
                    putJsonArray("ids") { hostIds.forEach { add(JsonPrimitive(it)) } }
                },
            )
    }

    /** 断开所有连接（手机换了网络：旧连接多半已经死了，不等保活超时）。 */
    fun dropAll() {
        current()?.send(buildJsonObject { put("method", "drop_all") })
    }

    /** 关掉 zeta-ssh（它读到输入结束就断开所有连接退出）。上面的命令随即以 [CONNECTION_FAILED] 结束。 */
    fun close() {
        synchronized(lock) { child.also { child = null } }?.stop()
    }

    private fun current(): Child? = synchronized(lock) { child?.takeIf { it.alive } }

    /** 在跑的 zeta-ssh，没有就起一个；起不来时是原因。 */
    private fun running(): Result<Child> =
        synchronized(lock) {
            child
                ?.takeIf { it.alive }
                ?.let {
                    return Result.success(it)
                }
            runCatching { Child(program()) }
                .onSuccess { child = it }
                .recoverCatching { throw IOException("Zeta 的 SSH 程序起不来：${it.message}") }
        }

    /** 一个 zeta-ssh 进程。 */
    private inner class Child(file: File) {
        private val process: java.lang.Process =
            ProcessBuilder(listOfNotNull(file.path, idleMs?.let { "-idle=${it}ms" }))
                .redirectErrorStream(false)
                .start()
        private val input = process.outputStream.buffered()
        private val output = process.inputStream.bufferedReader()
        val procs = ConcurrentHashMap<Long, GoProcess>()
        val probes = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
        private val dead = AtomicBoolean(false)
        @Volatile private var lastError = ""

        /** 它为什么没了（[alive] 为假以后才有意义）。 */
        @Volatile var reason = ""

        val alive: Boolean
            get() = !dead.get()

        /** 每路输出最多有多少没确认的（招呼里报的），管道开这么大，收的时候就不会挡。 */
        val window: Int

        init {
            thread(name = "zeta-ssh-stderr", isDaemon = true) {
                runCatching {
                    process.errorStream.bufferedReader().forEachLine {
                        if (it.isNotBlank()) lastError = it.trim()
                    }
                }
            }
            val hello = runCatching { output.readLine()?.let(::parse) }.getOrNull()
            if (hello?.string("type") != "hello") {
                process.destroy()
                process.waitFor(5, TimeUnit.SECONDS)
                throw IOException(lastError.ifEmpty { "没有打招呼" })
            }
            window = hello["window"]?.jsonPrimitive?.int ?: DEFAULT_WINDOW
            thread(name = "zeta-ssh-read", isDaemon = true) { read(output) }
        }

        /** 写一行请求；写不出去（它退出了）时是 false。 */
        fun send(message: JsonObject): Boolean =
            synchronized(input) {
                runCatching {
                    input.write((message.toString() + "\n").toByteArray())
                    input.flush()
                }
                    .isSuccess
            }

        private fun read(reader: BufferedReader) {
            runCatching {
                while (true) {
                    val line = reader.readLine() ?: break
                    runCatching { parse(line) }.getOrNull()?.let(::dispatch)
                }
            }
            lost()
        }

        private fun dispatch(message: JsonObject) {
            val p = message["p"]?.jsonPrimitive?.long
            when (message.string("type")) {
                "out" ->
                    procs[p]?.received(
                        message.string("stream") == "stderr",
                        decode(message.string("data")),
                    )
                "wrote" -> procs[p]?.wrote(message.string("error"))
                "exit" ->
                    procs
                        .remove(p)
                        ?.exited(
                            message["code"]?.jsonPrimitive?.int ?: CONNECTION_FAILED,
                            message.string("problem"),
                        )
                "reply" -> probes.remove(message["id"]?.jsonPrimitive?.long)?.complete(message)
                "call" -> calls.execute { answer(message) }
            }
        }

        /** 做 zeta-ssh 请应用做的事，答 answer。 */
        private fun answer(call: JsonObject) {
            val reply = runCatching {
                when (call.string("method")) {
                    "key" -> Answer(data = key.blob)
                    "sign" -> Answer(data = key.sign(decode(call.string("data"))))
                    "resolve" ->
                        Answer(
                            addrs =
                                InetAddress.getAllByName(call.string("host")).mapNotNull {
                                    it.hostAddress
                                },
                        )
                    else -> Answer(error = "不认得的请求")
                }
            }
                .getOrElse { Answer(error = it.message ?: it.javaClass.simpleName) }
            send(
                buildJsonObject {
                    put("method", "answer")
                    put("id", call["id"]?.jsonPrimitive?.long ?: 0)
                    reply.data?.let { put("data", Base64.getEncoder().encodeToString(it)) }
                    reply.addrs?.let { addrs ->
                        putJsonArray("addrs") {
                            addrs.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                    reply.error?.let { put("error", it) }
                },
            )
        }

        /** 它退出了（或写不进去了）：上面的命令和检查都失败，下次要用时另起一个。 */
        fun lost() {
            val why =
                "Zeta 的 SSH 程序退出了" + lastError.takeIf { it.isNotEmpty() }?.let { "：$it" }.orEmpty()
            reason = why
            if (!dead.compareAndSet(false, true)) return
            synchronized(lock) { if (child === this) child = null }
            process.destroy()
            procs.values.forEach { it.exited(CONNECTION_FAILED, why) }
            procs.clear()
            probes.values.forEach { it.completeExceptionally(IOException(why)) }
            probes.clear()
        }

        /** 关掉它的输入：它断开所有连接后退出，读的那条线程随即收尾（[lost]）。 */
        fun stop() {
            runCatching { synchronized(input) { input.close() } }
        }
    }

    private class Answer(
        val data: ByteArray? = null,
        val addrs: List<String>? = null,
        val error: String? = null,
    )

    /**
     * zeta-ssh 上的一个远程命令，当本地进程用。标准输出、标准错误各一条管道（读掉多少就向 zeta-ssh 确认多少，它才接着发），exit 来了算结束； 连不上、连接断了的退出码是
     * [CONNECTION_FAILED]，原因以 [PROBLEM_PREFIX] 开头写进标准错误。
     */
    private inner class GoProcess(val id: Long) : Process() {
        @Volatile private var child: Child? = null
        private val done = CountDownLatch(1)
        @Volatile private var exit = CONNECTION_FAILED
        private lateinit var stdout: Stream
        private lateinit var stderr: Stream

        /** 等着 wrote 的那一段输入。 */
        private val writing = Any()
        @Volatile private var pending: CompletableFuture<String?>? = null
        private val inputClosed = AtomicBoolean(false)
        private val ended = AtomicBoolean(false)

        fun attach(child: Child) {
            this.child = child
            stdout = Stream("stdout", child.window)
            stderr = Stream("stderr", child.window)
            child.procs[id] = this
            // 登记之前它就没了的（[Child.lost] 没看到这一个）：在这里结束
            if (!child.alive) exited(CONNECTION_FAILED, child.reason)
        }

        fun received(isStderr: Boolean, data: ByteArray) =
            (if (isStderr) stderr else stdout).received(data)

        fun wrote(error: String?) {
            pending?.complete(error)
        }

        fun exited(code: Int, problem: String?) {
            if (!ended.compareAndSet(false, true)) return
            if (!::stdout.isInitialized) {
                stdout = Stream("stdout", DEFAULT_WINDOW)
                stderr = Stream("stderr", DEFAULT_WINDOW)
            }
            if (!problem.isNullOrEmpty()) stderr.append("$PROBLEM_PREFIX$problem\n")
            exit = code
            stdout.end()
            stderr.end()
            pending?.complete("已经结束")
            done.countDown()
        }

        /** 一路输出。 */
        private inner class Stream(private val name: String, window: Int) {
            // 多留的地方放连不上时写进来的那句原因：收的线程写它时不能被挡住
            private val pipe = BytePipe(capacity = window + 64 * 1024)
            private val ackEvery = maxOf(1, window / 4)
            private var unacked = 0
            private var closed = false

            /** 收到 zeta-ssh 送来的；读的一方已经不读了的直接确认掉。 */
            fun received(data: ByteArray) {
                val drop = synchronized(this) { closed }
                if (drop) ack(data.size) else pipe.write(data, 0, data.size)
            }

            fun append(text: String) = text.toByteArray().let { pipe.write(it, 0, it.size) }

            fun end() = pipe.closeWriter()

            private fun ack(n: Int) {
                if (n <= 0 || done.count == 0L) return
                child?.send(
                    buildJsonObject {
                        put("method", "ack")
                        put("p", id)
                        put("stream", name)
                        put("n", n)
                    },
                )
            }

            val source: InputStream =
                object : InputStream() {
                    override fun read(): Int {
                        val one = ByteArray(1)
                        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = pipe.source.read(b, off, len)
                        if (n > 0) {
                            val due =
                                synchronized(this@Stream) {
                                    unacked += n
                                    if (unacked >= ackEvery) unacked.also { unacked = 0 } else 0
                                }
                            ack(due)
                        }
                        return n
                    }

                    override fun available(): Int = pipe.source.available()

                    /** 不读了：管道里剩下的和读了没确认的一起确认掉，之后收到的也直接确认。 */
                    override fun close() {
                        val due =
                            synchronized(this@Stream) {
                                closed = true
                                (unacked + pipe.source.available()).also { unacked = 0 }
                            }
                        pipe.source.close()
                        ack(due)
                    }
                }
        }

        /** 标准输入：每段（最多 [CHUNK] 字节）等 zeta-ssh 写进通道再写下一段；结束了、写不进时抛 IOException。 */
        private val stdin =
            object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(b: ByteArray, off: Int, len: Int) {
                    var offset = off
                    while (offset < off + len) {
                        val n = minOf(CHUNK, off + len - offset)
                        send(b.copyOfRange(offset, offset + n))
                        offset += n
                    }
                }

                private fun send(data: ByteArray) =
                    synchronized(writing) {
                        if (!isAlive) throw IOException("通道已经断了")
                        val reply = CompletableFuture<String?>()
                        pending = reply
                        val sent =
                            child?.send(
                                buildJsonObject {
                                    put("method", "stdin")
                                    put("p", id)
                                    put("data", Base64.getEncoder().encodeToString(data))
                                },
                            ) == true
                        if (!sent) throw IOException("通道已经断了")
                        // 进程这时已经结束的，exited 已经答过或马上答
                        if (!isAlive) reply.complete("已经结束")
                        reply.get()?.let { throw IOException(it) }
                    }

                override fun close() {
                    if (!inputClosed.compareAndSet(false, true) || !isAlive) return
                    child?.send(
                        buildJsonObject {
                            put("method", "stdin")
                            put("p", id)
                            put("eof", true)
                        },
                    )
                }
            }

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = stdout.source

        override fun getErrorStream(): InputStream = stderr.source

        override fun waitFor(): Int {
            done.await()
            return exit
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = done.await(timeout, unit)

        override fun exitValue(): Int {
            if (done.count > 0) throw IllegalThreadStateException("还在跑")
            return exit
        }

        override fun isAlive(): Boolean = done.count > 0

        /** 结束它：zeta-ssh 关掉那个通道（还在连的不连了），那边的命令读到输入结束、写不出去就会退出（托管的 Claude Code 进程不受影响）。 */
        override fun destroy() {
            if (!isAlive) return
            child?.send(
                buildJsonObject {
                    put("method", "close")
                    put("p", id)
                },
            )
        }
    }

    /** [probe] 的结果：对方的主机公钥（握手到那一步才有）、命令的结果，或连不上的原因（[hostKeyChanged] 时是指纹变了）。 */
    class ProbeResult(
        val hostKey: ByteArray?,
        val output: CommandResult?,
        val problem: String?,
        val hostKeyChanged: Boolean = false,
    )

    companion object {
        /** 连不上、连接断了时进程的退出码，和 OpenSSH 一样。 */
        const val CONNECTION_FAILED = 255

        /** 连不上的原因写进标准错误时的开头，后面已经是一句给人看的话，[com.dzshzx.zeta.core.RemoteHosts.sshProblem] 原样取后面的。 */
        const val PROBLEM_PREFIX = "zeta-ssh: "

        /** 手机上它在 APK 里的名字（lib/arm64-v8a/ 下）：装好后在应用的 nativeLibraryDir 里，那里的文件能执行。 */
        const val LIBRARY = "libzeta_ssh.so"

        /**
         * 测试版另带的 x86_64 那份（linux/amd64，也在 lib/arm64-v8a/ 下，见 app/build.gradle.kts 的
         * zetaSshEmulator），只给 x86_64 模拟器用： 模拟器靠 arm64 转译跑本应用，转译能执行 arm64 的 C 程序，Go 编的 arm64 程序（开不开
         * cgo 都一样）一启动就段错误。 换个名字放在同一个目录，是为了不新增 lib/x86_64/：有它安装器会把整个应用的 ABI 换成 x86_64，只有 arm64
         * 的库就装不上了。 不再在 x86_64 模拟器上测直连时，连同 zetaSshEmulator 和正式版的核对一起删掉。
         */
        const val EMULATOR_LIBRARY = "libzeta_ssh_x86_64.so"

        /** 一段输入最多多少字节。 */
        private const val CHUNK = 32 * 1024

        /** 招呼里没报窗口时（不会发生）照这个开管道。 */
        private const val DEFAULT_WINDOW = 256 * 1024

        private val json = Json { ignoreUnknownKeys = true }

        private fun parse(line: String): JsonObject = json.parseToJsonElement(line).jsonObject

        private fun JsonObject.string(name: String): String? =
            this[name]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

        private fun JsonObject.flag(name: String): Boolean =
            this[name]?.jsonPrimitive?.content == "true"

        private fun decode(text: String?): ByteArray =
            if (text.isNullOrEmpty()) ByteArray(0) else Base64.getDecoder().decode(text)
    }
}

/**
 * 一条字节管道：收远程命令输出的线程写，调用方从 [source] 读。不像 PipedInputStream 那样每秒醒一次查写端线程还在不在。 缓冲区从 8 KiB 起按需翻倍，最大
 * [capacity]；满了写的一方等着。读端关了以后写进来的丢掉； 写端关了（[closeWriter]）以后读完剩下的就是结尾。
 */
class BytePipe(private val capacity: Int = 256 * 1024) {
    private val lock = Object()
    private var buffer = ByteArray(minOf(8 * 1024, capacity))
    private var head = 0
    private var size = 0
    private var writerClosed = false
    private var readerClosed = false

    fun write(b: ByteArray, off: Int, len: Int) {
        var offset = off
        var left = len
        synchronized(lock) {
            while (left > 0) {
                if (readerClosed || writerClosed) return
                if (size == buffer.size) {
                    if (buffer.size < capacity) {
                        grow()
                    } else {
                        lock.wait()
                        continue
                    }
                }
                val tail = (head + size) % buffer.size
                val n = minOf(left, buffer.size - size, buffer.size - tail)
                System.arraycopy(b, offset, buffer, tail, n)
                size += n
                offset += n
                left -= n
                lock.notifyAll()
            }
        }
    }

    /** 换成两倍大的缓冲区（不超过 [capacity]），内容挪到开头。拿着 [lock] 时调用。 */
    private fun grow() {
        val bigger = ByteArray(minOf(buffer.size * 2, capacity))
        val first = minOf(size, buffer.size - head)
        System.arraycopy(buffer, head, bigger, 0, first)
        System.arraycopy(buffer, 0, bigger, first, size - first)
        buffer = bigger
        head = 0
    }

    fun closeWriter() =
        synchronized(lock) {
            writerClosed = true
            lock.notifyAll()
        }

    val source: InputStream =
        object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                synchronized(lock) {
                    while (size == 0) {
                        if (writerClosed || readerClosed) return -1
                        lock.wait()
                    }
                    val n = minOf(len, size, buffer.size - head)
                    System.arraycopy(buffer, head, b, off, n)
                    head = (head + n) % buffer.size
                    size -= n
                    lock.notifyAll()
                    return n
                }
            }

            override fun available(): Int = synchronized(lock) { size }

            override fun close() =
                synchronized(lock) {
                    readerClosed = true
                    size = 0
                    lock.notifyAll()
                }
        }
}
