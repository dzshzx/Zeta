package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.HostTesting.alive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * zeta_host.sh stop 怎么结束会话（end_claude）：先发 end_session 让 Claude Code 自己结束，不退才 SIGTERM、SIGKILL；
 * 整个在单独的会话里做，stop 立即返回。真的 zeta_host.sh 跑在临时 HOME 上，claude 换成替身。
 *
 * 不按墙上时钟断言。stop 在后台做完的事件是托管目录被删：它在 end_claude 返回之后才删，而 end_claude 等到进程不在跑才返回 （SIGKILL
 * 那一步除外，这里的替身都不走到那一步），所以目录没了时进程该已经不在了，[awaitStopped] 等的就是它，限时只防卡死。 要断言「宽限期还没到」的，宽限期设成
 * [LONG_GRACE]，比测试里任何一个限时都长。
 */
class HostStopTest {
    private lateinit var home: File
    private val host = File("src/main/assets/zeta_host.sh").absoluteFile
    private val name = AppVariant(AppVariant.RELEASE_PACKAGE + ".dev").linuxName
    private val hosts
        get() = File(home, ".cache/$name/hosts")

    private fun hostDir(key: String) = File(hosts, key)

    /** 这次测试的 ZETA_STOP_GRACE（秒）。 */
    private var grace = SHORT_GRACE

    /** 跑 zeta_host.sh 时另加的环境变量。 */
    private var extraEnv = emptyMap<String, String>()

    @Before
    fun setUp() {
        assumeTrue("要 Linux 的 /proc 和 bash", File("/proc/self/cmdline").exists())
        home = Files.createTempDirectory("zeta-stop").toFile().canonicalFile
    }

    @After
    fun tearDown() {
        if (!::home.isInitialized) return
        // 替身没了，还在等宽限期的 stop 随即做完退出，不留在后台
        HostTesting.killHosts(home, name)
        home.deleteRecursively()
    }

    /** 跑 zeta_host.sh（[HostTesting.run]：一分钟内没返回就失败）。 */
    private fun run(vararg arguments: String) {
        HostTesting.run(
            home,
            listOf("bash", host.path) + arguments,
            HostTesting.named(name) + ("ZETA_STOP_GRACE" to "$grace") + extraEnv,
        )
    }

    private fun start(key: String): Int {
        run(
            "start",
            key,
            home.path,
            "t",
            "-p",
            "--input-format",
            "stream-json",
            "--output-format",
            "stream-json",
        )
        return File(hostDir(key), "pid").readText().trim().toInt()
    }

    /** 等 stop 在后台做完（托管目录删掉）。 */
    private fun awaitStopped(key: String) =
        assertTrue("$key：stop 一直没做完", waitUntil { !hostDir(key).exists() })

    /** 替身收到 end_session 就退出、SIGTERM 它不理。宽限期比整个测试还长，信号不会来：stop 做完了，就是替身照 end_session 自己退出的。 */
    @Test
    fun stopAsksClaudeCodeToEndTheSession() {
        grace = LONG_GRACE
        HostTesting.writeClaudeStandIn(home, prelude = "trap '' TERM")
        val pid = start("k")
        run("stop", "k")
        awaitStopped("k")
        assertFalse("替身收到 end_session 后没退出", alive(pid))
    }

    /** stop 不等宽限期：宽限期比 [run] 的限时（一分钟）还长，stop 却返回了，这时卡住的进程和托管目录都还在。 进程没了以后，还在等的 stop 照样做完、删掉托管目录。 */
    @Test
    fun stopReturnsWithoutWaitingForTheGrace() {
        grace = LONG_GRACE
        HostTesting.writeClaudeStandIn(home, loop = HostTesting.READ_FOREVER)
        val pid = start("k")
        run("stop", "k")
        assertTrue("宽限期还没过就收掉了", alive(pid) && hostDir("k").exists())
        ProcessBuilder("kill", "-9", "$pid").start().waitFor(5, TimeUnit.SECONDS)
        awaitStopped("k")
    }

    /** 不理 end_session 的（卡住了）：宽限期过后在后台 SIGTERM 收掉，托管目录删掉。 */
    @Test
    fun stuckProcessIsTerminatedInTheBackground() {
        HostTesting.writeClaudeStandIn(home, loop = HostTesting.READ_FOREVER)
        val pid = start("k")
        run("stop", "k")
        awaitStopped("k")
        assertFalse("卡住的进程没被收掉", alive(pid))
    }

    /**
     * claude 被包了一层（[HostTesting.Wrapper]）时卡住的 Claude Code 收 SIGTERM，按登记和记号找到的它也一起收到，不会只杀外面那层、留下孤儿。
     * 两种包法各用各的托管目录，前一个的收尾碰不到后一个。
     */
    @Test
    fun aStuckWrappedClaudeIsTerminatedNotOrphaned() {
        for (wrapper in HostTesting.Wrapper.entries) {
            val key = "k-$wrapper"
            File(home, ".claude/sessions").deleteRecursively()
            HostTesting.writeClaudeStandIn(
                home,
                prelude = HostTesting.registersAs("s"),
                loop = HostTesting.READ_FOREVER,
            )
            extraEnv = HostTesting.wrapClaude(home, wrapper)
            val outer = start(key)
            val claude = HostTesting.registeredPid(home)
            assertTrue("$wrapper：pid 文件记的该是外面那层", outer != claude)
            assertEquals("$wrapper：记号", "$name/$key", HostTesting.envOf(claude, "ZETA_HOST"))
            run("stop", key)
            awaitStopped(key)
            assertFalse("$wrapper：Claude Code 没被收掉", alive(claude))
            assertFalse("$wrapper：外面那层没被收掉", alive(outer))
        }
    }

    private companion object {
        /** 卡住的进程等多久才收（秒）：短，测试快。 */
        const val SHORT_GRACE = 1

        /** 要断言宽限期还没到时用（秒）：比 [waitUntil] 和 [run] 的限时都长得多。 */
        const val LONG_GRACE = 600
    }
}
