package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.schedule.ScheduleRunner
import com.dzshzx.zeta.phone.PhoneTools
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.derivedStateOf
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
    val context: Application get() = app
    val settings = Settings(app)
    val helperScript: String by lazy { app.assets.open("zeta_helper.py").bufferedReader().use { it.readText() } }
    val hostScript: String by lazy { app.assets.open("zeta_host.sh").bufferedReader().use { it.readText() } }

    var phoneTools by mutableStateOf(settings.phoneTools)
        private set

    /**
     * 顶部状态胶囊要显示的对话：正在用手机工具的那个（几个都在用时取最近操作的），没有时为 null。
     * 可观察（随对话、手机工具、界面前后台的状态自动更新），在主线程上读。挑法见 [pickFocus]。
     */
    val overlayFocus: com.dzshzx.zeta.phone.OverlayFocus? get() = focusState.value

    private val focusState = derivedStateOf { pickFocus() }

    /** 刚结束一轮、胶囊还停留着显示“已完成”的那个，及那一句。 */
    private class Linger(val focus: ConversationFocus, val text: String)

    private var linger by mutableStateOf<Linger?>(null)
    private val endLinger = Runnable { linger = null }

    var backgroundScreen by mutableStateOf(settings.backgroundScreen)
        private set

    /** 立即生效：之后打开的应用放到哪块屏上。 */
    fun chooseBackgroundScreen(enabled: Boolean) {
        backgroundScreen = enabled
        settings.backgroundScreen = enabled
    }

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

    /** 侧栏里置顶的会话（会话 id）。只是应用这边的标记，会话记录不动。 */
    var pinnedSessions by mutableStateOf(settings.pinnedSessions)
        private set

    fun choosePinned(ids: Set<String>) {
        pinnedSessions = ids
        settings.pinnedSessions = ids
    }

    /** 设置从备份导入后：可观察的几份副本按存下来的值重读一遍，界面马上跟着变。在主线程上调用。 */
    internal fun reloadSettings() {
        phoneTools = settings.phoneTools
        backgroundScreen = settings.backgroundScreen
        autoLoadRemoteImages = settings.autoLoadRemoteImages
        darkTheme = settings.darkTheme
        pinnedSessions = settings.pinnedSessions
        SharedFolders.current = settings.sharedFolders
        env = settings.env
        if (current.items.isEmpty() && current.sessionId == null) current = blank()
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

    /**
     * 每个会话的手机工具，按 sessionId（新对话还没有时按 key）登记。后台屏幕比 Claude Code 进程活得久：
     * 进程结束、重新打开会话时新建的对话对象从这里拿回原来那份，屏上的应用还是原来的样子。
     * 主线程和工具线程（[makeRoom]）都会访问，用它自己当锁；持锁时不调用会去拿 PhoneTools 锁的东西。
     * 要在 [current] 之前初始化：[blank] 建对话时就会来要。
     */
    private val phones = HashMap<String, PhoneTools>()

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

    /** Eta 装的那份 Debian 还在不在。不在了就不再把它列为可选的位置。 */
    var etaPresent by mutableStateOf(false)
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

    /**
     * 界面是否正在显示（可观察：胶囊据此避开正显示着同一对话的聊天页）。
     * 按实例记：界面重建时新旧实例的 onStart/onStop 会交错，单个布尔值会被覆盖错。
     */
    var foreground by mutableStateOf(false)
        private set

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities.add(activity)
                foreground = true
                Notifier.clearAlert(app)
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivities.remove(activity)
                foreground = startedActivities.isNotEmpty()
            }
            override fun onActivityDestroyed(activity: Activity) {
                startedActivities.remove(activity)
                foreground = startedActivities.isNotEmpty()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
        ScheduleRunner.onAppStart(app)
    }

    /** 切走时还没做完的对话，按 sessionId（没有则按 key）保留到这一轮结束。改了以后调用 [backgroundChanged]。 */
    private val background = LinkedHashMap<String, Conversation>()

    /** [background] 里的对话的一份可观察副本（胶囊据此挑对话）。 */
    private var backgroundList by mutableStateOf<List<Conversation>>(emptyList())

    private fun backgroundChanged() {
        backgroundList = background.values.toList()
    }
    private val main = Handler(Looper.getMainLooper())

    private fun blank() = Conversation(this, settings.env?.defaultCwd ?: "/workspace", sessionId = null, title = "", historyFile = null)

    fun isRunning(sessionId: String): Boolean =
        (current.sessionId == sessionId && current.busy) || background[sessionId]?.busy == true

    /** 这个会话的对话还在进行：在跑、等确认，或者后台任务没做完。批量删除跳过这些。 */
    fun isActive(sessionId: String): Boolean {
        fun active(c: Conversation) = c.busy || c.pending != null || c.backgroundTasks > 0
        return (current.sessionId == sessionId && active(current)) || background[sessionId]?.let(::active) == true
    }

    /** 侧栏的“等你确认”标记：这个会话的对话正等着用户批准一次工具调用。 */
    fun isWaiting(sessionId: String): Boolean =
        (current.sessionId == sessionId && current.pending != null) || background[sessionId]?.pending != null

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
            val eta = LinuxEnv.exists(LinuxEnv.ETA_ROOTFS)
            main.post {
                setup = state
                env = settings.env
                ownStatus = own
                etaPresent = eta
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
                    tasks = o.int("tasks") ?: 0,
                )
            }.toList()
            main.post {
                // 这个进程里已经接着的（比如界面打开前定时任务起的对话）不再接一次，也不当闲着收掉；在主线程上看才不漏
                val known = (listOf(current) + background.values).mapNotNullTo(HashSet()) { it.hostKey }
                val others = hosts.filter { it.key !in known }
                // 这一轮结束了但后台任务还在跑的也接上，否则结束进程会把任务一起杀掉
                val active = others.filter { it.busy || it.pending != null || it.tasks > 0 }
                val idle = others - active.toSet()
                if (idle.isNotEmpty()) thread(name = "zeta-hosts", isDaemon = true) {
                    for (host in idle) {
                        runCatching { LinuxEnv.run(config, "exec bash -c " + LinuxEnv.quote(hostScript) + " zeta-host stop " + LinuxEnv.quote(host.key)) }
                    }
                }
                if (active.isEmpty()) return@post
                for (host in active) {
                    val conversation = Conversation(this, host.cwd, host.sessionId, host.title, host.historyFile, attach = host)
                    if (current.items.isEmpty() && current.sessionId == null && !current.isProcessAlive) {
                        current = conversation
                    } else {
                        background[conversation.sessionId ?: conversation.key] = conversation
                    }
                }
                backgroundChanged()
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

    fun archive(summary: SessionSummary, archived: Boolean) = archiveSessions(listOf(summary), archived)

    /** 归档或取消归档：只影响列表里显不显示，会话记录不动。 */
    fun archiveSessions(summaries: List<SessionSummary>, archived: Boolean) {
        val config = settings.env ?: return
        val ids = summaries.mapTo(LinkedHashSet()) { it.id }
        if (ids.isEmpty()) return
        sessions = sessions.map { if (it.id in ids) it.copy(archived = archived) else it }
        thread(isDaemon = true) {
            runCatching {
                LinuxEnv.run(
                    config, "exec python3 - archive " + ids.joinToString(" ") { LinuxEnv.quote(it) } + if (archived) " 1" else " 0",
                    stdin = helperScript,
                )
            }
        }
    }

    fun refreshSessions() {
        val config = settings.env ?: return
        thread(name = "zeta-sessions", isDaemon = true) {
            val result = runCatching { LinuxEnv.run(config, "exec python3 - list", stdin = helperScript, timeoutMs = 60_000) }.getOrNull() ?: return@thread
            val list = parseSessionSummaries(result.stdout)
            main.post { sessions = list }
        }
    }

    fun startNew(cwd: String? = null) {
        leaveCurrent()
        current = if (cwd == null) blank() else Conversation(this, cwd, sessionId = null, title = "", historyFile = null)
        prunePhones()
    }

    /** 换一个 rootfs（或原地重来）：丢掉已保存的环境，重新检查 root 并识别用户。 */
    fun redetect(rootfs: String) {
        rootfsHint = rootfs
        hintExplicit = true
        if (conversationsWorking) return
        current.shutdown()
        background.values.forEach { it.shutdown() }
        background.clear()
        backgroundChanged()
        // 换了环境，原来的会话都不再接着用：后台屏幕一并收掉
        synchronized(phones) { phones.values.toList().also { phones.clear() } }.forEach { it.destroy() }
        settings.env = null
        env = null
        account = null
        health = null
        ssh = null
        sessions = emptyList()
        current = blank()
        checkSetup()
    }

    /** 有对话在回答，或还有后台任务在跑（结束进程会把任务一起杀掉）。 */
    private val conversationsWorking: Boolean
        get() = (listOf(current) + background.values).any { it.busy || it.backgroundTasks > 0 }

    /** 有对话正在进行（或还有后台任务在跑）时不能换环境。 */
    val canSwitchEnvironment: Boolean get() = !conversationsWorking && !installer.running

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

    /** 安装一样可选工具（go / node / python）；已经是 Zeta 装的则更新到最新版。 */
    fun installOptional(name: String) = runMaintenance(
        title = (if (name in health?.zetaOwned.orEmpty()) "更新 " else "安装 ") + optionalName(name),
        asRoot = name != "python",
        command = installer.optionalCommand(name),
    ) { checkHealth() }

    /** 卸载一样 Zeta 装的可选工具。别处装的脚本会拒绝。 */
    fun removeOptional(name: String) = runMaintenance(
        title = "卸载 " + optionalName(name),
        asRoot = name != "python",
        command = installer.optionalCommand("remove") + " " + name,
    ) { checkHealth() }

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
        backgroundChanged()
        prunePhones()
    }

    /**
     * 从当前对话 [lastItemId] 所在那一轮之后分支：切到一个新对话，先显示到那一轮为止的内容，原对话不动。
     * 新会话由 Claude Code 从原会话复制（--fork-session），发出第一条消息后才写出记录、出现在侧栏里。
     */
    fun branch(lastItemId: String) {
        val source = current
        val sourceId = source.sessionId ?: return
        if (!source.canRewind) return
        val plan = branchPlan(source.items, lastItemId) ?: return
        val title = branchTitle(source.title, sessions.map { it.title })
        leaveCurrent()
        current = Conversation(
            this, source.cwd, sessionId = null, title = title, historyFile = null,
            fork = Fork(sourceId, plan.cut, plan.lastSeen, plan.items, source.skippedHistory),
        )
        prunePhones()
    }

    /**
     * 胶囊上的“在 Zeta 中打开”：切到会话 [key]（sessionId，新对话用它的 key）。
     * 还在后台跑的直接接过来；已经收掉的按会话列表重新打开；都找不到就不动。在主线程上调用。
     */
    fun openConversation(key: String) {
        if (current.sessionId == key || current.key == key) return
        val running = background[key]
        if (running != null) {
            leaveCurrent()
            background.remove(key)
            current = running
            backgroundChanged()
            prunePhones()
            return
        }
        sessions.firstOrNull { it.id == key }?.let(::open) ?: openUnlisted(key)
    }

    /** 会话列表里还没有（比如点通知冷启动，列表还没读出来）：按 id 查一次再打开。 */
    private fun openUnlisted(id: String) {
        val config = settings.env ?: return
        thread(name = "zeta-sessions", isDaemon = true) {
            val result = runCatching {
                LinuxEnv.run(config, "exec python3 - lookup " + LinuxEnv.quote(id), stdin = helperScript, timeoutMs = 60_000)
            }.getOrNull() ?: return@thread
            val summary = parseSessionSummaries(result.stdout).firstOrNull { it.id == id } ?: return@thread
            main.post { open(summary) }
        }
    }

    /** 会话 [key]（sessionId，新对话用它的 key）当前或在后台的那个对话对象；没有就是 null。 */
    internal fun findConversation(key: String): Conversation? =
        if (current.sessionId == key || current.key == key) current else background[key]

    /** 定时任务在后台起的对话：不切换界面上的当前对话，跑完照常收掉。在主线程上调用。 */
    internal fun adoptBackground(conversation: Conversation) {
        background[conversation.sessionId ?: conversation.key] = conversation
        backgroundChanged()
    }

    fun delete(summary: SessionSummary) = deleteSessions(listOf(summary))

    /** 删除会话：记录文件，连同 Claude Code 存在同名目录里的工具结果、子代理记录等；置顶标记一并去掉。 */
    fun deleteSessions(summaries: List<SessionSummary>) {
        val config = settings.env ?: return
        val ids = summaries.mapTo(HashSet()) { it.id }
        if (ids.isEmpty()) return
        if (current.sessionId in ids) {
            current.shutdown()
            current = blank()
        }
        for (id in ids) background.remove(id)?.shutdown()
        backgroundChanged()
        synchronized(phones) { ids.mapNotNull { phones.remove(it) } }.forEach { it.destroy() }
        prunePhones()
        sessions = sessions.filterNot { it.id in ids }
        if (pinnedSessions.any { it in ids }) choosePinned(pinnedSessions - ids)
        thread(isDaemon = true) {
            runCatching {
                LinuxEnv.run(config, "exec python3 - delete " + summaries.joinToString(" ") { LinuxEnv.quote(it.file) }, stdin = helperScript, timeoutMs = 60_000)
            }
            main.post { refreshSessions() }
        }
    }

    /** 离开当前对话：闲着的直接收掉进程省内存，正在跑的（包括还有后台任务的）留在后台跑完。 */
    private fun leaveCurrent() {
        val leaving = current
        if (leaving.busy || leaving.pending != null || leaving.backgroundTasks > 0) {
            background[leaving.sessionId ?: leaving.key] = leaving
            backgroundChanged()
        } else {
            leaving.shutdown()
        }
    }

    /** 会话 [key]（sessionId，新对话用它的 key）的手机工具：登记过就接着用原来那份。在主线程上调用。 */
    internal fun phoneFor(key: String): PhoneTools = synchronized(phones) {
        phones.getOrPut(key) { PhoneTools(app, settings, beforeCreate = ::makeRoom, onScreenGone = ::prunePhones) }
    }

    /** 对话的会话号变了：登记跟着换。新号下面要是还挂着别的一份（不该有），它已经没人找得到，收掉。 */
    internal fun rekeyPhone(phone: PhoneTools, from: String, to: String) {
        val stale = synchronized(phones) {
            // 已经不在登记里（没人用被丢掉、销毁了）就别再登记回去，否则以后打开这个会话会拿到一份建不了屏的
            if (phones[from] !== phone) return
            phones.remove(from)
            phones.put(to, phone)?.takeIf { it !== phone }
        }
        stale?.destroy()
    }

    /**
     * 切到后台的对话按会话号（还没有时按 key）存着。会话号后来才到的话跟着改：
     * 不然从列表打开这个会话时找不到它，会另建一个对话对象，两个对象抢同一份手机工具。在主线程上调用。
     */
    internal fun rekeyBackground(conversation: Conversation, from: String, to: String) {
        if (background[from] !== conversation) return
        background.remove(from)
        background[to] = conversation
        backgroundChanged()
    }

    /** 丢掉没有后台屏幕、也没有对话在用的那些，免得登记越积越多。在主线程上调用。 */
    private fun prunePhones() {
        val inUse = (listOf(current) + background.values).mapTo(HashSet()) { it.phone }
        val unused = synchronized(phones) {
            phones.values.filter { it !in inUse && !it.hasScreen }.also { phones.values.removeAll(it.toSet()) }
        }
        // 销毁而不只是丢开：还没结束的工具调用就不会再给它建出一块没人管的屏
        unused.forEach { it.destroy() }
    }

    /**
     * [phone] 要新建一块后台屏幕（工具线程上）。全应用已有 [MAX_SCREENS] 块时先腾出来：
     * 只挑对话没在进行的，用户正看着的当前对话放最后，其余最久没用的先收；都在忙就照样建。
     * 收屏另起线程做，这里不会去拿别的 PhoneTools 的锁，两个对话同时建屏也不会互相卡住。
     */
    private fun makeRoom(phone: PhoneTools) {
        val others = synchronized(phones) { phones.values.filter { it !== phone && it.hasScreen } }
        val excess = others.size - MAX_SCREENS + 1
        if (excess <= 0) return
        val watching = current.phone
        others.filter { !it.isBusy }
            .sortedWith(compareBy<PhoneTools> { it === watching }.thenBy { it.lastUsed })
            .take(excess)
            .forEach { it.evictBackground() }
    }

    /**
     * 胶囊显示谁：
     * - 刚结束一轮的那个先停留 [FINISHED_LINGER_MS]（显示“已完成”），期间它又开始了就照常显示；
     * - 否则在进行中、这一轮用过手机（看屏或动手）的对话，以及暂停着、用户正接管着的对话里，
     *   接管着的优先，其余取最近用手机的；
     * - Zeta 的聊天页正显示着这个对话、它又没有后台屏幕时不显示（聊天页上已经看得到进度，照 Eta）。
     * 只读可观察的状态，由 [focusState] 缓存。
     */
    private fun pickFocus(): ConversationFocus? {
        val shown = current
        val chatVisible = foreground
        fun visible(c: Conversation) = !(chatVisible && c === shown && c.phone.background == null)
        linger?.focus?.takeIf { !it.conversation.busy && visible(it.conversation) }?.let { return it }
        return (listOf(shown) + backgroundList).distinct()
            .filter { c ->
                val phone = c.phone
                (phone.userControlling || phone.paused || (c.busy && phone.engagedThisTurn)) && visible(c)
            }
            .maxWithOrNull(compareBy<Conversation> { it.phone.userControlling }.thenBy { it.phone.engagedAt })
            ?.overlay
    }

    /** [focus] 正停留在“已完成”时的那一句，否则为 null。 */
    internal fun finishingText(focus: ConversationFocus): String? =
        linger?.takeIf { it.focus === focus && !focus.conversation.busy }?.text

    /**
     * 对话的这一轮就要结束（还没清 busy 和手机工具的状态）。正显示在胶囊上的话，先停留一会儿显示 [text]（“已完成”）。
     * 在主线程上调用。
     */
    internal fun onTurnEnding(conversation: Conversation, text: String) {
        val focus = conversation.overlay
        if (focusState.value !== focus) return
        linger = Linger(focus, text)
        main.removeCallbacks(endLinger)
        main.postDelayed(endLinger, FINISHED_LINGER_MS)
    }

    /** [stopped]：这一轮是用户叫停的（聊天页或通知栏），不再提醒“已完成”。 */
    internal fun onTurnFinished(conversation: Conversation, stopped: Boolean = false) {
        if (conversation !== current) retireWhenIdle(conversation)
        // 定时任务起的对话由 ScheduleRunner 自己提醒（任务名和回答开头，点了打开这个对话）
        if (!stopped && (!foreground || conversation !== current) && !ScheduleRunner.owns(conversation)) {
            Notifier.alert(app, conversation.title.ifBlank { "Zeta" }, "已完成")
        }
        refreshSessions()
    }

    /** 闲着的对话最后一个后台任务也结束了（这一轮早已结束）。 */
    internal fun onBackgroundTasksDone(conversation: Conversation) {
        if (conversation !== current) retireWhenIdle(conversation)
    }

    /**
     * 切走的对话没事了（没有进行中的一轮、没有后台任务、没有等确认的调用）就收掉进程。
     * 稍等一下再看：后台任务跑完后 Claude Code 会紧接着自己另起一轮汇报结果（实测不到 0.2 秒），
     * 立刻收掉就把这一轮掐掉了；起了新的一轮的话等它结束再说。
     */
    private fun retireWhenIdle(conversation: Conversation) {
        main.postDelayed({
            if (conversation !== current && !conversation.busy && conversation.backgroundTasks == 0 && conversation.pending == null) {
                background.values.remove(conversation)
                backgroundChanged()
                conversation.shutdown()
                prunePhones()
            }
        }, RETIRE_DELAY_MS)
    }

    internal fun onAttentionNeeded(conversation: Conversation) {
        if ((!foreground || conversation !== current) && !ScheduleRunner.owns(conversation)) {
            Notifier.alert(app, conversation.title.ifBlank { "Zeta" }, "需要你确认")
        }
    }

    /** 有进程活着就保持前台服务，否则系统会在切到后台后很快回收本应用，连带结束 Claude Code。 */
    internal fun onActivityChanged() {
        val working = installer.running || maintenance?.running == true
        val active = working || current.isProcessAlive || background.values.any { it.isProcessAlive }
        // 后台任务跑着也要持有唤醒锁，否则息屏休眠时任务会被挂起
        val busy = working || conversationsWorking
        val running = (listOf(current) + background.values).filter { it.busy }.distinct().map { conversation ->
            conversation.title.ifBlank { "新对话" } + (conversation.phone.backgroundApp?.let { " · 在后台操作$it" } ?: "")
        }
        AgentService.update(app, active, busy, running)
    }

    /** 通知栏的“停止”：打断所有正在回答的对话，和聊天页的停止键一样。 */
    internal fun interruptAll() {
        (listOf(current) + background.values).filter { it.busy }.distinct().forEach { it.interrupt() }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private const val RETIRE_DELAY_MS = 5_000L

        /** 一轮结束后胶囊停留显示“已完成”多久。 */
        private const val FINISHED_LINGER_MS = 2_000L

        /** 全应用最多同时留几块后台屏幕：每块都连着一个 root 辅助进程和屏上开着的应用，太多了内存吃不消。 */
        private const val MAX_SCREENS = 3

        @Volatile private var instance: ConversationHub? = null

        fun get(app: Application): ConversationHub = instance ?: synchronized(this) {
            instance ?: ConversationHub(app).also { instance = it }
        }
    }
}

/** 可选工具在界面上的名字。 */
fun optionalName(name: String): String = when (name) {
    "go" -> "Go"
    "node" -> "Node.js"
    "apk" -> "APK 分析"
    else -> "Python / uv"
}
