package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 存储页的解析、文字和 Android 缓存目录的清理（在临时目录上，带指到外面的符号链接）。 */
class StorageTest {
    private lateinit var root: File
    private lateinit var outside: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("zeta-cache").toFile()
        outside = Files.createTempDirectory("zeta-cache-outside").toFile()
        File(outside, "keep.txt").writeText("keep")
    }

    @After
    fun tearDown() {
        removeTestDir(root, outside)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun parsesTheScanResult() {
        val result =
            json(
                """
                {"kinds":[
                  {"key":"claude-residue","bytes":9000,"files":3,"freeable":8000,"items":2,"busy":null,"parts":[{"name":"file-history","bytes":5000,"items":1},{"name":"projects","bytes":3000,"items":1}]},
                  {"key":"npm","bytes":100,"files":1,"freeable":100,"items":1,"busy":"npm","parts":[]},
                  {"key":"tmp","bytes":0,"files":0,"freeable":0,"items":0,"busy":null,"parts":[],"error":true}],
                 "projects":[
                  {"dir":"-small","bytes":10,"files":1,"sessions":1,"cwd":"/small"},
                  {"dir":"-workspace-claude","bytes":1000,"files":9,"sessions":4,"cwd":"/workspace/claude"}],
                 "cleanupPeriodDays":null}
                """,
            )
        val report = Storage.parseReport(result)
        assertNull(report.cleanupPeriodDays)
        val residue = report.linux.getValue("claude-residue")
        assertEquals(8000, residue.freeable)
        assertEquals(listOf("file-history", "projects"), residue.parts.map { it.name })
        assertEquals("npm", report.linux.getValue("npm").busy)
        assertTrue(report.linux.getValue("tmp").failed)
        assertEquals(listOf("-workspace-claude", "-small"), report.projects.map { it.dir })
        assertEquals(1010, report.projectBytes)
        assertEquals("/workspace/claude", report.projects.first().label)
        val leftover =
            ProjectUsage(
                "-tmp-claude-5000--workspace-claude-fddb288d-ee63-439a-904b-692d6c77e5e3-scratchpad",
                "",
                7000,
                0,
            )
        assertEquals("-tmp-claude-5000…e5e3-scratchpad", leftover.label)
        assertEquals(7, Storage.parseReport(json("""{"cleanupPeriodDays":7}""")).cleanupPeriodDays)
        assertTrue(Storage.parseReport(json("{}")).linux.isEmpty())
    }

    @Test
    fun parsesSessionsBiggestFirst() {
        val result =
            json(
                """
                {"sessions":[
                  {"id":"a","file":"/p/a.jsonl","bytes":10,"updated":2,"live":false},
                  {"id":"b","file":"/p/b.jsonl","bytes":30,"updated":1,"live":true}],
                 "rest":5}
                """,
            )
        val (sessions, rest) = Storage.parseSessions(result)
        assertEquals(listOf("b", "a"), sessions.map { it.id })
        assertTrue(sessions.first().live)
        assertEquals(5, rest)
    }

    @Test
    fun parsesCleanOutcome() {
        val outcome =
            Storage.parseCleaned(
                json("""{"key":"tmp","freed":4096,"removed":2,"failed":1,"busy":null}"""),
            )
        assertEquals(CleanOutcome(4096, 2, 1, null), outcome)
        assertEquals("npm", Storage.parseCleaned(json("""{"key":"npm","busy":"npm"}""")).busy)
    }

    @Test
    fun keepArgumentsOnlyPassSessionIds() {
        assertEquals(
            listOf("aaaaaaaa-1111-4222-8333-444444444444"),
            Storage.keepArguments(
                listOf(
                    "aaaaaaaa-1111-4222-8333-444444444444",
                    "../x",
                    "\$(id)",
                    "aaaaaaaa-1111-4222-8333-444444444444",
                    "",
                ),
            ),
        )
    }

    @Test
    fun formatsSizesLikeSystemSettings() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("999 B", formatBytes(999))
        assertEquals("1 KB", formatBytes(1000))
        assertEquals("1.5 KB", formatBytes(1500))
        assertEquals("12.3 MB", formatBytes(12_340_000))
        assertEquals("100 MB", formatBytes(100_000_000))
        assertEquals("1 MB", formatBytes(999_600))
        assertEquals("9.65 GB", formatBytes(9_654_209_000))
    }

    @Test
    fun confirmSaysWhatHowMuchAndWhatStays() {
        val usage =
            StorageUsage(
                9000,
                8000,
                2,
                listOf(StoragePart("file-history", 5000, 1), StoragePart("projects", 3000, 1)),
            )
        val text = Storage.confirmMessage(StorageKind.ClaudeResidue, usage)
        assertTrue(text.startsWith("删除："))
        assertTrue(text.contains("预计可释放约 8 KB（2 项）"))
        assertTrue(text.contains("file-history 5 KB"))
        assertTrue(text.contains("项目中的同名目录 3 KB"))
        assertTrue(text.contains("会话记录本身不会删除"))
        // 每一类都写明不删什么
        for (kind in StorageKind.entries) assertTrue(
            kind.name,
            Storage.confirmMessage(kind, StorageUsage(1, 1, 1)).contains("\n保留："),
        )
    }

    @Test
    fun summariesAndOutcomes() {
        assertEquals("统计中…", Storage.summary(StorageKind.Tmp, null))
        assertEquals(
            "npm 正在运行，请稍后清理",
            Storage.summary(StorageKind.Npm, StorageUsage(1, 1, 1, busy = "npm")),
        )
        assertEquals("无可清理内容", Storage.summary(StorageKind.Tmp, StorageUsage(5)))
        assertEquals("可清理 2 KB（3 项）", Storage.summary(StorageKind.Tmp, StorageUsage(5000, 2000, 3)))
        assertEquals("已释放 2 KB", Storage.outcomeText(StorageKind.Tmp, CleanOutcome(2000, 3)))
        assertTrue(
            Storage.outcomeText(StorageKind.Tmp, CleanOutcome(2000, 3, failed = 1))
                .contains("1 项未删除"),
        )
        assertEquals(
            "npm 正在运行，本次跳过清理npm 缓存",
            Storage.outcomeText(StorageKind.Npm, CleanOutcome(0, 0, busy = "npm")),
        )
        assertEquals("30 天（默认）", Storage.cleanupText(null))
        assertEquals("7 天", Storage.cleanupText(7))
    }

    private fun aged(file: File, ago: Long = 60 * 60 * 1000L): File {
        file.walkBottomUp().forEach { it.setLastModified(System.currentTimeMillis() - ago) }
        return file
    }

    @Test
    fun androidCacheDeletesOldEntriesWithoutFollowingLinks() {
        File(root, "shots").mkdirs()
        File(root, "shots/a.png").writeBytes(ByteArray(3000))
        // 目录里指到外面的链接：只删链接本身
        Files.createSymbolicLink(File(root, "shots/escape").toPath(), outside.toPath())
        aged(File(root, "shots"))
        aged(File(root, "old.bin").apply { writeBytes(ByteArray(2000)) })
        File(root, "fresh.bin").writeBytes(ByteArray(1000))
        // 直接下一层的链接不删
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        // 浏览器缓存交给 WebView 自己清
        aged(
            File(root, AndroidCache.WEBVIEW).apply {
                mkdirs()
                File(this, "data").writeBytes(ByteArray(500))
            },
        )

        val (total, freeable) = AndroidCache.scan(root)
        assertEquals(6500, total)
        assertEquals(5000, freeable)
        assertEquals(5000, AndroidCache.clean(root))
        assertFalse(File(root, "shots").exists())
        assertFalse(File(root, "old.bin").exists())
        assertTrue(File(root, "fresh.bin").exists())
        assertTrue(Files.isSymbolicLink(File(root, "link").toPath()))
        assertTrue(File(root, "${AndroidCache.WEBVIEW}/data").exists())
        assertTrue(File(outside, "keep.txt").exists())
    }

    @Test
    fun androidCacheRootThatIsALinkIsLeftAlone() {
        val linked = File(root, "linked-cache")
        Files.createSymbolicLink(linked.toPath(), outside.toPath())
        aged(File(outside, "keep.txt"))
        assertEquals(0, AndroidCache.clean(linked))
        assertTrue(File(outside, "keep.txt").exists())
    }
}
