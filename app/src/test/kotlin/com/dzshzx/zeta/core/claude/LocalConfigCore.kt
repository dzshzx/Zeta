package com.dzshzx.zeta.core.claude

import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.HelperProgram
import com.dzshzx.zeta.core.HelperServe
import com.dzshzx.zeta.core.LinuxShell
import com.dzshzx.zeta.core.TestHelper
import java.io.File

/**
 * 临时 HOME [home] 上真的 `zeta-helper serve`（本机架构那份，第一次用时装进 ~/.cache/zeta/bin）和经它读写 Claude Code 配置的
 * [client]，同手机上的 [ClaudeConfigCore.of]，只是连接直接在这台机器上起。核心经登录 shell 跑 claude：[fakeClaude] 时在
 * ~/.local/bin 放假的 claude（helper/claudecfg/testdata/fakeclaude）、~/.profile 把它放进 PATH，不碰本机真的
 * claude。用完 [close]（临时目录由调用方删）。
 */
class LocalConfigCore(private val home: File, fakeClaude: Boolean = true) : AutoCloseable {
    init {
        if (fakeClaude) {
            val bin = File(home, ".local/bin").apply { mkdirs() }
            java.nio.file.Files.createSymbolicLink(
                File(bin, "claude").toPath(),
                TestHelper.fakeClaude.toPath(),
            )
            File(home, ".profile")
                .appendText("export PATH=\"\$HOME/.local/bin:\$PATH\"\nexport TOOL_PREFIX=fake_\n")
        }
    }

    private val shell =
        object : LinuxShell {
            override val home: String = this@LocalConfigCore.home.path

            override fun start(command: String): Process {
                val builder =
                    ProcessBuilder("bash", "-c", command).directory(this@LocalConfigCore.home)
                builder.environment().apply {
                    clear()
                    put("HOME", home)
                    put("PATH", "/usr/bin:/bin")
                    put("LANG", "C.UTF-8")
                    put(AppVariant.LINUX_NAME_ENV, "zeta")
                }
                return builder.start()
            }

            override fun kill(process: Process) {
                process.destroyForcibly()
            }
        }

    private val serve =
        HelperServe(
            name = "config-test",
            open = { HelperServe.open(shell, TestHelper.program(HelperProgram.ZETA_HELPER)) },
            subscriptions = emptyList(),
            onMessage = {},
            onProblem = {},
        )

    val client = ClaudeConfigCore { method, params, timeoutMs ->
        serve.call(method, params, timeoutMs)
    }

    override fun close() = serve.close()
}
