package com.dzshzx.zeta.core.claude

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 测试用的 ZIP：[mode] 不为 null 时把这一项标成 Unix 上做的，外部属性的高 16 位写上它。 */
internal class ZipItem(
    val name: String,
    val data: ByteArray = ByteArray(0),
    val mode: Int? = null,
    val stored: Boolean = false,
)

internal fun zipOf(vararg items: ZipItem): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for (item in items) {
            val entry = ZipEntry(item.name)
            if (item.stored) {
                entry.method = ZipEntry.STORED
                entry.size = item.data.size.toLong()
                entry.compressedSize = item.data.size.toLong()
                entry.crc = java.util.zip.CRC32().apply { update(item.data) }.value
            }
            zip.putNextEntry(entry)
            zip.write(item.data)
            zip.closeEntry()
        }
    }
    val bytes = out.toByteArray()
    // 改中央目录里的 version made by 和外部属性
    var at = 0
    while (at + 46 <= bytes.size) {
        if (u32(bytes, at) == 0x02014b50L) {
            val nameLength = u16(bytes, at + 28)
            val name = String(bytes, at + 46, nameLength)
            items
                .firstOrNull { it.name == name }
                ?.mode
                ?.let { mode ->
                    bytes[at + 5] = 3
                    val external = mode.toLong() shl 16
                    for (k in 0 until 4) bytes[at + 38 + k] = (external shr (8 * k)).toByte()
                }
            at += 46 + nameLength + u16(bytes, at + 30) + u16(bytes, at + 32)
        } else {
            at++
        }
    }
    return bytes
}

/** 把某一项在中央目录里声明的解压后大小改掉（模拟压缩炸弹）。 */
internal fun patchDeclaredSize(bytes: ByteArray, name: String, size: Int): ByteArray {
    val copy = bytes.copyOf()
    var at = 0
    while (at + 46 <= copy.size) {
        if (u32(copy, at) == 0x02014b50L && String(copy, at + 46, u16(copy, at + 28)) == name) {
            for (k in 0 until 4) copy[at + 24 + k] = (size shr (8 * k)).toByte()
            return copy
        }
        at++
    }
    error("no entry $name")
}

private fun u16(d: ByteArray, at: Int) =
    (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8)

private fun u32(d: ByteArray, at: Int) = u16(d, at).toLong() or (u16(d, at + 2).toLong() shl 16)

private const val SKILL_MD =
    "---\nname: PDF Tools\ndescription: Work with PDF files\n---\n\n# PDF\n"

class SkillArchiveTest {
    private fun expectFailure(message: String, block: () -> Unit) {
        try {
            block()
            fail("应当失败：$message")
        } catch (error: SkillArchiveException) {
            assertTrue("「${error.message}」里没有「$message」", error.message!!.contains(message))
        }
    }

    @Test
    fun skillInFirstFolder() {
        val zip =
            zipOf(
                ZipItem("pdf-tools/"),
                ZipItem("pdf-tools/SKILL.md", SKILL_MD.toByteArray()),
                ZipItem(
                    "pdf-tools/scripts/run.sh",
                    "#!/bin/sh\necho hi\n".toByteArray(),
                    mode = 0x81ED,
                ),
                ZipItem(
                    "pdf-tools/ref.txt",
                    ByteArray(100_000) { (it % 7).toByte() },
                    stored = true,
                ),
                ZipItem("__MACOSX/pdf-tools/._SKILL.md", "junk".toByteArray()),
                ZipItem("pdf-tools/._ref.txt", "junk".toByteArray()),
            )
        val archive = SkillArchive.open(zip)
        val skill = archive.skill("download.zip")
        assertEquals("pdf-tools", skill.name)
        assertEquals("Work with PDF files", skill.description)
        assertEquals(listOf("SKILL.md", "scripts/run.sh", "ref.txt"), skill.members.map { it.path })
        assertTrue(skill.members.first { it.path == "scripts/run.sh" }.executable)
        assertFalse(skill.members.first { it.path == "ref.txt" }.executable)
    }

