package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 设备测试用的假 Claude（tools/fake-claude）怎么接进来：真的 zeta_host.sh 跑在临时 HOME 上。
 * 测试版（ZETA_NAME=zeta-dev）放了剧本才起假 Claude，正式版永远起真的 claude；剧本删掉后假 Claude 自己退出。
 * 假 Claude 的输出和真 Claude Code 对不对得上，见 FakeClaudeContractTest。
 */
class FakeClaudeHostTest {
    private lateinit var home: File
    private val host = File("src/main/assets/zeta_host.sh").absoluteFile
    private val fake = File("../tools/fake-claude/fake-claude.sh").absoluteFile
    private val release = AppVariant(AppVariant.RELEASE_PACKAGE).linuxName
    private val dev = AppVariant(AppVariant.RELEASE_PACKAGE + ".dev").linuxName
    private val fakeDir get() = File(home, ".config/zeta-dev/fake-claude")

    @Before
    fun setUp() {
        assumeTrue("要 Linux 的 /proc 和 bash、jq", File("/proc/self/cmdline").exists() && onPath("jq"))
        home = Files.createTempDirectory("zeta-fake").toFile().canonicalFile
        // 真的 claude 用一个一直读标准输入的替身代替
        File(home, "bin/claude").apply {
            parentFile!!.mkdirs()
            writeText("#!/bin/bash\nwhile read -r line; do :; done\n")
            setExecutable(true)
        }
    }

    @After
    fun tearDown() {
        if (!::home.isInitialized) return
        for ((name, key) in listOf(dev to "k-dev", release to "k-release", null to "k-old")) runCatching { run(name, "stop", key) }
        home.deleteRecursively()
    }

    private fun onPath(command: String) = System.getenv("PATH").orEmpty().split(':').any { File(it, command).canExecute() }

    /** 和 vd.py --dev fake 一样：脚本和剧本放进测试版的目录。 */
    private fun installFake() {
        fakeDir.mkdirs()
        fake.copyTo(File(fakeDir, "fake-claude.sh")).setExecutable(true)
        File(fakeDir, "scenario.jsonl").writeText("""{"steps":[{"text":"剧本里的回答","delay":0}]}""" + "\n")
    }

    /** 以 [name] 作 ZETA_NAME（null 表示不设）跑 zeta_host.sh，标准输入立即关掉：进程照样在后台跑。 */
    private fun run(name: String?, vararg arguments: String): String {
        val builder = ProcessBuilder(listOf("bash", host.path) + arguments).directory(home).redirectErrorStream(true)
        builder.environment().apply {
            clear()
            put("HOME", home.path)
            put("PATH", "${home.path}/bin:" + System.getenv("PATH"))
            put("LANG", "C.UTF-8")
            put("ZETA_IDLE_STEP", "1")
            if (name != null) put(AppVariant.LINUX_NAME_ENV, name)
        }
        val process = builder.start()
        process.outputStream.close()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue("命令超时：${arguments.toList()}", process.waitFor(30, TimeUnit.SECONDS))
        return out
    }

    private fun start(name: String?, key: String) =
        run(name, "start", key, home.path, "t", "-p", "--input-format", "stream-json", "--output-format", "stream-json")

    private fun hostDir(name: String?, key: String) = File(home, ".cache/${name ?: release}/hosts/$key")

    private fun pid(name: String?, key: String): Int = File(hostDir(name, key), "pid").readText().trim().toInt()

    private fun commandLine(pid: Int): String = File("/proc/$pid/cmdline").readText().replace('\u0000', ' ')

    private fun waitUntil(seconds: Int, condition: () -> Boolean): Boolean {
        repeat(seconds * 10) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return false
    }

    @Test
    fun devRunsTheFakeWhileAScenarioIsInPlace() {
        installFake()
        start(dev, "k-dev")
        val pid = pid(dev, "k-dev")
        assertTrue(commandLine(pid), commandLine(pid).contains("fake-claude/fake-claude.sh"))

        // 像 Zeta 一样往管道里写一条用户消息，照剧本答完一轮
        File(hostDir(dev, "k-dev"), "in").writeText(
            """{"type":"user","uuid":"0a1b2c3d-0000-4000-8000-000000000001","message":{"role":"user","content":[{"type":"text","text":"hi"}]}}""" + "\n",
        )
        val out = File(hostDir(dev, "k-dev"), "out.jsonl")
        assertTrue("没等到这一轮结束", waitUntil(20) { out.readText().contains("\"type\":\"result\"") })
        val text = out.readText()
        assertTrue(text, text.contains("\"fake_claude\":true") && text.contains("剧本里的回答") && text.contains("\"state\":\"completed\""))

        // 剧本撤下：闲着的假 Claude 自己退出，之后测试版起的就是真的 claude
        fakeDir.deleteRecursively()
        assertTrue("剧本删了，假 Claude 还在", waitUntil(10) { !File("/proc/$pid").exists() })
        start(dev, "k-dev")
        assertFalse(commandLine(pid(dev, "k-dev")).contains("fake-claude"))
    }

    @Test
    fun releaseAlwaysRunsTheRealClaude() {
        installFake()
        start(release, "k-release")
        start(null, "k-old")
        for ((name, key) in listOf(release to "k-release", null to "k-old")) {
            val line = commandLine(pid(name, key))
            assertTrue(line, line.contains("${home.path}/bin/claude") && !line.contains("fake-claude"))
        }
    }
}
