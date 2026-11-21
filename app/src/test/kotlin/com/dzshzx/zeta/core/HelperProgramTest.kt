package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 随包程序的安装（[HelperProgram]）：真 bash、临时 HOME。两份「程序」是小 shell 脚本，看装没装、装了哪一版、跑的是不是它。
 */
class HelperProgramTest {
    private lateinit var home: File
    private lateinit var fakeBin: File
    private val commands = ArrayList<String>()

    @Before
    fun setUp() {
        home = Files.createTempDirectory("zeta-helper-install").toFile()
        fakeBin = File(home, "fake-bin").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        home.deleteRecursively()
    }

    private fun program(tag: String, name: String = HelperProgram.ZETA_HELPER) =
        HelperProgram({ asset -> "#!/bin/sh\necho \"$tag ${asset.substringAfterLast('-')} \$*\"\n".toByteArray() }, "zeta", name)

    /** 在临时 HOME 上用 bash 跑一行；[cut] 时只把标准输入的前一半写进去（传到一半断了）。 */
    private fun exec(cut: Boolean = false): (String, ByteArray?) -> CommandResult? = { command, stdin ->
        commands += command
        val process = ProcessBuilder("bash", "-c", command).apply {
            environment()["HOME"] = home.path
            environment()["PATH"] = fakeBin.path + File.pathSeparator + System.getenv("PATH")
        }.start()
        process.outputStream.use { out -> if (stdin != null) out.write(if (cut) stdin.copyOf(stdin.size / 2) else stdin) }
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        CommandResult(process.exitValue(), out, err)
    }

    private val installed: List<String> get() = File(home, ".cache/zeta/bin").list().orEmpty().sorted()

    private fun machine(): String = ProcessBuilder("uname", "-m").start().inputStream.bufferedReader().readText().trim()

    @Test
    fun installsOnceThenRunsInstalled() {
        val program = program("v1")
        val arch = checkNotNull(HelperProgram.archOf(machine()))
        val first = program.run(exec(), "list", listOf("it's"))
        assertNotNull(first)
        assertEquals(0, first!!.exitCode)
        assertEquals("v1 $arch list it's\n", first.stdout)
        assertEquals(3, commands.size) // 没装、装上、再跑
        assertEquals(listOf("zeta-helper-" + program.version), installed)
        assertTrue(File(home, ".cache/zeta/bin/zeta-helper-" + program.version).canExecute())

        commands.clear()
        assertEquals("v1 $arch hosts\n", program.run(exec(), "hosts")!!.stdout)
        assertEquals(1, commands.size)
        assertEquals(0, program.ensure(exec())!!.exitCode)
    }

    @Test
    fun newVersionReplacesOld() {
        val old = program("v1")
        old.run(exec(), "list")
        val new = program("v2")
        assertFalse(old.version == new.version)
        assertTrue(new.run(exec(), "list")!!.stdout.startsWith("v2 "))
        assertEquals(listOf("zeta-helper-" + new.version), installed)
    }

    @Test
    fun programsInstallSideBySide() {
        val helper = program("h1")
        val switch = program("s1", HelperProgram.CLAUDE_SWITCH)
        assertEquals(".cache/zeta/bin/claude-switch-" + switch.version, switch.relativePath)
        assertEquals("claude-switch/linux-arm64", switch.asset("arm64"))
        helper.run(exec(), "list")
        assertTrue(switch.run(exec(), "--json")!!.stdout.startsWith("s1 "))
        // 换了 claude-switch 的版本：只删它自己的旧版本，zeta-helper 留着
        val newer = program("s2", HelperProgram.CLAUDE_SWITCH)
        assertTrue(newer.run(exec(), "--json")!!.stdout.startsWith("s2 "))
        assertEquals(listOf("claude-switch-" + newer.version, "zeta-helper-" + helper.version), installed)
        assertTrue(helper.run(exec(), "hosts")!!.stdout.startsWith("h1 "))
    }

    @Test
    fun incompleteTransferIsNotInstalled() {
        val program = program("v1")
        val result = program.run(exec(cut = true), "list")!!
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr, result.stderr.contains("没有完整传过去"))
        assertEquals(emptyList<String>(), installed) // 临时文件也删了
        // 下次照常装上
        assertEquals(0, program.run(exec(), "list")!!.exitCode)
    }

    @Test
    fun unknownMachineIsReported() {
        File(fakeBin, "uname").apply { writeText("#!/bin/sh\necho riscv64\n") }.setExecutable(true)
        val result = program("v1").run(exec(), "list")!!
        assertEquals(HelperProgram.MISSING, result.exitCode)
        assertTrue(result.stderr, result.stderr.contains("riscv64"))
        assertEquals(emptyList<String>(), installed)
    }

    @Test
    fun missingIsRecognizedOnlyWithItsMark() {
        val program = program("v1")
        assertEquals("aarch64", program.missingOn(CommandResult(97, "", "bash: warning\nzeta-helper-missing aarch64\n")))
        assertNull(program.missingOn(CommandResult(97, "", "something else")))
        assertNull(program.missingOn(CommandResult(1, "", "zeta-helper-missing aarch64")))
        // 别的程序的记号不算
        assertNull(program("v1", HelperProgram.CLAUDE_SWITCH).missingOn(CommandResult(97, "", "zeta-helper-missing aarch64\n")))
        assertEquals("arm64", HelperProgram.archOf("aarch64"))
        assertEquals("amd64", HelperProgram.archOf("x86_64"))
        assertNull(HelperProgram.archOf("riscv64"))
    }
}
