package com.dzshzx.zeta.core

import com.dzshzx.zeta.phone.PhoneTools
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class SetupState { Checking, NoRoot, NoEnvironment, Ready }

/** 应用级的单例：设置、历史会话列表、当前对话，以及仍在后台运行的对话。 */
class ConversationHub(private val app: Application) {
    val settings = Settings(app)
    val helperScript: String by lazy { app.assets.open("zeta_helper.py").bufferedReader().use { it.readText() } }
    val hostScript: String by lazy { app.assets.open("zeta_host.sh").bufferedReader().use { it.readText() } }

    val phone = PhoneTools(app, settings)

    var phoneTools by mutableStateOf(settings.phoneTools)
        private set

    /** 开关只影响之后新开的 Claude Code 进程；已经开着的对话下次重启进程时生效。 */
    fun choosePhoneTools(enabled: Boolean) {
        phoneTools = enabled
        settings.phoneTools = enabled
    }

    var autoLoadRemoteImages by mutableStateOf(settings.autoLoadRemoteImages)
        private set

    fun chooseAutoLoadRemoteImages(enabled: Boolean) {
        autoLoadRemoteImages = enabled
        settings.autoLoadRemoteImages = enabled
    }

    /** 外观：null 跟随系统，true 深色，false 浅色。 */
    var darkTheme by mutableStateOf(settings.darkTheme)
        private set

    fun chooseTheme(dark: Boolean?) {
        settings.darkTheme = dark
        darkTheme = dark
    }

    /** 别的应用分享进来、还没放进输入栏的图片和文件。 */
    var incomingShares by mutableStateOf<List<android.net.Uri>>(emptyList())

    private var hostsChecked = false

    /** Claude Code 上次报告的斜杠命令和技能；存一份，应用刚打开还没起进程时也能提示。 */
    var commands by mutableStateOf(loadCommands())
        private set

    internal fun updateCommands(list: List<SlashCommand>) {
        if (list == commands) return
        commands = list
        settings.commands = buildJsonArray {
            for (c in list) add(buildJsonObject { put("name", c.name); put("description", c.description); put("argumentHint", c.argumentHint) })
        }.toString()
    }

    private fun loadCommands(): List<SlashCommand> = runCatching {
        (json.parseToJsonElement(settings.commands ?: return@runCatching emptyList()) as JsonArray).mapNotNull { (it as? JsonObject)?.let { c ->
            SlashCommand(c.str("name") ?: return@let null, c.str("description").orEmpty(), c.str("argumentHint").orEmpty())
        } }
    }.getOrDefault(emptyList())

    var setup by mutableStateOf(SetupState.Checking)
        private set
    var sessions by mutableStateOf<List<SessionSummary>>(emptyList())
        private set
    var models by mutableStateOf<List<ModelOption>>(emptyList())
    var current by mutableStateOf(blank())
        private set

    /** 设置页显示用：当前环境配置（可观察）。 */
    var env by mutableStateOf(settings.env)
        private set

    /** 还没识别出环境时，设置页里显示和下次检测要用的 rootfs 路径。 */
    var rootfsHint by mutableStateOf(LinuxEnv.OWN_ROOTFS)
        private set

    /** [rootfsHint] 是用户指定的；否则检测时把已知的几个位置都找一遍。 */
    private var hintExplicit = false

    /** 安装 Debian 环境、登录 Claude 账号。 */
    val installer = EnvInstaller(this) { name -> app.assets.open(name).bufferedReader().use { it.readText() } }
    private val sshService = SshService(installer) { name -> app.assets.open(name).bufferedReader().use { it.readText() } }

    /** 当前环境里 SSH 服务的状态；还没查过是 null。 */
    var ssh by mutableStateOf<SshStatus?>(null)
        private set
    var sshWorking by mutableStateOf(false)
        private set
    val login = LoginFlow(this)

    /** Zeta 自带环境那个位置的安装情况；null 表示还没查过。 */
    var ownStatus by mutableStateOf<EnvStatus?>(null)
        private set

