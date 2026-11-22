package com.dzshzx.zeta.core.ssh

import com.dzshzx.zeta.core.DirectSshShell
import com.dzshzx.zeta.core.RemoteHost
import com.dzshzx.zeta.core.RemoteHosts
import com.dzshzx.zeta.core.RemoteShell
import com.dzshzx.zeta.core.waitUntil
import java.io.File
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
 * 手机上的直连（[GoSshLinks]）：起这台机器编的 zeta-ssh（app/build.gradle.kts 的 zetaSshHost），对着 JVM 里的一个真 SSH 服务器
 * （[TestSshServer]，Apache MINA SSHD）。验的是协议转换这一层：命令的输入输出和退出码、登录 shell 与否、按读掉的确认输出、Zeta 的密钥
 * 经回调签名（服务器验 Zeta 的公钥和签名，签错了登录不上）、域名经回调解析、主机指纹、断线、zeta-ssh 退出。连接本身的规矩在 Go 里测
 * （client/sshbridge、client/sshlink）。
 */
class GoSshLinksTest {
    private lateinit var home: File
    private lateinit var key: SshKey
    private lateinit var server: TestSshServer
    private lateinit var links: GoSshLinks

    private fun links(key: SshKey) =
        GoSshLinks(OwnKeyAuth { key }, idleMs = 500) {
            File(
                checkNotNull(System.getProperty("zeta.programs.zeta-ssh")) {
                    "测试任务没有传 zeta.programs.zeta-ssh（见 app/build.gradle.kts）"
                },
            )
        }

    @Before
    fun setUp() {
        home = Files.createTempDirectory("zeta-remote-home").toFile()
        key = SshKey.generate()
        server = TestSshServer(key, home)
        links = links(key)
    }

    @After
    fun tearDown() {
        links.close()
        server.close()
        home.deleteRecursively()
    }

    private fun probe(
        target: String = server.target.toString(),
        expected: ByteArray? = null,
        command: String = "true",
        links: GoSshLinks = this.links,
    ) = links.probe(target, expected, command, null, "zeta-test", "UTC")

    /** 加主机那一次：谁都认，把对方的主机公钥带回来。 */
    private fun hostKey(): ByteArray = checkNotNull(probe().hostKey)

    private fun shellHost(
        hostKey: ByteArray = SshKey.generate().blob,
        target: String = server.target.toString(),
    ) =
        RemoteHost(
            "r-test",
            "test",
            target,
            home.path,
            home.path,
            RemoteHost.Via.Direct,
            RemoteHosts.encodeKey(hostKey),
        )

    private fun shell(hostKey: ByteArray = hostKey(), target: String = server.target.toString()) =
        DirectSshShell(links, shellHost(hostKey, target), timeZone = "UTC", linuxName = "zeta-test")

    @Test
    fun keyLooksLikeOpenSsh() {
        val line = key.authorizedKey.split(' ')
        assertEquals(SshKey.ALGORITHM, line[0])
        assertArrayEquals(key.blob, java.util.Base64.getDecoder().decode(line[1]))
        assertTrue(key.fingerprint.startsWith("SHA256:") && !key.fingerprint.endsWith("="))
        assertEquals(SshKey.ALGORITHM, SshKey.hostKeyType(key.blob))
        // 认不得的：长度对、内容不是公钥的，短得读不出类型的，后面多出字节的
        assertNull(SshKey.hostKeyType(ByteArray(key.blob.size)))
        assertNull(SshKey.hostKeyType(key.blob.copyOf(6)))
        assertNull(SshKey.hostKeyType(key.blob + 0))
        assertEquals(4 + 11 + 4 + 32, key.blob.size)
    }

    @Test
    fun probeRunsTheCommandAndReportsTheHostKey() {
        val result = probe(command = "echo \"hi \$ZETA_NAME\"; echo oops >&2; exit 3")
        assertNull(result.problem)
        assertEquals(3, result.output?.exitCode)
        assertEquals("hi zeta-test\n", result.output?.stdout)
        assertEquals("oops\n", result.output?.stderr)
        val seen = checkNotNull(result.hostKey)
        assertEquals("ssh-ed25519", SshKey.hostKeyType(seen))
        // 认着同一个主机公钥再连一次照样连上；换一个就拒绝
        assertNull(probe(expected = seen).problem)
        val refused = probe(expected = SshKey.generate().blob)
        assertTrue(refused.hostKeyChanged)
        assertTrue(refused.problem, refused.problem!!.contains("主机指纹"))
        assertArrayEquals(seen, refused.hostKey)
    }

