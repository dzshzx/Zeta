package com.dzshzx.zeta.core

import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    val complete: Boolean get() = base && tools && user != null && claude
}

enum class InstallStep(val title: String) {
    Check("检查设备"),
    Download("下载 Debian 13"),
    Extract("解压并配置"),
    Tools("安装常用工具"),
    User("创建用户"),
    Claude("安装 Claude Code"),
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
    /** APK 分析工具：Zeta 装的显示各工具版本，别处装的是“版本未知”，没装是空的。 */
    val apk: String = "",
    /** 哪些可选工具是 Zeta 自己装的（只有这些能在 Zeta 里更新、卸载）。 */
    val zetaOwned: Set<String> = emptySet(),
) {
    /** 可选工具 [name]（go / node / python）的版本，没装是空的。 */
    fun version(name: String): String = when (name) {
        "go" -> go
        "node" -> node
        "apk" -> apk
        else -> uv
    }
    val ok: Boolean get() = missing.isEmpty() && workspaceWritable && network
}

private val AnsiEscape = Regex("\u001B\\[[0-9;?]*[A-Za-z]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\r")

/** 脚本输出的一行，去掉终端控制符。 */
internal fun cleanLine(line: String): String = AnsiEscape.replace(line, "").trim()

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

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var process: Process? = null
    @Volatile private var cancelled = false
    private val androidScript by lazy { assets("zeta_setup_android.sh") }
    private val debianScript by lazy { assets("zeta_setup_debian.sh") }

    private class Artifact(val sha256: String, val size: Long, val file: String)

    private fun artifact(): Artifact? = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
        when (abi) {
            "arm64-v8a" -> Artifact("3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918", 35_409_704L, "debian-trixie-aarch64-pd-v4.29.0.tar.xz")
            "x86_64" -> Artifact("4b8f33b80a10d734ff935e5934588572f860c0c38a68bf91db59af0580370716", 36_728_936L, "debian-trixie-x86_64-pd-v4.29.0.tar.xz")
            else -> null
        }
    }

    /** 读出 [base] 这个位置的安装情况。耗时，别在主线程调用。 */
    fun status(base: String = LinuxEnv.OWN_BASE): EnvStatus {
        val result = runCatching { LinuxEnv.runRoot(LinuxEnv.androidScript(androidScript, "status", base), 30_000) }.getOrNull() ?: return EnvStatus()
        var status = EnvStatus()
        for (line in result.stdout.lineSequence()) {
            val value = line.substringAfter('=', "")
            when (line.substringBefore('=')) {
                "base" -> status = status.copy(base = value == "1")
                "tools" -> status = status.copy(tools = value == "1")
                "claude" -> status = status.copy(claude = value == "1")
                "user" -> value.split(":").takeIf { it.size >= 4 }?.let {
                    status = status.copy(user = it[0], uid = it[1].toIntOrNull() ?: 0, gid = it[2].toIntOrNull() ?: 0, home = it[3])
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
            val failure = runCatching { install(userName) }.fold({ it }, { it.message ?: "安装中断" })
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
        val artifact = artifact() ?: return "暂不支持这台设备的架构：${Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"}"
        if (!LinuxEnv.hasRoot()) return "没有 root 权限，请先在 root 管理器里给 Zeta 授权"

        runAndroid(InstallStep.Check, "check", base)?.let { return it }
        if (status(base).base) {
            markDone(InstallStep.Download, InstallStep.Extract)
        } else {
            val official = "https://github.com/termux/proot-distro/releases/download/v4.29.0/${artifact.file}"
            // 压缩包有校验，走代理也不怕被换掉内容；官方地址连不上时才用
            runAndroid(InstallStep.Download, "download", base, artifact.sha256, artifact.size.toString(), official, "https://gh-proxy.com/$official")?.let { return it }
            runAndroid(InstallStep.Extract, "extract", base, artifact.sha256, "13")?.let { return it }
        }
        val root = LinuxEnv.rootConfig(rootfs)
        if (status(base).tools) markDone(InstallStep.Tools) else runDebian(InstallStep.Tools, root, "tools")?.let { return it }
        if (status(base).user != null) markDone(InstallStep.User) else runDebian(InstallStep.User, root, "user", userName)?.let { return it }
        val ready = status(base)
        val user = ready.user ?: return "没有找到刚建的用户"
        if (ready.claude) {
            markDone(InstallStep.Claude)
        } else {
            val config = LinuxEnvConfig(rootfs, user, ready.uid, ready.gid, ready.home ?: "/home/$user", "/workspace")
            runDebian(InstallStep.Claude, config, "claude")?.let { return it }
        }
        return null
    }

    private fun markDone(vararg steps: InstallStep) = main.post { done = done + steps }

    private fun runAndroid(step: InstallStep, vararg arguments: String): String? =
        run(step) { LinuxEnv.startRoot(LinuxEnv.androidScript(androidScript, *arguments)) }

    private fun runDebian(step: InstallStep, config: LinuxEnvConfig, vararg arguments: String): String? = run(step) {
        val command = "exec bash -c " + LinuxEnv.quote(debianScript) + " zeta-setup" + arguments.joinToString("") { " " + LinuxEnv.quote(it) } + " 2>&1"
        LinuxEnv.start(config, command)
    }

    private fun run(step: InstallStep, launch: () -> Process): String? {
        if (cancelled) return "已取消"
        main.post {
            this.step = step
            progress = null
            detail = ""
        }
        var reported: String? = null
        val tail = ArrayDeque<String>()
        val started = runCatching(launch).getOrElse { return "启动不了安装脚本：${it.message}" }
        process = started
        runCatching { started.outputStream.close() }
        started.inputStream.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = cleanLine(raw)
                when {
                    line.startsWith("@zeta progress ") -> {
                        val parts = line.split(" ")
                        val current = parts.getOrNull(2)?.toFloatOrNull()
                        val total = parts.getOrNull(3)?.toFloatOrNull()
                        if (current != null && total != null && total > 0) main.post { progress = (current / total).coerceIn(0f, 1f) }
                    }
                    line.startsWith("@zeta error ") -> reported = line.removePrefix("@zeta error ")
                    line.isNotEmpty() -> {
                        tail.addLast(line)
                        if (tail.size > 6) tail.removeFirst()
                        main.post { detail = line.take(160) }
                    }
                }
            }
        }
        val code = started.waitFor()
        process = null
        if (cancelled) return "已取消"
        if (code == 0) {
            markDone(step)
            return null
        }
        return reported ?: ("“${step.title}”没有成功（退出码 $code）" + if (tail.isEmpty()) "" else "\n" + tail.joinToString("\n"))
    }

    /** 给当前环境做体检。耗时，别在主线程调用。 */
    fun health(config: LinuxEnvConfig): EnvHealth? {
        val script = """
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
              echo "apk=版本未知"
            fi
        """.trimIndent()
        val command = "bash -c " + LinuxEnv.quote(script) + " zeta-health " + LinuxEnv.quote(config.defaultCwd) +
            "; bash -c " + LinuxEnv.quote(debianScript) + " zeta-setup owners"
        val result = runCatching { LinuxEnv.run(config, command, timeoutMs = 60_000) }.getOrNull()?.takeIf { it.exitCode == 0 } ?: return null
        // 换了环境或修复过以后，共享的内部存储目录要的 media_rw 组可能不在了：体检时顺手补上
        if (SharedFolders.current.any { SharedFolders.isMediaStorage(it.source) }) SharedFolders.ensureMediaGroup(config)
        val values = result.stdout.lineSequence().filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }.toList()
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
            zetaOwned = values.filter { it.first.startsWith("owner_") && it.second == "zeta" }.map { it.first.removePrefix("owner_") }.toSet(),
        )
    }

    /**
     * 在环境里跑一条维护命令（修复软件包、更新 Claude Code），把最后几行输出交回去。
     * [asRoot] 为真时以 root 执行。耗时，别在主线程调用。
     */
    fun maintain(config: LinuxEnvConfig, asRoot: Boolean, command: String, onLine: (String) -> Unit): Pair<Boolean, String> {
        val target = if (asRoot) LinuxEnv.rootConfig(config.rootfs) else config
        val started = runCatching { LinuxEnv.start(target, "$command 2>&1") }.getOrElse { return false to (it.message ?: "启动失败") }
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
    fun optionalCommand(name: String): String = "exec bash -c " + LinuxEnv.quote(debianScript) + " zeta-setup " + name

    /** “修复”要执行的命令：重新走一遍装工具那一步（会先修 dpkg，再补齐缺的包）。 */
    val repairCommand: String get() = "exec bash -c " + LinuxEnv.quote(debianScript) + " zeta-setup tools"
}

