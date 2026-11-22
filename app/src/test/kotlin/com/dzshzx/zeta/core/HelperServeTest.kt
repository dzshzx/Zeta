package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HelperServe] 对着真的 `zeta-helper serve`（本机架构那份，装进临时 HOME）：连上先订阅、收到整份，会话目录变了推来变化；
 * 那边的进程没了就重连、重新订阅；第一次就连不上的不重试；close 结束那边的进程。
 */
class HelperServeTest {
    private val dir = Files.createTempDirectory("zeta-serve").toFile().canonicalFile

    private val shell =
        object : LinuxShell {
            override val home: String = dir.path

            override fun start(command: String): Process =
                ProcessBuilder("bash", "-c", command)
                    .directory(dir)
                    .apply {
                        environment()["HOME"] = dir.path
                        environment()[AppVariant.LINUX_NAME_ENV] = "zeta"
                    }
                    .start()

            // serve 的命令行以 exec 起 zeta-helper，进程就是它本身
            override fun kill(process: Process) = process.destroy()
        }

    private val program = TestHelper.program(HelperProgram.ZETA_HELPER)
    private val messages = LinkedBlockingQueue<JsonObject>()

    /** 每次 [HelperServe] 报的：连上是空串，断开、连不上是原因。 */
    private val reports = LinkedBlockingQueue<String>()
    private val opened = LinkedBlockingQueue<Process>()

    /** 每次连上收到的招呼（zeta_hello）。 */
    private val hellos = LinkedBlockingQueue<JsonObject>()
    private val serves = ArrayList<HelperServe>()

    @After
    fun cleanup() {
        serves.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun serve(open: () -> HelperServe.Opened = ::openServe): HelperServe =
        HelperServe(
                name = "test",
                open = open,
                subscriptions = listOf("sessions.watch"),
                onMessage = messages::put,
                onProblem = { reports.put(it.orEmpty()) },
                onHello = hellos::put,
                backoff = { 0L },
            )
            .also { serves += it }

    private fun openServe(): HelperServe.Opened =
        HelperServe.open(shell, program).also { opened.put(it.process) }

    private fun <T> LinkedBlockingQueue<T>.next(): T =
        checkNotNull(poll(20, TimeUnit.SECONDS)) { "20 秒内没等到" }

    private fun sessions(): JsonObject {
        while (true) {
            val m = messages.next()
            if (m.str("type") == "zeta_sessions") return m
        }
    }

    private fun ids(m: JsonObject): List<String?> =
        m.arr("upsert").orEmpty().map { it.jsonObject.str("id") }

    private fun writeSession(id: String) {
        val file = File(dir, ".claude/projects/-w/$id.jsonl")
        file.parentFile.mkdirs()
        file.writeText("""{"type":"user","cwd":"/w","message":{"content":"你好"}}""" + "\n")
    }

    @Test
    fun subscribesAndReceivesChanges() {
        writeSession(A)
        serve()
        assertEquals("", reports.next())
        // 招呼里带主机名（os.Hostname，Linux 上读的就是这个文件）
        assertEquals(
            File("/proc/sys/kernel/hostname").readText().trim(),
            hellos.next().str("hostname"),
        )
        val full = sessions()
        assertEquals(true, full.bool("full"))
        assertEquals(listOf(A), ids(full))
        writeSession(B)
        val pushed = sessions()
        assertEquals(false, pushed.bool("full"))
        assertEquals(listOf(B), ids(pushed))
    }

    @Test
    fun reconnectsAndResubscribesWhenTheServerGoes() {
        writeSession(A)
        serve()
        assertEquals("", reports.next())
        assertEquals(listOf(A), ids(sessions()))
        shell.kill(opened.next())
        assertNotEquals("", reports.next())
        assertEquals("", reports.next())
        val again = sessions()
        assertEquals(true, again.bool("full"))
        assertEquals(listOf(A), ids(again))
    }

    @Test
    fun firstFailureIsReportedOnce() {
        val tries = AtomicInteger()
        serve {
            tries.incrementAndGet()
            error("还没有可用的 Linux 环境")
        }
        assertEquals("还没有可用的 Linux 环境", reports.next())
        assertNull(reports.poll(500, TimeUnit.MILLISECONDS))
        assertEquals(1, tries.get())
    }

    @Test
    fun closeEndsTheServer() {
        val s = serve()
        assertEquals("", reports.next())
        val process = opened.next()
        s.close()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        assertNull(reports.poll(500, TimeUnit.MILLISECONDS))
    }

    private companion object {
        const val A = "aaaaaaaa-0000-0000-0000-000000000001"
        const val B = "bbbbbbbb-0000-0000-0000-000000000002"
    }
}
