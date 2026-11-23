package com.dzshzx.zeta.core

import java.io.File

/**
 * 单元测试里直接跑的随包程序：Gradle 的 GoProgramTask（zetaHelper、claudeSwitch 任务）编出的那几份 （目录由测试任务经
 * zeta.programs.<程序名> 传进来，见 app/build.gradle.kts）。 测试在临时 HOME 上跑它们，不碰任何人真实的 ~/.claude，也不依赖开发机上装了什么。
 */
object TestHelper {
    /** zeta-helper 本机架构的那一份。 */
    val binary: File by lazy {
        val arch =
            checkNotNull(HelperProgram.archOf(System.getProperty("os.arch"))) {
                "zeta-helper 没有 ${System.getProperty("os.arch")} 的版本"
            }
        File(dir(HelperProgram.ZETA_HELPER), "linux-$arch").also {
            check(it.canExecute()) { "没有可执行的 $it" }
        }
    }

    val path: String
        get() = binary.path

    /** 假的 claude（helper/claudecfg/testdata/fakeclaude，Gradle 的 fakeClaudeHost 任务编的这台机器的一份）。 */
    val fakeClaude: File by lazy {
        File(
                checkNotNull(System.getProperty("zeta.programs.fakeclaude")) {
                    "测试任务没有传 zeta.programs.fakeclaude（见 app/build.gradle.kts）"
                },
            )
            .also { check(it.canExecute()) { "没有可执行的 $it" } }
    }

    /**
     * 随包程序 [name] 的 [HelperProgram]，两份都读构建出的文件：和应用里一样，第一次用时装进临时 HOME 的 ~/.cache/[linuxName]/bin。
     */
    fun program(name: String, linuxName: String = "zeta"): HelperProgram =
        HelperProgram(
            { asset -> File(dir(name), asset.substringAfter('/')).readBytes() },
            linuxName,
            name,
        )

    private fun dir(name: String): File =
        File(
            checkNotNull(System.getProperty("zeta.programs.$name")) {
                "测试任务没有传 zeta.programs.$name（见 app/build.gradle.kts）"
            },
        )
}