    /** zeta-ssh（golang.org/x/crypto/ssh）对着优先给 Ed25519 主机密钥的服务器：谈成的是 ML-KEM 混合或 curve25519 密钥交换。 */
    @Test
    fun modernAlgorithmsAreNegotiated() {
        val process = shell().start("echo up; sleep 30")
        try {
            assertEquals("up", process.inputStream.bufferedReader().readLine())
            val negotiated = server.negotiated
            assertTrue(negotiated.isNotEmpty())
            for ((kex, hostKeyAlgorithm) in negotiated) {
                assertEquals("ssh-ed25519", hostKeyAlgorithm)
                assertTrue(kex, kex.startsWith("mlkem768x25519") || kex.startsWith("curve25519"))
            }
            println("谈成的算法：$negotiated")
        } finally {
            process.destroy()
        }
    }

    @Test
    fun anotherKeyIsNotLetIn() {
        val stranger = links(SshKey.generate())
        try {
            val result = probe(links = stranger)
            assertNull(result.output)
            assertEquals("对方不认 Zeta 的公钥：把它加进那台设备的 ~/.ssh/authorized_keys", result.problem)
        } finally {
            stranger.close()
        }
    }

    @Test
    fun commandsGetStdinExitCodeAndEnvironment() {
        val result =
            shell()
                .run("cat; printf '|%s|%s' \"\$ZETA_NAME\" \"\$TZ\"; exit 7", stdin = "a b'c\"d\n")
        assertEquals(7, result.exitCode)
        assertEquals("a b'c\"d\n|zeta-test|UTC", result.stdout)
    }

    /** 登录 shell 读那边的 ~/.profile、PATH 补上 ~/.local/bin；startPlain 的不读。 */
    @Test
    fun loginShellReadsTheProfileAndPlainDoesNot() {
        File(home, ".profile").writeText("export FROM_PROFILE=yes\n")
        val command =
            "echo \"\${FROM_PROFILE:-no}\"; case \":\$PATH:\" in *\":\$HOME/.local/bin:\"*) echo local;; *) echo none;; esac"
        val shell = shell()
        assertEquals("yes\nlocal\n", shell.run(command).stdout)
        assertEquals("no\nnone\n", shell.run(command, plain = true).stdout)
    }

    @Test
    fun bigInputAndOutputArriveWhole() {
        val input = ByteArray(1_000_000) { (it % 251).toByte() }
        val shell = shell()
        val echoed = shell.run("cat | wc -c", input, 60_000)
        assertEquals("1000000", echoed.stdout.trim())
        val result = shell.run("head -c 3000000 /dev/zero | tr '\\0' x", timeoutMs = 60_000)
        assertEquals(0, result.exitCode)
        assertEquals(3_000_000, result.stdout.length)
    }

