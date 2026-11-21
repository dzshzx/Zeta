package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AttachedPathsTest {
    @Test
    fun referenceEntriesRoundTrip() {
        val folder = AttachedPaths.reference("/workspace/claude/zeta", folder = true)
        assertEquals("@/workspace/claude/zeta/", folder)
        assertTrue(AttachedPaths.isFolder(folder))
        assertEquals("/workspace/claude/zeta", AttachedPaths.path(folder))
        assertEquals("zeta", AttachedPaths.label(folder))

        val file = AttachedPaths.reference("/workspace/claude/notes.md", folder = false)
        assertTrue(AttachedPaths.isReference(file))
        assertFalse(AttachedPaths.isFolder(file))
        assertEquals("/workspace/claude/notes.md", AttachedPaths.path(file))
        assertEquals("notes.md", AttachedPaths.label(file))

        val root = AttachedPaths.reference("/", folder = true)
        assertEquals("/", AttachedPaths.path(root))
        assertEquals("/", AttachedPaths.label(root))

        // 复制进来的文件还是原来的样子
        val upload = "/home/u/.cache/zeta/files/ab12cd34/报告.pdf"
        assertFalse(AttachedPaths.isReference(upload))
        assertEquals(upload, AttachedPaths.path(upload))
        assertEquals("报告.pdf", AttachedPaths.label(upload))
    }

    @Test
    fun headersUseQuotedMentions() {
        assertEquals(
            "[Attached folder: @\"/mnt/shared/Download\"]",
            AttachedPaths.header("@/mnt/shared/Download/"),
        )
        assertEquals("[Attached file: @\"/a b/c.txt\"]", AttachedPaths.header("@/a b/c.txt"))
        assertEquals("[Attached file: /x/y.pdf]", AttachedPaths.header("/x/y.pdf"))
        for (entry in listOf("@/mnt/shared/Download/", "@/a b/c.txt", "/x/y.pdf", "@/")) {
            assertEquals(entry, AttachedPaths.parseHeader(AttachedPaths.header(entry)))
        }
        assertNull(AttachedPaths.parseHeader("[Attached folder: /no/mention]"))
        assertNull(AttachedPaths.parseHeader("hello"))
        assertNull(AttachedPaths.parseHeader("[Attached file: ]"))
    }

    @Test
    fun messageTextSplitsBackIntoAttachmentsAndBody() {
        val files =
            listOf(
                "/home/u/.cache/zeta/files/1/a.txt",
                "@/workspace/claude/zeta/",
                "@/etc/hostname",
            )
        val text = withAttachedFiles("看看这些", files)
        assertEquals(
            "[Attached file: /home/u/.cache/zeta/files/1/a.txt]\n[Attached folder: @\"/workspace/claude/zeta\"]\n" +
                "[Attached file: @\"/etc/hostname\"]\n\n看看这些",
            text,
        )
        assertEquals(files to "看看这些", splitAttachedFiles(text))
        assertEquals(
            listOf("@/x/") to "",
            splitAttachedFiles(withAttachedFiles("", listOf("@/x/"))),
        )
        assertEquals(emptyList<String>() to "[Attached 不是附件", splitAttachedFiles("[Attached 不是附件"))
    }

    @Test
    fun normalizeTypedPaths() {
        assertEquals("/workspace/claude", AttachedPaths.normalize("  /workspace//claude/ "))
        assertEquals("/workspace", AttachedPaths.normalize("/workspace/claude/.."))
        assertEquals("/home/u/x", AttachedPaths.normalize("~/x", home = "/home/u"))
        assertEquals("/home/u", AttachedPaths.normalize("~", home = "/home/u"))
        assertEquals("/", AttachedPaths.normalize("/"))
        assertNull(AttachedPaths.normalize("relative/path"))
        assertNull(AttachedPaths.normalize("~/x"))
        assertNull(AttachedPaths.normalize("/.."))
        assertNull(AttachedPaths.normalize("/a\"b"))
        assertNull(AttachedPaths.normalize("/a\nb"))
    }

    @Test
    fun androidPathsMapIntoLinux() {
        val shares =
            listOf(
                SharedFolder("sdcard", "/sdcard"),
                SharedFolder("Download", "/storage/emulated/0/Download"),
            )
        val rootfs = "/data/local/zeta/debian/rootfs"
        assertEquals(
            "/workspace/claude",
            AttachedPaths.linuxPathFor("/data/local/zeta/workspace/claude", shares, rootfs),
        )
        assertEquals(
            "/workspace",
            AttachedPaths.linuxPathFor("/data/local/zeta/workspace", shares, rootfs),
        )
        // Eta 的目录不是 Zeta 的工作区，只是 /data/local/tmp 下的一个目录
        assertEquals(
            "/data/local/tmp/eta/claude",
            AttachedPaths.linuxPathFor("/data/local/tmp/eta/claude", shares, rootfs),
        )
        assertEquals(
            "/data/local/tmp/x",
            AttachedPaths.linuxPathFor("/data/local/tmp/x", shares, rootfs),
        )
        assertEquals(
            "/home/dzshzx",
            AttachedPaths.linuxPathFor("$rootfs/home/dzshzx", shares, rootfs),
        )
        // 套着的共享文件夹取最具体的那个
        assertEquals(
            "/mnt/shared/Download/a",
            AttachedPaths.linuxPathFor("/sdcard/Download/a", shares, rootfs),
        )
        assertEquals(
            "/mnt/shared/sdcard/DCIM",
            AttachedPaths.linuxPathFor("/storage/emulated/0/DCIM", shares, rootfs),
        )
        assertEquals("/mnt/shared/sdcard", AttachedPaths.linuxPathFor("/sdcard", shares, rootfs))
        // 没共享的内部存储在 Linux 里看不到
        assertNull(
            AttachedPaths.linuxPathFor(
                "/sdcard/DCIM",
                listOf(SharedFolder("Download", "/sdcard/Download")),
                rootfs,
            ),
        )
        assertNull(
            AttachedPaths.linuxPathFor(
                "/sdcard/Downloads",
                listOf(SharedFolder("Download", "/sdcard/Download")),
                rootfs,
            ),
        )
        assertNull(AttachedPaths.linuxPathFor("relative", shares, rootfs))
    }

    @Test
    fun pathCheckParsesScriptOutput() {
        assertEquals(listOf(PathKind.Folder), PathCheck.parse("motd line\nzeta_path folder\n"))
        assertEquals(
            listOf(
                PathKind.File,
                PathKind.Missing,
                PathKind.Denied,
                PathKind.FolderNotInProcess,
                PathKind.FileNotInProcess,
                PathKind.Unknown,
            ),
            PathCheck.parse(
                "zeta_path file\nzeta_path missing\nzeta_path denied\nzeta_path hidden-folder\nzeta_path hidden-file\nzeta_path ???",
            ),
        )
        assertEquals(emptyList<PathKind>(), PathCheck.parse(""))
        assertTrue(
            PathCheck.command(listOf("/a b/it's"), null).endsWith(" zeta-path '' '/a b/it'\\''s'"),
        )
        assertTrue(
            PathCheck.command(listOf("/x", "/y"), "host-1")
                .endsWith(" zeta-path 'host-1' '/x' '/y'"),
        )
    }

    /** 托管目录按包分开（测试版是 zeta-dev），脚本里不能写死正式版的目录。 */
    @Test
    fun pathCheckScriptFollowsZetaName() {
        assertFalse(PathCheck.Script.contains(".cache/zeta/"))
        assertTrue(PathCheck.Script.contains("\${ZETA_NAME:-zeta}"))
    }

    /** 在本机的 sh 里真跑一遍查路径的脚本（用 JVM 自己当“对话进程”，它看到的和新开的环境一样）。 */
    @Test
    fun pathCheckScriptRunsInShell() {
        assumeTrue(File("/bin/sh").canExecute() && File("/proc/self/root").exists())
        val home = Files.createTempDirectory("zeta-path").toFile()
        try {
            val dir = File(home, "a dir").apply { mkdirs() }
            val file = File(dir, "it's.txt").apply { writeText("x") }
            val key = "host-1"
            File(home, ".cache/zeta/hosts/$key")
                .apply { mkdirs() }
                .resolve("pid")
                .writeText(ProcessHandle.current().pid().toString())
            fun runAll(paths: List<String>, hostKey: String?): List<PathKind> {
                val process =
                    ProcessBuilder(
                            listOf("sh", "-c", PathCheck.Script, "zeta-path", hostKey.orEmpty()) +
                                paths,
                        )
                        .apply {
                            environment()["HOME"] = home.path
                            environment().remove("ZETA_NAME")
                        }
                        .redirectErrorStream(true)
                        .start()
                return PathCheck.parse(
                    process.inputStream.bufferedReader().readText().also { process.waitFor() },
                )
            }
            fun run(path: String, hostKey: String?): PathKind =
                runAll(listOf(path), hostKey).single()
            assertEquals(PathKind.Folder, run(dir.path, null))
            assertEquals(PathKind.Folder, run(dir.path, key))
            assertEquals(PathKind.File, run(file.path, key))
            assertEquals(PathKind.Missing, run(dir.path + "/nope", key))
            assertEquals(PathKind.Folder, run(dir.path, "no-such-host"))
            // 一次查几个：顺序同参数（恢复草稿时看复制进去的文件还在不在）
            assertEquals(
                listOf(PathKind.File, PathKind.Missing, PathKind.Folder),
                runAll(listOf(file.path, dir.path + "/gone", dir.path), null),
            )
            if (System.getProperty("user.name") != "root" && file.setReadable(false, false)) {
                assertEquals(PathKind.Denied, run(file.path, key))
            }
        } finally {
            home.walkBottomUp().forEach {
                it.setReadable(true)
                it.delete()
            }
        }
    }
}
