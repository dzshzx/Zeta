package com.dzshzx.zeta.core

import com.dzshzx.zeta.i18n.Strings
import java.security.MessageDigest

/** 跑 zeta-helper 的一个子命令（子命令、参数）：调用方已经定好在哪个 Linux 环境里跑。 */
typealias HelperRun = (subcommand: String, arguments: List<String>) -> HelperResult

/**
 * zeta-helper 跑一个子命令的结果。它的约定（`helper/main.go`）：退出码 0 才算跑成，命令里的出错（路径不存在等）写在输出的 JSON 里；
 * 用法不对、命令本身失败时退出码非 0，原因在标准错误。所以只有 [Ok] 有输出，没跑成的只带原因：
 * 没跑成不能当作“输出为空”（会话列表清空、后台对话当作没有），调用方要么用输出，要么处理原因。
 */
sealed interface HelperResult {
    class Ok(val output: String) : HelperResult

    /** 没跑成：进不去 Linux 环境、超时、装不上 zeta-helper，或者它以非 0 退出。[reason] 给人看。 */
    class Failed(val reason: String) : HelperResult

    /** 跑成了的输出；没跑成是 null。 */
    val outputOrNull: String?
        get() = (this as? Ok)?.output

    companion object {
        /** 由一次执行的结果判断：[result] 为 null 是进程没起来。 */
        fun of(result: CommandResult?): HelperResult =
            when {
                result == null -> Failed(Strings.coreCantEnterLinuxEnv())
                result.exitCode == 0 -> Ok(result.stdout)
                result.timedOut -> Failed(Strings.coreHelperTimeout())
                else ->
                    Failed(
                        result.stderr.trim().takeLast(300).ifEmpty {
                            Strings.coreHelperExit(result.exitCode.toString())
                        },
                    )
            }
    }
}

/**
 * APK 里带着、在 Linux 一侧跑的 Go 程序 [name]：zeta-helper（读写 Claude Code 的会话记录、做存储统计和清理，源码在仓根 `helper/`，
 * 子命令见那里的 main.go），claude-switch（账号页用它管多个 Claude 账号，按 helper/go.mod 的 tool 编进来）。 每个程序带 arm64、amd64
 * 两份（[ARCHES]），由 [read] 按资源名（[asset]）取出。
 *
 * 程序不随每条命令传过去：第一次用时装到那边用户的 `~/.cache/<目录名>/bin/<程序名>-<版本>`（[relativePath]），之后的命令直接执行装好的。 [command]
 * 先看装没装，没装就报出那台机器的 CPU 架构、以 [MISSING] 退出；[run] 这才把对应的一份经标准输入传过去装上
 * （[install]），再跑一次。版本是两份程序内容的哈希，应用升级换了程序就装新的、删掉同名程序的旧版本。 两个命令同时去装也不要紧：各自写自己的临时文件，核对完整再改名。
 */
