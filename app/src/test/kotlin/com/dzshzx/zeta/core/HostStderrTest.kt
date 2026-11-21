package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * zeta_host.sh 在 Claude 进程结束时把标准错误输出留一份到 `~/.cache/<ZETA_NAME>/stderr/`，诊断页从那里读。 真脚本跑在临时 HOME
 * 上，用一个假的 claude。
 */
class HostStderrTest {
    private lateinit var home: File
    private val host = File("src/main/assets/zeta_host.sh").absoluteFile
    private val name = AppVariant(AppVariant.RELEASE_PACKAGE + ".dev").linuxName

    @Before
    fun setUp() {
        assumeTrue("要 Linux 的 bash", File("/proc/self/cmdline").exists())
        home = Files.createTempDirectory("zeta-stderr").toFile().canonicalFile
    }

    @After
    fun tearDown() {
        if (!::home.isInitialized) return
        // 测试半路失败时还开着的替身（它们读输入一直不退）
        HostTesting.killHosts(home, name)
        home.deleteRecursively()
    }

    /** claude 的替身：只跑 [script]，不读输入。 */
    private fun fakeClaude(script: String) = HostTesting.writeClaudeStandIn(home, loop = script)

    /** 起一个对话进程（[HostTesting.run]：应用这头马上断开，进程自己跑完）。 */
    private fun start(key: String) {
        HostTesting.run(
            home,
            listOf("bash", host.path, "start", key, home.path, "t", "-p"),
            HostTesting.named(name),
        )
    }

    private val kept: File
        get() = File(home, ".cache/$name/stderr")

    /** 等 [key] 留下的那份副本（[waitUntil]）。 */
    private fun keptFor(key: String): File? {
        var found: File? = null
        waitUntil {
            kept.listFiles()?.firstOrNull { it.name.endsWith("-$key.log") }.also { found = it } !=
                null
        }
        return found
    }

    @Test
    fun stderrIsKeptWithTheExitCode() {
        fakeClaude("echo 'fatal: something broke' >&2; exit 3")
        start("k-err")
        val file = keptFor("k-err")
        assertTrue("应留下标准错误输出", file != null)
        val text = file!!.readText()
        assertTrue(text, text.startsWith("# zeta: claude 退出码 3，"))
        assertTrue(text, text.contains("fatal: something broke"))
        // 托管目录照常由下一次接上或 hosts 清理，这里只核对它不在 hosts 下面
        assertTrue(file.parentFile!!.path.endsWith("/.cache/$name/stderr"))
    }

    /** 接着的一方见到进程已结束：报一行 zeta_exit，带退出码和标准错误输出的最后一段（base64，引号、反斜杠、中文原样还原）。 */
    @Test
    fun exitReportCarriesCodeAndStderr() {
        fakeClaude("""printf '%s\n' '出错了："引号" \ 反斜杠' >&2; exit 3""")
        val started =
            HostTesting.run(
                home,
                listOf("bash", host.path, "start", "k-exit", home.path, "t", "-p"),
                HostTesting.named(name),
            )
        val dir = File(home, ".cache/$name/hosts/k-exit")
        // 起进程的那一头可能已经见到它结束、报过了；没报的话等它结束再接上一次
        val out =
            started.takeIf { "zeta_exit" in it }
                ?: run {
                    assertTrue("进程没有结束", waitUntil { File(dir, "exit").exists() })
                    HostTesting.run(
                        home,
                        listOf("bash", host.path, "attach", "k-exit", "1"),
                        HostTesting.named(name),
                    )
                }
        val report =
            out.lineSequence()
                .filter { it.startsWith("{") }
                .map { Json.parseToJsonElement(it).jsonObject }
                .single { it.str("type") == "zeta_exit" }
        assertEquals(3, report.int("code"))
        assertEquals("出错了：\"引号\" \\ 反斜杠\n", hostExitStderr(report))
        assertTrue("报完删掉托管目录", !dir.exists())
    }

