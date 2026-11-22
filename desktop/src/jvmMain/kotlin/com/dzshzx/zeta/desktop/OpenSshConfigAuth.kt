package com.dzshzx.zeta.desktop

import com.dzshzx.zeta.core.RemoteHosts
import com.dzshzx.zeta.core.ssh.SshAuth
import com.dzshzx.zeta.core.ssh.SshKey
import com.dzshzx.zeta.core.ssh.SshProblem
import com.dzshzx.zeta.core.ssh.SshRoute
import com.dzshzx.zeta.core.ssh.SshTarget
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 桌面版直连照这台电脑的 OpenSSH 配置：地址（别名或 `用户@主机[:端口]`）交给 `ssh -G` 算出实际的主机、用户、端口和
 * IdentityFile（Include、Match、通配都照 OpenSSH 自己的规矩），再用 Zeta 自带的 SSH 拿那几个私钥文件登录；用户也可以给一台设备另选 ~/.ssh
 * 里的一把（[keys]），这时只用那一把。 不另生成密钥， 也不用往设备上另加公钥：命令行里 `ssh 别名` 连得上的，Zeta 就连得上。私钥只认没设口令的（Zeta
 * 不问口令）；跳板（ProxyJump、 ProxyCommand）不支持，配了就明说。主机指纹照旧在加设备时记下、以后只认它，不读 known_hosts。
 *
 * [home] 是 ~ 所在（Windows 上是 %USERPROFILE%）；[ssh] 是 OpenSSH 客户端的命令，[config] 不是 null 时用 `-F` 换掉
 * ~/.ssh/config（测试用）。
 */
class OpenSshConfigAuth(
    private val home: File = File(System.getProperty("user.home")),
    private val ssh: String = sshCommand(),
    private val config: File? = null,
) : SshAuth {
    override val aliases = true

    override fun ownKey(): SshKey? = null

    override fun resolve(target: String, key: String?): SshRoute {
        val parsed = SshTarget.parse(target)
        val args =
            if (parsed != null)
                listOf("-l", parsed.user, "-p", parsed.port.toString(), "--", parsed.host)
            else
                listOf(
                    "--",
                    RemoteHosts.alias(target) ?: throw SshProblem(RemoteHosts.CONFIG_TARGET_HINT),
                )
        val options = parse(run(listOf("-G") + args))
        val first = { name: String -> options[name]?.firstOrNull() }
        listOf("proxyjump", "proxycommand").forEach { name ->
            first(name)
                ?.takeIf { it != "none" }
                ?.let { throw SshProblem("~/.ssh/config 给「$target」配了 $name，Zeta 直连还不支持跳板") }
        }
        val host = first("hostname") ?: throw SshProblem("ssh -G 没给出「$target」的主机名")
        val user = first("user") ?: throw SshProblem("ssh -G 没给出「$target」的用户名")
        val port = first("port")?.toIntOrNull() ?: 22
        val files =
            if (key != null)
                listOf(
                    expand(key, host, user).takeIf { it.isFile }
                        ?: throw SshProblem("选的私钥 ${keyName(key)} 不在了"),
                )
            else
                options["identityfile"]
                    .orEmpty()
                    .map { expand(it, host, user) }
                    .filter { it.isFile }
                    .distinct()
        if (files.isEmpty())
            throw SshProblem("这台电脑的 ~/.ssh 里没有「$target」要用的私钥（IdentityFile 指的文件都不在），在设备页里选一把")
        val identities = files.flatMap(::load)
        if (identities.isEmpty())
            throw SshProblem("读不了「$target」的私钥 ${files.joinToString { it.name }}（设了口令的 Zeta 用不了）")
        return SshRoute(SshTarget(user, host, port), identities, files.joinToString { it.name })
    }

    override fun rejected(route: SshRoute) =
        "对方不认这台电脑 ~/.ssh 里的私钥（${route.keys}）：在命令行里 ssh 一次看看，或在设备页里换一把"

    /** ~/.ssh/config 和同目录的 config.local 里 Host 行列的主机；两个都不在时是空的。 */
    override fun configHosts(): List<String> {
        val dir = File(home, ".ssh")
        val files =
            config?.let { listOf(it) } ?: listOf(File(dir, "config"), File(dir, "config.local"))
        return RemoteHosts.parseAliases(
            files.filter { it.isFile }.joinToString("\n") { it.readText() },
        )
    }

    /** ~/.ssh 里旁边有同名 .pub 的私钥文件，按名字排；存成 ~/.ssh/名字。 */
    override fun keys(): List<String> =
        File(home, ".ssh")
            .listFiles()
            .orEmpty()
            .filter { it.isFile && File(it.path + ".pub").isFile }
            .map { "~/.ssh/" + it.name }
            .sorted()

    override fun keyName(key: String): String = key.substringAfterLast('/').substringAfterLast('\\')

    /** 跑 `ssh [-F config] args`，返回标准输出；跑不起来、超时、不是 0 时抛 [SshProblem]，带上它说的原因。 */
    private fun run(args: List<String>): String {
        val command = listOf(ssh) + (config?.let { listOf("-F", it.path) } ?: emptyList()) + args
        val process =
            try {
                ProcessBuilder(command).start()
            } catch (e: Exception) {
                throw SshProblem("这台电脑上找不到 OpenSSH 客户端（$ssh）：${e.message.orEmpty().take(120)}")
            }
        process.outputStream.close()
        // 标准错误只有一两行（不是终端的提示、配置错在哪），等标准输出读完再读不会堵住
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val errors = process.errorStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw SshProblem("ssh -G 没有响应")
        }
        if (process.exitValue() != 0)
            throw SshProblem(
                "ssh -G 读不了 ~/.ssh/config：" +
                    errors.trim().lines().lastOrNull().orEmpty().take(200),
            )
        return output
    }

    /** IdentityFile 里的 ~ 和 %d %h %r %u %% 换成实际的值；别的记号原样留着（那样的文件多半不在，跳过）。 */
    private fun expand(path: String, host: String, user: String): File {
        val home = home.path
        val text =
            if (path == "~" || path.startsWith("~/") || path.startsWith("~\\"))
                home + path.substring(1)
            else path
        val expanded =
            Regex("%[%dhru]").replace(text) {
                when (it.value) {
                    "%d" -> home
                    "%h" -> host
                    "%r" -> user
                    "%u" -> System.getProperty("user.name").orEmpty()
                    else -> "%"
                }
            }
        return File(expanded)
    }

    /** 私钥文件里的密钥；读不了、设了口令的跳过。 */
    private fun load(file: File): List<Identity> = runCatching {
        JSch().run {
            addIdentity(file.path)
            identityRepository.identities.filter { !it.isEncrypted }
        }
    }
        .getOrDefault(emptyList())

    companion object {
        /** `ssh -G` 的输出：每行 `名字 值`，名字小写；IdentityFile 这类能写好几个的按先后都留着。 */
        fun parse(output: String): Map<String, List<String>> =
            output
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && ' ' in it }
                .groupBy({ it.substringBefore(' ').lowercase() }, { it.substringAfter(' ').trim() })

        /** Windows 自带的 OpenSSH 客户端；不在那里（别的系统、自己装的）时交给 PATH 找。 */
        fun sshCommand(): String =
            System.getenv("SystemRoot")
                ?.let { File(it, "System32/OpenSSH/ssh.exe") }
                ?.takeIf { it.isFile }
                ?.path ?: "ssh"
    }
}
