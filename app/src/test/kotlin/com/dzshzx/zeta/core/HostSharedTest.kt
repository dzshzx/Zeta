package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.HostTesting.alive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 同一个包的几个客户端同时接着同一个托管进程（zeta_host.sh）：第二个客户端 start 同一个会话时接上现成的进程、不另起；
 * 每个接着的一方都看得到全部输出、都能写输入，几个写的一方的大行整行到达； 一个离开只断开它自己，最后一个离开且闲着时才结束；进程退出时每个接着的一方都收到 zeta_exit。 真的
 * zeta_host.sh 跑在临时 HOME 上，claude 换成替身。
 */
class HostSharedTest {
    private lateinit var home: File
    private val host = File("../helper/host/zeta_host.sh").absoluteFile
    private val name = AppVariant(AppVariant.RELEASE_PACKAGE + ".dev").linuxName
    private val clients = java.util.Collections.synchronizedList(mutableListOf<Client>())
    private val json = Json { ignoreUnknownKeys = true }

    private val got
        get() = File(home, "got")

    private val procs
        get() = File(home, "procs")

    @Before
    fun setUp() {
        assumeTrue("要 Linux 的 /proc 和 bash", File("/proc/self/cmdline").exists())
        home = Files.createTempDirectory("zeta-shared").toFile().canonicalFile
        // 替身：起来时在 procs 里记下自己；输入的每一行原样追加到 got，再输出一行回声（第几行）；读到 end_session 退出
        HostTesting.writeClaudeStandIn(
            home,
            prelude = "mkdir -p \"\$HOME/procs\"; : > \"\$HOME/procs/\$\$\"; n=0",
            loop =
                "while IFS= read -r line; do case \$line in *'\"subtype\":\"end_session\"'*) exit 0 ;; esac; " +
                    "printf '%s\\n' \"\$line\" >> \"\$HOME/got\"; n=\$((n + 1)); " +
                    "printf '{\"type\":\"echo\",\"n\":%d}\\n' \"\$n\"; done",
        )
    }

    @After
    fun tearDown() {
        if (!::home.isInitialized) return
        clients.forEach { it.process.destroyForcibly() }
        HostTesting.killHosts(home, name)
        home.deleteRecursively()
    }

    private fun hostDir(key: String) = File(home, ".cache/$name/hosts/$key")

    private fun command(vararg arguments: String) = listOf("bash", host.path) + arguments

    /** 接着托管进程的一个客户端：标准输入一直开着，标准输出一行行收进 [lines]。 */
    private inner class Client(arguments: List<String>, env: Map<String, String>) {
        val process: Process =
            HostTesting.builder(
                    home,
                    command(*arguments.toTypedArray()),
                    HostTesting.named(name) + env,
                )
                .start()
                .also { clients += this }
        val lines = LinkedBlockingQueue<JsonObject>()

        init {
            thread(isDaemon = true) {
                process.inputStream.bufferedReader().forEachLine { line ->
                    runCatching { json.parseToJsonElement(line) as? JsonObject }
                        .getOrNull()
                        ?.let(lines::put)
                }
            }
        }

        /** 托管脚本报的 zeta_attached。 */
        val attached: JsonObject = next { it.type == "zeta_attached" }

        val key: String
            get() = attached["key"]!!.jsonPrimitive.content

        val attacher: Int
            get() = attached["attacher"]!!.jsonPrimitive.int

        /** 等到第一行合 [match] 的（一分钟内，只防卡死）。 */
        fun next(match: (JsonObject) -> Boolean): JsonObject {
            val deadline = System.currentTimeMillis() + 60_000
            while (true) {
                val left = deadline - System.currentTimeMillis()
                assertTrue("一直没等到", left > 0)
                val line = lines.poll(left, TimeUnit.MILLISECONDS) ?: continue
                if (match(line)) return line
            }
        }

        fun send(line: String) {
            process.outputStream.write((line + "\n").toByteArray())
            process.outputStream.flush()
        }

        /** 应用这头断开（标准输入关掉），等托管脚本这一方退出。 */
        fun leave() {
            process.outputStream.close()
            assertTrue("接着的一方没退出", process.waitFor(60, TimeUnit.SECONDS))
        }
    }

