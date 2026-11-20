package com.dzshzx.zeta.core

import java.io.File

/**
 * 单元测试里直接跑的 zeta-helper：Gradle 的 zetaHelper 任务编出的、本机架构的那一份（目录由测试任务经 zeta.helper.dir 传进来）。
 * 测试在临时 HOME 上跑它，不碰任何人真实的 ~/.claude。
 */
object TestHelper {
    val binary: File by lazy {
        val dir = checkNotNull(System.getProperty("zeta.helper.dir")) { "测试任务没有传 zeta.helper.dir（见 app/build.gradle.kts）" }
        val arch = checkNotNull(HelperProgram.archOf(System.getProperty("os.arch"))) { "zeta-helper 没有 ${System.getProperty("os.arch")} 的版本" }
        File(dir, "linux-$arch").also { check(it.canExecute()) { "没有可执行的 $it" } }
    }

    val path: String get() = binary.path
}