/** Claude Code 的登录状态（`claude auth status`）。 */
data class AccountStatus(val loggedIn: Boolean, val email: String?, val plan: String?)

sealed interface LoginState {
    data object Idle : LoginState
    data object Starting : LoginState
    /** [notice] 是上一次提交被退回的原因（比如授权码没复制全）。 */
    data class WaitingCode(val url: String, val notice: String? = null) : LoginState
    data object Verifying : LoginState
    data object Done : LoginState
    data class Failed(val message: String) : LoginState
}

/**
 * 登录 Claude 账号。`claude auth login` 不需要终端：它打印一个授权链接，然后从标准输入读授权码。
 * 这里把链接交给界面去打开，再把用户贴回来的授权码写进去。
 */
class LoginFlow(private val hub: ConversationHub) {
    var state by mutableStateOf<LoginState>(LoginState.Idle)
        private set

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var process: Process? = null
    @Volatile private var generation = 0

    fun start() {
        val config = hub.settings.env ?: return
        cancel()
        val mine = ++generation
        state = LoginState.Starting
        // 登录会换掉凭据：闲着的对话进程先关掉（内容不丢），免得它们拦着下面的存档，或把旧账号写回 ~/.claude.json
        hub.closeIdleConversations()
        thread(name = "zeta-login", isDaemon = true) {
            // 登录别的账号会顶掉当前的：先交给 claude-switch 存档并退出，账号页里还能换回来；存不下就不登录，免得丢了它的令牌
            hub.accounts.prepareLoginBlocking()?.let {
                post(mine, LoginState.Failed(it))
                return@thread
            }
            if (mine != generation) return@thread // 存档的这几秒里用户取消了
            val started = runCatching { LinuxEnv.start(config, "exec claude auth login") }.getOrElse {
                post(mine, LoginState.Failed(it.message ?: "启动不了登录命令"))
                return@thread
            }
            process = started
            val errors = StringBuilder()
            var url: String? = null
            thread(isDaemon = true) {
                runCatching {
                    started.errorStream.bufferedReader().forEachLine { line ->
                        synchronized(errors) { errors.append(line).append('\n') }
                        // 授权码格式不对时它只是抱怨一句，然后接着等输入：回到等授权码的状态
                        val link = url
                        if (link != null && line.isNotBlank() && started.isAlive) {
                            main.post { if (mine == generation && state == LoginState.Verifying) state = LoginState.WaitingCode(link, explain(cleanLine(line))) }
                        }
                    }
                }
            }
            // 提示符那一行没有换行，所以按字符读，见到链接后面的空白就算读完了
            val reader = started.inputStream.bufferedReader()
            val seen = StringBuilder()
            while (true) {
                val c = runCatching { reader.read() }.getOrDefault(-1)
                if (c < 0) break
                seen.append(c.toChar())
                if (url == null && (c == '\n'.code || c == '>'.code)) {
                    url = LoginUrl.find(seen)?.value
                    if (url != null) post(mine, LoginState.WaitingCode(url))
                }
            }
            val code = started.waitFor()
            process = null
            if (mine != generation) return@thread
            if (code == 0) {
                hub.accounts.recordCurrentBlocking()
                post(mine, LoginState.Done)
                main.post { hub.accounts.switched() }
            } else {
                val message = explain(cleanLine(synchronized(errors) { errors.toString() }.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()))
                    .ifBlank { cleanLine(seen.toString().lineSequence().lastOrNull { it.isNotBlank() && "https://" !in it }.orEmpty()) }
                post(mine, LoginState.Failed(message.ifBlank { "登录没有成功（退出码 $code）" }))
            }
        }
    }