    private val JsonObject.type: String?
        get() = this["type"]?.jsonPrimitive?.content

    private fun start(key: String, vararg extra: String, env: Map<String, String> = emptyMap()) =
        Client(listOf("start", key, home.path, "t", "-p", "--resume", SESSION) + extra, env)

    private fun attach(key: String, env: Map<String, String> = emptyMap()) =
        Client(listOf("attach", key, "1"), env)

    /** zeta_host.sh detach：除了接着的一方 [attacher] 还有别人接着时输出 ended=0，没有时结束进程、输出 ended=1。 */
    private fun detach(key: String, attacher: Int) =
        HostTesting.run(home, command("detach", key, "$attacher"), HostTesting.named(name)).trim()

    private fun pidOf(key: String) = File(hostDir(key), "pid").readText().trim().toInt()

    private fun processCount() = procs.list().orEmpty().size

    /** 第二个、第三个客户端 start 同一个会话：接上第一个起的进程，不另起；分支出来的是新会话，照样起新进程。 */
    @Test
    fun startingARunningSessionAgainJoinsItsProcess() {
        val a = start("kA")
        assertEquals("kA", a.key)
        assertTrue(waitUntil { processCount() == 1 })
        // 这时替身还没输出：按 start 写的 session 认出会话。B 发的消息有了回声时，B 要是另起了进程，那个替身早已记下自己
        val b = start("kB")
        b.send("""{"type":"user","from":"b"}""")
        b.next { it.type == "echo" }
        assertEquals("进程数", 1, processCount())
        assertEquals("接上的该是 A 起的进程", "kA", b.key)
        assertFalse("不该另建托管目录", hostDir("kB").exists())
        assertEquals("A 也看得到", 1, a.next { it.type == "echo" }["n"]!!.jsonPrimitive.int)
        // 有了输出以后以输出里的会话为准（旧版脚本起的目录没有 session 文件）
        File(hostDir("kA"), "out.jsonl")
            .appendText("{\"type\":\"system\",\"session_id\":\"$SESSION\"}\n")
        File(hostDir("kA"), "session").delete()
        val c = start("kC")
        assertEquals("kA", c.key)
        assertEquals("进程数", 1, processCount())
        // 接上时从现有输出的下一行读起（回声、system 两行之后）：之前的内容应用从会话记录里读
        assertEquals(3, c.attached["from"]!!.jsonPrimitive.int)
        start("kD", "--fork-session")
        assertTrue("分支该起新进程", waitUntil { processCount() == 2 } && hostDir("kD").exists())
    }

    /** 两个客户端同时 start 同一个会话：start 找现成的和起新的之间持着锁，只起一个进程。 */
    @Test
    fun twoStartsAtOnceStartOneProcess() {
        val go = CountDownLatch(1)
        val started =
            (1..2).map { i ->
                thread {
                    go.await()
                    start("k$i")
                }
            }
        go.countDown()
        started.forEach { it.join(60_000) }
        val keys = clients.map { it.key }.toSet()
        assertEquals("两个都该接着同一个：$keys", 1, keys.size)
        assertEquals("进程数", 1, processCount())
    }

    /** 两个接着的一方都看得到全部输出，谁写的输入都到 Claude Code。 */
    @Test
    fun everyAttacherSeesAllOutput() {
        val a = start("kA")
        val b = attach("kA")
        a.send("""{"type":"user","from":"a"}""")
        for (c in listOf(a, b)) assertEquals(
            1,
            c.next { it.type == "echo" }["n"]!!.jsonPrimitive.int,
        )
        b.send("""{"type":"user","from":"b"}""")
        for (c in listOf(a, b)) assertEquals(
            2,
            c.next { it.type == "echo" }["n"]!!.jsonPrimitive.int,
        )
    }

