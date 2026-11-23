package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 草稿存盘的格式、上限和侧栏那一行；恢复时查文件还在不在的那段脚本。 */
class DraftRecordsTest {
    private fun record(
        key: String,
        text: String = "",
        at: Long = 0,
        images: List<DraftImage> = emptyList(),
        files: List<DraftFile> = emptyList(),
        selection: Pair<Int, Int> = text.length to text.length,
    ) = DraftRecord(key, text, selection.first, selection.second, images, files, at)

    @Test
    fun roundTrip() {
        val records =
            listOf(
                record(
                    "s1",
                    "第一行\n第二行 \"引号\"",
                    at = 5,
                    selection = 2 to 4,
                    images = listOf(DraftImage("0b6f-1", "image/jpeg", 1234)),
                    files =
                        listOf(
                            DraftFile(
                                "/home/u/.cache/zeta/files/ab12/报告 v2.pdf",
                                "报告 v2.pdf (1.2 MB)",
                            ),
                            DraftFile("@/workspace/claude/zeta/", "zeta"),
                        ),
                ),
                record(ComposerDrafts.NEW, "新对话里写了一半", at = 9),
            )
        assertEquals(records, DraftRecords.decode(DraftRecords.encode(records)))
    }

    @Test
    fun decodeSkipsBadEntries() {
        assertEquals(emptyList<DraftRecord>(), DraftRecords.decode(null))
        assertEquals(emptyList<DraftRecord>(), DraftRecords.decode("不是 JSON"))
        assertEquals(emptyList<DraftRecord>(), DraftRecords.decode("[]"))
        val raw =
            """
            {"version":1,"drafts":[
              "坏的",
              {"text":"没有 key"},
              {"key":"s1","text":"光标越界","selection":[99,-3],"files":[{"entry":"相对路径"},{"entry":"@/workspace"}]},
              {"key":"s1","text":"同一个 key 的第二份"},
              {"key":"s2","text":"  "}
            ]}
            """
                .trimIndent()
        val decoded = DraftRecords.decode(raw)
        assertEquals(1, decoded.size)
        val only = decoded.single()
        assertEquals("光标越界", only.text)
        assertEquals(4, only.selectionStart)
        assertEquals(0, only.selectionEnd)
        // 不是附件说明的路径不要；没存名字的用路径的最后一段
        assertEquals(listOf(DraftFile("@/workspace", "workspace")), only.files)
    }

    @Test
    fun fitKeepsNewestWithinLimits() {
        val many =
            (1..DraftRecords.MAX_DRAFTS + 5).map { record("s$it", "草稿 $it", at = it.toLong()) } +
                record("empty", " \n", at = 999)
        val kept = DraftRecords.fit(many)
        assertEquals(DraftRecords.MAX_DRAFTS, kept.size)
        assertEquals("s${DraftRecords.MAX_DRAFTS + 5}", kept.first().key)
        assertTrue("最旧的几份先丢", kept.none { it.key == "s1" })
        assertTrue("空的不存", kept.none { it.key == "empty" })
    }

    @Test
    fun fitClipsText() {
        val long = "字".repeat(DraftRecords.MAX_TEXT + 100)
        val kept = DraftRecords.fit(listOf(record("s1", long, at = 3)))
        assertEquals(DraftRecords.MAX_TEXT, kept.single().text.length)
        assertEquals("光标跟着收进文字里", DraftRecords.MAX_TEXT, kept.single().selectionEnd)

        // 一个表情不切成两半
        val emoji =
            DraftRecords.fit(listOf(record("e", "x".repeat(DraftRecords.MAX_TEXT - 1) + "😀")))
        assertEquals("x".repeat(DraftRecords.MAX_TEXT - 1), emoji.single().text)

        // 总量用完后，更旧的只剩放得下的那段，再旧的没有文字了，图片照样存
        val first = record("new1", "a".repeat(DraftRecords.MAX_TEXT), at = 10)
        val second = record("new2", "b".repeat(DraftRecords.MAX_TEXT), at = 9)
        val leftover = DraftRecords.MAX_TOTAL_TEXT - 2 * DraftRecords.MAX_TEXT
        val third = record("old", "c".repeat(leftover) + "tail", at = 8)
        val fourth =
            record("older", "d", at = 7, images = listOf(DraftImage("i1", "image/jpeg", 10)))
        val fitted = DraftRecords.fit(listOf(fourth, third, second, first))
        assertEquals(listOf("new1", "new2", "old", "older"), fitted.map { it.key })
        assertEquals("c".repeat(leftover), fitted[2].text)
        assertEquals("", fitted[3].text)
        assertEquals(0, fitted[3].selectionStart)
        assertEquals(1, fitted[3].images.size)
    }

    @Test
    fun fitDropsImagesOverBudget() {
        val big = DraftRecords.MAX_IMAGE_BYTES / 2 + 1
        val newest = record("s1", "新", at = 3, images = listOf(DraftImage("a", "image/jpeg", big)))
        val older =
            record(
                "s2",
                "旧",
                at = 2,
                images =
                    listOf(DraftImage("b", "image/jpeg", big), DraftImage("c", "image/jpeg", 100)),
            )
        val onlyImages = record("s3", at = 1, images = listOf(DraftImage("d", "image/jpeg", big)))
        val fitted = DraftRecords.fit(listOf(onlyImages, older, newest))
        assertEquals(listOf("a"), fitted[0].images.map { it.id })
        assertEquals("放不下的跳过，小的照样放", listOf("c"), fitted[1].images.map { it.id })
        assertEquals("只有图片、图片又放不下的整份不存", listOf("s1", "s2"), fitted.map { it.key })
    }

    @Test
    fun preview() {
        assertEquals("先看日志 再说", DraftRecords.preview(record("s", "\n  先看日志   再说\n第二行")))
        assertEquals(
            "2 张图片",
            DraftRecords.preview(
                record(
                    "s",
                    images =
                        listOf(DraftImage("a", "image/jpeg", 1), DraftImage("b", "image/jpeg", 1)),
                ),
            ),
        )
        assertEquals(
            "a.txt",
            DraftRecords.preview(record("s", files = listOf(DraftFile("/x/a.txt", "a.txt")))),
        )
        assertEquals(
            "a.txt 等 2 个文件",
            DraftRecords.preview(
                record("s", files = listOf(DraftFile("/x/a.txt", "a.txt"), DraftFile("@/y", "y"))),
            ),
        )
        assertEquals(60, DraftRecords.preview(record("s", "长".repeat(100))).length)
    }

    /** 恢复草稿时查复制进去的文件还在不在（[PathCheck] 一次查几个）：整条命令在本机 sh 里跑一遍，路径里有空格、引号也不出错。 */
    @Test
    fun missingFilesCheck() {
        val dir = Files.createTempDirectory("zeta-drafts").toFile()
        try {
            val there = File(dir, "有 空格 的'文件'.txt").apply { writeText("x") }
            val gone = File(dir, "已经清掉了.pdf").path
            val process =
                ProcessBuilder("sh", "-c", PathCheck.command(listOf(there.path, gone), null))
                    .apply { environment()["HOME"] = dir.path }
                    .redirectErrorStream(true)
                    .start()
            val out = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor())
            assertEquals(listOf(PathKind.File, PathKind.Missing), PathCheck.parse(out))
        } finally {
            removeTestDir(dir)
        }
    }
}
