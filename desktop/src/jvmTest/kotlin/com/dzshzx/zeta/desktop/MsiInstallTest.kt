package com.dzshzx.zeta.desktop

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Windows 上装 MSI 的脚本和重开命令（[MsiScript]）照 Windows 的路径写法核对；构建时写进 jar 的版本（[DesktopVersion]）读得出来。 */
class MsiInstallTest {
    private val home = """C:\Users\Zeta User\AppData\Local\Programs\Zeta"""

    private fun relaunch(
        arguments: List<String>? = null,
        classPath: String = "",
        javaCommand: String? = MsiScript.MAIN_CLASS,
    ) =
        MsiScript.relaunch(
            command = """$home\runtime\bin\javaw.exe""",
            arguments = arguments,
            fallbackCommand = "unused",
            jvmOptions = emptyList(),
            classPath = classPath,
            javaCommand = javaCommand,
            workDir = home,
            pathSeparator = ';',
            separator = '\\',
        )

    @Test
    fun argumentsAreQuotedTheWayWindowsSplitsThem() {
        assertEquals("/i plain", MsiScript.commandLine(listOf("/i", "plain")))
        assertEquals(""""C:\a b\Zeta.msi"""", MsiScript.commandLine(listOf("""C:\a b\Zeta.msi""")))
        assertEquals("\"\"", MsiScript.commandLine(listOf("")))
        // 引号前的反斜杠加倍再转义引号；结尾的反斜杠在收尾的引号前加倍
        assertEquals(""""say \"hi\""""", MsiScript.commandLine(listOf("""say "hi"""")))
        assertEquals(""""a\\\"b"""", MsiScript.commandLine(listOf("""a\"b""")))
        assertEquals(""""C:\a b\\"""", MsiScript.commandLine(listOf("""C:\a b\""")))
        assertEquals("'it''s'", MsiScript.literal("it's"))
    }

    @Test
    fun theScriptWaitsInstallsAndReopensWithPathsThatHaveSpaces() {
        val msi = """C:\Users\Zeta User\AppData\Local\Zeta\update\Zeta-v0.7.6.msi"""
        val log = """C:\Users\Zeta User\AppData\Roaming\Zeta\diag\msi-install.log"""
        val launch = relaunch(classPath = """$home\app\a.jar;$home\app\b.jar""")
        val script = MsiScript.script(4242, msi, launch, log)
        assertEquals(
            listOf(
                "Wait-Process -Id 4242 -Timeout 120 -ErrorAction SilentlyContinue",
                "if (Get-Process -Id 4242 -ErrorAction SilentlyContinue) { exit 1 }",
                "Start-Process -FilePath 'msiexec.exe' -ArgumentList '/i \"$msi\" /passive /norestart /l \"$log\"' -Wait",
                "Start-Process -FilePath '$home\\runtime\\bin\\javaw.exe' " +
                    "-ArgumentList '-cp \"$home\\app\\*\" com.dzshzx.zeta.desktop.MainKt' -WorkingDirectory '$home'",
            ),
            script.lines(),
        )
        // 单引号在 PowerShell 的字符串里写两遍
        val quoted =
            MsiScript.script(1, """C:\O'Brien\Zeta.msi""", relaunch(listOf("-jar", "x.jar")), "l")
        assertTrue(quoted, quoted.contains("""'/i C:\O''Brien\Zeta.msi /passive"""))
        // 交给 PowerShell 的是 UTF-16LE 的 base64，解出来就是原脚本
        val command = MsiScript.powershell("""C:\Windows""", script)
        assertEquals(
            """C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe""",
            command.first(),
        )
        assertEquals("-EncodedCommand", command[command.size - 2])
        assertEquals(script, String(Base64.getDecoder().decode(command.last()), Charsets.UTF_16LE))
        assertEquals("powershell.exe", MsiScript.powershell(null, script).first())
    }

    @Test
    fun theRelaunchCommandKeepsTheClassPathWildcard() {
        // 能取到参数（Linux 上）就原样用
        assertEquals(
            listOf("-cp", "app/*", "Main"),
            relaunch(listOf("-cp", "app/*", "Main")).arguments,
        )
        // Windows 上取不到：开始菜单的 -cp "<安装目录>\app\*" 被展开成一个个 jar，要还原成通配
        val expanded = """$home\app\org.jetbrains.compose.ui-1.9.jar;$home\app\desktop-jvm.jar"""
        assertEquals(
            listOf("-cp", """$home\app\*""", MsiScript.MAIN_CLASS),
            relaunch(classPath = expanded).arguments,
        )
        // 不是同一个目录下的 jar 就照原样
        val mixed = """$home\app\a.jar;$home\classes"""
        assertEquals(
            listOf("-cp", mixed, MsiScript.MAIN_CLASS),
            relaunch(classPath = mixed).arguments,
        )
        // 没有 sun.java.command 时用入口类
        assertEquals(MsiScript.MAIN_CLASS, relaunch(javaCommand = null).arguments.single())
        assertEquals(home, relaunch().workDir)
    }

    @Test
    fun theBuildWritesTheVersionIntoTheJar() {
        val current = DesktopVersion.current
        assertTrue(current.toString(), current.code > 0)
        assertTrue(
            current.name,
            Regex("""\d+\.\d+\.\d+(-dev\.\d+\+g[0-9a-f]+)?""").matches(current.name),
        )
        assertEquals(
            DesktopVersion(905, "0.7.6"),
            DesktopVersion.parse("versionCode=905\nversionName=0.7.6\n"),
        )
        assertTrue(DesktopVersion.parse("versionCode=905\nversionName=0.7.6\n").isRelease)
        assertFalse(
            DesktopVersion.parse("versionCode=905\nversionName=0.7.6-dev.1+gabc1234\n").isRelease,
        )
        assertEquals(DesktopVersion.Unknown, DesktopVersion.parse("versionName=0.7.6\n"))
        assertEquals(
            DesktopVersion.Unknown,
            DesktopVersion.parse("versionCode=x\nversionName=0.7.6\n"),
        )
    }
}
