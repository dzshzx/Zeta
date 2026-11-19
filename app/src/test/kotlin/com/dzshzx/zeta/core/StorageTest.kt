package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
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
        root.deleteRecursively()
        outside.deleteRecursively()
    }

    @Test
    fun parsesReportLines() {
        val out = """
            {"type":"zeta_storage","key":"claude-residue","bytes":9000,"files":3,"freeable":8000,"items":2,"busy":null,"parts":[{"name":"file-history","bytes":5000,"items":1},{"name":"projects","bytes":3000,"items":1}]}
            {"type":"zeta_storage","key":"npm","bytes":100,"files":1,"freeable":100,"items":1,"busy":"npm","parts":[]}
            {"type":"zeta_storage","key":"tmp","error":true}
            {"type":"zeta_storage_project","dir":"-small","bytes":10,"files":1,"sessions":1,"cwd":"/small"}
            {"type":"zeta_storage_project","dir":"-workspace-claude","bytes":1000,"files":9,"sessions":4,"cwd":"/workspace/claude"}
            {"type":"zeta_storage_settings","cleanupPeriodDays":null}
            not json
            {"type":"zeta_storage_done"}
        """.trimIndent()
        val report = Storage.parseReport(out)
        assertTrue(report.complete)
        assertNull(report.cleanupPeriodDays)
        val residue = report.linux.getValue("claude-residue")
        assertEquals(8000, residue.freeable)
        assertEquals(listOf("file-history", "projects"), residue.parts.map { it.name })
        assertEquals("npm", report.linux.getValue("npm").busy)
        assertTrue(report.linux.getValue("tmp").failed)
        assertEquals(listOf("-workspace-claude", "-small"), report.projects.map { it.dir })
        assertEquals(1010, report.projectBytes)
        assertEquals("/workspace/claude", report.projects.first().label)
        val leftover = ProjectUsage("-tmp-claude-5000--workspace-claude-fddb288d-ee63-439a-904b-692d6c77e5e3-scratchpad", "", 7000, 0)
        assertEquals("-tmp-claude-5000…e5e3-scratchpad", leftover.label)
        assertEquals(7, Storage.parseReport("{\"type\":\"zeta_storage_settings\",\"cleanupPeriodDays\":7}").cleanupPeriodDays)
        assertFalse(Storage.parseReport("").complete)
    }

    @Test
    fun parsesSessionsBiggestFirst() {
        val out = """
            {"type":"zeta_storage_session","id":"a","file":"/p/a.jsonl","bytes":10,"updated":2,"live":false}
            {"type":"zeta_storage_session","id":"b","file":"/p/b.jsonl","bytes":30,"updated":1,"live":true}
            {"type":"zeta_storage_rest","bytes":5}
        """.trimIndent()
        val (sessions, rest) = Storage.parseSessions(out)
        assertEquals(listOf("b", "a"), sessions.map { it.id })
        assertTrue(sessions.first().live)
        assertEquals(5, rest)
    }

    @Test
    fun parsesCleanOutcome() {
        val outcome = Storage.parseCleaned("{\"type\":\"zeta_cleaned\",\"key\":\"tmp\",\"freed\":4096,\"removed\":2,\"failed\":1,\"busy\":null}")!!
        assertEquals(CleanOutcome(4096, 2, 1, null), outcome)
        assertNull(Storage.parseCleaned("garbage"))
    }

    @Test
    fun keepArgumentsOnlyPassSessionIds() {
        assertEquals(
            listOf("aaaaaaaa-1111-4222-8333-444444444444"),
            Storage.keepArguments(listOf("aaaaaaaa-1111-4222-8333-444444444444", "../x", "\$(id)", "aaaaaaaa-1111-4222-8333-444444444444", "")),
        )
    }

    @Test
    fun formatsSizesLikeSystemSettings() {
        assertEquals("0 B", Storage.size(0))
        assertEquals("999 B", Storage.size(999))
        assertEquals("1 KB", Storage.size(1000))
        assertEquals("1.5 KB", Storage.size(1500))
        assertEquals("12.3 MB", Storage.size(12_340_000))
        assertEquals("100 MB", Storage.size(100_000_000))
        assertEquals("1 MB", Storage.size(999_600))
        assertEquals("9.65 GB", Storage.size(9_654_209_000))
    }

    @Test
    fun confirmSaysWhatHowMuchAndWhatStays() {
        val usage = StorageUsage(9000, 8000, 2, listOf(StoragePart("file-history", 5000, 1), StoragePart("projects", 3000, 1)))
        val text = Storage.confirmMessage(StorageKind.ClaudeResidue, usage)
        assertTrue(text.startsWith("删除："))
        assertTrue(text.contains("能腾出约 8 KB（2 项）"))
        assertTrue(text.contains("file-history 5 KB"))
        assertTrue(text.contains("项目里的同名目录 3 KB"))
        assertTrue(text.contains("会话记录本身一个都不删"))
        // 每一类都写明不删什么
        for (kind in StorageKind.entries) assertTrue(kind.name, Storage.confirmMessage(kind, StorageUsage(1, 1, 1)).contains("\n不删："))
    }

    @Test
    fun summariesAndOutcomes() {
        assertEquals("统计中…", Storage.summary(StorageKind.Tmp, null))
        assertEquals("npm 正在运行，稍后再清", Storage.summary(StorageKind.Npm, StorageUsage(1, 1, 1, busy = "npm")))
        assertEquals("没有可清理的", Storage.summary(StorageKind.Tmp, StorageUsage(5)))
        assertEquals("可清理 2 KB（3 项）", Storage.summary(StorageKind.Tmp, StorageUsage(5000, 2000, 3)))
        assertEquals("已腾出 2 KB", Storage.outcomeText(StorageKind.Tmp, CleanOutcome(2000, 3)))
        assertTrue(Storage.outcomeText(StorageKind.Tmp, CleanOutcome(2000, 3, failed = 1)).contains("1 项没删成"))
        assertEquals("npm 正在运行，这次没有清理npm 缓存", Storage.outcomeText(StorageKind.Npm, CleanOutcome(0, 0, busy = "npm")))
        assertEquals("30 天（默认）", Storage.cleanupText(null))
        assertEquals("7 天", Storage.cleanupText(7))
    }

    @Test
    fun everyLinuxKindHasAHelperPlan() {
        val helper = File("src/main/assets/zeta_helper.py").readText()
        for (kind in StorageKind.entries.filter { it.inLinux }) assertTrue(kind.key, helper.contains("\"${kind.key}\":"))
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
        aged(File(root, AndroidCache.WEBVIEW).apply { mkdirs(); File(this, "data").writeBytes(ByteArray(500)) })

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
