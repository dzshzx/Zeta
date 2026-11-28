package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.ssh.SshKey
import com.dzshzx.zeta.core.ssh.SshLinks
import com.dzshzx.zeta.core.ssh.SshTarget
import com.dzshzx.zeta.i18n.Strings
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
 * 一台远程主机：上面装着 Claude Code。怎么连见 [via]：经手机 Debian 里的 OpenSSH（[DebianSshShell]）， [target] 是 ssh
 * 认的目的地（~/.ssh/config 里的别名、host、user@host 或 ssh://user@host:port）；或 Zeta 直连（[DirectSshShell]），
 * [target] 是 `用户@主机[:端口]`（[SshTarget]），[hostKey] 是加主机时记下的主机公钥（SSH 线上格式的 base64），以后只认它。 加主机时手机
 * Debian 就绪就默认经 Debian（[ConversationHub.defaultVia]），加好以后能改（[ConversationHub.changeVia]）。 [home]
 * 是连上时查到的家目录，[defaultCwd] 是在这台主机上开新对话的默认工作目录。界面上叫它 [name]：用户起的 [customName]，没起时是它自己报的主机名
 * [hostname]（`uname -n`，连上时记下），还没记下时是地址。[connected] 是主机页上的「连接」开关：关着时 Zeta 不连它
 * （[ConversationHub.setConnected]），它上面的对话在那边照样跑。
 */
