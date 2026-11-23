package com.dzshzx.zeta.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue

/**
 * 测试收尾：结束用着 [dirs] 的进程，等它们都退出了再删这些目录。「用着」是当前目录或任一环境变量的值在这些目录里：经 [HostTesting.builder] 起的进程的
 * HOME、核心的 ZETA_CORE_DIR 都算，子进程和 setsid 甩出去的进程都继承环境，所以不用登记。 不等就删时，还在跑的进程（托管脚本的收尾、假 Claude
 * 退出前的清理）会和删除同时改目录，deleteRecursively 遍历到一半 目录没了就抛 AssertionError。结束后每 10 毫秒再查一次，期间新 fork 出来的也结束；最多等
 * 60 秒，只防卡死，到点还有就失败。 只看比这个测试进程晚起的进程：别的进程不会用着测试新建的临时目录，也省得去读它们的环境。
 */
internal fun removeTestDir(vararg dirs: File) {
    val roots = dirs.flatMap { listOf(it.absolutePath, it.canonicalPath) }.toSet()
    fun inside(path: String) = roots.any { path == it || path.startsWith("$it/") }
    val deadline = System.nanoTime() + 60_000_000_000L
    while (true) {
        val left = processesUsing(::inside)
        if (left.isEmpty()) break
        assertTrue("${dirs.toList()} 还有进程用着：$left", System.nanoTime() < deadline)
        left.forEach { pid -> ProcessHandle.of(pid.toLong()).ifPresent { it.destroyForcibly() } }
        Thread.sleep(10)
    }
    dirs.forEach { it.deleteRecursively() }
}

private val selfStart: Long = startTime(ProcessHandle.current().pid().toInt())!!

/** 比这个测试进程晚起、还在跑、当前目录或某个环境变量的值让 [inside] 认出来的进程号。 */
private fun processesUsing(inside: (String) -> Boolean): List<Int> =
    File("/proc")
        .list()
        .orEmpty()
        .mapNotNull { it.toIntOrNull() }
        .filter { pid ->
            val start = startTime(pid)
            start != null &&
                start > selfStart &&
                (cwdOf(pid)?.let(inside) == true || environValues(pid).any(inside))
        }

/** 进程 [pid] 的启动时刻（/proc/<pid>/stat 第 22 项，开机后的时钟滴答数）；进程没了或是僵尸时是 null。 */
private fun startTime(pid: Int): Long? {
    val fields =
        runCatching { File("/proc/$pid/stat").readText() }
            .getOrNull()
            ?.substringAfterLast(") ")
            ?.split(' ') ?: return null
    // fields[0] 是第 3 项（状态）
    return if (fields[0] == "Z") null else fields[19].toLong()
}

private fun cwdOf(pid: Int): String? = runCatching {
    Files.readSymbolicLink(Path.of("/proc/$pid/cwd")).toString()
}
    .getOrNull()

private fun environValues(pid: Int): List<String> = runCatching {
    File("/proc/$pid/environ").readText()
}
    .getOrDefault("")
    .split('\u0000')
    .map { it.substringAfter('=') }