    /** 两个写的一方同时各写几条比管道缓冲（64 KB）大得多的行：Claude Code 收到的每一行都是完整的一条，一条不少。 */
    @Test
    fun bigLinesFromTwoWritersArriveWhole() {
        val a = start("kA")
        val b = attach("kA")
        val each = 4
        val go = CountDownLatch(1)
        val writers =
            listOf(a to "a", b to "b").map { (client, who) ->
                thread {
                    go.await()
                    repeat(each) { i ->
                        val payload = who.repeat(BIG_LINE) + i
                        client.send("""{"type":"user","from":"$who","i":$i,"data":"$payload"}""")
                    }
                }
            }
        go.countDown()
        writers.forEach { it.join(60_000) }
        assertTrue("没收齐", waitUntil { got.exists() && got.readLines().size >= 2 * each })
        val received =
            got.readLines().map { line ->
                val o = runCatching { json.parseToJsonElement(line) as JsonObject }.getOrNull()
                assertNotNull("插断了的行（${line.length} 字节）", o)
                o!!["from"]!!.jsonPrimitive.content + o["i"]!!.jsonPrimitive.int
            }
        assertEquals(
            (0 until each).flatMap { listOf("a$it", "b$it") }.sorted(),
            received.sorted(),
        )
    }

    /**
     * 一个离开（应用断开、detach）只断开它自己：还有人接着时 detach 什么也不做，看门狗也不算它闲着； 最后一个离开后闲满上限，看门狗才结束它。
     * 「不算闲着」不靠多等几秒：sleep 换成记账的替身，看门狗每轮在 rounds 里记一行它的托管目录，等轮数超过闲置上限再断言。
     */
    @Test
    fun theProcessStaysWhileAnyoneIsAttached() {
        val rounds = File(home, "rounds")
        File(home, "bin/sleep").apply {
            writeText(
                "#!/bin/bash\n" +
                    "[ \"\$1\" = 1 ] && tr '\\0' '\\n' < /proc/\$PPID/cmdline | grep -m1 -x '/.*/hosts/[^/]*' >> '${rounds.path}'\n" +
                    "PATH=\${PATH#*:} exec sleep \"\$@\"\n",
            )
            setExecutable(true)
        }
        val env = mapOf("ZETA_IDLE_LIMIT" to "$IDLE_LIMIT")
        val a = start("kA", env = env)
        val b = attach("kA", env = env)
        val pid = pidOf("kA")
        a.leave()
        // detach 看到还有 B 接着就直接返回，不发 end_session、不起结束的那段
        assertEquals("ended=0", detach("kA", a.attacher))
        assertTrue("A 一走进程就没了", alive(pid))
        fun seen() =
            rounds
                .takeIf { it.exists() }
                ?.readLines()
                .orEmpty()
                .count {
                    it == hostDir("kA").path
                }
        val before = seen()
        waitUntil { !alive(pid) || seen() >= before + IDLE_LIMIT + 2 }
        assertTrue("还有人接着时被看门狗结束了", alive(pid))
        b.leave()
        assertTrue("最后一个离开后闲着也没结束", waitUntil { !alive(pid) })
    }

    /**
     * 看门狗每轮读这台主机设的闲置上限（~/.config/<包>/idle-minutes，设置页经核心写）：它压过测试用的 ZETA_IDLE_LIMIT，
     * 没人接着、闲着满这么多轮就结束进程。
     */
    @Test
    fun theWatchdogUsesTheHostsIdleLimit() {
        File(home, ".config/$name/idle-minutes").apply {
            parentFile.mkdirs()
            writeText("2\n")
        }
        val a = start("kA", env = mapOf("ZETA_IDLE_LIMIT" to "1000"))
        val pid = pidOf("kA")
        a.leave()
        assertTrue("按这台主机设的 2 轮，闲着没人接着却一直没结束", waitUntil { !alive(pid) })
    }

