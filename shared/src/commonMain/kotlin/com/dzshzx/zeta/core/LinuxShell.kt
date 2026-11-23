package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.diag.DiagEvents
import com.dzshzx.zeta.core.ssh.SshLinks
import com.dzshzx.zeta.i18n.Strings
import java.util.TimeZone

/**
 * 以普通用户身份跑命令的一台 Linux：手机里的 Debian（[EnvShell]），或经 SSH 连过去的远程主机（[RemoteShell]）。 Claude Code
 * 进程、会话记录、附件、Claude Code 的配置都经它读写，调用方不管命令落在哪台机器上。 测试里可以换成别的（比如直接起 bash、用临时 HOME）。
 */
interface LinuxShell {
    /** 这个用户的家目录。 */
    val home: String

    /** 起一个进程跑 [command]（交给登录 shell 的一段命令），调用方负责读写和收尾。 */
    fun start(command: String): Process

    /**
     * 同 [start]，只是 [command] 用不着登录 shell 设的环境（PATH 里加的工具、代理之类）：只用 bash、coreutils 和按绝对路径执行的
     * zeta-helper。远程主机上这样的命令不起登录 shell，省下那边登录配置（~/.profile 等）的几十毫秒； 手机里 Debian 的登录配置很轻，照 [start] 跑。
     */
    fun startPlain(command: String): Process = start(command)

    /** 结束 [start] 起的进程，连同它派生的子进程。 */
    fun kill(process: Process)

    /**
     * 跑完 [command] 收齐输出；[stdin] 写进标准输入后关掉。到 [timeoutMs] 还没结束就结束它，结果标成超时（[CommandResult.timedOut]）。
     * [plain] 为真时经 [startPlain] 起。
     */
    fun run(
        command: String,
        stdin: String? = null,
        timeoutMs: Long = 30_000,
        plain: Boolean = false,
    ): CommandResult = run(command, stdin?.toByteArray(), timeoutMs, plain)

    /** 同上，标准输入是原样的字节（装 zeta-helper 时传程序本身，见 [HelperProgram]）。 */
    fun run(
        command: String,
        stdin: ByteArray?,
        timeoutMs: Long,
        plain: Boolean = false,
    ): CommandResult =
        LinuxEnv.capture(
                if (plain) startPlain(command) else start(command),
                stdin,
                timeoutMs,
                ::kill,
            )
            .also { DiagEvents.linuxRun(command, it) }
}

/** 手机里的 Debian：经 su 进去（[LinuxEnv]）。 */
class EnvShell(private val config: LinuxEnvConfig) : LinuxShell {
    override val home: String
        get() = config.home

    override fun start(command: String): Process = LinuxEnv.start(config, command)

    override fun kill(process: Process) = LinuxEnv.kill(process)
}

/**
 * 远程主机上的 shell：命令在那边用户的登录 shell 环境里跑（[remoteCommand]），连接断了的命令以 [CONNECTION_FAILED] 结束， 托管出去的 Claude
 * Code 进程不受影响（serve 连接由 [HelperServe] 据此重连）。两种连法：经手机 Debian 里的 OpenSSH（[DebianSshShell]）， 或 Zeta
 * 自己连（[DirectSshShell]），见 [RemoteHost.via]。
 */
sealed class RemoteShell(
    protected val host: RemoteHost,
    protected val timeZone: String,
    protected val linuxName: String,
) : LinuxShell {
    override val home: String
        get() = host.home

    final override fun start(command: String): Process = launch(command, login = true)

    final override fun startPlain(command: String): Process = launch(command, login = false)

    /** 在那边跑 [command]：[login] 时在登录 shell 里，否则只用 bash（[remoteCommand]）。 */
    protected abstract fun launch(command: String, login: Boolean): Process

    companion object {
        /** 连不上（网络、认证、主机密钥不对）或连接断了时的退出码，和 ssh 一样；远端命令的退出码原样带回。 */
        const val CONNECTION_FAILED = SshLinks.CONNECTION_FAILED

        /**
         * 交给远程用户 shell（bash、zsh 都行）的一行：换成 bash 跑 [command]，环境和本机 Debian 里一样带上 Zeta 的目录名、
         * 手机的时区。[login] 时是 bash 的登录 shell，PATH 再补上 ~/.local/bin（Claude Code 的安装程序放在那里， 有的主机只在交互
         * shell 里加进 PATH）；不是时不读那边的登录配置（[LinuxShell.startPlain]）。
         */
        fun remoteCommand(
            command: String,
            linuxName: String,
            timeZone: String,
            login: Boolean,
        ): String =
            "exec env " +
                AppVariant.LINUX_NAME_ENV +
                "=" +
                LinuxEnv.quote(linuxName) +
                " TZ=" +
                LinuxEnv.quote(timeZone) +
                " ZETA_LANG=\"\$LANG\" LANG=C.UTF-8 TERM=dumb bash " +
                (if (login) "-lc " + LinuxEnv.quote("PATH=\"\$PATH:\$HOME/.local/bin\"; $command")
                else "-c " + LinuxEnv.quote(command))
    }
}

