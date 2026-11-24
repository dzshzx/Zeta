package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 测试收尾的 [removeTestDir]：先结束用着这个目录的进程（HOME、当前目录或别的环境变量在里面，连同子进程）并等它们退出，再删目录；别的进程不碰。 */
class RemoveTestDirTest {
    private lateinit var dir: File
    private lateinit var elsewhere: File
    private val started = ArrayList<Process>()

    @Before
    fun setUp() {
        TestDeps.require("Linux 的 /proc", File("/proc/self/environ").exists())
        dir = Files.createTempDirectory("zeta-remove").toFile().canonicalFile
        elsewhere = Files.createTempDirectory("zeta-remove-elsewhere").toFile().canonicalFile
    }

    @After
    fun tearDown() {
        started.forEach { it.destroyForcibly() }
        dir.deleteRecursively()
        elsewhere.deleteRecursively()
    }

    private fun start(builder: ProcessBuilder): Process = builder.start().also { started += it }

    @Test
    fun processesWithThisHomeAndTheirChildrenEndBeforeTheDirectoryGoes() {
        // 一边在目录里不停建、删子目录（同托管脚本收尾时删托管目录），一边在后台留一个子进程
        val parent =
            start(
                HostTesting.builder(
                    dir,
                    listOf(
                        "bash",
                        "-c",
                        "sleep 1000 & echo \$!; while :; do mkdir -p \"\$HOME/a/b\"; rm -rf \"\$HOME/a\"; done",
                    ),
                ),
            )
        val child = parent.inputStream.bufferedReader().readLine().trim().toInt()
        assertTrue(HostTesting.alive(child))

        removeTestDir(dir)

        assertFalse("父进程还在", HostTesting.alive(parent.pid().toInt()))
        assertFalse("后台的子进程还在", HostTesting.alive(child))
        assertFalse(dir.exists())
    }

    @Test
    fun aProcessWorkingInsideTheDirectoryEndsToo() {
        val inside = File(dir, "work").apply { mkdirs() }
        val process =
            start(
                ProcessBuilder("sleep", "1000").directory(inside).apply {
                    environment()["HOME"] = elsewhere.path
                },
            )

        removeTestDir(dir)

        assertFalse(HostTesting.alive(process.pid().toInt()))
        assertFalse(dir.exists())
    }

    @Test
    fun aProcessWithAnotherVariablePointingInsideEndsToo() {
        // 同核心：HOME 和当前目录在别处，ZETA_CORE_DIR 指着要删的目录
        val process =
            start(
                HostTesting.builder(
                    elsewhere,
                    listOf("sleep", "1000"),
                    mapOf("ZETA_CORE_DIR" to File(dir, "core").path),
                ),
            )

        removeTestDir(dir)

        assertFalse(HostTesting.alive(process.pid().toInt()))
        assertFalse(dir.exists())
    }

    @Test
    fun otherProcessesAreLeftAlone() {
        val other = start(HostTesting.builder(elsewhere, listOf("sleep", "1000")))

        removeTestDir(dir)

        assertTrue(HostTesting.alive(other.pid().toInt()))
        assertFalse(dir.exists())
    }
}
