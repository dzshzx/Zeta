package com.dzshzx.zeta.core.ssh

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import kotlin.concurrent.thread
import org.apache.sshd.common.kex.KexProposalOption
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command

/**
 * 测试用的远程主机：JVM 里起一个 SSH 服务器（Apache MINA SSHD），主机密钥是 Ed25519，只认 [clientKey]，命令交给本机的 sh -c 跑，
 * HOME 换成 [home]、PATH 换成系统默认的（和真的登录 shell 一样，用户自己的程序在 ~/.local/bin，由 Zeta 补进 PATH）。
 * 客户端经一个中转连它（[target]）：[cut] 像断网一样掐断所有连接，[down] 时连不上（连上就被对方关掉）。
 * 连接断了时，服务器像 sshd 一样结束那个通道上的命令（整个进程组），命令自己 setsid 出去的进程不受影响。
 */
class TestSshServer(val clientKey: SshKey, val home: File) : AutoCloseable {
    private val dir = Files.createTempDirectory("zeta-sshd").toFile()
    private val server = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        // 和 OpenSSH 一样优先给 Ed25519 的主机密钥：手机上验它的签名靠 BouncyCastle，测试走同一条路
        // （用 MINA 自己生成：它经 BouncyCastle 认 Ed25519 密钥，认不出 JDK 的）
        keyPairProvider = KeyPairProvider.wrap(KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256))
        // 按指纹比：服务器自己按 OpenSSH 的线上格式编码收到的公钥，和 Zeta 拼的对得上才算
        setPublickeyAuthenticator { _, key, _ -> KeyUtils.getFingerPrint(key) == clientKey.fingerprint }
        setPasswordAuthenticator(null)
        setKeyboardInteractiveAuthenticator(null)
        setCommandFactory { _, command -> ShellCommand(command, home) }
        start()
    }
    private val relay = Relay(server.port)

    val target: SshTarget get() = SshTarget("zeta-test", "127.0.0.1", relay.port)

    /** 服务器上现在有几条连接。 */
    val sessions: Int get() = server.activeSessions.size

    /** 现有各条连接谈成的密钥交换和主机密钥算法。 */
    val negotiated: List<Pair<String, String>>
        get() = server.activeSessions.map {
            it.getNegotiatedKexParameter(KexProposalOption.ALGORITHMS) to it.getNegotiatedKexParameter(KexProposalOption.SERVERKEYS)
        }

    /** 现有各条连接谈成的压缩算法：（客户端到服务器，服务器到客户端）。 */
    val compression: List<Pair<String, String>>
        get() = server.activeSessions.map {
            it.getNegotiatedKexParameter(KexProposalOption.C2SCOMP) to it.getNegotiatedKexParameter(KexProposalOption.S2CCOMP)
        }

    /** 像断网一样掐断现有的连接。 */
    fun cut() = relay.cut()

    /** 为 true 时连不上：新连接一连上就被关掉。端口一直占着，不让并行的别的测试拿去用。 */
    var down: Boolean
        get() = relay.down
        set(value) {
            relay.down = value
        }

    override fun close() {
        relay.close()
        runCatching { server.stop(true) }
        dir.deleteRecursively()
    }

    /** sh -c 跑一条命令；setsid 让它自成一组，连接断了时整组结束。 */
    private class ShellCommand(private val command: String, private val home: File) : Command {
        private lateinit var input: InputStream
        private lateinit var output: OutputStream
        private lateinit var error: OutputStream
        private var exit: ExitCallback? = null
        @Volatile private var process: Process? = null

        override fun setInputStream(`in`: InputStream) {
            input = `in`
        }

        override fun setOutputStream(out: OutputStream) {
            output = out
        }

        override fun setErrorStream(err: OutputStream) {
            error = err
        }

        override fun setExitCallback(callback: ExitCallback) {
            exit = callback
        }

        override fun start(channel: ChannelSession, env: Environment) {
            val builder = ProcessBuilder("setsid", "sh", "-c", command).directory(home)
            builder.environment().apply {
                clear()
                put("HOME", home.path)
                put("PATH", "/usr/local/bin:/usr/bin:/bin")
                put("USER", "zeta-test")
                put("LANG", "C.UTF-8")
            }
            val p = builder.start()
            process = p
            thread(isDaemon = true) {
                pump(input, p.outputStream)
                runCatching { p.outputStream.close() }
            }
            val out = thread(isDaemon = true) { pump(p.inputStream, output) }
            val err = thread(isDaemon = true) { pump(p.errorStream, error) }
            thread(isDaemon = true) {
                val code = p.waitFor()
                out.join(5_000)
                err.join(5_000)
                exit?.onExit(code)
            }
        }

        private fun pump(from: InputStream, to: OutputStream) {
            runCatching {
                val buffer = ByteArray(8192)
                while (true) {
                    val n = from.read(buffer)
                    if (n < 0) break
                    to.write(buffer, 0, n)
                    to.flush()
                }
            }
        }

        override fun destroy(channel: ChannelSession) {
            val p = process ?: return
            if (p.isAlive) runCatching { ProcessBuilder("kill", "-TERM", "--", "-${p.pid()}").start().waitFor() }
        }
    }

    /** 本机端口上的中转：把连进来的连接接到服务器上，[cut] 时两头一起关掉。 */
    private class Relay(private val upstream: Int) : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val sockets = Collections.synchronizedList(ArrayList<Socket>())
        val port: Int = listener.localPort

        @Volatile var down = false
            set(value) {
                field = value
                if (value) cut()
            }

        init {
            thread(isDaemon = true, name = "relay-accept") { accept() }
        }

        private fun accept() {
            while (!listener.isClosed) {
                val client = runCatching { listener.accept() }.getOrNull() ?: break
                if (down) {
                    client.close()
                    continue
                }
                val server = runCatching { Socket(InetAddress.getLoopbackAddress(), upstream) }.getOrNull()
                if (server == null) {
                    client.close()
                    continue
                }
                sockets += client
                sockets += server
                thread(isDaemon = true) { copy(client, server) }
                thread(isDaemon = true) { copy(server, client) }
            }
        }

        private fun copy(from: Socket, to: Socket) {
            runCatching { from.getInputStream().copyTo(to.getOutputStream()) }
            runCatching { from.close() }
            runCatching { to.close() }
        }

        fun cut() {
            val all = synchronized(sockets) { sockets.toList().also { sockets.clear() } }
            all.forEach { runCatching { it.close() } }
        }

        override fun close() {
            runCatching { listener.close() }
            cut()
        }
    }
}
