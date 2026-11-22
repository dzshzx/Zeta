package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.claude.ClaudeAccountsTest
import com.dzshzx.zeta.core.ssh.SshKey
import com.dzshzx.zeta.core.ssh.SshLinks
import com.dzshzx.zeta.core.ssh.TestSshServer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 远程主机上的对话怎么接：真的 zeta_host.sh 经 [RemoteShell] 跑在“远端”（HOME 是临时目录，PATH 是系统默认的： 远端自己的 claude 在
 * ~/.local/bin，由 [RemoteShell.remoteCommand] 补进 PATH；不换的话会先找到这台机器上真的 claude）。
 * 连接断了时那条连接上的命令一起结束，托管的进程不受影响。远端的 claude 是个回声：每读到一行就输出 {"type":"echo","n":第几行}， 读到 end_session
 * 就退出（和 Claude Code 一样，收尾的 stop 不用等满宽限期）。 两种连法各跑一遍：[DebianRemoteLinkTest]（假 ssh）和
 * [DirectRemoteLinkTest]（Zeta 直连 JVM 里的 SSH 服务器）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
abstract class RemoteLinkContract {
    protected lateinit var root: File
    protected lateinit var remoteHome: File
    private lateinit var shell: RemoteShell
    private val hostScript = File("src/main/assets/zeta_host.sh").absoluteFile.readText()

    private val events = LinkedBlockingQueue<JsonObject>()
    private val links = LinkedBlockingQueue<ProcessLink>()
    private val exits = LinkedBlockingQueue<ProcessExit>()
    private val started = ArrayList<ClaudeProcess>()

    /** 搭好远端，返回连它的 shell。 */
    protected abstract fun connect(): RemoteShell

    /** 像断网一样断掉现有的连接：连接上的命令一起结束，托管的 claude 还在。 */
    protected abstract fun dropConnection()

    /** 为 true 时连不上（对方拒绝连接）。 */
    protected abstract var down: Boolean

    /** 连不上时标准错误里会有的字样。 */
    protected abstract val refusedMarker: String

    protected open fun cleanUp() = Unit

    @Before
    fun setUp() {
        assumeTrue(
            "要 Linux 的 /proc、bash 和 setsid",
            File("/proc/self").exists() && File("/usr/bin/setsid").canExecute(),
        )
        root = Files.createTempDirectory("zeta-link").toFile().canonicalFile
        remoteHome = File(root, "remote").apply { mkdirs() }
        File(remoteHome, ".local/bin/claude").apply {
            parentFile!!.mkdirs()
            writeText(
                "#!/bin/bash\nn=0\nwhile IFS= read -r line; do case ${'$'}line in *end_session*) exit 0 ;; esac; n=${'$'}((n + 1)); printf '{\"type\":\"echo\",\"n\":%d}\\n' \"${'$'}n\"; done\n",
            )
            setExecutable(true)
        }
        // 远端的登录配置每读一次记一行：起进程要读，接上、重连不读（LinuxShell.startPlain）
        File(remoteHome, ".bash_profile").writeText("echo x >> \"\$HOME/profile-reads\"\n")
        shell = connect()
    }

    private fun profileReads(): Int =
        File(remoteHome, "profile-reads").takeIf { it.exists() }?.readLines()?.size ?: 0

    @After
    fun tearDown() {
        if (!::root.isInitialized) return
        down = false
        started.forEach { it.stop() }
        // stop 在别的线程上送“stop”命令：等托管目录没了再删临时目录
        waitUntil(10) { File(remoteHome, ".cache/zeta-test/hosts").listFiles().isNullOrEmpty() }
        cleanUp()
        root.deleteRecursively()
    }

    private fun launch(launch: ClaudeProcess.Launch, reconnect: Boolean = true): ClaudeProcess =
        ClaudeProcess(
                shell = shell,
                hostScript = hostScript,
                hostKey = "k1",
                launch = launch,
                onEvent = { events.add(it) },
                onExit = { exits.add(it) },
                onLink = { links.add(it) },
                reconnect = reconnect,
                backoff = { 300L },
            )
            .also { started += it }

