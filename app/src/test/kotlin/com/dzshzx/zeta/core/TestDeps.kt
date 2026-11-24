package com.dzshzx.zeta.core

import java.io.File
import org.junit.AssumptionViolatedException

/**
 * 测试要用的本机依赖（真的 claude、jq、Linux 的 /proc 和 bash）。缺了在本机失败，只在 CI（环境变量 CI 非空）里跳过并打印一行： 不然门禁环境的 PATH
 * 里哪天找不到 claude，几十个契约测试会一起悄悄跳过，门禁照样显示通过。
 */
object TestDeps {
    /** 找本机命令的 PATH：Claude Code 原生安装放在 ~/.local/bin，测试进程的 PATH 里不一定有它。契约测试起真 claude 时也用它当 PATH。 */
    val searchPath: String =
        listOfNotNull(
                System.getProperty("user.home")?.let { "$it/.local/bin" },
                System.getenv("PATH"),
            )
            .joinToString(":")

    fun onPath(command: String): Boolean =
        searchPath.split(':').any { File(it, command).canExecute() }

    /** 要 [what]：[present] 为假时在本机让测试失败，在 CI 里跳过。 */
    fun require(what: String, present: Boolean) {
        if (present) return
        // 跳过时 Gradle 打一行 SKIPPED（app/build.gradle.kts 的 testLogging）
        if (!System.getenv("CI").isNullOrEmpty()) throw AssumptionViolatedException("CI 上缺 $what")
        throw AssertionError("缺 $what：本机测试要它，装上再跑（只在 CI 里跳过）")
    }

    fun requireClaude() = require("claude 命令", onPath("claude"))

    fun requireJq() = require("jq", onPath("jq"))

    /** 托管脚本和各种 shell 测试要 Linux 的 /proc 和 bash。 */
    fun requireLinux() = require("Linux 的 /proc 和 bash", File("/proc/self/cmdline").exists())
}