    /** 把授权码交给登录命令。 */
    fun submit(code: String) {
        val running = process ?: return
        val waiting = state as? LoginState.WaitingCode ?: return
        // 授权码是“两段中间夹一个 #”，少了后半段登录命令不会收
        if ('#' !in code) {
            state = waiting.copy(notice = "授权码不完整：要连中间的 # 和它后面的部分一起复制")
            return
        }
        state = LoginState.Verifying
        thread(isDaemon = true) {
            runCatching {
                running.outputStream.write((code.trim() + "\n").toByteArray())
                running.outputStream.flush()
            }
        }
    }

    fun cancel() {
        generation++
        process?.let(LinuxEnv::kill)
        process = null
        state = LoginState.Idle
    }

    /** 用户改了授权码，上一次被退回的提示就不该再挂着。 */
    fun clearNotice() {
        (state as? LoginState.WaitingCode)?.let { if (it.notice != null) state = it.copy(notice = null) }
    }

    private fun post(mine: Int, next: LoginState) = main.post { if (mine == generation) state = next }

    /** 把登录命令的几句常见英文提示换成中文，别的原样给出。 */
    private fun explain(message: String): String = when {
        "full code" in message -> "授权码不完整：要连中间的 # 和它后面的部分一起复制"
        "wasn't accepted" in message -> "授权码没有被接受，可能已经过期或者复制错了。请重新开始登录"
        else -> message
    }

    private companion object {
        val LoginUrl = Regex("https://[^\\s\\u001B\\u0007]+")
    }
}
