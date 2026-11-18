package com.dzshzx.zeta.core

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
    var rootfsHint by mutableStateOf(LinuxEnv.DEFAULT_ROOTFS)
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
            val state = when {
                !LinuxEnv.hasRoot() -> SetupState.NoRoot
                settings.env != null -> SetupState.Ready
                else -> LinuxEnv.detect(rootfsHint)?.let { detected ->
                    // Eta 的工作区在 /workspace；有 claude 子目录就用它，和终端里的日常目录一致
                    val cwd = runCatching {
                        LinuxEnv.run(detected, "[ -d /workspace/claude ] && echo /workspace/claude || echo /workspace").stdout.trim()
                    }.getOrNull()?.takeIf { it.startsWith("/") } ?: "/workspace"
                    settings.env = detected.copy(defaultCwd = cwd)
                    SetupState.Ready
                } ?: SetupState.NoEnvironment
            }
            main.post {
                setup = state
                env = settings.env
                if (state == SetupState.Ready) {
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
        settings.env = null
        env = null
        checkSetup()
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
        val active = current.isProcessAlive || background.values.any { it.isProcessAlive }
        val busy = current.busy || background.values.any { it.busy }
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