    @Test
    fun nameFallsBackToFolderThenFileName() {
        val noName = "---\ndescription: d\n---\n"
        assertEquals(
            "my-skill",
            SkillArchive.open(zipOf(ZipItem("My Skill/SKILL.md", noName.toByteArray())))
                .skill("x.zip")
                .name,
        )
        assertEquals(
            "from-file",
            SkillArchive.open(zipOf(ZipItem("SKILL.md", noName.toByteArray())))
                .skill("From File.zip")
                .name,
        )
        // 根上和子目录里都有时取根上那个
        val both =
            zipOf(
                ZipItem("SKILL.md", SKILL_MD.toByteArray()),
                ZipItem("inner/SKILL.md", noName.toByteArray()),
            )
        assertEquals(
            listOf("SKILL.md", "inner/SKILL.md"),
            SkillArchive.open(both).skill("a.zip").members.map { it.path },
        )
        assertEquals("synced-x", SkillArchive.toSkillName("Synced X"))
        assertFalse(SkillArchive.isValidName("synced"))
    }

    @Test
    fun rejectsBadPackages() {
        expectFailure("有效的 ZIP") { SkillArchive.open("not a zip".toByteArray()) }
        expectFailure("没有 SKILL.md") {
            SkillArchive.open(zipOf(ZipItem("a/b/SKILL.md", SKILL_MD.toByteArray()))).skill("x.zip")
        }
        expectFailure("description") {
            SkillArchive.open(zipOf(ZipItem("SKILL.md", "# no front".toByteArray()))).skill("x.zip")
        }
        expectFailure("不安全的路径") {
            SkillArchive.open(
                    zipOf(
                        ZipItem("SKILL.md", SKILL_MD.toByteArray()),
                        ZipItem("../evil.sh", "x".toByteArray()),
                    ),
                )
                .skill("x.zip")
        }
        expectFailure("不安全的路径") {
            SkillArchive.open(
                    zipOf(
                        ZipItem("SKILL.md", SKILL_MD.toByteArray()),
                        ZipItem("a\\..\\evil", "x".toByteArray()),
                    ),
                )
                .skill("x.zip")
        }
        expectFailure("不安全的路径") {
            SkillArchive.open(
                    zipOf(
                        ZipItem("s/SKILL.md", SKILL_MD.toByteArray()),
                        ZipItem("s/x/../../y", "x".toByteArray()),
                    ),
                )
                .skill("x.zip")
        }
        expectFailure("符号链接") {
            SkillArchive.open(
                    zipOf(
                        ZipItem("SKILL.md", SKILL_MD.toByteArray()),
                        ZipItem("link", "/etc/passwd".toByteArray(), mode = 0xA1FF),
                    ),
                )
                .skill("x.zip")
        }
        expectFailure("认不出 Skill 的名字") {
            SkillArchive.open(
                    zipOf(ZipItem("SKILL.md", "---\ndescription: d\n---\n".toByteArray())),
                )
                .skill("中文.zip")
        }
    }

    @Test
    fun declaredSizeMustMatch() {
        val zip =
            zipOf(
                ZipItem("SKILL.md", SKILL_MD.toByteArray()),
                ZipItem("big.bin", ByteArray(50_000)),
            )
        expectFailure("比声明的大") {
            SkillArchive.open(patchDeclaredSize(zip, "big.bin", 10)).skill("x.zip")
        }
    }

    @Test
    fun tarExtractsWithSystemTar() {
        val longName = "deep/" + "segment-".repeat(20) + "file.txt"
        val zip =
            zipOf(
                ZipItem("SKILL.md", SKILL_MD.toByteArray()),
                ZipItem("empty/"),
                ZipItem("bin/tool", "#!/bin/sh\n".toByteArray(), mode = 0x81ED),
                ZipItem(longName, "long".toByteArray()),
                ZipItem("资料/说明.md", "中文".toByteArray()),
                ZipItem("blob", ByteArray(70_001) { it.toByte() }),
            )
        val archive = SkillArchive.open(zip)
        val skill = archive.skill("x.zip")
        val tar = ByteArrayOutputStream().also { archive.writeTar(skill, it) }.toByteArray()
        assertEquals(0, tar.size % 512)
        val dir = Files.createTempDirectory("zeta-tar").toFile()
        try {
            val process =
                ProcessBuilder("tar", "-x", "--no-same-owner", "-f", "-", "-C", dir.path)
                    .redirectErrorStream(true)
                    .start()
            process.outputStream.use { it.write(tar) }
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
            assertEquals(SKILL_MD, File(dir, "SKILL.md").readText())
            assertTrue(File(dir, "empty").isDirectory)
            assertTrue(File(dir, "bin/tool").canExecute())
            assertFalse(File(dir, "SKILL.md").canExecute())
            assertEquals("long", File(dir, longName).readText())
            assertEquals("中文", File(dir, "资料/说明.md").readText())
            assertArrayEquals(ByteArray(70_001) { it.toByte() }, File(dir, "blob").readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }
}
