package com.dzshzx.zeta.core

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.i18n.Strings
import kotlin.concurrent.thread

/** 一个位置上的 Debian 环境装到了哪一步。 */
data class EnvStatus(
    val base: Boolean = false,
    val tools: Boolean = false,
    val user: String? = null,
    val uid: Int = 0,
    val gid: Int = 0,
    val home: String? = null,
    val claude: Boolean = false,
) {
    val complete: Boolean
        get() = base && tools && user != null && claude
}

enum class InstallStep(private val titleText: () -> String) {
    Check(Strings::coreInstallCheck),
    Download(Strings::coreInstallDownload),
    Extract(Strings::coreInstallExtract),
    Tools(Strings::coreInstallTools),
    User(Strings::coreInstallUser),
    Claude(Strings::coreInstallClaude);

    /** 安装页上这一步的名字。 */
    val title: String
        get() = titleText()
}

/** 环境体检的结果。 */
data class EnvHealth(
    val missing: List<String>,
    val workspaceWritable: Boolean,
    val network: Boolean,
    val freeBytes: Long,
    val debian: String,
    val claude: String,
    /** 可选工具的版本，没装是空的。 */
    val uv: String = "",
    val node: String = "",
    val go: String = "",
    /** APK 分析工具：Zeta 装的是各工具的版本，别处装的是“?”（显示成“版本未知”，见 [version]），没装是空的。 */
    val apk: String = "",
    /** 哪些可选工具是 Zeta 自己装的（只有这些能在 Zeta 里更新、卸载）。 */
    val zetaOwned: Set<String> = emptySet(),
) {
    /** 可选工具 [name]（go / node / python）的版本，没装是空的。 */
    fun version(name: String): String =
        when (name) {
            "go" -> go
            "node" -> node
            "apk" -> if (apk == "?") Strings.coreToolVersionUnknown() else apk
            else -> uv
        }

    val ok: Boolean
        get() = missing.isEmpty() && workspaceWritable && network
}

private val AnsiEscape =
    Regex("\u001B\\[[0-9;?]*[A-Za-z]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\r")

/** 脚本输出的一行，去掉终端控制符。 */
fun cleanLine(line: String): String = AnsiEscape.replace(line, "").trim()

/** 安装脚本的进度行「@zeta progress <已完成> <总数>」折成 0 到 1；数不对、总数不是正数时是 null。 */
fun progressFraction(line: String): Float? {
    val parts = line.split(" ")
    val current = parts.getOrNull(2)?.toFloatOrNull() ?: return null
    val total = parts.getOrNull(3)?.toFloatOrNull() ?: return null
    return if (total > 0) (current / total).coerceIn(0f, 1f) else null
}

/**
 * 安装 Debian 环境：下载、解压在 Android 一侧做，装工具、建用户、装 Claude Code 进到 Debian 里做。
 * 每一步做之前先看是不是已经做过，所以中途失败或被打断后再点一次就是接着装。
 */
class EnvInstaller(private val hub: ConversationHub, private val assets: (String) -> String) {
    var running by mutableStateOf(false)
        private set

    var step by mutableStateOf<InstallStep?>(null)
        private set

    var done by mutableStateOf<Set<InstallStep>>(emptySet())
        private set

    /** 当前这一步的进度，0 到 1；null 表示没法估计。 */
    var progress by mutableStateOf<Float?>(null)
        private set

    /** 当前这一步最近输出的一行。 */
    var detail by mutableStateOf("")
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var finished by mutableStateOf(false)
        private set

    private val main = MainHandler()
    @Volatile private var process: Process? = null
    @Volatile private var cancelled = false
    private val androidScript by lazy { assets("zeta_setup_android.sh") }
    private val debianScript by lazy { assets("zeta_setup_debian.sh") }

    private class Artifact(val sha256: String, val size: Long, val file: String)

