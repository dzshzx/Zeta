package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.HostTesting.alive
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 正式版和测试版共用同一个 Linux 用户：真的 zeta_host.sh 和 zeta-helper 跑在临时 HOME 上， 用一个假的
 * claude，确认两个包的后台对话各在各的目录，互相看不到、结束不了对方的。
 */
class HostIsolationTest {
    private lateinit var home: File
    private val host = File("../helper/host/zeta_host.sh").absoluteFile
    private val release = AppVariant(AppVariant.RELEASE_PACKAGE).linuxName
    private val dev = AppVariant(AppVariant.RELEASE_PACKAGE + ".dev").linuxName

    @Before
    fun setUp() {
        TestDeps.requireLinux()
        home = Files.createTempDirectory("zeta-hosts").toFile().canonicalFile
        HostTesting.writeClaudeStandIn(home)
    }

    @After
    fun tearDown() {
        if (!::home.isInitialized) return
        removeTestDir(home)
    }

    /** 以 [name] 作 ZETA_NAME（null 表示不设，和旧版正式版一样）跑一条命令（[HostTesting.run]）。 */
    private fun run(name: String?, vararg command: String): String =
        HostTesting.run(home, command.toList(), HostTesting.named(name))

    private fun start(name: String?, key: String) =
        run(name, "bash", host.path, "start", key, home.path, "t", "-p")

    private fun hosts(name: String?): List<String> =
        run(name, TestHelper.path, "hosts")
            .lineSequence()
            .filter { it.startsWith("{") }
            .map { Json.parseToJsonElement(it).jsonObject["key"]!!.jsonPrimitive.content }
            .toList()

    private fun pid(name: String, key: String): Int =
        File(home, ".cache/$name/hosts/$key/pid").readText().trim().toInt()

    /** 等进程 [pid] 结束，上限只防卡死。 */
    private fun gone(pid: Int): Boolean = waitUntil { !alive(pid) }

    @Test
    fun hostsStayWithTheirOwnPackage() {
        // 旧版正式版不传 ZETA_NAME：落在原来的 ~/.cache/zeta，新版正式版（传 zeta）照样接得上
        start(null, "k-release")
        start(dev, "k-dev")
        val releasePid = pid(release, "k-release")
        val devPid = pid(dev, "k-dev")
        assertTrue(alive(releasePid) && alive(devPid))
        assertFalse(File(home, ".cache/$release/hosts/k-dev").exists())
        assertFalse(File(home, ".cache/$dev/hosts/k-release").exists())

        assertEquals(listOf("k-release"), hosts(release))
        assertEquals(listOf("k-dev"), hosts(dev))

        // 测试版结束不了正式版的对话：同一个标识在它自己的目录里不存在
        run(dev, "bash", host.path, "stop", "k-release")
        assertTrue(alive(releasePid))
        assertEquals(listOf("k-release"), hosts(release))

        run(dev, "bash", host.path, "stop", "k-dev")
        assertTrue(gone(devPid))
        assertEquals(emptyList<String>(), hosts(dev))
        assertTrue(alive(releasePid))

        run(release, "bash", host.path, "stop", "k-release")
        assertTrue(gone(releasePid))
    }

    /** 接回后台对话时的标题：会话记录里改过的名字压过自动起的，都没有用启动时记下的，再没有用开头那句。 */
    @Test
    fun hostTitleComesFromTranscript() {
        start(release, "k-release")
        val session = "0a1b2c3d-0000-4000-8000-000000000001"
        // 会话号从 Claude Code 的输出里认：zeta_host.sh 把它的标准输出逐行追加进 out.jsonl，一轮以 system init 开头
        File(home, ".cache/$release/hosts/k-release/out.jsonl")
            .appendText(
                """{"type":"system","subtype":"init","cwd":"/p","session_id":"$session","model":"claude-opus-5-5","permissionMode":"default"}""" +
                    "\n",
            )
        val transcript =
            File(home, ".claude/projects/-p/$session.jsonl").apply { parentFile!!.mkdirs() }
        val user = """{"type":"user","message":{"role":"user","content":"第一句话"},"cwd":"/p"}"""
        fun title(): String =
            run(release, TestHelper.path, "hosts")
                .lineSequence()
                .filter { it.startsWith("{") }
                .map { Json.parseToJsonElement(it).jsonObject["title"]!!.jsonPrimitive.content }
                .single()

        transcript.writeText(
            user +
                "\n" +
                """{"type":"custom-title","customTitle":"改过的"}""" +
                "\n" +
                """{"type":"ai-title","aiTitle":"自动的"}""" +
                "\n",
        )
        assertEquals("改过的", title())
        transcript.writeText(user + "\n" + """{"type":"ai-title","aiTitle":"自动的"}""" + "\n")
        assertEquals("自动的", title())
        transcript.writeText(user + "\n")
        assertEquals("t", title())
        File(home, ".cache/$release/hosts/k-release/title").writeText("")
        assertEquals("第一句话", title())
    }
}
