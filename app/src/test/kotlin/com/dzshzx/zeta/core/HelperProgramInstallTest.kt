package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 几处同时装同一个程序（几个对话同时起来）时都装得上，装完只剩程序和不带版本的链接。 链接要先建在临时名字上再改名换过去：ln -sfn 是先删后建，同时装时会撞上别人刚建的链接而失败。
 * 桌面版同一段命令由 client/hostlink 的 TestConcurrentInstallsAllSucceed 钉住。
 */
class HelperProgramInstallTest {
    @Test
    fun concurrentInstallsAllSucceed() {
        val program = HelperProgram({ ByteArray(0) }, "zeta-test")
        val binary = "#!/bin/sh\necho ok\n".toByteArray()
        val command = program.installCommand(binary)
        val home = Files.createTempDirectory("zeta-install").toFile()
        try {
            repeat(ROUNDS) { installTogether(command, binary, home) }
            val dir = File(home, ".cache/zeta-test/bin")
            val name = HelperProgram.ZETA_HELPER
            assertEquals(
                "$name-${program.version}",
                Files.readSymbolicLink(File(dir, name).toPath()).toString(),
            )
            // 临时文件、临时链接都不留
            assertEquals(listOf(name, "$name-${program.version}"), dir.list()!!.sorted())
        } finally {
            home.deleteRecursively()
        }
    }

    private fun installTogether(command: String, binary: ByteArray, home: File) {
        val start = CountDownLatch(1)
        val failures = Collections.synchronizedList(ArrayList<String>())
        val workers =
            List(TOGETHER) {
                thread {
                    start.await()
                    val process =
                        ProcessBuilder("bash", "-c", command)
                            .redirectErrorStream(true)
                            .apply { environment()["HOME"] = home.path }
                            .start()
                    process.outputStream.use { it.write(binary) }
                    val output = process.inputStream.bufferedReader().readText()
                    if (process.waitFor() != 0) failures.add(output)
                }
            }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(emptyList<String>(), failures)
    }

    private companion object {
        const val ROUNDS = 10
        const val TOGETHER = 16
    }
}
