package com.dzshzx.zeta.core

import com.dzshzx.zeta.ui.pane.exportedMarkdown
import com.dzshzx.zeta.ui.pane.searchHitOf
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 侧栏和图片读核心的单次方法（docs/core.md「存储与文件」）的结果：对着真的 `zeta-helper serve`（本机架构那份，装进临时 HOME），
 * 应用里那几个解析（[lookedUpSessions]、[exportedMarkdown]、[searchHitOf]）读得出核心答的、推的。
 */
class CoreMethodsTest {
    private val dir = Files.createTempDirectory("zeta-core-methods").toFile().canonicalFile

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

            override fun kill(process: Process) = process.destroy()
        }

    private val pushed = LinkedBlockingQueue<JsonObject>()

    private val serve =
        HelperServe(
            name = "test",
            open = { HelperServe.open(shell, TestHelper.program(HelperProgram.ZETA_HELPER)) },
            subscriptions = emptyList(),
            onMessage = pushed::put,
            onProblem = {},
        )

    @After
    fun cleanup() {
        serve.close()
        dir.deleteRecursively()
    }

    private fun writeSession(id: String, text: String): File =
        File(dir, ".claude/projects/-w/$id.jsonl").apply {
            parentFile.mkdirs()
            writeText(
                """{"type":"user","cwd":"/w","message":{"content":"$text"}}""" +
                    "\n" +
                    """{"type":"user","message":{"content":[{"type":"text","text":"图"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGk="}}]}}""" +
                    "\n",
            )
        }

    private fun call(method: String, params: JsonObject) = checkNotNull(serve.call(method, params))

    @Test
    fun sidebarReadsWhatTheCoreAnswers() {
        val file = writeSession(A, "找一找苹果")
        call(
            "sessions.rename",
            buildJsonObject {
                put("session", A)
                put("title", "改过的名字")
            },
        )
        val found =
            lookedUpSessions(
                call(
                    "sessions.lookup",
                    buildJsonObject { put("sessions", jsonStrings(listOf(A, B))) },
                ),
                "host-1",
            )
        val summary = found!!.single()
        assertEquals(A, summary.id)
        assertEquals("改过的名字", summary.title)
        assertEquals("/w", summary.cwd)
        assertEquals("host-1", summary.backend)

        val exported =
            exportedMarkdown(call("sessions.export", buildJsonObject { put("session", A) }))!!
        assertEquals("改过的名字", exported.title)
        assertTrue(exported.markdown.contains("找一找苹果"))
        assertTrue(exported.fileName, exported.fileName.endsWith(".md"))

        val done = call("sessions.search", buildJsonObject { put("query", "苹果") }) as JsonObject
        assertEquals(true, done.bool("complete"))
        val hit = searchHitOf(checkNotNull(pushed.poll(20, TimeUnit.SECONDS)))
        assertEquals(A, hit!!.first)
        assertTrue(hit.second.contains("苹果"))

        val image =
            call(
                "sessions.image",
                buildJsonObject {
                    put("file", file.path)
                    put("ref", "1.1")
                },
            )
                as JsonObject
        assertEquals("aGk=", image.str("data"))
        assertNotNull(image.str("media_type"))
    }

    private companion object {
        const val A = "aaaaaaaa-0000-0000-0000-000000000001"
        const val B = "bbbbbbbb-0000-0000-0000-000000000002"
    }
}
