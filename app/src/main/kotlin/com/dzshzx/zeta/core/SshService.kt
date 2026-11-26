package com.dzshzx.zeta.core

/** SSH 服务的状态：Debian 里实际生效的配置，加上 Android 侧看到的进程和开机脚本。 */
data class SshStatus(
    val installed: Boolean,
    val running: Boolean,
    val autostart: Boolean,
    val port: String,
    val listen: String,
    val keys: Int,
    val passwordLogin: Boolean,
)

/**
 * Debian 里的 sshd：从手机上的终端应用或别的设备连进这个环境用。 装包和配置在 Debian 里做（zeta_setup_debian.sh），启动、停止、开机自启在 Android
 * 侧做（zeta_ssh_android.sh）。 都是耗时操作，别在主线程调用。
 */
class SshService(private val installer: EnvInstaller, assets: (String) -> String) {
    private val androidScript by lazy { assets("zeta_ssh_android.sh") }
    private val enterScript by lazy { assets("zeta_enter.sh") }

    fun status(config: LinuxEnvConfig): SshStatus? {
        val inside =
            runCatching {
                LinuxEnv.run(
                    LinuxEnv.rootConfig(config.rootfs),
                    installer.optionalCommand("ssh-status") + " " + LinuxEnv.quote(config.user),
                    timeoutMs = 30_000,
                )
            }
                .getOrNull()
                ?.takeIf { it.exitCode == 0 } ?: return null
        val outside =
            runCatching { android("status", config.rootfs) }
                .getOrNull()
                ?.takeIf { it.exitCode == 0 } ?: return null
        val values =
            (inside.stdout + "\n" + outside.stdout)
                .lineSequence()
                .filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=').trim() }
        return SshStatus(
            installed = values["installed"] == "1",
            running = values["running"] == "1",
            autostart = values["autostart"] == "1",
            port = values["port"].orEmpty(),
            listen = values["listen"].orEmpty(),
            keys = values["keys"]?.toIntOrNull() ?: 0,
            passwordLogin = values["password"] == "yes",
        )
    }

    /** 下面三个返回失败的原因，成功是 null。 */
    fun start(config: LinuxEnvConfig): String? =
        android("start", config.rootfs, enterScript).problem()

    fun stop(config: LinuxEnvConfig): String? = android("stop", config.rootfs).problem()

    fun autostart(config: LinuxEnvConfig, on: Boolean): String? {
        val result =
            if (on) android("autostart", config.rootfs, "on", enterScript)
            else android("autostart", config.rootfs, "off")
        return result.problem()
    }

    private fun android(command: String, rootfs: String, vararg more: String): CommandResult =
        LinuxEnv.runRoot(LinuxEnv.androidScript(androidScript, command, rootfs, *more), 30_000)
}