/**
 * 经手机 Debian 连的远程主机：在 [local]（手机的 Debian）里用 OpenSSH 连到 [host]。 密钥、主机别名、跳板都用 Debian 里 ~/.ssh
 * 的配置，Zeta 自己不存密钥。连接经 ControlMaster 复用， 列会话、读历史这类短命令不用每次重新握手。
 *
 * 结束进程时结束的是本地的 ssh：连接一断，那边的命令读到输入结束就退出（zeta_host.sh 的 attach 是这样）， 托管出去的 Claude Code 进程不受影响。
 */
class DebianSshShell(
    private val local: LinuxShell,
    host: RemoteHost,
    timeZone: String = TimeZone.getDefault().id,
    linuxName: String = AppVariant.current.linuxName,
) : RemoteShell(host, timeZone, linuxName) {
    override fun launch(command: String, login: Boolean): Process =
        local.start(sshCommand(host.target, remoteCommand(command, linuxName, timeZone, login)))

    override fun kill(process: Process) = local.kill(process)

    companion object {
        /**
         * ssh 的选项：不问密码（连不上直接失败），没见过的主机记下它的密钥（换过密钥的照样拒绝）， 网络断了约 45 秒内发现，压缩（对话输出、历史都是大段
         * JSON），连接复用（主连接最后一个命令结束后再留 5 分钟）。 命令行上的选项优先于 ~/.ssh/config 里的同名项。
         */
        val Options =
            listOf(
                "BatchMode=yes",
                "ConnectTimeout=15",
                "StrictHostKeyChecking=accept-new",
                "ServerAliveInterval=15",
                "ServerAliveCountMax=3",
                "Compression=yes",
                "ControlMaster=auto",
                "ControlPath=$CONTROL_PATH",
                "ControlPersist=300",
            )

        private const val CONTROL_PATH = "~/.ssh/zeta-%C"

        /**
         * 在本地 Debian 里执行的那行：关掉到 [targets] 的主连接（ControlMaster）。挂在上面的命令随即以 255 结束（实测十几毫秒）， 和网络断了一样由
         * [HelperServe] 重连。手机换了网络时用：旧连接多半已经死了，不等保活包超时（约 45 秒）。 没有主连接的那台 ssh 报错退出，不碍事；整行总以 0 结束。
         */
        fun closeMasters(targets: List<String>): String =
            targets.joinToString("") {
                "ssh -o " +
                    LinuxEnv.quote("ControlPath=$CONTROL_PATH") +
                    " -O exit -- " +
                    LinuxEnv.quote(it) +
                    " 2>/dev/null; "
            } + "exit 0"

        /** ssh 认的目的地：~/.ssh/config 里的别名、host 或 user@host，不能以 - 开头，不能有空白和引号。 */
        fun validTarget(target: String): Boolean =
            target.isNotEmpty() &&
                !target.startsWith("-") &&
                target.none {
                    it.isWhitespace() || it == '\'' || it == '"' || it == '\\' || it < ' '
                }

        /** 在本地 Debian 里执行的那行：ssh 到 [target]，把 [remote] 交给那边的登录 shell。 */
        fun sshCommand(target: String, remote: String): String =
            "exec ssh -T " +
                Options.joinToString(" ") { "-o " + LinuxEnv.quote(it) } +
                " -- " +
                LinuxEnv.quote(target) +
                " " +
                LinuxEnv.quote(remote)
    }
}

/**
 * Zeta 直连的远程主机：不经手机的 Debian，用应用自带的 SSH（[SshLinks]）连过去，用 Zeta 自己的密钥（[SshLinks.key]）。 手机上没装 Debian
 * 也能用。[start] 马上返回，连接在后台建；结束进程是关掉那个通道。交给那边 shell 的写法由 zeta-ssh 拼。
 */
class DirectSshShell(
    private val links: SshLinks,
    host: RemoteHost,
    timeZone: String = TimeZone.getDefault().id,
    linuxName: String = AppVariant.current.linuxName,
) : RemoteShell(host, timeZone, linuxName) {
    init {
        checkNotNull(host.hostKey?.let(RemoteHosts::decodeKey)) { Strings.coreDirectNoHostKey() }
    }

    override fun launch(command: String, login: Boolean): Process =
        links.start(host, command, login, linuxName, timeZone)

    override fun kill(process: Process) = process.destroy()
}