    private fun message(n: Int) = buildJsonObject {
        put("type", "user")
        put("n", n)
    }

    /** 接下来的一条回声的序号。 */
    private fun nextEcho(): Int {
        val event =
            checkNotNull(events.poll(20, TimeUnit.SECONDS)) {
                "没等到回声；结束：" + exits.map { it.code to it.stderr } + "，连接：" + links.toList()
            }
        assertEquals("echo", event.str("type"))
        return event.int("n")!!
    }

    @Test
    fun droppedConnectionResumesFromTheNextLine() {
        val process = launch(ClaudeProcess.Launch.Start(remoteHome.path, "t", emptyList()))
        process.send(message(1))
        assertEquals(1, nextEcho())
        process.send(message(2))
        assertEquals(2, nextEcho())

        // 断了，而且一时连不上：期间发的消息留着，接上后送到
        down = true
        dropConnection()
        val lost = links.poll(20, TimeUnit.SECONDS)
        assertTrue("没报断开：$lost", lost is ProcessLink.Reconnecting)
        process.send(message(3))
        // 再失败一两次（ssh 直接以 255 退出）也接着重连
        assertTrue(
            waitUntil(10) { links.any { it is ProcessLink.Reconnecting && it.attempt >= 2 } },
        )
        down = false
        assertTrue("没重新接上", waitUntil(20) { links.contains(ProcessLink.Connected) })
        // 从断开前读到的下一行接着读：不重复、不漏
        assertEquals(3, nextEcho())
        process.send(message(4))
        assertEquals(4, nextEcho())
        assertTrue(exits.isEmpty())
        assertEquals("登录配置只在起进程时读", 1, profileReads())
    }

    @Test
    fun hostedProcessEndingIsReportedAfterReconnect() {
        val process = launch(ClaudeProcess.Launch.Start(remoteHome.path, "t", emptyList()))
        process.send(message(1))
        assertEquals(1, nextEcho())
        // 断着的时候远端的 claude 结束了：接上时读到剩下的输出和退出码
        down = true
        dropConnection()
        assertTrue(links.poll(20, TimeUnit.SECONDS) is ProcessLink.Reconnecting)
        val pid = File(remoteHome, ".cache/zeta-test/hosts/k1/pid").readText().trim().toLong()
        ProcessHandle.of(pid).ifPresent { it.destroy() }
        assertTrue(waitUntil(10) { File(remoteHome, ".cache/zeta-test/hosts/k1/exit").exists() })
        down = false
        val exit = checkNotNull(exits.poll(30, TimeUnit.SECONDS)) { "没等到结束" }
        assertEquals(143, exit.code)
        assertFalse("托管目录应由接着的一方删掉", File(remoteHome, ".cache/zeta-test/hosts/k1").exists())
    }

    @Test
    fun startThatNeverConnectsIsNotRetried() {
        down = true
        launch(ClaudeProcess.Launch.Start(remoteHome.path, "t", emptyList()))
        val exit = checkNotNull(exits.poll(20, TimeUnit.SECONDS)) { "没等到结束" }
        assertEquals(RemoteShell.CONNECTION_FAILED, exit.code)
        assertTrue(exit.stderr, exit.stderr.contains(refusedMarker))
        assertTrue(links.isEmpty())
    }

    @Test
    fun attachingToARunningProcessStartsFromTheGivenLine() {
        // 像 Zeta 被杀：这一头的连接没了、不重连，托管的进程还在
        val first =
            launch(ClaudeProcess.Launch.Start(remoteHome.path, "t", emptyList()), reconnect = false)
        first.send(message(1))
        first.send(message(2))
        assertEquals(1, nextEcho())
        assertEquals(2, nextEcho())
        dropConnection()
        assertEquals(
            RemoteShell.CONNECTION_FAILED,
            checkNotNull(exits.poll(20, TimeUnit.SECONDS)) { "没等到结束" }.code,
        )
        started.remove(first)
        // 重开后从第 2 行接上：先读到 n=2，之后照常
        val again = launch(ClaudeProcess.Launch.Attach(fromLine = 2))
        assertEquals(2, nextEcho())
        again.send(message(3))
        assertEquals(3, nextEcho())
    }
}