data class RemoteHost(
    val id: String,
    val customName: String?,
    val target: String,
    val home: String,
    val defaultCwd: String,
    val via: Via = Via.Debian,
    val hostKey: String? = null,
    val hostname: String? = null,
    val connected: Boolean = true,
) {
    /** 界面上的名字。 */
    val name: String
        get() = customName ?: hostname ?: RemoteHosts.displayTarget(target)

    enum class Via(val key: String) {
        Debian("debian"),
        Direct("direct");

        /** 界面上的名字。 */
        val label: String
            get() =
                when (this) {
                    Debian -> Strings.coreHostViaDebian()
                    Direct -> Strings.coreHostViaDirect()
                }
    }

    /** 直连的主机指纹（SHA256），经 Debian 的是 null。 */
    val fingerprint: String?
        get() = hostKey?.let(RemoteHosts::fingerprint)
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
                it.customName?.let { name -> put("name", name) }
                it.hostname?.let { name -> put("hostname", name) }
                put("target", it.target)
                put("home", it.home)
                put("cwd", it.defaultCwd)
                put("via", it.via.key)
                it.hostKey?.let { key -> put("hostKey", key) }
                if (!it.connected) put("off", true)
            }
        }
    }
        .toString()

    /** 坏掉的条目（缺字段、目的地不合规矩、直连的没有主机指纹、编号重复）跳过，整体坏了就是空清单。 */
    fun decode(text: String?): List<RemoteHost> {
        if (text.isNullOrBlank()) return emptyList()
        val array =
            runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull()
                ?: return emptyList()
        val hosts = ArrayList<RemoteHost>()
        for (element in array) {
            val item = element as? JsonObject ?: continue
            val id = item.str("id")?.takeIf { it.startsWith("r-") } ?: continue
            val via =
                if (item.str("via") == RemoteHost.Via.Direct.key) RemoteHost.Via.Direct
                else RemoteHost.Via.Debian
            val target = item.str("target")?.takeIf { validTarget(it, via) } ?: continue
            val hostKey = item.str("hostKey")?.takeIf { decodeKey(it) != null }
            if (via == RemoteHost.Via.Direct && hostKey == null) continue
            val home = item.str("home")?.takeIf { it.startsWith("/") } ?: continue
            if (hosts.any { it.id == id }) continue
            hosts +=
                RemoteHost(
                    id = id,
                    // 早先没起名字时存的是地址：那不算用户起的
                    customName =
                        item
                            .str("name")
                            ?.trim()
                            ?.ifEmpty { null }
                            ?.takeIf { it != target && it != displayTarget(target) },
                    target = target,
                    home = home,
                    defaultCwd = item.str("cwd")?.takeIf { it.startsWith("/") } ?: home,
                    via = via,
                    hostKey = hostKey.takeIf { via == RemoteHost.Via.Direct },
                    hostname = item.str("hostname")?.trim()?.ifEmpty { null },
                    connected = item.bool("off") != true,
                )
        }
        return hosts
    }

    /** 给设备起的名字有没有问题：太长、和别的设备（[others] 是它们现在的名字，不含它自己）重名。空的是不起名字，没问题；没问题返回 null。 */
    fun nameProblem(name: String, others: List<String>): String? {
        val clean = name.trim()
        return when {
            clean.length > 40 -> Strings.coreHostNameTooLong()
            clean.isNotEmpty() && clean in others -> Strings.coreHostNameTaken(clean)
            else -> null
        }
    }

    /** 手机本机在主机清单里的小字（换主机的菜单、设置首页）：它的 Claude Code 跑在哪。 */
    val LOCAL_SUMMARY: String
        get() = Strings.coreHostLocalSummary()

    /** 「连接」关着的远程主机怎样（主机页的开关下面、聊天页的已断开说明）。 */
    val OFF_NOTE: String
        get() = Strings.coreHostOffNote()

    /**
     * 连上以后在那边查的几样：家目录、claude 在不在、什么版本，系统和 CPU（zeta-helper 只有 Linux 的 arm64、amd64 两份）。 每样一行
     * `zeta_<名字> <值>`， 查不到的值是空的。登录 shell 打出来的别的行（欢迎语之类）跳过。
     */
    val ProbeScript =
        """
        printf 'zeta_home %s\n' "${'$'}HOME"
        printf 'zeta_claude %s\n' "${'$'}(claude --version 2>/dev/null | head -n 1)"
        printf 'zeta_agy %s\n' "${'$'}({ agy --version 2>/dev/null || "${'$'}HOME/.local/bin/agy" --version 2>/dev/null; } | head -n 1)"
        printf 'zeta_system %s\n' "${'$'}(uname -sm 2>/dev/null)"
        printf 'zeta_hostname %s\n' "${'$'}(uname -n 2>/dev/null)"
        """
            .trimIndent()

    private fun extractVersion(raw: String): String =
        Regex("""\d+\.\d+[\w.-]*""").find(raw)?.value ?: raw.trim()

    /**
     * 连一次 [target] 查到的情况。直连的 [Ok.hostKey] 是对方的主机公钥（base64），加主机时记下；[Ok.hostname] 是它报的主机名，查不到时为 null。
     */
    sealed interface Probe {
        data class Ok(
            val home: String,
            val claude: String,
            val system: String,
            val hostKey: String? = null,
            val hostname: String? = null,
            val agy: String = "",
        ) : Probe

        data class Failed(val reason: String) : Probe

        /** 直连时对方的主机公钥和记下的不一样；[hostKey] 是对方现在的。 */
        data class HostKeyChanged(val hostKey: String) : Probe
    }

    /** 跑 [ProbeScript] 的结果（[result]）怎么看；直连时带上看到的主机公钥 [hostKey]。 */
    fun parseProbe(result: CommandResult, hostKey: String? = null): Probe {
        val values =
            result.stdout
                .lineSequence()
                .filter { it.startsWith("zeta_") }
                .associate { line ->
                    line.substringBefore(' ').removePrefix("zeta_") to
                        line.substringAfter(' ', "").trim()
                }
        val home = values["home"].orEmpty()
        val claude = values["claude"].orEmpty().substringBefore(" (").trim()
        val agy = extractVersion(values["agy"].orEmpty())
        val system = values["system"].orEmpty()
        return when {
            result.timedOut -> Probe.Failed(Strings.coreConnectionTimeout())
            result.exitCode == RemoteShell.CONNECTION_FAILED || home.isEmpty() ->
                Probe.Failed(sshProblem(result.stderr))
            claude.isEmpty() && agy.isEmpty() -> Probe.Failed(Strings.coreHostNoClaude())
            !helperRuns(system) ->
                Probe.Failed(
                    Strings.coreHostUnsupported(system.ifEmpty { Strings.coreHostUnknownSystem() }),
                )
            else ->
                Probe.Ok(
                    home,
                    claude,
                    system,
                    hostKey,
                    values["hostname"]?.ifEmpty { null },
                    agy,
                )
        }
    }

    /** `uname -sm` 报的 [system] 上 zeta-helper 跑得起来：Linux，CPU 是 [HelperProgram.ARCHES] 里的一种。 */
    fun helperRuns(system: String): Boolean =
        system.startsWith("Linux ") && HelperProgram.archOf(system.substringAfterLast(' ')) != null

    /** 经手机的 Debian（[local]）连一次 [target]，查家目录、claude、系统和 CPU。阻塞，别在主线程上调用。 */
    fun probeDebian(local: LinuxShell, target: String): Probe {
        if (!DebianSshShell.validTarget(target)) return Probe.Failed(Strings.coreHostBadTarget())
        val shell = DebianSshShell(local, RemoteHost("probe", null, target, "/", "/"))
        val result =
            runCatching { shell.run(ProbeScript, timeoutMs = 45_000) }.getOrNull()
                ?: return Probe.Failed(Strings.coreHostPhoneLinuxUnreachable())
        return parseProbe(result)
    }

    /**
     * Zeta 直连 [target] 查一次。[expectedHostKey] 是记下的主机公钥（重新检查时），加主机时为 null：谁都认，看到的放进结果。 阻塞，别在主线程上调用。
     */
    fun probeDirect(
        links: SshLinks,
        target: String,
        expectedHostKey: String? = null,
        linuxName: String = AppVariant.current.linuxName,
    ): Probe {
        val result = runCatching {
            links.probe(
                target,
                expectedHostKey?.let(::decodeKey),
                ProbeScript,
                linuxName,
                TimeZone.getDefault().id,
            )
        }
            .getOrElse {
                return Probe.Failed(it.message ?: Strings.coreNotConnected())
            }
        val seen = result.hostKey?.let(::encodeKey)
        if (result.hostKeyChanged && seen != null) return Probe.HostKeyChanged(seen)
        val output =
            result.output ?: return Probe.Failed(result.problem ?: Strings.coreNotConnected())
        return parseProbe(output, seen)
    }

    /** 存着的地址 [target] 合连法 [via] 的规矩：直连的是 `用户@主机[:端口]`，经 Debian 的交给 OpenSSH 认得的。 */
    private fun validTarget(target: String, via: RemoteHost.Via): Boolean =
        if (via == RemoteHost.Via.Direct) SshTarget.parse(target) != null
        else DebianSshShell.validTarget(target)

    /** 直连地址的写法。 */
    val DIRECT_TARGET_HINT: String
        get() = Strings.coreHostDirectHint()

    /** 经 Debian 连的地址的写法。 */
    val DEBIAN_TARGET_HINT: String
        get() = Strings.coreHostDebianHint()

    /**
     * 地址 [text]（用户输入的，或这台主机另一种连法用的）换成连法 [via] 的写法；这种连法用不了它时是 null。 直连只认写明用户的
     * `用户@主机[:端口]`（[SshTarget]）；经 Debian 的交给 OpenSSH（[SshTarget.openSsh]），也认 ~/.ssh/config
     * 里的别名和不带用户、端口的主机名（用户、端口照那边的配置）。
     */
    fun targetFor(text: String, via: RemoteHost.Via): String? {
        val parsed = SshTarget.parse(text)
        return when (via) {
            RemoteHost.Via.Direct -> parsed?.toString()
            RemoteHost.Via.Debian -> parsed?.openSsh ?: alias(text)
        }
    }

    /** [text] 当成 ~/.ssh/config 里的别名或不带用户、端口的主机名（交给 OpenSSH 解析）；不像的是 null。 */
    fun alias(text: String): String? =
        text.trim().takeIf {
            DebianSshShell.validTarget(it) && it.none { c -> c == '@' || c == ':' || c == '/' }
        }

    /** 给人看的地址：写明用户的按直连的写法（不带 ssh://），别名原样。 */
    fun displayTarget(target: String): String = SshTarget.parse(target)?.toString() ?: target

    fun encodeKey(blob: ByteArray): String = Base64.getEncoder().encodeToString(blob)

    /** base64 的主机公钥，坏的、认不得的（[SshKey.hostKeyType]）是 null。 */
    fun decodeKey(text: String): ByteArray? = runCatching {
        Base64.getDecoder().decode(text)
    }
        .getOrNull()
        ?.takeIf { SshKey.hostKeyType(it) != null }

    /** 主机公钥 [text]（[encodeKey] 的结果）的指纹，坏的是 null。 */
    fun fingerprint(text: String): String? = decodeKey(text)?.let(SshKey::fingerprint)

    /** 列出 Debian 里 ~/.ssh/config、~/.ssh/config.local 的 Host 那几行，交给 [parseAliases]。自己总以 0 退出。 */
    private const val AliasesScript =
        "for f in \"\$HOME/.ssh/config\" \"\$HOME/.ssh/config.local\"; do [ -r \"\$f\" ] && grep -iE '^[[:space:]]*Host[[:space:]]' \"\$f\"; done; exit 0"

    /**
     * 手机 Debian（[local]）的 ~/.ssh/config 里能选的主机（[parseAliases]）。脚本自己总以 0 退出，不是 0 就是没跑成（进不去
     * Debian、超时）： 这时抛出，消息是给人看的原因，不当成没有别名。阻塞，别在主线程上调用。
     */
    fun readAliases(local: LinuxShell): List<String> {
        val result = local.run(AliasesScript)
        check(result.exitCode == 0) {
            if (result.timedOut) Strings.coreHostDebianTimeout()
            else
                result.stderr.trim().takeLast(200).ifEmpty {
                    Strings.coreHostDebianExit(result.exitCode.toString())
                }
        }
        return parseAliases(result.stdout)
    }

    /** Host 行里的别名，按出现的先后、去重；带通配符的（*、?、!）不是一台具体的主机，跳过。 */
    fun parseAliases(stdout: String): List<String> =
        stdout
            .lineSequence()
            .map { it.trim() }
            .filter {
                it.length > 4 &&
                    it.substring(0, 4).equals("host", ignoreCase = true) &&
                    it[4].isWhitespace()
            }
            .flatMap { it.substring(5).trim().split(Regex("\\s+")) }
            .filter { alias ->
                alias.isNotEmpty() &&
                    alias.none { it == '*' || it == '?' || it == '!' } &&
                    DebianSshShell.validTarget(alias)
            }
            .distinct()
            .toList()

    /** ssh 连不上时它在标准错误里说的原因，翻成一句话；认不出的原样给最后一行。直连的已经是一句话（[SshLinks.PROBLEM_PREFIX] 后面）。 */
    fun sshProblem(stderr: String): String {
        val text = stderr.trim()
        return when {
            text.contains(SshLinks.PROBLEM_PREFIX) ->
                text.substringAfterLast(SshLinks.PROBLEM_PREFIX).lineSequence().first().trim()
            text.contains("Permission denied") -> Strings.coreSshKeyRejected()
            text.contains("Could not resolve hostname") -> Strings.coreSshHostNotFound()
            text.contains("Host key verification failed") ||
                text.contains("REMOTE HOST IDENTIFICATION HAS CHANGED") ->
                Strings.coreSshHostKeyChanged()
            text.contains("timed out") -> Strings.coreConnectionTimeout()
            text.contains("Connection refused") -> Strings.coreSshRefused()
            text.contains("No route to host") || text.contains("Network is unreachable") ->
                Strings.coreSshUnreachable()
            text.isEmpty() -> Strings.coreNotConnected()
            else -> text.lines().last().take(200)
        }
    }
}