    /** detach 时没有别人接着：和 stop 一样结束进程、删掉托管目录。 */
    @Test
    fun detachingTheLastAttacherEndsTheProcess() {
        val a = start("kA")
        val pid = pidOf("kA")
        val attacher = a.attacher
        a.leave()
        assertEquals("ended=1", detach("kA", attacher))
        assertTrue("没结束", waitUntil { !alive(pid) && !hostDir("kA").exists() })
    }

    /** 旧版脚本接着时只写 attach、不写 attach.d：它记的进程还在、命令行里有 zeta-host 就算还接着，detach 不结束进程； 那个进程不在了以后不算。 */
    @Test
    fun aLegacyAttacherKeepsTheProcess() {
        val a = start("kA")
        val pid = pidOf("kA")
        val attacher = a.attacher
        a.leave()
        // 扮旧版接着的一方：命令行和旧版脚本一样是 bash -c <脚本> zeta-host attach …，一直读着输入
        val old =
            HostTesting.builder(
                    home,
                    listOf("bash", "-c", "read -r _", "zeta-host", "attach", "kA", "1"),
                    HostTesting.named(name),
                )
                .start()
        try {
            File(hostDir("kA"), "attach").writeText("${old.pid()}\n")
            assertEquals("旧版接着时被结束了", "ended=0", detach("kA", attacher))
        } finally {
            old.destroyForcibly().waitFor()
        }
        assertEquals("ended=1", detach("kA", attacher))
        assertTrue("旧版走了以后没结束", waitUntil { !alive(pid) && !hostDir("kA").exists() })
    }

    /** 进程退出时每个接着的一方都收到正常的 zeta_exit（目录留到最后一个读完才删），之后托管目录删掉。 */
    @Test
    fun everyAttacherGetsTheExitReport() {
        val a = start("kA")
        val b = attach("kA")
        val c = attach("kA")
        b.send(HostTesting.END_SESSION.trim())
        for (client in listOf(a, b, c)) {
            val exit = client.next { it.type == "zeta_exit" }
            assertEquals(0, exit["code"]!!.jsonPrimitive.int)
        }
        assertTrue("托管目录没删", waitUntil { !hostDir("kA").exists() })
    }

    /**
     * Claude Code 用客户端带来的 ZETA_LANG（连进来时那边的 LANG），主机上没装这个语言环境时用 C.UTF-8；托管脚本自己还是 C.UTF-8。 locale
     * 换成替身，只列 C 和 zh_CN.utf8，结果不看这台机器装了什么。
     */
    @Test
    fun claudeGetsTheHostLanguageWhenItIsInstalled() {
        File(home, "bin/locale").apply {
            writeText(
                "#!/bin/sh\n[ \"\$1\" = -a ] && printf 'C\\nC.utf8\\nPOSIX\\nzh_CN.utf8\\n'\n",
            )
            setExecutable(true)
        }
        // 同一个会话再 start 会接上已有的进程：每种情况用自己的会话
        fun langOf(key: String, lang: String): String {
            Client(
                listOf("start", key, home.path, "t", "-p", "--resume", SESSION.dropLast(2) + key),
                mapOf("ZETA_LANG" to lang),
            )
            return File("/proc/${pidOf(key)}/environ")
                .readText()
                .split('\u0000')
                .single { it.startsWith("LANG=") }
                .removePrefix("LANG=")
        }
        assertEquals("zh_CN.UTF-8", langOf("kA", "zh_CN.UTF-8"))
        assertEquals("没装的语言环境", "C.UTF-8", langOf("kB", "fr_FR.UTF-8"))
        assertEquals("C.UTF-8", langOf("kC", "C"))
    }

    private companion object {
        const val SESSION = "aaaaaaaa-1111-4222-8333-555555555555"

        /** 大行的长度（字节）：比管道缓冲（64 KB）大得多。 */
        const val BIG_LINE = 300_000

        /** 看门狗测试里的闲置上限（ZETA_IDLE_LIMIT，轮）。 */
        const val IDLE_LIMIT = 2
    }
}