    private fun artifact(): Artifact? =
        Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
            when (abi) {
                "arm64-v8a" ->
                    Artifact(
                        "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918",
                        35_409_704L,
                        "debian-trixie-aarch64-pd-v4.29.0.tar.xz",
                    )
                "x86_64" ->
                    Artifact(
                        "4b8f33b80a10d734ff935e5934588572f860c0c38a68bf91db59af0580370716",
                        36_728_936L,
                        "debian-trixie-x86_64-pd-v4.29.0.tar.xz",
                    )
                else -> null
            }
        }

    /** 读出 [base] 这个位置的安装情况。耗时，别在主线程调用。 */
    fun status(base: String = LinuxEnv.OWN_BASE): EnvStatus {
        val result =
            runCatching {
                LinuxEnv.runRoot(LinuxEnv.androidScript(androidScript, "status", base), 30_000)
            }
                .getOrNull() ?: return EnvStatus()
        var status = EnvStatus()
        for (line in result.stdout.lineSequence()) {
            val value = line.substringAfter('=', "")
            when (line.substringBefore('=')) {
                "base" -> status = status.copy(base = value == "1")
                "tools" -> status = status.copy(tools = value == "1")
                "claude" -> status = status.copy(claude = value == "1")
                "user" ->
                    value
                        .split(":")
                        .takeIf { it.size >= 4 }
                        ?.let {
                            status =
                                status.copy(
                                    user = it[0],
                                    uid = it[1].toIntOrNull() ?: 0,
                                    gid = it[2].toIntOrNull() ?: 0,
                                    home = it[3],
                                )
                        }
            }
        }
        return status
    }

    fun start(userName: String) {
        if (running) return
        running = true
        cancelled = false
        error = null
        finished = false
        done = emptySet()
        step = null
        hub.onActivityChanged()
        thread(name = "zeta-install", isDaemon = true) {
            val failure = runCatching {
                install(userName)
            }
                .fold({ it }, { it.message ?: Strings.coreInstallInterrupted() })
            main.post {
                running = false
                progress = null
                if (failure == null) {
                    finished = true
                    step = null
                    detail = ""
                } else if (!cancelled) {
                    error = failure
                }
                hub.onActivityChanged()
                if (failure == null) hub.environment.onEnvironmentInstalled()
            }
        }
    }

    fun cancel() {
        cancelled = true
        process?.let(LinuxEnv::kill)
    }

    /** 返回 null 表示全部完成，否则是失败原因。 */
    private fun install(userName: String): String? {
        val base = LinuxEnv.OWN_BASE
        val rootfs = LinuxEnv.OWN_ROOTFS
        val artifact =
            artifact()
                ?: return Strings.coreInstallUnsupportedAbi(
                    Build.SUPPORTED_ABIS.firstOrNull() ?: Strings.coreInstallUnknownAbi(),
                )
        if (!LinuxEnv.hasRoot()) return Strings.coreInstallNoRoot()

        runAndroid(InstallStep.Check, "check", base)?.let {
            return it
        }
        if (status(base).base) {
            markDone(InstallStep.Download, InstallStep.Extract)
        } else {
            val official =
                "https://github.com/termux/proot-distro/releases/download/v4.29.0/${artifact.file}"
            // 压缩包有校验，走代理也不怕被换掉内容；官方地址连不上时才用
            runAndroid(
                    InstallStep.Download,
                    "download",
                    base,
                    artifact.sha256,
                    artifact.size.toString(),
                    official,
                    "https://gh-proxy.com/$official",
                )
                ?.let {
                    return it
                }
            runAndroid(InstallStep.Extract, "extract", base, artifact.sha256, "13")?.let {
                return it
            }
        }
        val root = LinuxEnv.rootConfig(rootfs)
        if (status(base).tools) markDone(InstallStep.Tools)
        else
            runDebian(InstallStep.Tools, root, "tools")?.let {
                return it
            }
        if (status(base).user != null) markDone(InstallStep.User)
        else
            runDebian(InstallStep.User, root, "user", userName)?.let {
                return it
            }
        val ready = status(base)
        val user = ready.user ?: return Strings.coreInstallNoUser()
        if (ready.claude) {
            markDone(InstallStep.Claude)
        } else {
            val home = ready.home ?: "/home/$user"
            val config = LinuxEnvConfig(rootfs, user, ready.uid, ready.gid, home, home)
            runDebian(InstallStep.Claude, config, "claude")?.let {
                return it
            }
        }
        return null
    }

    private fun markDone(vararg steps: InstallStep) = main.post { done = done + steps }

    private fun runAndroid(step: InstallStep, vararg arguments: String): String? =
        run(step) { LinuxEnv.startRoot(LinuxEnv.androidScript(androidScript, *arguments)) }

    private fun runDebian(
        step: InstallStep,
        config: LinuxEnvConfig,
        vararg arguments: String,
    ): String? =
        run(step) {
            val command =
                "exec bash -c " +
                    LinuxEnv.quote(debianScript) +
                    " zeta-setup" +
                    arguments.joinToString("") { " " + LinuxEnv.quote(it) } +
                    " 2>&1"
            LinuxEnv.start(config, command)
        }

    private fun run(step: InstallStep, launch: () -> Process): String? {
        if (cancelled) return Strings.coreCancelled()
        main.post {
            this.step = step
            progress = null
            detail = ""
        }
        var reported: String? = null
        val tail = ArrayDeque<String>()
        val started =
            runCatching(launch).getOrElse {
                return Strings.coreInstallScriptFailed(it.message.orEmpty())
            }
        process = started
        runCatching { started.outputStream.close() }
        started.inputStream.bufferedReader().useLines { lines ->
            for (raw in lines) reported = onScriptLine(cleanLine(raw), tail) ?: reported
        }
        val code = started.waitFor()
        process = null
        if (cancelled) return Strings.coreCancelled()
        if (code == 0) {
            markDone(step)
            return null
        }
        return reported
            ?: (Strings.coreInstallStepFailed(step.title, code) +
                if (tail.isEmpty()) "" else "\n" + tail.joinToString("\n"))
    }

    /** 安装脚本输出的一行：进度、出错原因（返回它）或普通输出（留最后 6 行，最新一行显示出来）。 */
    private fun onScriptLine(line: String, tail: ArrayDeque<String>): String? {
        when {
            line.startsWith("@zeta progress ") ->
                progressFraction(line)?.let { main.post { progress = it } }
            line.startsWith("@zeta error ") -> return line.removePrefix("@zeta error ")
            line.isNotEmpty() -> {
                tail.addLast(line)
                if (tail.size > 6) tail.removeFirst()
                main.post { detail = line.take(160) }
            }
        }
        return null
    }

    /** 给当前环境做体检。耗时，别在主线程调用。 */
    fun health(config: LinuxEnvConfig): EnvHealth? {
        val script =
            """
            for c in bash git rg fd python3 curl jq sudo claude; do command -v ${'$'}c >/dev/null 2>&1 || echo "missing=${'$'}c"; done
            [ -d "${'$'}1" ] && [ -w "${'$'}1" ] && echo workspace=1
            getent hosts api.anthropic.com >/dev/null 2>&1 && echo network=1
            echo "free=${'$'}(df -k / | awk 'NR==2 {print ${'$'}4}')"
            echo "debian=${'$'}(cat /etc/debian_version 2>/dev/null)"
            echo "claude=${'$'}(claude --version 2>/dev/null | head -n 1)"
            echo "uv=${'$'}(uv --version 2>/dev/null | head -n 1)"
            echo "node=${'$'}(node --version 2>/dev/null | head -n 1)"
            echo "go=${'$'}(go env GOVERSION 2>/dev/null | head -n 1)"
            m=/var/lib/zeta/tools/apk
            if [ -f "${'$'}m" ]; then
              echo "apk=apktool ${'$'}(sed -n 's/^apktool=//p' "${'$'}m") · jadx ${'$'}(sed -n 's/^jadx=//p' "${'$'}m") · smali ${'$'}(sed -n 's/^smali=//p' "${'$'}m")"
            elif command -v apktool >/dev/null 2>&1 || command -v jadx >/dev/null 2>&1; then
              echo "apk=?"
            fi
            """
                .trimIndent()
        val command =
            "bash -c " +
                LinuxEnv.quote(script) +
                " zeta-health " +
                LinuxEnv.quote(config.defaultCwd) +
                "; bash -c " +
                LinuxEnv.quote(debianScript) +
                " zeta-setup owners"
        val result =
            runCatching { LinuxEnv.run(config, command, timeoutMs = 60_000) }
                .getOrNull()
                ?.takeIf { it.exitCode == 0 } ?: return null
        // 换了环境或修复过以后，共享的内部存储目录要的 media_rw 组可能不在了：体检时顺手补上
        if (SharedFolders.current.any { SharedFolders.isMediaStorage(it.source) })
            SharedFolders.ensureMediaGroup(config)
        val values =
            result.stdout
                .lineSequence()
                .filter { '=' in it }
                .map { it.substringBefore('=') to it.substringAfter('=') }
                .toList()
        fun value(key: String) = values.firstOrNull { it.first == key }?.second.orEmpty()
        return EnvHealth(
            missing = values.filter { it.first == "missing" }.map { it.second },
            workspaceWritable = value("workspace") == "1",
            network = value("network") == "1",
            freeBytes = (value("free").toLongOrNull() ?: 0L) * 1024,
            debian = value("debian"),
            claude = value("claude").substringBefore(" ("),
            uv = value("uv").removePrefix("uv ").substringBefore(" ("),
            node = value("node").removePrefix("v"),
            go = value("go").removePrefix("go"),
            apk = value("apk"),
            zetaOwned =
                values
                    .filter { it.first.startsWith("owner_") && it.second == "zeta" }
                    .map { it.first.removePrefix("owner_") }
                    .toSet(),
        )
    }

    /** 在环境里跑一条维护命令（修复软件包、更新 Claude Code），把最后几行输出交回去。 [asRoot] 为真时以 root 执行。耗时，别在主线程调用。 */
    fun maintain(
        config: LinuxEnvConfig,
        asRoot: Boolean,
        command: String,
        onLine: (String) -> Unit,
    ): Pair<Boolean, String> {
        val target = if (asRoot) LinuxEnv.rootConfig(config.rootfs) else config
        val started = runCatching {
            LinuxEnv.start(target, "$command 2>&1")
        }
            .getOrElse {
                return false to (it.message ?: Strings.coreStartFailed())
            }
        runCatching { started.outputStream.close() }
        val tail = ArrayDeque<String>()
        var reported: String? = null
        started.inputStream.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = cleanLine(raw)
                if (line.startsWith("@zeta error ")) reported = line.removePrefix("@zeta error ")
                if (line.isEmpty() || line.startsWith("@zeta ")) continue
                tail.addLast(line)
                if (tail.size > 8) tail.removeFirst()
                onLine(line.take(160))
            }
        }
        val ok = started.waitFor() == 0
        return ok to (reported ?: tail.joinToString("\n"))
    }

    /** 装或更新一样可选工具（zeta_setup_debian.sh 里的 python / node / go），以及别的子命令。 */
    fun optionalCommand(name: String): String =
        "exec bash -c " + LinuxEnv.quote(debianScript) + " zeta-setup " + name

    /** “修复”要执行的命令：重新走一遍装工具那一步（会先修 dpkg，再补齐缺的包）。 */
    val repairCommand: String
        get() = "exec bash -c " + LinuxEnv.quote(debianScript) + " zeta-setup tools"
}

/**
 * Claude Code 的登录状态（`claude auth status`）。在用中转时 [relay] 是中转名、[relayUrl] 是它的地址：这时没有账号登录，但 Claude
 * Code 能用，[loggedIn] 也为真（聊天页不给登录提示）。
 */
data class AccountStatus(
    val loggedIn: Boolean,
    val email: String?,
    val plan: String?,
    val relay: String? = null,
    val relayUrl: String? = null,
) {
    companion object {
        fun relay(name: String, baseUrl: String) =
            AccountStatus(
                loggedIn = true,
                email = null,
                plan = null,
                relay = name,
                relayUrl = baseUrl,
            )
    }
}