    /** 不读标准输出了（关掉它）：剩下的输出照样确认掉，那边的命令不会卡住。 */
    @Test
    fun closedOutputDoesNotHoldTheCommand() {
        val process = shell().start("head -c 3000000 /dev/zero; echo done >&2")
        process.inputStream.close()
        assertEquals("done\n", process.errorStream.bufferedReader().readText())
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
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

    /** 换网络时（[GoSshLinks.dropAll]）开着的命令马上以 255 结束，下一个重新连。 */
    @Test
    fun dropAllEndsRunningCommands() {
        val shell = shell()
        val long = shell.start("echo up; sleep 30")
        assertEquals("up", long.inputStream.bufferedReader().readLine())
        links.dropAll()
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
        // 之后的命令照常跑；结束掉的那个一直没跑
        assertEquals("next\n", shell.run("echo next").stdout)
        assertFalse(marker.exists())
    }

    /** 输入写到已经结束的进程里会失败，调用方知道要重发。 */
    @Test
    fun inputToAnEndedCommandFails() {
        val process = shell().start("true")
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        val failed = runCatching { process.outputStream.write("late".toByteArray()) }
        assertTrue(failed.exceptionOrNull() is java.io.IOException)
    }

    /** 域名交给系统解析（Go 在 Android 上自己解析不了）：localhost 经 InetAddress 解析后连上。 */
    @Test
    fun namesAreResolvedByTheApp() {
        val target = SshTarget("zeta-test", "localhost", server.target.port).toString()
        assertEquals("named\n", shell(target = target).run("echo named").stdout)
        assertEquals("找不到这台设备（地址解析失败）", probe(target = "zeta-test@nope.invalid").problem)
    }

    /** 关掉 zeta-ssh：上面的命令以 255 结束（它断开连接时报的，或它没了），下一个命令另起一个。 */
    @Test
    fun closedProgramEndsCommandsAndTheNextOneRestartsIt() {
        val shell = shell()
        val long = shell.start("echo up; sleep 30")
        assertEquals("up", long.inputStream.bufferedReader().readLine())
        links.close()
        assertTrue(long.waitFor(20, TimeUnit.SECONDS))
        assertEquals(RemoteShell.CONNECTION_FAILED, long.exitValue())
        assertEquals("again\n", shell.run("echo again").stdout)
    }

    /** zeta-ssh 自己没了（这里是个打完招呼、收到一行就退出的假程序）：上面的命令以 255 结束，原因说它退出了。 */
    @Test
    fun programThatDiesEndsItsCommands() {
        val fake =
            File(home, "fake-zeta-ssh").apply {
                writeText(
                    "#!/bin/sh\necho '{\"type\":\"hello\",\"protocol\":1,\"window\":262144}'\n" +
                        "read line\necho boom >&2\nexit 3\n",
                )
                setExecutable(true)
            }
        val dying = GoSshLinks(OwnKeyAuth { key }) { fake }
        val process = DirectSshShell(dying, shellHost(), "UTC", "zeta-test").start("true")
        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        assertEquals(RemoteShell.CONNECTION_FAILED, process.exitValue())
        val problem = RemoteHosts.sshProblem(process.errorStream.bufferedReader().readText())
        assertTrue(problem, problem.startsWith("Zeta 的 SSH 程序退出了"))
    }

    @Test
    fun programThatCannotStartIsExplained() {
        val broken = GoSshLinks(OwnKeyAuth { key }) { File(home, "missing") }
        val result = broken.probe(server.target.toString(), null, "true", null, "zeta-test", "UTC")
        assertTrue(result.problem, result.problem!!.startsWith("Zeta 的 SSH 程序起不来"))
    }

    @Test
    fun probeThroughRemoteHostsParsesTheScript() {
        File(home, ".local/bin/claude").apply {
            parentFile!!.mkdirs()
            writeText("#!/bin/sh\necho '2.1.400 (Claude Code)'\n")
            setExecutable(true)
        }
        val probe =
            RemoteHosts.probeDirect(links, server.target.toString(), linuxName = "zeta-test")
        assertTrue(probe.toString(), probe is RemoteHosts.Probe.Ok)
        probe as RemoteHosts.Probe.Ok
        assertEquals(home.path, probe.home)
        assertEquals("2.1.400", probe.claude)
        assertNotNull(probe.hostKey)
        val changed =
            RemoteHosts.probeDirect(
                links,
                server.target.toString(),
                RemoteHosts.encodeKey(SshKey.generate().blob),
                linuxName = "zeta-test",
            )
        assertEquals(RemoteHosts.Probe.HostKeyChanged(probe.hostKey!!), changed)
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
    fun targets() {
        assertEquals(SshTarget("me", "example.com", 22), SshTarget.parse("me@example.com"))
        assertEquals(
            SshTarget("me", "example.com", 2222),
            SshTarget.parse("ssh://me@example.com:2222/"),
        )
        assertEquals(SshTarget("me", "::1", 2222), SshTarget.parse("me@[::1]:2222"))
        assertEquals(SshTarget("me", "fe80::1", 22), SshTarget.parse("me@fe80::1"))
        assertEquals("me@[::1]:2222", SshTarget("me", "::1", 2222).toString())
        for (bad in
            listOf(
                "example.com",
                "@example.com",
                "me@",
                "me@host:0",
                "me@host:99999",
                "me@-oProxy",
                "me@a b",
                "-me@host",
                "me@host;rm",
            )) {
            assertNull(bad, SshTarget.parse(bad))
        }
    }
}
