package com.dzshzx.zeta.core.ssh

import com.dzshzx.zeta.core.DirectSshShell
import com.dzshzx.zeta.core.RemoteHost
import com.dzshzx.zeta.core.RemoteHosts
import com.dzshzx.zeta.core.RemoteShell
import com.dzshzx.zeta.core.waitUntil
import com.jcraft.jsch.JSchException
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Zeta 直连：密钥、主机指纹、连接复用、断线，对着 JVM 里的一个真 SSH 服务器（[TestSshServer]）。
 * 走的是 OpenSSH 认的线上格式：服务器要验 Zeta 的公钥和签名，签错了登录不上。
 */
class SshLinksTest {
    private lateinit var home: File
    private lateinit var key: SshKey
    private lateinit var server: TestSshServer
    private lateinit var links: SshLinks

    @Before
    fun setUp() {
        home = Files.createTempDirectory("zeta-remote-home").toFile()
        key = SshKey.generate()
        server = TestSshServer(key, home)
        links = SshLinks({ key }, idleMs = 500)
    }

    @After
    fun tearDown() {
        links.dropAll()
        server.close()
        home.deleteRecursively()
    }

    /** 加主机那一次：谁都认，把对方的主机公钥带回来。 */
    private fun hostKey(): ByteArray = checkNotNull(links.probe(server.target, null, "true").hostKey)

    private fun shell(hostKey: ByteArray = hostKey()) = DirectSshShell(
        links,
        RemoteHost("r-test", "test", server.target.toString(), home.path, home.path, RemoteHost.Via.Direct, RemoteHosts.encodeKey(hostKey)),
        timeZone = "UTC",
        linuxName = "zeta-test",
    )

    @Test
    fun keyLooksLikeOpenSsh() {
        val line = key.authorizedKey.split(' ')
        assertEquals(SshKey.ALGORITHM, line[0])
        assertArrayEquals(key.blob, java.util.Base64.getDecoder().decode(line[1]))
        assertTrue(key.fingerprint.startsWith("SHA256:") && !key.fingerprint.endsWith("="))
        assertEquals(SshKey.ALGORITHM, SshLinks.hostKey(key.blob)?.type)
        // 认不得的：长度对、内容不是公钥的，和短得读不出类型的
        assertNull(SshLinks.hostKey(ByteArray(key.blob.size)))
        assertNull(SshLinks.hostKey(key.blob.copyOf(6)))
        assertEquals("ssh-ed25519", SshKey.ALGORITHM)
        assertEquals(4 + 11 + 4 + 32, key.blob.size)
    }

    @Test
    fun probeRunsTheCommandAndReportsTheHostKey() {
        val result = links.probe(server.target, null, "echo hi; echo oops >&2; exit 3")
        assertNull(result.problem)
        assertEquals(3, result.output?.exitCode)
        assertEquals("hi\n", result.output?.stdout)
        val seen = checkNotNull(result.hostKey)
        assertEquals("ssh-ed25519", SshLinks.hostKey(seen)?.type)
        // 认着同一个主机公钥再连一次照样连上；换一个就拒绝
        assertNull(links.probe(server.target, seen, "true").problem)
        val other = SshKey.generate().blob
        val refused = links.probe(server.target, other, "true")
        assertTrue(refused.hostKeyChanged)
        assertTrue(refused.problem, refused.problem!!.contains("主机指纹"))
        assertArrayEquals(seen, refused.hostKey)
    }

    /** 手机上 Ed25519、curve25519、ML-KEM 都靠 BouncyCastle：对着优先给这几样的服务器，谈成的就是它们。 */
    @Test
    fun modernAlgorithmsAreNegotiated() {
        val process = links.start("h", server.target, hostKey(), "echo up; sleep 5")
        try {
            assertEquals("up", process.inputStream.bufferedReader().readLine())
            val negotiated = server.negotiated
            assertTrue(negotiated.isNotEmpty())
            for ((kex, hostKeyAlgorithm) in negotiated) {
                assertEquals("ssh-ed25519", hostKeyAlgorithm)
                assertTrue(kex, kex.startsWith("mlkem768x25519") || kex.startsWith("sntrup761x25519") || kex.startsWith("curve25519"))
            }
            println("谈成的算法：$negotiated")
        } finally {
            process.destroy()
        }
    }

    /** 两个方向都压缩：服务器支持 zlib@openssh.com（OpenSSH 默认就是它）时谈成的就是它，压过的内容原样到达。 */
    @Test
    fun compressionIsNegotiated() {
        val text = "{\"type\":\"assistant\"}".repeat(20_000)
        val process = links.start("h", server.target, hostKey(), "cat")
        try {
            process.outputStream.use { it.write(text.toByteArray()) }
            assertEquals(text, process.inputStream.bufferedReader().readText())
            assertEquals(listOf("zlib@openssh.com" to "zlib@openssh.com"), server.compression.distinct())
        } finally {
            process.destroy()
        }
    }

    /** 管道从小缓冲区起按需变大：内容绕到开头时扩容，读出来的顺序不乱；写端关了读完就是结尾。 */
    @Test
    fun pipeGrowsWithoutReordering() {
        val pipe = BytePipe(capacity = 64 * 1024)
        val data = ByteArray(40_000) { (it % 251).toByte() }
        val read = java.io.ByteArrayOutputStream()
        pipe.write(data, 0, 6_000)
        val buffer = ByteArray(4_000)
        read.write(buffer, 0, pipe.source.read(buffer, 0, 4_000))
        pipe.write(data, 6_000, data.size - 6_000)
        pipe.closeWriter()
        read.write(pipe.source.readBytes())
        assertArrayEquals(data, read.toByteArray())
    }

