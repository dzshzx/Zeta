package com.dzshzx.zeta.desktop

import java.io.File
import java.io.IOException
import java.lang.management.ManagementFactory

/** 装下载好、核对过的 MSI。 */
fun interface MsiInstaller {
    /** 开始装 [msi]：起好安装进程返回 null，之后本程序要马上退出（见 [WindowsMsiInstaller]）；起不了返回原因。 */
    fun start(msi: File): String?
}

/** 一条要执行的命令：可执行文件、参数、工作目录。 */
data class Launch(val command: String, val arguments: List<String>, val workDir: String)

/**
 * Windows 上装 MSI：起一个脱离本进程的 PowerShell，它等本进程退出（MSI 要换掉正在用的 javaw.exe 和 jar）→ `msiexec /i <msi>
 * /passive /norestart` 等它装完 → 用本进程启动时的同一条命令重新打开 Zeta（装没装上都重开：没装上时 MSI 已回滚到旧版）。脚本写成 MSI 旁边的
 * install.ps1（UTF-8 带 BOM，Windows PowerShell 5.1 才按 UTF-8 读）交给 `-File`。不用
 * `-EncodedCommand`、`-WindowStyle Hidden`： 卡巴斯基把「隐藏窗口加编码命令」当恶意脚本，拒绝起进程，还会把本进程之后起的进程全拦下（在 nerv-win
 * 上实测）；Java 起子进程本来就不开控制台窗口。对话的 Claude Code 进程跑在远程主机上、由托管脚本管着，Zeta 退出不影响，重开后照常接上。
 *
 * 安装的记录写到 [log]（msiexec 的 `/l`），装不上时看它。
 */
