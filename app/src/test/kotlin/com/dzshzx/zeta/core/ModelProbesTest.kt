package com.dzshzx.zeta.core

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.claude.ClaudeAccountsTest
import com.dzshzx.zeta.core.ssh.TestSshServer
import com.dzshzx.zeta.shots.Fixtures
import java.io.File
import java.nio.file.Files
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 只为要模型清单起的进程（[ModelProbes]、[ConversationHub.requestModels]）：真的 zeta_host.sh 跑在临时 HOME 上，claude 是个替身，
 * 每次起来记一行、收到的每一行都记下，按 [Reply] 答 initialize，和真 Claude Code 一样收到 end_session 就退出。
 * 清单写进 hub、进程收掉（end_session，托管目录清掉）、同一台同时只起一个、超时也收掉、提前退出算没要到；
 * 远程设备经 Zeta 直连 JVM 里的 SSH 服务器（[TestSshServer]），当前对话在手机上时也去要。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ModelProbesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val hostScript = File("src/main/assets/zeta_host.sh").absoluteFile
    private lateinit var home: File
    private val homes = ArrayList<File>()

    /** 替身怎么答 initialize。 */
    private enum class Reply { Models, Silent, Crash }

    @Before
    fun setUp() {
        assumeTrue("要 Linux 的 /proc、bash 和 setsid", File("/proc/self").exists() && File("/usr/bin/setsid").canExecute())
        home = Files.createTempDirectory("zeta-models").toFile().canonicalFile
        homes += home
    }

    @After
    fun tearDown() {
        // 没收掉的托管进程经 stop 结束，等托管目录都没了再删临时目录
        for (dir in homes) {
            hostDirs(dir).forEach { key ->
                runCatching { HostTesting.run(dir, listOf("bash", hostScript.path, "stop", key.name), HostTesting.named(key.parentFile!!.parentFile!!.name)) }
            }
            waitUntil(15) { hostDirs(dir).isEmpty() }
        }
        homes.forEach { it.deleteRecursively() }
    }

    /** [dir] 这个 HOME 里还在的托管目录（不管 ZETA_NAME 是什么）。 */
    private fun hostDirs(dir: File): List<File> =
        File(dir, ".cache").listFiles().orEmpty().flatMap { File(it, "hosts").listFiles().orEmpty().toList() }

    /** 起替身的 shell：HOME 是 [home]，PATH 最前面是替身所在的 bin。 */
    private val shell: LinuxShell by lazy {
        ClaudeAccountsTest.localShell(home, mapOf("PATH" to "${home.path}/bin:" + System.getenv("PATH"), "ZETA_IDLE_STEP" to "1"))
    }

    /** 在 [dir]/[bin] 下写 claude 的替身。 */
    private fun standIn(reply: Reply, dir: File = home, bin: String = "bin") {
        val answer = when (reply) {
            Reply.Models ->
                "id=${'$'}{line#*\\\"request_id\\\":\\\"}; id=${'$'}{id%%\\\"*}; " +
                    "printf '%s\\n' \"{\\\"type\\\":\\\"control_response\\\",\\\"response\\\":{\\\"subtype\\\":\\\"success\\\",\\\"request_id\\\":\\\"${'$'}id\\\"," +
                    "\\\"response\\\":{\\\"models\\\":[" +
                    "{\\\"value\\\":\\\"opus\\\",\\\"displayName\\\":\\\"Opus\\\",\\\"resolvedModel\\\":\\\"claude-opus-5-5\\\",\\\"supportedEffortLevels\\\":[\\\"low\\\",\\\"high\\\"]}," +
                    "{\\\"value\\\":\\\"haiku\\\",\\\"displayName\\\":\\\"Haiku\\\",\\\"resolvedModel\\\":\\\"claude-haiku-5-5\\\"}]," +
                    "\\\"commands\\\":[{\\\"name\\\":\\\"deploy\\\"}]}}}\""
            Reply.Silent -> ":"
            Reply.Crash -> "exit 3"
        }
        File(dir, "$bin/claude").apply {
            parentFile!!.mkdirs()
            writeText(
                """
                #!/bin/bash
                echo "${'$'}PWD" >> "${'$'}HOME/claude-starts"
                printf '%s\n' "${'$'}*" > "${'$'}HOME/claude-args"
                ${if (reply == Reply.Crash) "exit 3" else ""}
                while read -r line; do
                  printf '%s\n' "${'$'}line" >> "${'$'}HOME/claude-in"
                  case ${'$'}line in
                    *'"subtype":"end_session"'*) exit 0 ;;
                    *'"subtype":"initialize"'*) $answer ;;
                  esac
                done
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
    }

    private fun starts(dir: File = home): List<String> = File(dir, "claude-starts").takeIf { it.exists() }?.readLines().orEmpty()

    private fun received(dir: File = home): List<String> = File(dir, "claude-in").takeIf { it.exists() }?.readLines().orEmpty()

    @Test
    fun theAnswerGoesIntoTheHubAndTheProcessEnds() {
        val hub = Fixtures.readyHub(app)
        standIn(Reply.Models)
        val probes = ModelProbes(hub) { shell }
        probes.probe(Backends.LOCAL)
        assertTrue(probes.probing(Backends.LOCAL))
        awaitMain { hub.models[Backends.LOCAL] != null }
        assertEquals(
            listOf(
                ModelOption("opus", "Opus", "", "claude-opus-5-5", listOf("low", "high")),
                ModelOption("haiku", "Haiku", "", "claude-haiku-5-5", null),
            ),
            hub.models[Backends.LOCAL],
        )
        assertFalse(probes.probing(Backends.LOCAL))
        // 斜杠命令清单不记：它随工作目录和 MCP 服务器变
        assertNull(hub.settings.commands[Backends.LOCAL])
        // 收掉了：Claude Code 收到 end_session 退出，托管目录清掉
        assertTrue("托管目录没清掉", waitUntil(15) { hostDirs(home).isEmpty() })
        val input = received()
        assertEquals(input.toString(), 2, input.size)
        assertTrue(input[0], input[0].contains("\"subtype\":\"initialize\""))
        assertTrue(input[1], input[1].contains("\"subtype\":\"end_session\""))
        // 在家目录里起，参数只有 stream-json 那几样：不接会话、不存会话、不起 MCP 服务器
        assertEquals(listOf(home.path), starts())
        assertEquals(QuotaProbe.ARGS.joinToString(" "), File(home, "claude-args").readText().trim())
    }

    @Test
    fun oneProbePerDeviceAtATime() {
        val hub = Fixtures.readyHub(app)
        standIn(Reply.Models)
        val probes = ModelProbes(hub) { shell }
        probes.probe(Backends.LOCAL)
        probes.probe(Backends.LOCAL)
        awaitMain { !probes.probing(Backends.LOCAL) }
        assertEquals(1, starts().size)
        // 上一个收掉了才再起
        probes.probe(Backends.LOCAL)
        awaitMain { !probes.probing(Backends.LOCAL) }
        assertEquals(2, starts().size)
    }

    @Test
    fun noAnswerInTimeStopsTheProcess() {
        val hub = Fixtures.readyHub(app)
        standIn(Reply.Silent)
        val probes = ModelProbes(hub) { shell }
        probes.probe(Backends.LOCAL)
        // 进程接上、收到了 initialize，再让主线程的时钟走：差一点到时限时还在等，到了就收掉
        assertTrue("没收到 initialize", waitUntil(15) { received().any { it.contains("\"subtype\":\"initialize\"") } })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ModelProbes.TIMEOUT_MS - 1_000))
        assertTrue(probes.probing(Backends.LOCAL))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
        assertFalse(probes.probing(Backends.LOCAL))
        assertTrue("托管目录没清掉", waitUntil(15) { hostDirs(home).isEmpty() })
        assertTrue(received().toString(), received().any { it.contains("\"subtype\":\"end_session\"") })
        assertNull(hub.models[Backends.LOCAL])
    }

    @Test
    fun anEarlyExitCountsAsNoAnswer() {
        val hub = Fixtures.readyHub(app)
        standIn(Reply.Crash)
        val probes = ModelProbes(hub) { shell }
        probes.probe(Backends.LOCAL)
        awaitMain { !probes.probing(Backends.LOCAL) }
        assertNull(hub.models[Backends.LOCAL])
        assertTrue("托管目录没清掉", waitUntil(15) { hostDirs(home).isEmpty() })
        // 没有 shell、进程起不来：什么都不起
        ModelProbes(hub) { null }.apply { probe(Backends.LOCAL); assertFalse(probing(Backends.LOCAL)) }
        val broken = object : LinuxShell by shell {
            override fun start(command: String): Process = throw IllegalStateException("进不去")
        }
        ModelProbes(hub) { broken }.apply { probe(Backends.LOCAL); assertFalse(probing(Backends.LOCAL)) }
    }

    /** 当前对话就在那台上：让它起进程，清单随它的 initialize 来，不另起；那台用不了时什么都不做。 */
    @Test
    fun theCurrentConversationOnTheDeviceIsUsedInstead() {
        val hub = Fixtures.readyHub(app)
        assertEquals(Backends.LOCAL, hub.current.backend)
        hub.requestModels(Backends.LOCAL)
        assertTrue(hub.current.isProcessAlive)
        assertFalse(hub.modelProbes.probing(Backends.LOCAL))
        hub.requestModels("r-gone")
        assertFalse(hub.modelProbes.probing("r-gone"))
    }

    /** 用户报的那种情形：当前对话在手机上，打开一台远程设备的设置页，那台的清单也要得到；有了就不再要。 */
    @Test
    fun aRemoteDeviceIsAskedWhileTheCurrentConversationIsOnThePhone() {
        val remote = File(home, "remote").apply { mkdirs() }
        homes += remote
        // 远端的 PATH 是系统默认的，claude 在 ~/.local/bin（Zeta 起进程时补进 PATH）
        standIn(Reply.Models, dir = remote, bin = ".local/bin")
        val hub = Fixtures.readyHub(app)
        val server = TestSshServer(Fixtures.sshKey, remote)
        try {
            val hostKey = checkNotNull(hub.ssh.probe(server.target, null, "true").hostKey)
            val device = RemoteHost(
                "r-00000001", "工作站", server.target.toString(), remote.path, remote.path, RemoteHost.Via.Direct, RemoteHosts.encodeKey(hostKey),
            )
            hub.updateRemoteHosts(listOf(device))
            assertEquals(Backends.LOCAL, hub.current.backend)
            hub.requestModels(device.id)
            assertTrue(hub.modelProbes.probing(device.id))
            awaitMain { hub.models[device.id] != null }
            assertEquals(listOf("opus", "haiku"), hub.models[device.id]?.map { it.value })
            assertNull(hub.models[Backends.LOCAL])
            assertTrue("远端的托管目录没清掉", waitUntil(15) { hostDirs(remote).isEmpty() })
            hub.requestModels(device.id)
            assertFalse(hub.modelProbes.probing(device.id))
            assertEquals(listOf(remote.path), starts(remote))
        } finally {
            hub.ssh.dropAll()
            server.close()
        }
    }
}
