package com.dzshzx.zeta.phone.tools

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.CommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** app_control 的 install、uninstall、clear_data：经假的 root shell 走一遍，核对发了哪些命令、怎么报结果。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AppPackagesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** 按命令开头答话的假 shell，记下收到的命令。 */
    private class FakeShell(private val answers: List<Pair<String, CommandResult>>) : Shell {
        val commands = ArrayList<String>()

        override fun run(command: String, timeoutMs: Long): CommandResult {
            commands += command
            return answers.firstOrNull { command.startsWith(it.first) }?.second
                ?: CommandResult(0, "", "")
        }
    }

    private fun ok(out: String) = CommandResult(0, out, "")

    private fun failure(block: () -> Unit): String =
        (runCatching(block).exceptionOrNull() as? ToolError)?.message ?: "没有抛 ToolError"

    @Test
    fun uninstallChecksAndVerifies() {
        // 先装着，卸完读回来没了
        var listed = 0
        val sh =
            object : Shell {
                val commands = ArrayList<String>()

                override fun run(command: String, timeoutMs: Long): CommandResult {
                    commands += command
                    return when {
                        command.startsWith("pm list packages") ->
                            ok(if (listed++ == 0) "package:com.x\n" else "")
                        command.startsWith("pm uninstall") -> ok("Success\n")
                        else -> ok("")
                    }
                }
            }
        val text = AppPackages.uninstall(app, sh, "com.x", keepData = true) { "X ($it)" }
        assertEquals("uninstalled X (com.x)（留着数据，再装同一个包时还在）", text)
        assertEquals("pm uninstall -k --user 0 'com.x'", sh.commands[1])

        val missing = FakeShell(listOf("pm list packages" to ok("package:com.x.y\n")))
        assertEquals(
            "没有安装 com.x",
            failure { AppPackages.uninstall(app, missing, "com.x", false) { it } },
        )
        val refused =
            FakeShell(
                listOf(
                    "pm list packages" to ok("package:com.x\n"),
                    "pm uninstall" to
                        CommandResult(1, "Failure [DELETE_FAILED_INTERNAL_ERROR]", ""),
                ),
            )
        assertEquals(
            "卸载失败：Failure [DELETE_FAILED_INTERNAL_ERROR]",
            failure { AppPackages.uninstall(app, refused, "com.x", false) { it } },
        )
    }

    @Test
    fun clearDataNeedsSuccess() {
        val sh =
            FakeShell(
                listOf("pm list packages" to ok("package:com.x\n"), "pm clear" to ok("Success\n")),
            )
        assertEquals("cleared data of com.x", AppPackages.clearData(sh, "com.x") { it })
        assertEquals("pm clear --user 0 'com.x'", sh.commands.last())
        val failed =
            FakeShell(
                listOf("pm list packages" to ok("package:com.x\n"), "pm clear" to ok("Failed\n")),
            )
        assertEquals("清除数据失败：Failed", failure { AppPackages.clearData(failed, "com.x") { it } })
    }

    @Test
    fun installExplainsBadPaths() {
        assertTrue(
            failure { AppPackages.install(app, FakeShell(emptyList()), "a.apk") }.contains("绝对路径"),
        )
        // 没有 Linux 环境时，Linux 里的路径无从换算
        assertTrue(
            failure { AppPackages.install(app, FakeShell(emptyList()), "/home/zeta/a.apk") }
                .contains("还没有 Linux 环境"),
        )
        val missing = FakeShell(listOf("[ -e" to CommandResult(4, "", "")))
        val text = failure { AppPackages.install(app, missing, "/sdcard/Download/a.apk") }
        assertTrue(text, text.startsWith("手机上没有这个文件：/sdcard/Download/a.apk"))
        // 抄过来的不是 APK（这里是空文件）：不交给 pm
        val notApk = FakeShell(emptyList())
        val bad = failure { AppPackages.install(app, notApk, "/sdcard/a.apk") }
        assertTrue(bad, bad.contains("不是能装的 APK"))
        assertTrue(notApk.commands.none { "pm install" in it })
    }
}
