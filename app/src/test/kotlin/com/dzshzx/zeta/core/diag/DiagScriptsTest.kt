package com.dzshzx.zeta.core.diag

import com.dzshzx.zeta.core.TestDeps
import com.dzshzx.zeta.core.removeTestDir
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagScriptsTest {
    private val home = "/home/someone"

    @Test
    fun probeOutputIsParsed() {
        val out =
            """
            claude=2.1.330 (Claude Code)
            node=v22.11.0
            debian=13.1
            file	running	1795053600	2048	/home/someone/.cache/zeta-dev/hosts/k1/err.log	修一下 bug
            file	finished	1795053500.5	100	/home/someone/.cache/zeta-dev/stderr/20261119-095820-k0.log
            file	finished	1795053590.25	300	/home/someone/.cache/zeta-dev/stderr/20261119-095950-k2.log
            file	debug	1795053000.0	70000	/home/someone/.claude/debug/abc.txt
            file	debug	bad	1	/home/someone/.claude/debug/broken.txt
            file	weird	1	1	/x
            noise
            """
                .trimIndent()
        val probe = DiagScripts.parseProbe(out)
        assertEquals("2.1.330", probe.claude)
        assertEquals("22.11.0", probe.node)
        assertEquals("13.1", probe.debian)
        assertEquals(
            listOf(LogKind.Running, LogKind.Finished, LogKind.Finished, LogKind.Debug),
            probe.files.map { it.kind },
        )
        assertEquals("修一下 bug", probe.files.first().title)
        // 同一类里新的在前
        assertEquals(
            listOf("20261119-095950-k2.log", "20261119-095820-k0.log"),
            probe.files(LogKind.Finished).map { it.name },
        )
        assertEquals(1_795_053_590_250L, probe.files(LogKind.Finished).first().modified)
        assertEquals(70000L, probe.files(LogKind.Debug).single().size)
    }

    @Test
    fun emptyProbe() {
        val probe = DiagScripts.parseProbe("claude=\nnode=\ndebian=\n")
        assertEquals("", probe.claude)
        assertTrue(probe.files.isEmpty())
    }

    @Test
    fun onlyKnownLogPlacesAreReadable() {
        val name = "zeta-dev"
        assertTrue(DiagScripts.readable("$home/.claude/debug/abc.txt", home, name))
        assertTrue(DiagScripts.readable("$home/.cache/zeta-dev/stderr/x.log", home, name))
        assertTrue(DiagScripts.readable("$home/.cache/zeta-dev/hosts/k1/err.log", home, "$name"))
        assertFalse(
            "正式版的目录测试版不读",
            DiagScripts.readable("$home/.cache/zeta/stderr/x.log", home, name),
        )
        assertFalse(DiagScripts.readable("$home/.claude/debug/../.credentials.json", home, name))
        assertFalse(DiagScripts.readable("$home/.claude/.credentials.json", home, name))
        assertFalse(DiagScripts.readable("$home/.claude/debug/sub/x.txt", home, name))
        assertFalse(DiagScripts.readable("$home/.cache/zeta-dev/hosts/k1/out.jsonl", home, name))
        assertFalse(DiagScripts.readable("$home/.cache/zeta-dev/hosts/a/b/err.log", home, name))
        assertFalse(DiagScripts.readable("relative/err.log", home, name))
        assertFalse(DiagScripts.readable("$home/.claude/debug/", home, name))
    }

    @Test
    fun tailsAreDecoded() {
        fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
        val out = "/a/x.log\t${b64("第一行\nsecond")}\n/a/empty.log\t\n/a/bad.log\t!!!\nnot a line\n"
        assertEquals(
            mapOf("/a/x.log" to "第一行\nsecond", "/a/empty.log" to ""),
            DiagScripts.parseTails(out),
        )
    }

    @Test
    fun bundleIsRedactedAndNamed() {
        val running =
            LogFile(LogKind.Running, "$home/.cache/zeta-dev/hosts/k1/err.log", 10, 0, "标题")
        val finished =
            LogFile(LogKind.Finished, "$home/.cache/zeta-dev/stderr/20261119-k0.log", 100_000, 0)
        val debug = LogFile(LogKind.Debug, "$home/.claude/debug/a b.txt", 10, 0)
        val files =
            DiagBundle.files(
                info = "Zeta 0.6.21",
                log = "export GH_TOKEN=fake-token-1",
                logcat = "Authorization: Bearer fake-token-2",
                tails =
                    mapOf(
                        running to "boom",
                        finished to "x",
                        debug to "{\"accessToken\":\"fake-token-3\"}",
                    ),
            )
        assertEquals(
            listOf(
                "info.txt",
                "zeta-log.txt",
                "logcat.txt",
                "claude-stderr/running/k1.log",
                "claude-stderr/20261119-k0.log",
                "claude-debug/a_b.txt",
            ),
            files.keys.toList(),
        )
        for ((name, text) in files) assertFalse("$name 里有没打码的", text.contains("fake-token"))
        assertTrue(
            files.getValue("claude-stderr/20261119-k0.log").startsWith("# 原文件 100 KB，这里只有最后 64 KB"),
        )
        assertTrue(
            files.getValue("claude-debug/a_b.txt").startsWith("# $home/.claude/debug/a b.txt\n"),
        )
    }

    @Test
    fun duplicateNamesGetANumber() {
        val a = LogFile(LogKind.Debug, "$home/.claude/debug/a:b.txt", 1, 0)
        val b = LogFile(LogKind.Debug, "$home/.claude/debug/a;b.txt", 1, 0)
        assertEquals(
            listOf("claude-debug/a_b.txt", "claude-debug/a_b-2.txt"),
            DiagBundle.files("", "", "", mapOf(a to "1", b to "2")).keys.drop(3),
        )
    }

    /** 真跑一遍探测脚本（临时 HOME，假的 claude），核对脚本和解析对得上。 */
    @Test
    fun probeScriptRunsInBash() {
        TestDeps.require("Linux 的 bash 和 GNU find", File("/proc/self/cmdline").exists())
        val dir = Files.createTempDirectory("zeta-probe").toFile().canonicalFile
        try {
            File(dir, "bin/claude").apply {
                parentFile!!.mkdirs()
                writeText("#!/bin/sh\necho '9.9.9 (Claude Code)'\n")
                setExecutable(true)
            }
            File(dir, ".cache/zeta-dev/hosts/k1").mkdirs()
            File(dir, ".cache/zeta-dev/hosts/k1/err.log").writeText("oops\n")
            File(dir, ".cache/zeta-dev/hosts/k1/title").writeText("a\tb\nc")
            File(dir, ".cache/zeta-dev/stderr").mkdirs()
            File(dir, ".cache/zeta-dev/stderr/1-k0.log").writeText("old\n")
            File(dir, ".claude/debug").mkdirs()
            File(dir, ".claude/debug/d1.txt").writeText("debug line\n")
            // 正式版的不该被测试版列出来
            File(dir, ".cache/zeta/stderr").mkdirs()
            File(dir, ".cache/zeta/stderr/2-k9.log").writeText("release\n")
            val builder = ProcessBuilder("bash", "-c", DiagScripts.PROBE).directory(dir)
            builder.environment().apply {
                put("HOME", dir.path)
                put("PATH", "${dir.path}/bin:" + System.getenv("PATH"))
                put("ZETA_NAME", "zeta-dev")
            }
            val process = builder.start()
            val out = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            val probe = DiagScripts.parseProbe(out)
            assertEquals("9.9.9", probe.claude)
            assertEquals(listOf("err.log", "1-k0.log", "d1.txt"), probe.files.map { it.name })
            assertEquals("a b c", probe.files.first().title)
            assertTrue(probe.files.all { DiagScripts.readable(it.path, dir.path, "zeta-dev") })

            val tails =
                ProcessBuilder(
                        listOf("bash", "-c", DiagScripts.TAILS, "zeta-diag") +
                            probe.files.map { it.path },
                    )
                    .start()
            val tailOut = tails.inputStream.bufferedReader().readText()
            assertTrue(tails.waitFor(30, TimeUnit.SECONDS))
            assertEquals(
                listOf("oops\n", "old\n", "debug line\n"),
                DiagScripts.parseTails(tailOut).values.toList(),
            )
        } finally {
            removeTestDir(dir)
        }
    }
}