/** 经手机 Debian 的 OpenSSH：中间是一个假 ssh（本机 bash 起，远程命令交给 sh -c），被结束时像断网一样以 255 退出，它在远端起的进程一起结束。 */
class DebianRemoteLinkTest : RemoteLinkContract() {
    private lateinit var pids: File
    private lateinit var downFile: File

    override fun connect(): RemoteShell {
        pids = File(root, "pids").apply { mkdirs() }
        downFile = File(root, "down")
        val bin = File(root, "bin").apply { mkdirs() }
        File(bin, "ssh").apply {
            writeText(
                """
                #!/bin/bash
                while [ ${'$'}# -gt 0 ]; do case "${'$'}1" in -T) shift ;; -o) shift 2 ;; --) shift; break ;; *) break ;; esac; done
                target=${'$'}1; shift
                if [ -f "${downFile.path}" ]; then echo "ssh: connect to host ${'$'}target port 22: Connection refused" >&2; exit 255; fi
                HOME="${remoteHome.path}" PATH=/usr/local/bin:/usr/bin:/bin setsid sh -c "${'$'}*" <&0 &
                child=${'$'}!
                echo ${'$'}child > "${pids.path}/${'$'}${'$'}"
                trap 'kill -TERM -- -${'$'}child 2>/dev/null; rm -f "${pids.path}/${'$'}${'$'}"; exit 255' TERM
                wait ${'$'}child
                code=${'$'}?
                rm -f "${pids.path}/${'$'}${'$'}"
                exit ${'$'}code
                """
                    .trimIndent() + "\n",
            )
            setExecutable(true)
        }
        val local =
            ClaudeAccountsTest.localShell(
                File(root, "local").apply { mkdirs() },
                mapOf("PATH" to bin.path + ":" + System.getenv("PATH")),
            )
        return DebianSshShell(
            local,
            RemoteHost("r-test", "test", "test-host", remoteHome.path, remoteHome.path),
            timeZone = "UTC",
            linuxName = "zeta-test",
        )
    }

    override fun dropConnection() {
        val ssh = pids.listFiles().orEmpty().map { it.name.toLong() }
        assertTrue("没有连着的 ssh", ssh.isNotEmpty())
        ssh.forEach { ProcessHandle.of(it).ifPresent { handle -> handle.destroy() } }
    }

    override var down: Boolean
        get() = downFile.exists()
        set(value) {
            if (value) downFile.createNewFile() else downFile.delete()
        }

    override val refusedMarker = "Connection refused"
}

/** Zeta 直连：JVM 里的 SSH 服务器（[TestSshServer]），中间的中转能像断网一样掐断连接、拒绝新连接。 */
class DirectRemoteLinkTest : RemoteLinkContract() {
    private lateinit var server: TestSshServer
    private lateinit var links: SshLinks

    override fun connect(): RemoteShell {
        val key = SshKey.generate()
        server = TestSshServer(key, remoteHome)
        links = SshLinks({ key })
        val hostKey = checkNotNull(links.probe(server.target, null, "true").hostKey)
        val host =
            RemoteHost(
                "r-test",
                "test",
                server.target.toString(),
                remoteHome.path,
                remoteHome.path,
                RemoteHost.Via.Direct,
                RemoteHosts.encodeKey(hostKey),
            )
        return DirectSshShell(links, host, timeZone = "UTC", linuxName = "zeta-test")
    }

    override fun dropConnection() = server.cut()

    override var down: Boolean
        get() = server.down
        set(value) {
            server.down = value
        }

    override val refusedMarker = SshLinks.PROBLEM_PREFIX

    override fun cleanUp() {
        links.dropAll()
        server.close()
    }
}
