package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.HostTesting.alive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * zeta_host.sh stop 怎么结束会话（end_claude）：先发 end_session 让 Claude Code 自己结束，不退才 SIGTERM、SIGKILL；
 * 整个在单独的会话里做，stop 立即返回。真的 zeta_host.sh 跑在临时 HOME 上，claude 换成替身，宽限期设成 [GRACE] 秒。
 */
class HostStopTest {
    private lateinit var home: File
    private val host = File("src/main/assets/zeta_host.sh").absoluteFile
    private val name = AppVariant(AppVariant.RELEASE_PACKAGE + ".dev").linuxName
    private val key = "k-stop"
    private val hostDir
        get() = File(home, ".cache/$name/hosts/$key")

    @Before
    fun setUp() {
        assumeTrue("要 Linux 的 /proc 和 bash", File("/proc/self/cmdline").exists())
        home = Files.createTempDirectory("zeta-stop").toFile().canonicalFile
    }

    @After
    fun tearDown() {
        if (!::home.isInitialized) return
        runCatching { File(hostDir, "pid").readText().trim().toInt() }
            .getOrNull()
            ?.let { pid ->
                ProcessBuilder("kill", "-9", "$pid").start().waitFor(5, TimeUnit.SECONDS)
            }
        HostTesting.killRegistered(home)
        home.deleteRecursively()
    }

    /** 跑 zeta_host.sh 时另加的环境变量。 */
    private var extraEnv = emptyMap<String, String>()

    /** 跑 zeta_host.sh（[HostTesting.run]），返回用了多少毫秒。 */
    private fun run(vararg arguments: String): Long {
        val started = System.currentTimeMillis()
        HostTesting.run(
            home,
            listOf("bash", host.path) + arguments,
            HostTesting.named(name) + ("ZETA_STOP_GRACE" to "$GRACE") + extraEnv,
        )
        return System.currentTimeMillis() - started
    }

    private fun start(): Int {
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
        return File(hostDir, "pid").readText().trim().toInt()
    }

    /** 替身收到 end_session 就退出、SIGTERM 它不理：宽限期还没到就没了，走的是 end_session 而不是信号。 */
    @Test
    fun stopAsksClaudeCodeToEndTheSession() {
        HostTesting.writeClaudeStandIn(home, prelude = "trap '' TERM")
        val pid = start()
        val took = run("stop", key)
        assertTrue("stop 要立即返回（$took 毫秒）", took < 1_500)
        assertTrue("替身收到 end_session 后没退出", waitUntil(GRACE - 1) { !alive(pid) })
        assertTrue("托管目录没删", waitUntil(5) { !hostDir.exists() })
    }

    /** 不理 end_session 的（卡住了）：stop 照样立即返回，宽限期过后在后台 SIGTERM 收掉，托管目录删掉。 */
    @Test
    fun stuckProcessIsTerminatedInTheBackground() {
        HostTesting.writeClaudeStandIn(home, loop = HostTesting.READ_FOREVER)
        val pid = start()
        val took = run("stop", key)
        assertTrue("stop 要立即返回（$took 毫秒）", took < 1_500)
        assertTrue("还没到点就收掉了", alive(pid) && hostDir.exists())
        assertTrue("卡住的进程没被收掉", waitUntil(GRACE + 5) { !alive(pid) })
        assertTrue("托管目录没删", waitUntil(5) { !hostDir.exists() })
    }

    /**
     * 登录配置把 claude 包了一层（shell 函数或不 exec 的脚本）：pid 文件记的是外面那层，Claude Code 身上带着托管目录的记号 ZETA_HOST。 卡住的
     * Claude Code 收 SIGTERM 时，按登记和记号找到的它也一起收到，不会只杀外面那层、把它留成没人管的孤儿。
     */
    @Test
    fun aStuckWrappedClaudeIsTerminatedNotOrphaned() {
        for (wrapper in HostTesting.Wrapper.entries) {
            File(home, ".claude/sessions").deleteRecursively()
            HostTesting.writeClaudeStandIn(
                home,
                prelude = HostTesting.registersAs("s"),
                loop = HostTesting.READ_FOREVER,
            )
            extraEnv = HostTesting.wrapClaude(home, wrapper)
            val outer = start()
            val claude = HostTesting.registeredPid(home)
            assertTrue("$wrapper：pid 文件记的该是外面那层", outer != claude)
            assertEquals("$wrapper：记号", "$name/$key", HostTesting.envOf(claude, "ZETA_HOST"))
            run("stop", key)
            assertTrue("$wrapper：Claude Code 没被收掉", waitUntil(GRACE + 5) { !alive(claude) })
            assertTrue("$wrapper：托管目录没删", waitUntil(5) { !hostDir.exists() })
        }
    }

    private companion object {
        const val GRACE = 3
    }
}