    /**
     * 同一个标识刚结束又起了新进程（stop 后马上又 start）：旧进程的那段晚一步写的 exit 不落进新的托管目录，否则新进程会被当成已结束。 不靠时机：date
     * 换成等放行的替身，旧的那段留标准错误输出副本时停在那里，新进程起来以后才放行。
     */
    @Test
    fun aLateExitOfTheOldProcessStaysOutOfTheNewDirectory() {
        val gate = File(home, "go")
        val waiting = File(home, "at-gate")
        File(home, "bin/date").apply {
            parentFile!!.mkdirs()
            writeText(
                "#!/bin/bash\n: > '${waiting.path}'\n" +
                    "while [ ! -e '${gate.path}' ] && [ -d '${home.path}' ]; do sleep 0.05; done\n" +
                    "PATH=\${PATH#*:} exec date \"\$@\"\n",
            )
            setExecutable(true)
        }
        fakeClaude("echo boom >&2; " + HostTesting.READ_UNTIL_END)
        start("k")
        val dir = File(home, ".cache/$name/hosts/k")
        fun pid() = File(dir, "pid").readText().trim().toInt()
        val old = pid()
        val oldRun = HostTesting.parentOf(old)
        File(dir, "in").appendText(HostTesting.END_SESSION)
        assertTrue("旧进程的那段没停在留副本那一步", waitUntil { waiting.exists() })
        start("k")
        val new = pid()
        val newRun = HostTesting.parentOf(new)
        gate.writeText("")
        assertTrue("旧进程的那段没做完", waitUntil { !HostTesting.alive(oldRun) })
        assertFalse("旧进程的 exit 落进了新的托管目录", File(dir, "exit").exists())
        assertTrue(HostTesting.alive(new))
        // 收尾：新进程也结束，等它的那段做完再删临时目录
        File(dir, "in").appendText(HostTesting.END_SESSION)
        assertTrue("新进程的那段没做完", waitUntil { !HostTesting.alive(newRun) })
    }

    @Test
    fun exitStderrDropsAHalfCharacterAtTheCut() {
        val bytes = "好的".toByteArray()
        val cut = java.util.Base64.getEncoder().encodeToString(bytes.copyOfRange(1, bytes.size))
        assertEquals(
            "的",
            hostExitStderr(
                Json.parseToJsonElement("""{"type":"zeta_exit","code":1,"stderr_base64":"$cut"}""")
                    .jsonObject,
            ),
        )
        assertEquals(
            "",
            hostExitStderr(Json.parseToJsonElement("""{"type":"zeta_exit","code":1}""").jsonObject),
        )
    }

    @Test
    fun emptyStderrLeavesNothing() {
        fakeClaude("exit 0")
        start("k-quiet")
        // 再跑一个有输出的：它的那份出现时，前一个早就结束了
        fakeClaude("echo 'boom' >&2; exit 1")
        start("k-loud")
        assertTrue(keptFor("k-loud") != null)
        assertEquals(
            listOf("k-loud"),
            kept.listFiles().orEmpty().map {
                it.name.substringAfterLast("-k-").removeSuffix(".log").let { k -> "k-$k" }
            },
        )
    }

    @Test
    fun onlyTheLatestTwentyAreKept() {
        kept.mkdirs()
        val old = System.currentTimeMillis() - 3_600_000
        repeat(25) { i ->
            File(kept, "20000101-0000%02d-old$i.log".format(i)).apply {
                writeText("x")
                setLastModified(old + i * 1000L)
            }
        }
        fakeClaude("echo 'boom' >&2; exit 1")
        start("k-new")
        assertTrue(keptFor("k-new") != null)
        repeat(50) {
            if ((kept.listFiles()?.size ?: 0) <= 20) return@repeat
            Thread.sleep(100)
        }
        val names = kept.listFiles().orEmpty().map { it.name }
        assertEquals(20, names.size)
        assertTrue(names.any { it.endsWith("-k-new.log") })
        // 最旧的几份删掉了
        assertTrue(names.none { it.endsWith("-old0.log") || it.endsWith("-old5.log") })
    }
}
