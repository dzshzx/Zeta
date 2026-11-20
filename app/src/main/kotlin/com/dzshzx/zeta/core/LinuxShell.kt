package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.diag.DiagEvents
import com.dzshzx.zeta.core.ssh.SshLinks
import com.dzshzx.zeta.core.ssh.SshTarget
import java.util.TimeZone

/**
 * 以普通用户身份跑命令的一台 Linux：手机里的 Debian（[EnvShell]），或经 SSH 连过去的远程主机（[RemoteShell]）。
 * Claude Code 进程、会话记录、附件、Claude Code 的配置都经它读写，调用方不管命令落在哪台机器上。
 * 测试里可以换成别的（比如直接起 bash、用临时 HOME）。
 */
interface LinuxShell {
    /** 这个用户的家目录。 */
    val home: String

    /** 起一个进程跑 [command]（交给登录 shell 的一段命令），调用方负责读写和收尾。 */
    fun start(command: String): Process

    /** 结束 [start] 起的进程，连同它派生的子进程。 */
    fun kill(process: Process)

    /** 跑完 [command] 收齐输出；[stdin] 写进标准输入后关掉。到 [timeoutMs] 还没结束就结束它，结果标成超时（[CommandResult.timedOut]）。 */
    fun run(command: String, stdin: String? = null, timeoutMs: Long = 30_000): CommandResult = run(command, stdin?.toByteArray(), timeoutMs)

    /** 同上，标准输入是原样的字节（装 zeta-helper 时传程序本身，见 [HelperProgram]）。 */
    fun run(command: String, stdin: ByteArray?, timeoutMs: Long): CommandResult =
        LinuxEnv.capture(start(command), stdin, timeoutMs, ::kill).also { DiagEvents.linuxRun(command, it) }
}

/** 手机里的 Debian：经 su 进去（[LinuxEnv]）。 */
class EnvShell(private val config: LinuxEnvConfig) : LinuxShell {
    override val home: String get() = config.home

    override fun start(command: String): Process = LinuxEnv.start(config, command)

    override fun kill(process: Process) = LinuxEnv.kill(process)
}

/**
 * 远程主机上的 shell：命令在那边用户的登录 shell 环境里跑（[remoteCommand]），连接断了的命令以 [CONNECTION_FAILED] 结束，
 * 托管出去的 Claude Code 进程不受影响（[ClaudeProcess] 据此重连）。两种连法：经手机 Debian 里的 OpenSSH（[DebianSshShell]），
 * 或 Zeta 自己连（[DirectSshShell]），见 [RemoteHost.via]。
 */
sealed class RemoteShell(protected val host: RemoteHost, private val timeZone: String, private val linuxName: String) : LinuxShell {
    override val home: String get() = host.home

    /** 交给那边登录 shell 的一行（[remoteCommand]）。 */
    protected fun wrap(command: String): String = remoteCommand(command, linuxName, timeZone)

    companion object {
        /** 连不上（网络、认证、主机密钥不对）或连接断了时的退出码，和 ssh 一样；远端命令的退出码原样带回。 */
        const val CONNECTION_FAILED = SshLinks.CONNECTION_FAILED

        /**
         * 交给远程登录 shell（bash、zsh 都行）的一行：换成 bash 的登录 shell 跑 [command]，环境和本机 Debian 里一样带上
         * Zeta 的目录名、手机的时区。Claude Code 的安装程序把它放在 ~/.local/bin，有的主机只在交互 shell 里加进 PATH，这里补上。
         */
        fun remoteCommand(command: String, linuxName: String, timeZone: String): String =
            "exec env " + AppVariant.LINUX_NAME_ENV + "=" + LinuxEnv.quote(linuxName) + " TZ=" + LinuxEnv.quote(timeZone) +
                " LANG=C.UTF-8 TERM=dumb bash -lc " + LinuxEnv.quote("PATH=\"\$PATH:\$HOME/.local/bin\"; $command")
    }
}

/**
 * 经手机 Debian 连的远程主机：在 [local]（手机的 Debian）里用 OpenSSH 连到 [host]。
 * 密钥、主机别名、跳板都用 Debian 里 ~/.ssh 的配置，Zeta 自己不存密钥。连接经 ControlMaster 复用，
 * 列会话、读历史这类短命令不用每次重新握手。
 *
 * 结束进程时结束的是本地的 ssh：连接一断，那边的命令读到输入结束就退出（zeta_host.sh 的 attach 是这样），
 * 托管出去的 Claude Code 进程不受影响。
 */
class DebianSshShell(
    private val local: LinuxShell,
    host: RemoteHost,
    timeZone: String = TimeZone.getDefault().id,
    linuxName: String = AppVariant.current.linuxName,
) : RemoteShell(host, timeZone, linuxName) {
    override fun start(command: String): Process = local.start(sshCommand(host.target, wrap(command)))

    override fun kill(process: Process) = local.kill(process)

    companion object {
        /**
         * ssh 的选项：不问密码（连不上直接失败），没见过的主机记下它的密钥（换过密钥的照样拒绝），
         * 网络断了约 45 秒内发现，压缩（对话输出、历史都是大段 JSON），连接复用（主连接最后一个命令结束后再留 5 分钟）。
         * 命令行上的选项优先于 ~/.ssh/config 里的同名项。
         */
        val Options = listOf(
            "BatchMode=yes",
            "ConnectTimeout=15",
            "StrictHostKeyChecking=accept-new",
            "ServerAliveInterval=15",
            "ServerAliveCountMax=3",
            "Compression=yes",
            "ControlMaster=auto",
            "ControlPath=~/.ssh/zeta-%C",
            "ControlPersist=300",
        )

        /** ssh 认的目的地：~/.ssh/config 里的别名、host 或 user@host，不能以 - 开头，不能有空白和引号。 */
        fun validTarget(target: String): Boolean =
            target.isNotEmpty() && !target.startsWith("-") && target.none { it.isWhitespace() || it == '\'' || it == '"' || it == '\\' || it < ' ' }

        /** 在本地 Debian 里执行的那行：ssh 到 [target]，把 [remote] 交给那边的登录 shell。 */
        fun sshCommand(target: String, remote: String): String =
            "exec ssh -T " + Options.joinToString(" ") { "-o " + LinuxEnv.quote(it) } + " -- " + LinuxEnv.quote(target) + " " + LinuxEnv.quote(remote)
    }
}

/**
 * Zeta 直连的远程主机：不经手机的 Debian，用应用自带的 SSH（[SshLinks]）和 Zeta 自己的密钥连过去。
 * 手机上没装 Debian 也能用。[start] 马上返回，连接在后台建；结束进程是关掉那个通道。
 */
class DirectSshShell(
    private val links: SshLinks,
    host: RemoteHost,
    timeZone: String = TimeZone.getDefault().id,
    linuxName: String = AppVariant.current.linuxName,
) : RemoteShell(host, timeZone, linuxName) {
    private val target = checkNotNull(SshTarget.parse(host.target)) { "直连的地址不对：${host.target}" }
    private val hostKey = checkNotNull(host.hostKey?.let(RemoteHosts::decodeKey)) { "直连的主机没有记下指纹" }

    override fun start(command: String): Process = links.start(host.id, target, hostKey, wrap(command))

    override fun kill(process: Process) = process.destroy()
}