    /** 当前环境里 Claude Code 的登录状态；null 表示还没查过。 */
    var account by mutableStateOf<AccountStatus?>(null)
        private set

    var health by mutableStateOf<EnvHealth?>(null)
        private set
    var healthChecking by mutableStateOf(false)
        private set

    /** 正在进行或刚做完的一项维护（修复、更新 Claude Code）。 */
    data class Maintenance(val title: String, val running: Boolean, val detail: String, val ok: Boolean = true)

    var maintenance by mutableStateOf<Maintenance?>(null)
        private set

    /** Claude Code 自己配置的默认权限模式（从第一次会话的 init 里得知，仅本次运行有效）。 */
    var defaultPermissionMode: String? = null

    /** 界面是否在前台；不在前台时一轮结束或需要批准会发通知。 */
    private val startedActivities = HashSet<Activity>()

    /** 界面是否正在显示。按实例记：界面重建时新旧实例的 onStart/onStop 会交错，单个布尔值会被覆盖错。 */
    val foreground: Boolean get() = startedActivities.isNotEmpty()

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities.add(activity)
                Notifier.clearAlert(app)
            }
            override fun onActivityStopped(activity: Activity) { startedActivities.remove(activity) }
            override fun onActivityDestroyed(activity: Activity) { startedActivities.remove(activity) }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    /** 切走时还没做完的对话，按 sessionId（没有则按 key）保留到这一轮结束。 */
    private val background = LinkedHashMap<String, Conversation>()
    private val main = Handler(Looper.getMainLooper())

    private fun blank() = Conversation(this, settings.env?.defaultCwd ?: "/workspace", sessionId = null, title = "", historyFile = null)

    fun isRunning(sessionId: String): Boolean =
        (current.sessionId == sessionId && current.busy) || background[sessionId]?.busy == true

    fun checkSetup() {
        setup = SetupState.Checking
        thread(name = "zeta-setup", isDaemon = true) {
            val saved = settings.env
            val state = when {
                !LinuxEnv.hasRoot() -> SetupState.NoRoot
                // 环境可能已经不在了（比如装它的应用被卸载），不能只凭存下来的设置就当它可用
                saved != null -> if (LinuxEnv.exists(saved.rootfs)) SetupState.Ready else SetupState.NoEnvironment
                else -> (if (hintExplicit) listOf(rootfsHint) else LinuxEnv.KNOWN_ROOTFS).firstNotNullOfOrNull { LinuxEnv.detect(it) }?.let { detected ->
                    // Eta 的工作区在 /workspace；有 claude 子目录就用它，和终端里的日常目录一致
                    val cwd = runCatching {
                        LinuxEnv.run(detected, "[ -d /workspace/claude ] && echo /workspace/claude || echo /workspace").stdout.trim()
                    }.getOrNull()?.takeIf { it.startsWith("/") } ?: "/workspace"
                    settings.env = detected.copy(defaultCwd = cwd)
                    SetupState.Ready
                } ?: SetupState.NoEnvironment
            }
            val own = installer.status()
            main.post {
                setup = state
                env = settings.env
                ownStatus = own
                if (state == SetupState.Ready) {
                    refreshAccount()
                    if (current.items.isEmpty() && current.sessionId == null) current = blank()
                    refreshSessions()
                    if (!hostsChecked) {
                        hostsChecked = true
                        reattachHosts()
                    }
                }
            }
        }
    }

    /**
     * 应用上次是被系统回收或划掉的话，Claude Code 进程可能还在后台。
     * 还在干活或等确认的重新接上；闲着的直接结束，省内存。
     */
    private fun reattachHosts() {
        val config = settings.env ?: return
        thread(name = "zeta-hosts", isDaemon = true) {
            val result = runCatching { LinuxEnv.run(config, "exec python3 - hosts", stdin = helperScript, timeoutMs = 60_000) }.getOrNull() ?: return@thread
            val hosts = result.stdout.lineSequence().mapNotNull { line ->
                val o = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@mapNotNull null
                HostInfo(
                    key = o.str("key") ?: return@mapNotNull null,
                    cwd = o.str("cwd").orEmpty().ifBlank { config.defaultCwd },
                    title = o.str("title").orEmpty(),
                    sessionId = o.str("session"),
                    historyFile = o.str("file"),
                    lines = o.int("lines") ?: 0,
                    busy = o.bool("busy") == true,
                    pending = o.obj("pending"),
                    permissionMode = o.str("mode"),
                )
            }.toList()
            for (host in hosts.filter { !it.busy && it.pending == null }) {
                runCatching { LinuxEnv.run(config, "exec bash -c " + LinuxEnv.quote(hostScript) + " zeta-host stop " + LinuxEnv.quote(host.key)) }
            }
            val active = hosts.filter { it.busy || it.pending != null }
            if (active.isEmpty()) return@thread
            main.post {
                for (host in active) {
                    val conversation = Conversation(this, host.cwd, host.sessionId, host.title, host.historyFile, attach = host)
                    if (current.items.isEmpty() && current.sessionId == null && !current.isProcessAlive) {
                        current = conversation
                    } else {
                        background[conversation.sessionId ?: conversation.key] = conversation
                    }
                }
                onActivityChanged()
            }
        }
    }

    fun rename(summary: SessionSummary, title: String) {
        val config = settings.env ?: return
        val clean = title.trim().replace(Regex("\\s+"), " ").take(200)
        if (clean.isEmpty()) return
        sessions = sessions.map { if (it.id == summary.id) it.copy(title = clean) else it }
        if (current.sessionId == summary.id) current.title = clean
        background[summary.id]?.title = clean
        thread(isDaemon = true) {
            runCatching {
                LinuxEnv.run(config, "exec python3 - rename " + LinuxEnv.quote(summary.file) + " " + LinuxEnv.quote(clean), stdin = helperScript)
            }
        }
    }

    /** 归档或取消归档：只影响列表里显不显示，会话记录不动。 */
    fun archive(summary: SessionSummary, archived: Boolean) {
        val config = settings.env ?: return
        sessions = sessions.map { if (it.id == summary.id) it.copy(archived = archived) else it }
        thread(isDaemon = true) {
            runCatching {
                LinuxEnv.run(config, "exec python3 - archive " + LinuxEnv.quote(summary.id) + if (archived) " 1" else " 0", stdin = helperScript)
            }
        }
    }

    fun refreshSessions() {
        val config = settings.env ?: return
        thread(name = "zeta-sessions", isDaemon = true) {
            val result = runCatching { LinuxEnv.run(config, "exec python3 - list", stdin = helperScript, timeoutMs = 60_000) }.getOrNull() ?: return@thread
            val list = result.stdout.lineSequence().mapNotNull { line ->
                val o = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@mapNotNull null
                SessionSummary(
                    id = o.str("id") ?: return@mapNotNull null,
                    file = o.str("file") ?: return@mapNotNull null,
                    cwd = o.str("cwd").orEmpty(),
                    title = o.str("title").orEmpty(),
                    preview = o.str("preview").orEmpty(),
                    updated = (o["updated"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                    external = o.bool("external") == true,
                    archived = o.bool("archived") == true,
                )
            }.toList()
            main.post { sessions = list }
        }
    }

    fun startNew(cwd: String? = null) {
        leaveCurrent()
        current = if (cwd == null) blank() else Conversation(this, cwd, sessionId = null, title = "", historyFile = null)
    }

    /** 换一个 rootfs（或原地重来）：丢掉已保存的环境，重新检查 root 并识别用户。 */
    fun redetect(rootfs: String) {
        rootfsHint = rootfs
        hintExplicit = true
        if (current.busy || background.values.any { it.busy }) return
        current.shutdown()
        background.values.forEach { it.shutdown() }
        background.clear()
        settings.env = null
        env = null
        account = null
        health = null
        ssh = null
        sessions = emptyList()
        current = blank()
        checkSetup()
    }

    /** 有对话正在进行时不能换环境。 */
    val canSwitchEnvironment: Boolean get() = !current.busy && background.values.none { it.busy } && !installer.running

    /** 环境装好了：还没有可用环境就直接用它，已经在用别的环境则由用户自己决定要不要切过来。 */
    internal fun onEnvironmentInstalled() {
        thread(isDaemon = true) {
            val own = installer.status()
            main.post {
                ownStatus = own
                if (setup != SetupState.Ready || settings.env?.rootfs == LinuxEnv.OWN_ROOTFS) redetect(LinuxEnv.OWN_ROOTFS)
            }
        }
    }

    fun refreshAccount() {
        val config = settings.env ?: return
        thread(name = "zeta-account", isDaemon = true) {
            val result = runCatching { LinuxEnv.run(config, "exec claude auth status", timeoutMs = 60_000) }.getOrNull() ?: return@thread
            val o = runCatching { json.parseToJsonElement(result.stdout) as? JsonObject }.getOrNull()
            val status = if (o == null) null else AccountStatus(o.bool("loggedIn") == true, o.str("email"), o.str("subscriptionType"))
            main.post { if (settings.env?.rootfs == config.rootfs) account = status }
        }
    }

    fun checkHealth() {
        val config = settings.env ?: return
        if (healthChecking) return
        healthChecking = true
        thread(name = "zeta-health", isDaemon = true) {
            val result = installer.health(config)
            main.post {
                healthChecking = false
                health = result
            }
        }
    }

    private fun runMaintenance(title: String, asRoot: Boolean, command: String, after: () -> Unit = {}) {
        val config = settings.env ?: return
        if (maintenance?.running == true) return
        maintenance = Maintenance(title, running = true, detail = "")
        onActivityChanged()
        thread(name = "zeta-maintain", isDaemon = true) {
            val (ok, output) = installer.maintain(config, asRoot, command) { line -> main.post { maintenance = Maintenance(title, running = true, detail = line) } }
            main.post {
                maintenance = Maintenance(title, running = false, detail = output.lines().takeLast(4).joinToString("\n"), ok = ok)
                onActivityChanged()
                after()
            }
        }
    }

    /** 修复软件包并补齐常用工具。 */
    fun repairEnvironment() = runMaintenance("修复环境", asRoot = true, command = installer.repairCommand) { checkHealth() }

    fun updateClaude() = runMaintenance("更新 Claude Code", asRoot = false, command = "exec claude update") { checkHealth() }

    fun installPython() = runMaintenance("安装 Python / uv", asRoot = false, command = installer.optionalCommand("python")) { checkHealth() }

    fun installGo() = runMaintenance("安装 Go", asRoot = true, command = installer.optionalCommand("go")) { checkHealth() }

    fun refreshSsh() {
        val config = settings.env ?: return
        thread(name = "zeta-ssh", isDaemon = true) {
            val status = sshService.status(config)
            main.post { if (settings.env?.rootfs == config.rootfs) ssh = status }
        }
    }

    /** 启动、停止、开机自启这类很快的操作；不成功时把原因放进维护卡片。 */
    private fun sshAction(title: String, action: (LinuxEnvConfig) -> String?) {
        val config = settings.env ?: return
        if (sshWorking) return
        sshWorking = true
        thread(name = "zeta-ssh", isDaemon = true) {
            val problem = runCatching { action(config) }.getOrElse { it.message ?: "没有成功" }
            val status = sshService.status(config)
            main.post {
                sshWorking = false
                ssh = status
                if (problem != null) maintenance = Maintenance(title, running = false, detail = problem, ok = false)
            }
        }
    }

    fun startSsh() = sshAction("启动 SSH 服务") { sshService.start(it) }

    fun stopSsh() = sshAction("停止 SSH 服务") { sshService.stop(it) }

    fun setSshAutostart(on: Boolean) = sshAction("开机自动启动") { sshService.autostart(it, on) }

    fun setupSsh() {
        val user = settings.env?.user ?: return
        runMaintenance("安装 SSH 服务", asRoot = true, command = installer.optionalCommand("ssh-setup") + " " + LinuxEnv.quote(user)) { refreshSsh() }
    }

    fun addSshKey(key: String) {
        val user = settings.env?.user ?: return
        val line = key.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return
        runMaintenance("添加公钥", asRoot = true, command = installer.optionalCommand("ssh-addkey") + " " + LinuxEnv.quote(user) + " " + LinuxEnv.quote(line)) { refreshSsh() }
    }

    /** 卸载一样可选工具：go / node / python。 */
    fun removeOptional(name: String) = runMaintenance(
        title = "卸载 " + when (name) { "go" -> "Go"; "node" -> "Node.js"; else -> "Python / uv" },
        asRoot = name != "python",
        command = installer.optionalCommand("remove") + " " + name,
    ) { checkHealth() }

    fun installNode() = runMaintenance("安装 Node.js", asRoot = true, command = installer.optionalCommand("node")) { checkHealth() }

    fun logout() = runMaintenance("退出登录", asRoot = false, command = "exec claude auth logout") { refreshAccount() }

    fun dismissMaintenance() {
        if (maintenance?.running == false) maintenance = null
    }

    fun setDefaultCwd(cwd: String) {
        val updated = settings.env?.copy(defaultCwd = cwd) ?: return
        settings.env = updated
        env = updated
        if (current.items.isEmpty() && current.sessionId == null) current = blank()
    }

    fun open(summary: SessionSummary) {
        if (current.sessionId == summary.id) return
        leaveCurrent()
        current = background.remove(summary.id)
            ?: Conversation(
                this, summary.cwd.ifBlank { settings.env?.defaultCwd ?: "/workspace" }, summary.id,
                summary.title.ifBlank { summary.preview.take(40) }, summary.file, external = summary.external,
            )
    }

    fun delete(summary: SessionSummary) {
        val config = settings.env ?: return
        if (current.sessionId == summary.id) {
            current.shutdown()
            current = blank()
        }
        background.remove(summary.id)?.shutdown()
        sessions = sessions.filterNot { it.id == summary.id }
        thread(isDaemon = true) {
            runCatching { LinuxEnv.run(config, "exec python3 - delete " + LinuxEnv.quote(summary.file), stdin = helperScript) }
            main.post { refreshSessions() }
        }
    }

    /** 离开当前对话：闲着的直接收掉进程省内存，正在跑的留在后台跑完。 */
    private fun leaveCurrent() {
        val leaving = current
        if (leaving.busy || leaving.pending != null) {
            background[leaving.sessionId ?: leaving.key] = leaving
        } else {
            leaving.shutdown()
        }
    }

    internal fun onTurnFinished(conversation: Conversation) {
        if (conversation !== current) {
            background.values.remove(conversation)
            conversation.shutdown()
        }
        if (!foreground || conversation !== current) {
            Notifier.alert(app, conversation.title.ifBlank { "Zeta" }, "已完成")
        }
        refreshSessions()
    }

    internal fun onAttentionNeeded(conversation: Conversation) {
        if (!foreground || conversation !== current) {
            Notifier.alert(app, conversation.title.ifBlank { "Zeta" }, "需要你确认")
        }
    }

    /** 有进程活着就保持前台服务，否则系统会在切到后台后很快回收本应用，连带结束 Claude Code。 */
    internal fun onActivityChanged() {
        val working = installer.running || maintenance?.running == true
        val active = working || current.isProcessAlive || background.values.any { it.isProcessAlive }
        val busy = working || current.busy || background.values.any { it.busy }
        AgentService.update(app, active, busy)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        @Volatile private var instance: ConversationHub? = null

        fun get(app: Application): ConversationHub = instance ?: synchronized(this) {
            instance ?: ConversationHub(app).also { instance = it }
        }
    }
}
