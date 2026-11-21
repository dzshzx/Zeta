package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.ssh.SshKey
import com.dzshzx.zeta.core.ssh.SshLinks
import com.dzshzx.zeta.core.ssh.SshTarget
import java.util.Base64
import java.util.TimeZone
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/**
 * 一台远程主机：上面装着 Claude Code。怎么连见 [via]：经手机 Debian 里的 OpenSSH（[DebianSshShell]），
 * [target] 是 ssh 认的目的地（~/.ssh/config 里的别名、host、user@host 或 ssh://user@host:port）；或 Zeta 直连（[DirectSshShell]），
 * [target] 是 `用户@主机[:端口]`（[SshTarget]），[hostKey] 是加主机时记下的主机公钥（SSH 线上格式的 base64），以后只认它。
 * 加主机时手机 Debian 就绪就默认经 Debian（[ConversationHub.defaultVia]），加好以后能改（[ConversationHub.changeVia]）。
 * [home] 是连上时查到的家目录，[defaultCwd] 是在这台主机上开新对话的默认工作目录。
 */
data class RemoteHost(
    val id: String,
    val name: String,
    val target: String,
    val home: String,
    val defaultCwd: String,
    val via: Via = Via.Debian,
    val hostKey: String? = null,
) {
    enum class Via(val key: String, val label: String) {
        Debian("debian", "经手机 Debian"),
        Direct("direct", "Zeta 直连"),
    }

    /** 直连的主机指纹（SHA256），经 Debian 的是 null。 */
    val fingerprint: String? get() = hostKey?.let(RemoteHosts::fingerprint)
}

/**
 * Claude Code 跑在哪：手机里的 Debian（[LOCAL]）或某台远程主机（[RemoteHost.id]）。对话、会话列表、托管进程都按它分开，
 * 存成字符串（设置、对话、会话概要里都是这一个值）。
 */
object Backends {
    const val LOCAL = "local"

    /** 新远程主机的编号：r- 开头，和 [LOCAL] 不会撞上。 */
    fun newId(): String = "r-" + UUID.randomUUID().toString().take(8)
}

object RemoteHosts {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(hosts: List<RemoteHost>): String = buildJsonArray {
        hosts.forEach {
            addJsonObject {
                put("id", it.id)
                put("name", it.name)
                put("target", it.target)
                put("home", it.home)
                put("cwd", it.defaultCwd)
                put("via", it.via.key)
                it.hostKey?.let { key -> put("hostKey", key) }
            }
        }
    }.toString()

