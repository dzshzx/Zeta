package com.dzshzx.zeta.core.ssh

import com.dzshzx.zeta.core.CommandResult
import com.dzshzx.zeta.core.RemoteHost
import java.io.InputStream

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
 * Zeta 直连远程主机的 SSH（[RemoteHost.Via.Direct]）：每台主机一条连接，每个命令开一个通道，和 OpenSSH 的 ControlMaster 一样省握手。
 * 连接断了，上面的命令都以 [CONNECTION_FAILED] 结束，下一个命令重新连；最后一个通道关掉一分钟后断开。主机指纹在加主机时记下（[probe]），
 * 以后连接只认这一个，变了就拒绝。实现是随 APK 带的 Go 程序 zeta-ssh（app 的 GoSshLinks）。
 */
interface SshLinks {
    /** 地址连到哪、用哪些密钥（设置页上的公钥、能选的私钥）。 */
    val auth: SshAuth

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
    ): Process

    /**
     * 连一次 [target] 在登录 shell 里跑 [command]（加主机、重新检查时用）：认 [expectedHostKey]（null
     * 时谁都认），返回对方的主机公钥和命令的结果。 用私钥 [key]（null 照 [auth] 的默认）。连接用完就断开，不进连接池。阻塞，别在主线程上调用。
     */
    fun probe(
        target: String,
        expectedHostKey: ByteArray?,
        command: String,
        key: String?,
        linuxName: String,
        timeZone: String,
        timeoutMs: Long = 45_000,
    ): ProbeResult

    /** 只留下 [hostIds] 这几台的连接，别的断开（主机删了），上面的命令都以 [CONNECTION_FAILED] 结束。 */
    fun retain(hostIds: Set<String>)

    /** 断开所有连接（手机换了网络：旧连接多半已经死了，不等保活超时）。 */
    fun dropAll()

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
