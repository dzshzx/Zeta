package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * zeta-helper 的 delete / archive：在一个临时 HOME 上真跑一遍，不碰任何人真实的 ~/.claude。 删除要连同 Claude Code 存在 <项目>/<会话
 * id>/ 下的东西一起删，但只删这一个会话的、在 ~/.claude/projects 里的真目录。
 */
class HelperDeleteTest {
    private lateinit var home: File
    private lateinit var outside: File
    private val project
        get() = File(home, ".claude/projects/-workspace-test")

    private val a = "aaaaaaaa-1111-4222-8333-444444444444"
    private val b = "bbbbbbbb-1111-4222-8333-444444444444"
    private val c = "cccccccc-1111-4222-8333-444444444444"

    @Before
    fun setUp() {
        home = Files.createTempDirectory("zeta-helper-home").toFile()
        outside = Files.createTempDirectory("zeta-helper-outside").toFile()
        project.mkdirs()
        for (id in listOf(a, b, c)) File(project, "$id.jsonl")
            .writeText("{\"type\":\"user\",\"sessionId\":\"$id\"}\n")
        File(project, "$a/tool-results").mkdirs()
        File(project, "$a/tool-results/shot.png").writeText("png")
        File(project, "$a/subagents").mkdirs()
        File(project, "$a/subagents/agent-1.jsonl").writeText("{}\n")
        File(project, "$b/tool-results").mkdirs()
        File(project, "$b/tool-results/other.png").writeText("png")
        File(outside, "keep.txt").writeText("keep")
    }

    @After
    fun tearDown() {
        if (::home.isInitialized) home.deleteRecursively()
        if (::outside.isInitialized) outside.deleteRecursively()
    }

    private fun helper(vararg args: String): Pair<Int, String> {
        val process =
            ProcessBuilder(listOf(TestHelper.path) + args)
                .apply { environment()["HOME"] = home.path }
                .start()
        process.outputStream.close()
        val out = process.inputStream.bufferedReader().readText()
        process.errorStream.readBytes()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        return process.exitValue() to out
    }

    @Test
    fun deleteRemovesRecordAndItsOwnDirectoryOnly() {
        val (code, out) = helper("delete", File(project, "$a.jsonl").path)
        assertEquals(0, code)
        assertTrue(out.contains("\"dir\":true"))
        assertFalse(File(project, "$a.jsonl").exists())
        assertFalse(File(project, a).exists())
        // 别的会话原样
        assertTrue(File(project, "$b.jsonl").exists())
        assertTrue(File(project, "$b/tool-results/other.png").exists())
    }

    @Test
    fun deleteTakesSeveralSessions() {
        val (code, _) =
            helper("delete", File(project, "$a.jsonl").path, File(project, "$b.jsonl").path)
        assertEquals(0, code)
        assertFalse(File(project, a).exists())
        assertFalse(File(project, b).exists())
        assertTrue(File(project, "$c.jsonl").exists())
    }

    @Test
    fun symlinkedDirectoriesAreNotFollowed() {
        // 同名“目录”是指到外面的符号链接：不删，外面的东西也不动
        Files.createSymbolicLink(File(project, c).toPath(), outside.toPath())
        // 会话目录里指到外面的链接：只删链接本身
        Files.createSymbolicLink(File(project, "$a/tool-results/link").toPath(), outside.toPath())
        val (code, _) =
            helper("delete", File(project, "$c.jsonl").path, File(project, "$a.jsonl").path)
        assertEquals(0, code)
        assertFalse(File(project, "$c.jsonl").exists())
        assertTrue(Files.isSymbolicLink(File(project, c).toPath()))
        assertFalse(File(project, a).exists())
        assertTrue(File(outside, "keep.txt").exists())
    }

    @Test
    fun pathsOutsideProjectsAreRefusedAsAWhole() {
        val stray = File(outside, "$a.jsonl").apply { writeText("{}\n") }
        val sneaky = File(project, "../../../../" + outside.name + "/$a.jsonl").path
        for (bad in listOf(stray.path, sneaky, File(project, "$a.txt").path)) {
            val (code, _) = helper("delete", File(project, "$b.jsonl").path, bad)
            assertNotEquals("应拒绝 $bad", 0, code)
        }
        assertTrue(stray.exists())
        assertTrue(File(project, "$b.jsonl").exists())
        assertTrue(File(project, b).exists())
    }

    @Test
    fun archiveTakesSeveralIds() {
        assertEquals(0, helper("archive", a, b, "1").first)
        val archived = File(home, ".config/zeta/archived")
        assertEquals(listOf(a, b), archived.readLines())
        assertEquals(0, helper("archive", a, "0").first)
        assertEquals(listOf(b), archived.readLines())
        assertNotEquals(0, helper("archive", a, "../x", "1").first)
    }
}
