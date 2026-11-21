package com.dzshzx.zeta.core.ssh

import com.dzshzx.zeta.core.CommandResult
import com.dzshzx.zeta.core.LinuxEnv
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchAlgoNegoFailException
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 用户名、主机、端口都写明的目的地：`用户@主机`，可以带端口（`用户@主机:端口`、`用户@[IPv6]:端口`），前面可以带 `ssh://`。 Zeta 直连只认这种（直连不读
 * ~/.ssh/config，所以用户名不能省）；经手机 Debian 连时交给 OpenSSH 的是 [openSsh]。
 */
data class SshTarget(val user: String, val host: String, val port: Int) {
    private val bracketed: String
        get() = if (':' in host) "[$host]" else host

    override fun toString(): String =
        if (port == 22) "$user@$bracketed" else "$user@$bracketed:$port"

    /** 交给 OpenSSH 的写法：它不认 `主机:端口`，端口不是 22 或主机是 IPv6 时写成 `ssh://用户@主机:端口`。 */
    val openSsh: String
        get() = if (port == 22 && ':' !in host) "$user@$host" else "ssh://$user@$bracketed:$port"

    companion object {
        private val UserChars = Regex("[A-Za-z0-9._][A-Za-z0-9._-]*")
        private val HostChars = Regex("[A-Za-z0-9._%:-]+")

        fun parse(text: String): SshTarget? {
            val t = text.trim().removePrefix("ssh://").removeSuffix("/")
            val at = t.lastIndexOf('@')
            if (at <= 0) return null
            val user = t.substring(0, at).takeIf { UserChars.matches(it) } ?: return null
            val rest = t.substring(at + 1)
            val (host, port) =
                when {
                    rest.startsWith("[") -> {
                        val close = rest.indexOf(']')
                        if (close < 0) return null
                        val after = rest.substring(close + 1)
                        rest.substring(1, close) to
                            if (after.isEmpty()) 22 else after.removePrefix(":").toIntOrNull()
                    }
                    rest.count { it == ':' } == 1 ->
                        rest.substringBefore(':') to rest.substringAfter(':').toIntOrNull()
                    else -> rest to 22
                }
            if (
                port == null ||
                    port !in 1..65535 ||
                    host.isEmpty() ||
                    host.startsWith("-") ||
                    !HostChars.matches(host)
            )
                return null
            return SshTarget(user, host, port)
        }
    }
}

/**
 * Zeta 自己连远程主机的 SSH（JSch，用 [SshKey]）：每台主机一条连接，每个命令开一个通道，和 OpenSSH 的 ControlMaster 一样省握手。 连接断了（保活包
 * 45 秒左右没回音、对方关了），上面的命令都以 [CONNECTION_FAILED] 结束；下一个命令重新连。 最后一个通道关掉 [idleMs]
 * 后断开，闲着时不发保活包，不让手机的网络一直醒着。 主机指纹在加主机时记下（[probe]），以后连接只认这一个，变了就拒绝。
 */
class SshLinks(keyProvider: () -> SshKey, private val idleMs: Long = 60_000) {
    /** Zeta 的密钥；第一次用时从 Keystore 取（没有就生成）。 */
    val key: SshKey by lazy(keyProvider)

