package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 正式版和测试版共用同一个 Linux 用户：真的 zeta_host.sh 和 zeta_helper.py 跑在临时 HOME 上，
 * 用一个假的 claude，确认两个包的后台对话各在各的目录，互相看不到、结束不了对方的。
 */
class HostIsolationTest {
    private lateinit var home: File
    private val host = File("src/main/assets/zeta_host.sh").absoluteFile
    private val helper = File("src/main/assets/zeta_helper.py").absoluteFile
    private val release = AppVariant(AppVariant.RELEASE_PACKAGE).linuxName
    private val dev = AppVariant(AppVariant.RELEASE_PACKAGE + ".dev").linuxName

    @Before
    fun setUp() {
        assumeTrue("要 Linux 的 /proc 和 bash、python3", File("/proc/self/cmdline").exists())
        home = Files.createTempDirectory("zeta-hosts").toFile().canonicalFile
        // 假的 claude：一直读标准输入（宿主脚本的 FIFO），命令行里带 claude，助手脚本才认它是活的
        File(home, "bin/claude").apply {
            parentFile!!.mkdirs()
            writeText("#!/bin/bash\nwhile read -r line; do :; done\n")
            setExecutable(true)
        }
    }

    @After
    fun tearDown() {
        if (!::home.isInitialized) return
        runCatching { run(null, "bash", host.path, "stop", "k-release") }
        runCatching { run(dev, "bash", host.path, "stop", "k-dev") }
        home.deleteRecursively()
    }

    /** 以 [name] 作 ZETA_NAME（null 表示不设，和旧版正式版一样）跑一条命令，标准输入立即关掉。 */
    private fun run(name: String?, vararg command: String): String {
        val builder = ProcessBuilder(*command).directory(home).redirectErrorStream(true)
        builder.environment().apply {
            clear()
            put("HOME", home.path)
            put("PATH", "${home.path}/bin:" + System.getenv("PATH"))
            put("LANG", "C.UTF-8")
            // 看门狗每秒看一次，测完它很快自己退出，不留后台的 sleep
            put("ZETA_IDLE_STEP", "1")
            if (name != null) put(AppVariant.LINUX_NAME_ENV, name)
        }
        val process = builder.start()
        process.outputStream.close()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue("命令超时：${command.toList()}", process.waitFor(30, TimeUnit.SECONDS))
        return out
    }

    private fun start(name: String?, key: String) = run(name, "bash", host.path, "start", key, home.path, "t", "-p")

    private fun hosts(name: String?): List<String> = run(name, "python3", "-I", helper.path, "hosts").lineSequence()
        .filter { it.startsWith("{") }
        .map { Json.parseToJsonElement(it).jsonObject["key"]!!.jsonPrimitive.content }
        .toList()

    private fun pid(name: String, key: String): Int = File(home, ".cache/$name/hosts/$key/pid").readText().trim().toInt()

    private fun alive(pid: Int) = File("/proc/$pid").exists()

    private fun gone(pid: Int): Boolean {
        repeat(50) {
            if (!alive(pid)) return true
            Thread.sleep(100)
        }
        return false
    }

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
        File(home, ".cache/$release/hosts/k-release/out.jsonl").appendText(
            """{"type":"system","subtype":"init","cwd":"/p","session_id":"$session","model":"claude-opus-5-5","permissionMode":"default"}""" + "\n",
        )
        val transcript = File(home, ".claude/projects/-p/$session.jsonl").apply { parentFile!!.mkdirs() }
        val user = """{"type":"user","message":{"role":"user","content":"第一句话"},"cwd":"/p"}"""
        fun title(): String = run(release, "python3", "-I", helper.path, "hosts").lineSequence()
            .filter { it.startsWith("{") }
            .map { Json.parseToJsonElement(it).jsonObject["title"]!!.jsonPrimitive.content }
            .single()

        transcript.writeText(
            user + "\n" + """{"type":"custom-title","customTitle":"改过的"}""" + "\n" + """{"type":"ai-title","aiTitle":"自动的"}""" + "\n",
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