    @Test
    fun anotherKeyIsNotLetIn() {
        val stranger = SshLinks({ SshKey.generate() })
        val result = stranger.probe(server.target, null, "true")
        assertNull(result.output)
        assertTrue(result.problem, result.problem!!.contains("不认 Zeta 的公钥"))
    }

    @Test
    fun commandsGetStdinExitCodeAndEnvironment() {
        val shell = shell()
        val result = shell.run("cat; printf '|%s|%s' \"\$ZETA_NAME\" \"\$TZ\"; exit 7", stdin = "a b'c\"d\n")
        assertEquals(7, result.exitCode)
        assertEquals("a b'c\"d\n|zeta-test|UTC", result.stdout)
    }

    @Test
    fun bigOutputArrivesWhole() {
        val result = shell().run("head -c 3000000 /dev/zero | tr '\\0' x", timeoutMs = 60_000)
        assertEquals(0, result.exitCode)
        assertEquals(3_000_000, result.stdout.length)
    }

    @Test
    fun commandsShareOneConnectionAndItClosesWhenIdle() {
        val shell = shell()
        val long = shell.start("sleep 30")
        repeat(3) { assertEquals("ok\n", shell.run("echo ok").stdout) }
        assertEquals(1, server.sessions)
        shell.kill(long)
        assertTrue(long.waitFor(10, TimeUnit.SECONDS))
        // 最后一个通道关了，闲 0.5 秒后断开
        assertTrue(waitUntil(10) { server.sessions == 0 })
    }

    @Test
    fun droppedConnectionEndsCommandsAndTheNextOneReconnects() {
        val shell = shell()
        val long = shell.start("sleep 30")
        assertEquals("ok\n", shell.run("echo ok").stdout)
        server.cut()
        assertTrue(long.waitFor(20, TimeUnit.SECONDS))
        assertEquals(RemoteShell.CONNECTION_FAILED, long.exitValue())
        assertEquals("again\n", shell.run("echo again").stdout)
    }

    @Test
    fun unreachableHostFailsWithAReason() {
        val shell = shell()
        server.down = true
        val result = shell.run("echo never", timeoutMs = 30_000)
        assertEquals(RemoteShell.CONNECTION_FAILED, result.exitCode)
        assertEquals("对方断开了连接", RemoteHosts.sshProblem(result.stderr))
        server.down = false
        assertEquals("back\n", shell.run("echo back").stdout)
    }

    @Test
    fun killBeforeConnectedNeverRuns() {
        val marker = File(home, "ran")
        val shell = shell()
        server.down = true
        val process = shell.start("touch ${marker.path}")
        shell.kill(process)
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        server.down = false
        Thread.sleep(500)
        assertFalse(marker.exists())
    }

    @Test
    fun probeThroughRemoteHostsParsesTheScript() {
        File(home, ".local/bin/claude").apply {
            parentFile!!.mkdirs()
            writeText("#!/bin/sh\necho '2.1.400 (Claude Code)'\n")
            setExecutable(true)
        }
        val probe = RemoteHosts.probeDirect(links, server.target.toString(), linuxName = "zeta-test")
        assertTrue(probe.toString(), probe is RemoteHosts.Probe.Ok)
        probe as RemoteHosts.Probe.Ok
        assertEquals(home.path, probe.home)
        assertEquals("2.1.400", probe.claude)
        assertNotNull(probe.hostKey)
        val changed = RemoteHosts.probeDirect(links, server.target.toString(), RemoteHosts.encodeKey(SshKey.generate().blob), linuxName = "zeta-test")
        assertEquals(RemoteHosts.Probe.HostKeyChanged(probe.hostKey!!), changed)
    }

    @Test
    fun targets() {
        assertEquals(SshTarget("me", "example.com", 22), SshTarget.parse("me@example.com"))
        assertEquals(SshTarget("me", "example.com", 2222), SshTarget.parse("ssh://me@example.com:2222/"))
        assertEquals(SshTarget("me", "::1", 2222), SshTarget.parse("me@[::1]:2222"))
        assertEquals(SshTarget("me", "fe80::1", 22), SshTarget.parse("me@fe80::1"))
        assertEquals("me@[::1]:2222", SshTarget("me", "::1", 2222).toString())
        for (bad in listOf("example.com", "@example.com", "me@", "me@host:0", "me@host:99999", "me@-oProxy", "me@a b", "-me@host", "me@host;rm")) {
            assertNull(bad, SshTarget.parse(bad))
        }
    }

    /** 系统给的说明随语言变（JVM 上是中文），按异常的类型和 errno 名字认。 */
    @Test
    fun problemsAreReadFromExceptionTypes() {
        fun wrapped(cause: Throwable) = JSchException("Session.connect: $cause", cause)
        assertEquals("对方拒绝连接（端口上没有 SSH 服务）", SshLinks.problem(wrapped(ConnectException("连接被拒绝"))))
        assertEquals("网络不通", SshLinks.problem(wrapped(ConnectException("failed to connect to /10.0.0.9 (port 22): connect failed: ENETUNREACH (Network is unreachable)"))))
        assertEquals("网络不通", SshLinks.problem(wrapped(NoRouteToHostException("没有到主机的路由"))))
        assertEquals("连接超时", SshLinks.problem(wrapped(SocketTimeoutException("连接超时"))))
        assertEquals("连接超时", SshLinks.problem(JSchException("timeout: socket is not established")))
        assertEquals("找不到这台设备（地址解析失败）", SshLinks.problem(wrapped(UnknownHostException("nope.invalid"))))
        assertTrue(SshLinks.problem(JSchException("Auth fail for methods 'publickey'")).contains("不认 Zeta 的公钥"))
    }
}