    private val jsch by lazy { JSch().apply { addIdentity(key, null) } }
    private val links = ConcurrentHashMap<String, Link>()
    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "zeta-ssh-idle").apply { isDaemon = true }
    }

    /** 在主机上跑 [command]（交给那边的登录 shell）。马上返回，连接在后台建：连不上的进程以 [CONNECTION_FAILED] 结束，原因在标准错误里。 */
    fun start(hostId: String, target: SshTarget, hostKey: ByteArray, command: String): Process {
        val process = SshProcess()
        thread(name = "zeta-ssh-open", isDaemon = true) {
            val link = link(hostId, target, hostKey)
            try {
                open(link, command, process)
            } catch (e: Exception) {
                // 复用的连接可能刚好断了（还没发现）：等它标成断开，换一条新的再试一次
                if (e is JSchException && link.awaitDown()) {
                    link.drop()
                    runCatching { open(link, command, process) }
                        .onFailure { process.fail(problem(it)) }
                } else {
                    process.fail(problem(e))
                }
            }
        }
        return process
    }

    private fun open(link: Link, command: String, process: SshProcess) {
        if (process.killed) return process.fail("已经结束")
        exec(
            link.session(),
            command,
            process,
            opened = link::channelOpened,
            release = link::channelClosed,
        )
    }

    /**
     * 在 [session] 上开一个通道跑 [command]，输出接到 [process]：通道建好时调 [opened]，关掉时调 [release]（只调一次）。
     * 开不成就抛异常，这次的输出不算数。
     */
    private fun exec(
        session: Session,
        command: String,
        process: SshProcess,
        opened: () -> Unit = {},
        release: () -> Unit = {},
    ) {
        // 连接正在断开时 JSch 不抛异常、给 null
        val channel = session.openChannel("exec") as? ChannelExec ?: throw JSchException("开不了通道")
        channel.setCommand(command.toByteArray(Charsets.UTF_8))
        opened()
        val attempt = process.attempt(channel, release)
        channel.setOutputStream(attempt.stdoutSink, false)
        channel.setExtOutputStream(attempt.stderrSink, false)
        try {
            channel.connect(15_000)
        } catch (e: Exception) {
            attempt.abandon()
            throw e
        }
        process.connected(channel.outputStream)
    }

    /**
     * 连一次 [target] 跑 [command]（加主机、重新检查时用）：认 [expectedHostKey]（null 时谁都认），
     * 返回对方的主机指纹和命令的结果。连接用完就断开，不进连接池。阻塞，别在主线程上调用。
     */
    fun probe(
        target: SshTarget,
        expectedHostKey: ByteArray?,
        command: String,
        timeoutMs: Long = 45_000,
    ): ProbeResult {
        var seen: ByteArray? = null
        val session =
            try {
                connect(target, expectedHostKey) { seen = it }
            } catch (e: Exception) {
                return ProbeResult(
                    seen,
                    null,
                    problem(e),
                    hostKeyChanged = e is JSchChangedHostKeyException,
                )
            }
        return try {
            val process = SshProcess()
            exec(session, command, process)
            // 超时的结果标着 timedOut
            ProbeResult(seen, LinuxEnv.capture(process, null, timeoutMs, Process::destroy), null)
        } catch (e: Exception) {
            ProbeResult(seen, null, problem(e))
        } finally {
            session.disconnect()
        }
    }

    /** [probe] 的结果：对方的主机公钥（握手到那一步才有）、命令的结果，或连不上的原因。 */
    class ProbeResult(
        val hostKey: ByteArray?,
        val output: CommandResult?,
        val problem: String?,
        val hostKeyChanged: Boolean = false,
    )

    /** 只留下 [hostIds] 这几台的连接，别的断开（主机删了、改了地址），上面的命令都以 [CONNECTION_FAILED] 结束。 */
    fun retain(hostIds: Set<String>) {
        links.keys.filter { it !in hostIds }.forEach { links.remove(it)?.drop() }
    }

    /** 断开所有连接（手机换了网络：旧连接多半已经死了，不等保活超时）。 */
    fun dropAll() {
        links.values.forEach { it.drop() }
    }

    private fun link(hostId: String, target: SshTarget, hostKey: ByteArray): Link =
        synchronized(links) {
            val existing = links[hostId]
            if (
                existing != null &&
                    existing.target == target &&
                    existing.hostKey.contentEquals(hostKey)
            )
                return existing
            existing?.drop()
            Link(target, hostKey).also { links[hostId] = it }
        }

    private fun connect(
        target: SshTarget,
        expectedHostKey: ByteArray?,
        seen: (ByteArray) -> Unit = {},
    ): Session =
        jsch.getSession(target.user, target.host, target.port).apply {
            BouncyCastleAlgorithms.forEach { (name, implementation) ->
                setConfig(name, implementation)
            }
            setHostKeyRepository(HostKeys(expectedHostKey, seen))
            setConfig("StrictHostKeyChecking", "yes")
            setConfig("PreferredAuthentications", "publickey")
            // 两个方向都压缩（对方不支持就不压）：Claude Code 的输出、历史、会话列表都是大段 JSON，第一次装 zeta-helper 要传几 MB，
            // 蜂窝网络下流量和时间都省
            setConfig("compression.s2c", COMPRESSION)
            setConfig("compression.c2s", COMPRESSION)
            userInfo = NoPrompts
            setServerAliveInterval(15_000)
            setServerAliveCountMax(3)
            connect(15_000)
        }

    /** 一台主机的连接：没连上或断了时，下一个要用的人去连（同一时间只连一次）。 */
    private inner class Link(val target: SshTarget, val hostKey: ByteArray) {
        @Volatile private var session: Session? = null
        private val channels = AtomicInteger()
        @Volatile private var idle: ScheduledFuture<*>? = null

        @Synchronized
        fun session(): Session {
            session
                ?.takeIf { it.isConnected }
                ?.let {
                    return it
                }
            return connect(target, hostKey).also { session = it }
        }

        fun connected(): Boolean = session?.isConnected == true

        /**
         * 等这条连接标成断开，最多 [ms] 毫秒，断开了是 true。JSch 发现连接断了时先关掉上面的通道、再把会话标成断开：
         * 通道一关调用方就会接着发下一个命令，夹在中间开通道会失败，这时会话看上去还连着。
         */
        fun awaitDown(ms: Long = 2_000): Boolean {
            val deadline = System.nanoTime() + ms * 1_000_000
            while (connected()) {
                if (System.nanoTime() > deadline) return false
                Thread.sleep(20)
            }
            return true
        }

        fun channelOpened() {
            channels.incrementAndGet()
            idle?.cancel(false)
        }

        /** 在 JSch 的会话线程上调用：不拿锁，断开交给定时器线程。 */
        fun channelClosed() {
            if (channels.decrementAndGet() > 0) return
            idle =
                timer.schedule({ if (channels.get() == 0) drop() }, idleMs, TimeUnit.MILLISECONDS)
        }

        /** 断开：下一个要用的人重新连。断开要往连接上写，交给定时器线程做，调用方（常是主线程）不等。 */
        fun drop() {
            val old = synchronized(this) { session.also { session = null } } ?: return
            timer.execute { old.disconnect() }
        }
    }

    /** 只认 [expected] 这一个主机公钥；[expected] 是 null 时（加主机那一次）谁都认，看到的交给 [seen]。 */
    private class HostKeys(
        private val expected: ByteArray?,
        private val seen: (ByteArray) -> Unit,
    ) : HostKeyRepository {
        override fun check(host: String?, key: ByteArray): Int {
            seen(key)
            return when {
                expected == null || expected.contentEquals(key) -> HostKeyRepository.OK
                else -> HostKeyRepository.CHANGED
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit

        override fun remove(host: String?, type: String?) = Unit

        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

        override fun getKnownHostsRepositoryID(): String = "zeta"

        // 让 JSch 先谈记下的那种主机密钥：对方有好几种时，不会因为换了一种而当成指纹变了
        override fun getHostKey(): Array<HostKey> =
            expected?.let(::hostKey)?.let { arrayOf(it) } ?: emptyArray()

        override fun getHostKey(host: String?, type: String?): Array<HostKey> =
            getHostKey().filter { type == null || it.type == type }.toTypedArray()
    }

    /** 不问用户：没有密码、口令可答，主机密钥只按 [HostKeys] 判。 */
    private object NoPrompts : UserInfo {
        override fun getPassphrase(): String? = null

        override fun getPassword(): String? = null

        override fun promptPassword(message: String?): Boolean = false

        override fun promptPassphrase(message: String?): Boolean = false

        override fun promptYesNo(message: String?): Boolean = false

        override fun showMessage(message: String?) = Unit
    }

    companion object {
        /** 压缩算法的优先顺序：OpenSSH 服务器默认只开登录后才压的 zlib@openssh.com。 */
        internal const val COMPRESSION = "zlib@openssh.com,zlib,none"

        /**
         * Ed25519、X25519、ML-KEM 一律用 JSch 的 BouncyCastle 实现：Android 自带的加密库没有 Ed25519，JSch 在 Android
         * 上 也认不出 Java 版本（按 Java 8 处理），不写死的话 JVM 上的测试走 JDK 的实现、手机上走 BouncyCastle，测的不是同一份代码。
         */
        private val BouncyCastleAlgorithms =
            mapOf(
                "ssh-ed25519" to "com.jcraft.jsch.bc.SignatureEd25519",
                "xdh" to "com.jcraft.jsch.bc.XDH",
                "mlkem768" to "com.jcraft.jsch.bc.MLKEM768",
            )

        /**
         * 主机公钥 [blob]（SSH 线上格式）交给 JSch 认，认不得的（坏的、JSch 不支持的类型）是 null。 记下的主机公钥都要过这一关：连接时 [HostKeys]
         * 用它让 JSch 先谈这一种。
         */
        fun hostKey(blob: ByteArray): HostKey? = runCatching { HostKey("zeta", blob) }.getOrNull()

        /** 连不上、连接断了时进程的退出码，和 OpenSSH 一样。 */
        const val CONNECTION_FAILED = 255

        /**
         * 连不上的原因写进标准错误时的开头，[problem] 已经翻成一句话，[com.dzshzx.zeta.core.RemoteHosts.sshProblem] 原样取后面的。
         */
        const val PROBLEM_PREFIX = "zeta-ssh: "

        /**
         * JSch 抛出来的异常翻成一句话。系统给的说明文字随语言变（JVM 上是“连接被拒绝”），先看异常的类型， 再看 Android 写在说明里的 errno
         * 名字（ECONNREFUSED、ENETUNREACH 之类）。
         */
        fun problem(e: Throwable): String {
            val causes = generateSequence(e) { it.cause }.take(5).toList()
            val text = causes.joinToString(" ") { it.message.orEmpty() }
            val unreachable =
                Regex("ENETUNREACH|EHOSTUNREACH|unreachable", RegexOption.IGNORE_CASE)
                    .containsMatchIn(text)
            return when {
                causes.any { it is JSchChangedHostKeyException } ->
                    "主机指纹和加设备时记下的不一样（设备重装过，或者连到了别的机器上）"
                text.contains("Auth fail") || text.contains("Auth cancel") ->
                    "对方不认 Zeta 的公钥：把它加进那台设备的 ~/.ssh/authorized_keys"
                causes.any { it is UnknownHostException } -> "找不到这台设备（地址解析失败）"
                causes.any { it is NoRouteToHostException } || unreachable -> "网络不通"
                causes.any { it is SocketTimeoutException } ||
                    Regex("timeout|timed out|ETIMEDOUT", RegexOption.IGNORE_CASE)
                        .containsMatchIn(text) -> "连接超时"
                causes.any { it is ConnectException } -> "对方拒绝连接（端口上没有 SSH 服务）"
                causes.any { it is JSchAlgoNegoFailException } ->
                    "和对方谈不拢加密算法：" + e.message.orEmpty().take(160)
                text.contains("Connection is closed by foreign host") ||
                    text.contains("connection is closed") -> "对方断开了连接"
                else -> (e.message ?: e.javaClass.simpleName).take(200)
            }
        }
    }
}

/**
 * 一个通道上的远程命令，当本地进程用：标准输出、标准错误各一条管道，JSch 的会话线程往里写。 通道关掉（命令结束、被结束、连接断了）时 JSch
 * 最后关掉标准错误那条，这时算结束；没收到退出码的算 [SshLinks.CONNECTION_FAILED]。
 * 标准输入在连上以前写会等着连上。复用的连接刚好断了时会换一条连接重开通道（[attempt]），前一次的输出不再算数。
 */
internal class SshProcess : Process() {
    private val stdout = BytePipe()
    private val stderr = BytePipe()
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val ended = AtomicBoolean(false)
    @Volatile private var current: Attempt? = null
    @Volatile private var channelInput: OutputStream? = null
    @Volatile private var exit = SshLinks.CONNECTION_FAILED

    @Volatile
    var killed = false
        private set

    /** 在 [channel] 上开一次：输出接到这次的 [Attempt]；通道关掉时调 [release]（只调一次）。 */
    fun attempt(channel: ChannelExec, release: () -> Unit): Attempt =
        Attempt(channel, release).also { current = it }

    inner class Attempt(val channel: ChannelExec, private val release: () -> Unit) {
        private val active = AtomicBoolean(true)
        private val released = AtomicBoolean(false)

        private fun releaseOnce() {
            if (released.compareAndSet(false, true)) release()
        }

        /** JSch 往 [pipe] 里写的口子：这次作废以后写进来的丢掉。 */
        private fun sink(pipe: BytePipe, onClose: () -> Unit): OutputStream =
            object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (active.get()) pipe.write(b, off, len)
                }

                override fun close() = onClose()
            }

        val stdoutSink: OutputStream = sink(stdout) { if (active.get()) stdout.closeWriter() }

        /** JSch 最后关它：这时命令算结束。 */
        val stderrSink: OutputStream =
            sink(stderr) {
                releaseOnce()
                if (active.compareAndSet(true, false))
                    end(channel.exitStatus.takeIf { it >= 0 } ?: SshLinks.CONNECTION_FAILED)
            }

        /** 这次没开成：输出不再算数，连接上的计数还回去。 */
        fun abandon() {
            active.set(false)
            releaseOnce()
        }
    }

    /** 通道开好了：标准输入接到 [input]；这期间被结束的话马上关掉。 */
    fun connected(input: OutputStream) {
        channelInput = input
        ready.countDown()
        if (killed) current?.channel?.disconnect()
    }

    /** 没连上：原因写进标准错误，以 [SshLinks.CONNECTION_FAILED] 结束。 */
    fun fail(reason: String) {
        if (ended.get()) return
        val text = (SshLinks.PROBLEM_PREFIX + reason + "\n").toByteArray()
        stderr.write(text, 0, text.size)
        end(SshLinks.CONNECTION_FAILED)
    }

    private fun end(code: Int) {
        if (!ended.compareAndSet(false, true)) return
        exit = code
        stdout.closeWriter()
        stderr.closeWriter()
        ready.countDown()
        done.countDown()
    }

    /**
     * 标准输入。JSch 的通道关了以后，往它的输出流里写会被悄悄丢掉（不报错），调用方就不知道要重发； 这里拿着通道的锁先看它还连着没有，断了就抛 IOException。拿着锁时 JSch
     * 关不了它（断开通道要先拿同一把锁）。
     */
    private val stdin =
        object : OutputStream() {
            private fun <T> guarded(block: (OutputStream) -> T): T {
                ready.await()
                val out = channelInput ?: throw IOException("没有连上")
                val channel = current?.channel ?: throw IOException("没有连上")
                synchronized(channel) {
                    if (!channel.isConnected || channel.isClosed) throw IOException("通道已经断了")
                    return block(out)
                }
            }

            override fun write(b: Int) = guarded { it.write(b) }

            override fun write(b: ByteArray, off: Int, len: Int) = guarded { it.write(b, off, len) }

            override fun flush() = guarded { it.flush() }

            override fun close() {
                runCatching { guarded { it.close() } }
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

    /**
     * 关掉通道：那边的命令读到输入结束、写不出去就会退出（托管的 Claude Code 进程不受影响）。还在连的，连上就关。
     * 关通道要往连接上写、拿通道的锁，另起线程做，调用方（常是主线程）不等。
     */
    override fun destroy() {
        killed = true
        if (channelInput == null) return
        val channel = current?.channel ?: return
        thread(name = "zeta-ssh-close", isDaemon = true) { channel.disconnect() }
    }
}

/**
 * 一条字节管道：JSch 的会话线程写，调用方从 [source] 读。不像 PipedInputStream 那样每秒醒一次查写端线程还在不在。 缓冲区从 8 KiB 起按需翻倍，最大
 * [capacity]；满了写的一方等着。读端关了以后写进来的丢掉； 写端关了（[closeWriter]）以后读完剩下的就是结尾。
 */
internal class BytePipe(private val capacity: Int = 256 * 1024) {
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