    /** 坏掉的条目（缺字段、目的地不合规矩、直连的没有主机指纹、编号重复）跳过，整体坏了就是空清单。 */
    fun decode(text: String?): List<RemoteHost> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        val hosts = ArrayList<RemoteHost>()
        for (element in array) {
            val item = element as? JsonObject ?: continue
            val id = item.str("id")?.takeIf { it.startsWith("r-") } ?: continue
            val via = if (item.str("via") == RemoteHost.Via.Direct.key) RemoteHost.Via.Direct else RemoteHost.Via.Debian
            val target = item.str("target")?.takeIf { if (via == RemoteHost.Via.Direct) SshTarget.parse(it) != null else DebianSshShell.validTarget(it) } ?: continue
            val hostKey = item.str("hostKey")?.takeIf { decodeKey(it) != null }
            if (via == RemoteHost.Via.Direct && hostKey == null) continue
            val home = item.str("home")?.takeIf { it.startsWith("/") } ?: continue
            if (hosts.any { it.id == id }) continue
            hosts += RemoteHost(
                id = id,
                name = item.str("name")?.trim()?.ifEmpty { null } ?: target,
                target = target,
                home = home,
                defaultCwd = item.str("cwd")?.takeIf { it.startsWith("/") } ?: home,
                via = via,
                hostKey = hostKey.takeIf { via == RemoteHost.Via.Direct },
            )
        }
        return hosts
    }

    /** 给主机起的名字有没有问题：空的、和别的主机重名（[others] 不含它自己）、和手机本机的叫法撞上。没问题返回 null。 */
    fun nameProblem(name: String, others: List<RemoteHost>): String? {
        val clean = name.trim()
        return when {
            clean.isEmpty() -> "名字不能为空"
            clean.length > 40 -> "名字太长了"
            clean == LOCAL_NAME -> "「$LOCAL_NAME」指手机里的 Debian，换个名字"
            others.any { it.name == clean } -> "已经有叫「$clean」的主机了"
            else -> null
        }
    }

    /** 界面上手机里那个 Debian 的叫法。 */
    const val LOCAL_NAME = "手机"

    /** 手机本机在主机清单里的小字（换主机的菜单、设置首页）：它的 Claude Code 跑在哪。 */
    const val LOCAL_SUMMARY = "手机里的 Debian"

    /**
     * 连上以后在那边查的几样：家目录、claude 在不在、什么版本，系统和 CPU（zeta-helper 只有 Linux 的 arm64、amd64 两份）。
     * 每样一行 `zeta_<名字> <值>`，
     * 查不到的值是空的。登录 shell 打出来的别的行（欢迎语之类）跳过。
     */
    val ProbeScript = """
        printf 'zeta_home %s\n' "${'$'}HOME"
        printf 'zeta_claude %s\n' "${'$'}(claude --version 2>/dev/null | head -n 1)"
        printf 'zeta_system %s\n' "${'$'}(uname -sm 2>/dev/null)"
    """.trimIndent()

    /** 连一次 [target] 查到的情况。直连的 [Ok.hostKey] 是对方的主机公钥（base64），加主机时记下。 */
    sealed interface Probe {
        data class Ok(val home: String, val claude: String, val system: String, val hostKey: String? = null) : Probe

        data class Failed(val reason: String) : Probe

        /** 直连时对方的主机公钥和记下的不一样；[hostKey] 是对方现在的。 */
        data class HostKeyChanged(val hostKey: String) : Probe
    }

    /** 跑 [ProbeScript] 的结果（[result]）怎么看；直连时带上看到的主机公钥 [hostKey]。 */
    fun parseProbe(result: CommandResult, hostKey: String? = null): Probe {
        val values = result.stdout.lineSequence().filter { it.startsWith("zeta_") }.associate { line ->
            line.substringBefore(' ').removePrefix("zeta_") to line.substringAfter(' ', "").trim()
        }
        val home = values["home"].orEmpty()
        val claude = values["claude"].orEmpty()
        val system = values["system"].orEmpty()
        return when {
            result.timedOut -> Probe.Failed("连接超时")
            result.exitCode == RemoteShell.CONNECTION_FAILED || home.isEmpty() -> Probe.Failed(sshProblem(result.stderr))
            claude.isEmpty() -> Probe.Failed("那台主机上找不到 claude（登录 shell 的 PATH 里和 ~/.local/bin 都没有）")
            !helperRuns(system) -> Probe.Failed("Zeta 读会话记录的程序只能在 arm64、x86-64 的 Linux 上跑，这台是 ${system.ifEmpty { "未知的系统" }}")
            else -> Probe.Ok(home, claude.substringBefore(" ("), system, hostKey)
        }
    }

    /** `uname -sm` 报的 [system] 上 zeta-helper 跑得起来：Linux，CPU 是 [HelperProgram.ARCHES] 里的一种。 */
    fun helperRuns(system: String): Boolean =
        system.startsWith("Linux ") && HelperProgram.archOf(system.substringAfterLast(' ')) != null

    /** 经手机的 Debian（[local]）连一次 [target]，查家目录、claude、系统和 CPU。阻塞，别在主线程上调用。 */
    fun probeDebian(local: LinuxShell, target: String): Probe {
        if (!DebianSshShell.validTarget(target)) return Probe.Failed("地址里不能有空白、引号，也不能以 - 开头")
        val shell = DebianSshShell(local, RemoteHost("probe", target, target, "/", "/"))
        val result = runCatching { shell.run(ProbeScript, timeoutMs = 45_000) }.getOrNull() ?: return Probe.Failed("进不了手机里的 Linux 环境")
        return parseProbe(result)
    }

    /**
     * Zeta 直连 [target] 查一次。[expectedHostKey] 是记下的主机公钥（重新检查时），加主机时为 null：谁都认，看到的放进结果。
     * 阻塞，别在主线程上调用。
     */
    fun probeDirect(links: SshLinks, target: String, expectedHostKey: String? = null, linuxName: String = AppVariant.current.linuxName): Probe {
        val parsed = SshTarget.parse(target) ?: return Probe.Failed(DIRECT_TARGET_HINT)
        val command = RemoteShell.remoteCommand(ProbeScript, linuxName, TimeZone.getDefault().id, login = true)
        val result = runCatching { links.probe(parsed, expectedHostKey?.let(::decodeKey), command) }.getOrElse { return Probe.Failed(SshLinks.problem(it)) }
        val seen = result.hostKey?.let(::encodeKey)
        if (result.hostKeyChanged && seen != null) return Probe.HostKeyChanged(seen)
        val output = result.output ?: return Probe.Failed(result.problem ?: "没有连上")
        return parseProbe(output, seen)
    }

    /** 直连地址的写法。 */
    const val DIRECT_TARGET_HINT = "地址写成 用户@主机，端口不是 22 的写成 用户@主机:端口"

    /** 经 Debian 连的地址的写法。 */
    const val DEBIAN_TARGET_HINT = "地址写成 用户@主机[:端口]，也可以是手机 Debian 的 ~/.ssh/config 里的别名"

    /**
     * 地址 [text]（用户输入的，或这台主机另一种连法用的）换成连法 [via] 的写法；这种连法用不了它时是 null。
     * 直连只认写明用户的 `用户@主机[:端口]`（[SshTarget]）；经 Debian 的交给 OpenSSH（[SshTarget.openSsh]），
     * 也认 ~/.ssh/config 里的别名和不带用户、端口的主机名（用户、端口照那边的配置）。
     */
    fun targetFor(text: String, via: RemoteHost.Via): String? {
        val parsed = SshTarget.parse(text)
        return when (via) {
            RemoteHost.Via.Direct -> parsed?.toString()
            RemoteHost.Via.Debian -> parsed?.openSsh ?: text.trim().takeIf { DebianSshShell.validTarget(it) && it.none { c -> c == '@' || c == ':' || c == '/' } }
        }
    }

    /** 给人看的地址：写明用户的按直连的写法（不带 ssh://），别名原样。 */
    fun displayTarget(target: String): String = SshTarget.parse(target)?.toString() ?: target

    fun encodeKey(blob: ByteArray): String = Base64.getEncoder().encodeToString(blob)

    /** base64 的主机公钥，坏的、JSch 认不得的（[SshLinks.hostKey]）是 null。 */
    fun decodeKey(text: String): ByteArray? = runCatching { Base64.getDecoder().decode(text) }.getOrNull()?.takeIf { SshLinks.hostKey(it) != null }

    /** 主机公钥 [text]（[encodeKey] 的结果）的指纹，坏的是 null。 */
    fun fingerprint(text: String): String? = decodeKey(text)?.let(SshKey::fingerprint)

    /** 列出 Debian 里 ~/.ssh/config、~/.ssh/config.local 的 Host 那几行，交给 [parseAliases]。自己总以 0 退出。 */
    private const val AliasesScript = "for f in \"\$HOME/.ssh/config\" \"\$HOME/.ssh/config.local\"; do [ -r \"\$f\" ] && grep -iE '^[[:space:]]*Host[[:space:]]' \"\$f\"; done; exit 0"

    /**
     * 手机 Debian（[local]）的 ~/.ssh/config 里能选的主机（[parseAliases]）。脚本自己总以 0 退出，不是 0 就是没跑成（进不去 Debian、超时）：
     * 这时抛出，消息是给人看的原因，不当成没有别名。阻塞，别在主线程上调用。
     */
    fun readAliases(local: LinuxShell): List<String> {
        val result = local.run(AliasesScript)
        check(result.exitCode == 0) {
            if (result.timedOut) "手机 Debian 没有响应（超时）" else result.stderr.trim().takeLast(200).ifEmpty { "进不去手机 Debian（退出码 ${result.exitCode}）" }
        }
        return parseAliases(result.stdout)
    }

    /** Host 行里的别名，按出现的先后、去重；带通配符的（*、?、!）不是一台具体的主机，跳过。 */
    fun parseAliases(stdout: String): List<String> = stdout.lineSequence()
        .map { it.trim() }
        .filter { it.length > 4 && it.substring(0, 4).equals("host", ignoreCase = true) && it[4].isWhitespace() }
        .flatMap { it.substring(5).trim().split(Regex("\\s+")) }
        .filter { alias -> alias.isNotEmpty() && alias.none { it == '*' || it == '?' || it == '!' } && DebianSshShell.validTarget(alias) }
        .distinct()
        .toList()

    /** ssh 连不上时它在标准错误里说的原因，翻成一句话；认不出的原样给最后一行。直连的已经是一句话（[SshLinks.PROBLEM_PREFIX] 后面）。 */
    fun sshProblem(stderr: String): String {
        val text = stderr.trim()
        return when {
            text.contains(SshLinks.PROBLEM_PREFIX) -> text.substringAfterLast(SshLinks.PROBLEM_PREFIX).lineSequence().first().trim()
            text.contains("Permission denied") -> "对方不认手机 Debian 里的 SSH 密钥（Permission denied）"
            text.contains("Could not resolve hostname") -> "找不到这台主机（地址解析失败）"
            text.contains("Host key verification failed") || text.contains("REMOTE HOST IDENTIFICATION HAS CHANGED") ->
                "主机密钥和记下的不一样，先在 Debian 里核对 ~/.ssh/known_hosts"
            text.contains("timed out") -> "连接超时"
            text.contains("Connection refused") -> "对方拒绝连接（端口上没有 SSH 服务）"
            text.contains("No route to host") || text.contains("Network is unreachable") -> "网络不通"
            text.isEmpty() -> "没有连上"
            else -> text.lines().last().take(200)
        }
    }
}