class HelperProgram(
    private val read: (String) -> ByteArray,
    private val linuxName: String,
    val name: String = ZETA_HELPER,
) {
    private val binaries: Map<String, ByteArray> by lazy {
        ARCHES.associateWith { read(asset(it)) }
    }

    /** 两份程序合起来的哈希，取前 16 位：装好的文件名带着它。 */
    val version: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        ARCHES.forEach { digest.update(binaries.getValue(it)) }
        hex(digest.digest()).take(16)
    }

    /** 装好的程序相对那边家目录的路径。 */
    val relativePath: String
        get() = ".cache/$linuxName/bin/$name-$version"

    /** 架构 [arch] 的那份程序在 assets 里的名字（构建时由 app/build.gradle.kts 的 GoProgramTask 编出来）。 */
    fun asset(arch: String): String = "$name/linux-$arch"

    /** 没装时 [command] 在标准错误里打的记号，后面跟 `uname -m`。 */
    private val missingMark: String
        get() = "$name-missing"

    private val directory: String
        get() = "\"\$HOME/.cache/$linuxName/bin\""

    private val file: String
        get() = "\"\$HOME/$relativePath\""

    private val check: String
        get() =
            "f=$file; [ -x \"\$f\" ] || { echo \"$missingMark \$(uname -m)\" >&2; exit $MISSING; }"

    /** 在 Linux 里跑子命令 [subcommand] 的那一行，参数逐个加引号：装好了就执行，没装就报架构、以 [MISSING] 退出。 */
    fun command(subcommand: String, arguments: List<String> = emptyList()): String =
        "$check; exec \"\$f\" $subcommand" + arguments.joinToString("") { " " + LinuxEnv.quote(it) }

    /**
     * 经 [exec]（在那台 Linux 的登录 shell 里跑一行，可带标准输入）跑子命令 [subcommand]；没装就先装上再跑一次。 [exec] 返回
     * null（进不去、起不来）时也是 null。阻塞。
     */
    fun run(
        exec: (command: String, stdin: ByteArray?) -> CommandResult?,
        subcommand: String,
        arguments: List<String> = emptyList(),
    ): CommandResult? = installing(exec) { exec(command(subcommand, arguments), null) }

    /** 只确认装好了（没装就装上），不跑子命令：边读边出结果、要自己起进程的命令（search）起之前用。 装好了（或刚装上）返回退出码 0 的结果。 */
    fun ensure(exec: (command: String, stdin: ByteArray?) -> CommandResult?): CommandResult? =
        installing(exec) { exec(check, null) }

    private fun installing(
        exec: (String, ByteArray?) -> CommandResult?,
        attempt: () -> CommandResult?,
    ): CommandResult? {
        val first = attempt() ?: return null
        val machine = missingOn(first) ?: return first
        val installed = install(exec, machine) ?: return null
        if (installed.exitCode != 0) return installed
        return attempt()
    }

    /**
     * 经 [exec] 把 `uname -m` 报 [machine] 的那台需要的一份装上（不先看装没装：调用方已经知道没装）。 装上了是退出码 0；没有那台能用的版本时以
     * [MISSING] 结束，装不上时是那次的退出码，原因都在标准错误里。 [exec] 返回 null（进不去、起不来）时也是 null。阻塞。
     */
    fun install(
        exec: (command: String, stdin: ByteArray?) -> CommandResult?,
        machine: String,
    ): CommandResult? {
        val binary =
            archOf(machine)?.let(binaries::getValue)
                ?: return CommandResult(MISSING, "", Strings.coreProgramNoBuild(name, machine))
        val installed = exec(installCommand(binary), binary) ?: return null
        if (installed.exitCode != 0)
            return CommandResult(
                installed.exitCode,
                installed.stdout,
                Strings.coreProgramInstallFailed(name, installed.stderr.trim()),
            )
        return installed
    }

    /**
     * 把经标准输入传来的程序 [binary] 装上：写进同一目录下的临时文件，核对哈希（传到一半断了的不装），改名成正式的名字， 再删掉这个程序别的版本（别的程序的不动）， 最后让不带版本的
     * `<程序名>` 链接指向它：Zeta 之外要调它的（电脑那边 tmux 窗口里的 zeta-helper desk、Claude Code 的 SessionStart
     * hook）用这个固定的名字。
     */
    fun installCommand(binary: ByteArray): String {
        val sha = hex(MessageDigest.getInstance("SHA-256").digest(binary))
        // 和桌面版 client/hostlink 的 InstallCommand 是同一段命令（链接为什么先建在临时名字上见那里），两份都要拼成
        // client/hostlink/testdata/install-command.txt（HelperProgramTest 核对）
        val link =
            if (name == ZETA_HELPER)
                "l=\"\$d/${tempPrefix(name)}.link.\$\$\"; ln -sfn \"$name-$version\" \"\$l\" && mv -f \"\$l\" \"\$d/$name\"; "
            else ""
        return "d=$directory; mkdir -p \"\$d\" && t=\$(mktemp \"\$d/${tempPrefix(name)}.XXXXXX\") || exit 1; " +
            "if cat > \"\$t\" && [ \"\$(sha256sum < \"\$t\")\" = \"$sha  -\" ] && chmod 755 \"\$t\" && mv -f \"\$t\" $file; then " +
            "for o in \"\$d\"/$name-*; do [ \"\$o\" = $file ] || rm -f \"\$o\"; done; " +
            link +
            "else rm -f \"\$t\"; echo '$name 没有完整传过去' >&2; exit 1; fi"
    }

    /** [result] 是「没装」的话，那台机器的 `uname -m`；别的情况是 null。 */
    fun missingOn(result: CommandResult): String? {
        if (result.exitCode != MISSING) return null
        return result.stderr
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$missingMark ") }
            ?.removePrefix(missingMark)
            ?.trim()
    }

    companion object {
        /** APK 里带的两份：手机的 Debian 是 arm64，模拟器和多数远程主机是 amd64。 */
        val ARCHES = listOf("arm64", "amd64")

        /** APK 里带的程序：名字也是 assets 里的目录名和装好的文件名的前缀，彼此不能是对方的前缀（装新版时按前缀删旧版）。 */
        const val ZETA_HELPER = "zeta-helper"
        const val CLAUDE_SWITCH = "claude-switch"

        /** 没装时 [command] 的退出码；标准错误里有一行 `<程序名>-missing` 加 `uname -m`。 */
        const val MISSING = 97

        /** 装程序 [name] 时临时文件名的前缀（诊断日志据此认出这是在装）。 */
        fun tempPrefix(name: String): String = ".$name"

        /** `uname -m` 报的 [machine] 对应 [ARCHES] 里的哪一份；不认识的是 null。 */
        fun archOf(machine: String): String? =
            when (machine) {
                "aarch64",
                "arm64" -> "arm64"
                "x86_64",
                "amd64" -> "amd64"
                else -> null
            }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