class WindowsMsiInstaller(private val log: File) : MsiInstaller {
    override fun start(msi: File): String? {
        if (!isWindows()) return "只有 Windows 版能自动安装"
        val script =
            MsiScript.script(
                ProcessHandle.current().pid(),
                msi.path,
                MsiScript.relaunch(),
                log.path,
            )
        val file = File(msi.parentFile, SCRIPT)
        return try {
            log.parentFile?.mkdirs()
            file.writeBytes(MsiScript.scriptFile(script))
            ProcessBuilder(MsiScript.powershell(System.getenv("SystemRoot"), file.path))
                .redirectInput(ProcessBuilder.Redirect.from(File("NUL")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            null
        } catch (e: IOException) {
            "启动安装程序失败：${e.message}"
        }
    }

    private fun isWindows() = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private companion object {
        /** 安装脚本的文件名，和 MSI 放在一起（下次启动清理下载目录时一并删掉）。 */
        const val SCRIPT = "install.ps1"
    }
}

/** [WindowsMsiInstaller] 用的命令和脚本，都是纯函数，测试在 Linux 上照 Windows 的写法核对。 */
object MsiScript {
    /** 桌面版的入口类（Main.kt 的 main）。 */
    const val MAIN_CLASS = "com.dzshzx.zeta.desktop.MainKt"

    /** 本进程的重开命令：取 [ProcessHandle.Info] 记的命令和参数；Windows 上它只给可执行文件、不给参数，参数就照 JVM 自己记的拼回来。 */
    fun relaunch(): Launch {
        val info = ProcessHandle.current().info()
        val javaHome = System.getProperty("java.home")
        return relaunch(
            command = info.command().orElse(null),
            arguments = info.arguments().orElse(null)?.toList(),
            fallbackCommand =
                File(
                        javaHome,
                        "bin/" +
                            if (File(javaHome, "bin/javaw.exe").isFile) "javaw.exe" else "java",
                    )
                    .path,
            jvmOptions =
                runCatching { ManagementFactory.getRuntimeMXBean().inputArguments }
                    .getOrNull()
                    .orEmpty(),
            classPath = System.getProperty("java.class.path").orEmpty(),
            javaCommand = System.getProperty("sun.java.command"),
            workDir = System.getProperty("user.dir"),
            pathSeparator = File.pathSeparatorChar,
            separator = File.separatorChar,
        )
    }

    /**
     * 拼重开的命令：[arguments] 有就原样用；没有时（Windows）是 JVM 选项 [jvmOptions]、`-cp`
     * 加类路径、入口类和程序参数（[javaCommand]，即 `sun.java.command`）。类路径要还原成通配：开始菜单的快捷方式写的是 `-cp
     * "<安装目录>\app\*"`，启动器把它展开成一个个 jar，新版本的 jar 名字会变，照展开的旧名字重开就找不到类。
     */
    fun relaunch(
        command: String?,
        arguments: List<String>?,
        fallbackCommand: String,
        jvmOptions: List<String>,
        classPath: String,
        javaCommand: String?,
        workDir: String,
        pathSeparator: Char,
        separator: Char,
    ): Launch {
        val exe = command?.takeIf { it.isNotBlank() } ?: fallbackCommand
        if (!arguments.isNullOrEmpty()) return Launch(exe, arguments, workDir)
        val main = javaCommand?.trim()?.takeIf { it.isNotEmpty() }?.split(' ') ?: listOf(MAIN_CLASS)
        val cp =
            classPath
                .takeIf { it.isNotEmpty() }
                ?.let { listOf("-cp", wildcard(it, pathSeparator, separator)) }
        return Launch(exe, jvmOptions + cp.orEmpty() + main, workDir)
    }

    /** 类路径里全是同一个目录下的 jar 时换成 `<目录>\*`，否则原样。 */
    fun wildcard(classPath: String, pathSeparator: Char, separator: Char): String {
        val entries = classPath.split(pathSeparator).filter { it.isNotEmpty() }
        val dirs =
            entries.map { it.substringBeforeLast(separator, missingDelimiterValue = "") }.toSet()
        val dir = dirs.singleOrNull() ?: return classPath
        return if (dir.isNotEmpty() && entries.all { it.endsWith(".jar", ignoreCase = true) })
            "$dir$separator*"
        else classPath
    }

    /** 安装脚本：等进程 [pid] 退出，装 [msi]（记录写到 [log]），再执行 [relaunch]。本进程过两分钟还没退出就不装了。 */
    fun script(pid: Long, msi: String, relaunch: Launch, log: String): String {
        val install = commandLine(listOf("/i", msi, "/passive", "/norestart", "/l", log))
        val reopen = buildString {
            append("Start-Process -FilePath ").append(literal(relaunch.command))
            if (relaunch.arguments.isNotEmpty())
                append(" -ArgumentList ").append(literal(commandLine(relaunch.arguments)))
            append(" -WorkingDirectory ").append(literal(relaunch.workDir))
        }
        return listOf(
                "Wait-Process -Id $pid -Timeout 120 -ErrorAction SilentlyContinue",
                "if (Get-Process -Id $pid -ErrorAction SilentlyContinue) { exit 1 }",
                "Start-Process -FilePath 'msiexec.exe' -ArgumentList ${literal(install)} -Wait",
                reopen,
            )
            .joinToString("\n")
    }

    /**
     * 起 PowerShell 执行脚本文件 [scriptPath] 的命令。[systemRoot] 是 %SystemRoot%。`-ExecutionPolicy Bypass`
     * 只管这一次， 免得系统策略禁了本地脚本。
     */
    fun powershell(systemRoot: String?, scriptPath: String): List<String> {
        val exe =
            systemRoot
                ?.takeIf { it.isNotBlank() }
                ?.let { "$it\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" }
                ?: "powershell.exe"
        return listOf(
            exe,
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            scriptPath,
        )
    }

    /** 脚本文件的内容：UTF-8 带 BOM。Windows PowerShell 5.1 读不带 BOM 的文件按系统代码页解码，中文路径会乱。 */
    fun scriptFile(script: String): ByteArray =
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            script.toByteArray(Charsets.UTF_8)

    /** PowerShell 的单引号字符串：里面的单引号写两遍，别的字符都不转义。 */
    fun literal(text: String): String = "'" + text.replace("'", "''") + "'"

    /** 把参数拼成 Windows 的命令行（CommandLineToArgvW 的规则）：带空格、引号或为空的加双引号，引号前的反斜杠加倍。 */
    fun commandLine(arguments: List<String>): String =
        arguments.joinToString(" ", transform = ::quote)

    private fun quote(arg: String): String {
        if (arg.isNotEmpty() && arg.none { it == ' ' || it == '\t' || it == '"' }) return arg
        val out = StringBuilder("\"")
        var slashes = 0
        for (c in arg) {
            when (c) {
                '\\' -> slashes++
                '"' -> {
                    out.append("\\".repeat(slashes * 2 + 1)).append('"')
                    slashes = 0
                }
                else -> {
                    out.append("\\".repeat(slashes)).append(c)
                    slashes = 0
                }
            }
        }
        return out.append("\\".repeat(slashes * 2)).append('"').toString()
    }
}
