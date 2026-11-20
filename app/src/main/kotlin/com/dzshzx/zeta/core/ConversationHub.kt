package com.dzshzx.zeta.core

import com.dzshzx.zeta.core.claude.ClaudeConfigClient
import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.core.claude.defaults
import com.dzshzx.zeta.core.diag.DiagLog
import com.dzshzx.zeta.core.schedule.ScheduleRunner
import com.dzshzx.zeta.phone.AccessibilityGuard
import com.dzshzx.zeta.phone.PhoneTools
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 应用级的单例：当前对话和切到后台还在跑的对话（打开、切换、分支、收掉、接回进程），各会话的手机工具，界面前后台，
 * 以及挂在它上面的几块：设置 [settings]、Linux 环境 [environment]、会话列表 [sessionList]、
 * 胶囊与提醒 [alerts]、额度 [quota]、账号 [accounts]。
 *
 * hub 是对话事件的入口：Conversation 的事件（一轮就要结束、结束了、等确认、后台任务做完、活动变化）都调 hub 上的 on… 方法，
 * 由 hub 分给上面这几块，Conversation 不直接找它们。只转给一处的（[onTurnEnding]、[onAttentionNeeded]）也照样经 hub 转。
 */
class ConversationHub(private val app: Application) {
    val context: Application get() = app
    val settings = Settings.of(app)
    val hostScript: String by lazy { app.assets.open("zeta_host.sh").bufferedReader().use { it.readText() } }

    /** Linux 一侧的程序 zeta-helper：APK 里带着，第一次用时装进 Linux（[HelperProgram]）。 */
    val helperProgram = HelperProgram({ name -> app.assets.open(name).use { it.readBytes() } }, AppVariant.current.linuxName)

    /**
     * 在 Linux 环境（默认是当前设置的那个）里跑 zeta-helper 的子命令 [subcommand]，参数逐个加引号；还没装就先装上。
     * 跑成了才有输出（[HelperResult]）。阻塞，别在主线程上调用。
     */
    fun helper(subcommand: String, arguments: List<String> = emptyList(), timeoutMs: Long = 30_000, config: LinuxEnvConfig? = settings.env): HelperResult {
        config ?: return HelperResult.Failed("还没有可用的 Linux 环境")
        return HelperResult.of(runCatching { helperProgram.run({ command, stdin -> LinuxEnv.run(config, command, stdin, timeoutMs) }, subcommand, arguments) }.getOrNull())
    }

    /** 确认 zeta-helper 已装进 Linux 环境 [config]（没装就装上）；要自己起它的进程（边读边出结果）之前用。装好了是 true。阻塞。 */
    fun ensureHelper(config: LinuxEnvConfig): Boolean =
        runCatching { helperProgram.ensure { command, stdin -> LinuxEnv.run(config, command, stdin, 60_000) }?.exitCode == 0 }.getOrDefault(false)

    /**
     * 新对话的默认值：Claude Code 自己的设置（~/.claude/settings.json，见 [ClaudeDefaults]），Zeta 不另存一份。
     * 新对话起进程时不带模型、思考强度和权限模式，由 Claude Code 照它定；对话里选的只管那个对话。设置页读写它；还没读到时为 null。
     */
    var claudeDefaults by mutableStateOf<ClaudeDefaults?>(null)
        private set

    /**
     * Claude Code 的默认权限模式（settings.json 没设时是逐项确认）；还没读到时为 null。
     * 对话还没定下模式时输入栏显示它（[Conversation.shownPermissionMode]）。
     */
    val defaultPermissionMode: String? get() = claudeDefaults?.let { it.permissionMode ?: "default" }

    /** 读到或改了 Claude Code 的默认值。在主线程上调用。 */
    internal fun updateClaudeDefaults(defaults: ClaudeDefaults) {
        claudeDefaults = defaults
    }

    /**
     * 还开着的 Claude Code 进程上用户在对话里定的权限模式（进程键 → 模式）。只在进程活着时有意义，和 Claude Code 里
     * 「只用于这个会话」一样：应用被回收后接回进程时照它恢复（[Conversation] 的 attach），进程没了在 [reattachHosts] 里清掉。
     */
    internal fun hostMode(key: String): String? = settings.hostModes[key]

    internal fun rememberHostMode(key: String, mode: String) {
        val modes = settings.hostModes
        if (modes[key] != mode) settings.hostModes = modes + (key to mode)
    }

    /** 重读 Claude Code 的默认值：环境就绪时、打开设置页时。读不到就照旧。 */
    fun refreshClaudeDefaults() {
        if (settings.env == null) return
        thread(name = "zeta-defaults", isDaemon = true) {
            val defaults = runCatching { ClaudeConfigClient.of(this).defaults() }.getOrNull() ?: return@thread
            main.post { updateClaudeDefaults(defaults) }
        }
    }

    /**
     * 当前对话还没开始（没有内容、没有会话）时，按现在的默认工作目录重建它：环境就绪时，
     * 设置从备份导入后（[Settings.restore] 已经让各项按导入的值重读），换了默认工作目录时。在主线程上调用。
     */
    internal fun renewBlank() {
        if (current.items.isEmpty() && current.sessionId == null) current = blank()
    }

    /** 从外面送进来（分享、打开、截屏）、还等用户选放进哪个对话的东西，见 [offerIntake]。 */
    var pendingIntake by mutableStateOf<Intake?>(null)

    /** 已经定了放进当前对话、等它的输入栏取走的东西。 */
    var incoming by mutableStateOf<Intake?>(null)

    private var hostsChecked = false


    /** Claude Code 报的模型选项（initialize 的应答）；还没起过进程时是空的，见 [modelOptions]。 */
    var models by mutableStateOf<List<ModelOption>>(emptyList())
        internal set

    /** 选择器里的模型：Claude Code 报的，还没拿到时是几个常用别名（[FallbackModels]）。 */
    val modelOptions: List<ModelOption> get() = models.ifEmpty { FallbackModels }

    /**
     * 每个会话的手机工具，按 sessionId（新对话还没有时按 key）登记。后台屏幕比 Claude Code 进程活得久：
     * 进程结束、重新打开会话时新建的对话对象从这里拿回原来那份，屏上的应用还是原来的样子。
     * 主线程和工具线程（[makeRoom]）都会访问，用它自己当锁；持锁时不调用会去拿 PhoneTools 锁的东西。
     * 要在 [current] 之前初始化：[blank] 建对话时就会来要。
     */
    private val phones = HashMap<String, PhoneTools>()

    /** 各对话没发出的输入草稿，按会话号保管，Zeta 重开后还在。 */
    val drafts = ComposerDrafts(app, settings)

    var current by mutableStateOf(blank())
        private set

    /** Linux 环境：检查、安装、维护、SSH、Claude 账号的登录状态。 */
    val environment = EnvController(this) { name -> app.assets.open(name).bufferedReader().use { it.readText() } }

    /** 胶囊、结果卡片、完成与确认提醒、实时通知显示哪个对话。 */
    val alerts = TurnAlerts(this)

    /** 侧栏的会话列表。 */
    val sessionList = SessionList(this)

    /** 当前账号的订阅额度（所有对话共用）。 */
    val quota = QuotaService(this)

    /** claude-switch 管着的 Claude 账号，和切换账号。 */
    val accounts = AccountService(this)

    /** 界面是否在前台；不在前台时一轮结束或需要批准会发通知。 */
    private val startedActivities = HashSet<Activity>()

    private var shown by mutableStateOf(false)

    /**
     * 界面是否正在显示（可观察：胶囊据此避开正显示着同一对话的聊天页）。
     * 按实例记：界面重建时新旧实例的 onStart/onStop 会交错，单个布尔值会被覆盖错。
     * 切到后台时开始等当前对话闲下来再收掉进程，回到前台就不等了（[retireCurrentWhenIdle]）。
     */
    var foreground: Boolean
        get() = shown
        internal set(value) {
            shown = value
            retireCurrentWhenIdle()
        }

    /** 用户正看着这个对话（Zeta 在前台、显示的就是它）：完成、确认都不用另外提醒。 */
    internal fun isWatching(conversation: Conversation): Boolean = foreground && conversation === current

    init {
        DiagLog.init(app)
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
        AccessibilityGuard.start(app)
    }

    /**
     * 切走的对话（parked）：不在前台显示、进程还留着的对话，按切走的先后排，最后一个是最近的（[IntakeRouting.pick] 要它）。
     * 切走时还没做完的保留到这一轮结束。可观察，只在主线程上改。按 [Conversation.openKey] 找（[parkedFor]），
     * 会话号后来才到也找得到，从列表打开这个会话时不会另建一个对话对象、和它抢同一份手机工具。
     */
    private val parked = mutableStateListOf<Conversation>()

    /** 切走的对话里会话号（新对话是 key）为 [key] 的那个。 */
    private fun parkedFor(key: String): Conversation? = parked.firstOrNull { it.openKey == key }

    /** Zeta 手上的全部对话：当前对话和切到后台还在跑的，当前的在前（可观察）。后台任务面板、胶囊、通知都按它挑。 */
    val liveConversations: List<Conversation> get() = listOf(current) + parkedConversations

    /** 切到后台还在跑的对话（定时任务起的也在这里），不含当前对话，按切走的先后排（可观察）。 */
    val parkedConversations: List<Conversation> get() = parked.filter { it !== current }
    private val main = Handler(Looper.getMainLooper())

    private fun blank() = Conversation(this, settings.env?.defaultCwd ?: "/workspace", sessionId = null, title = "", historyFile = null)

    fun isRunning(sessionId: String): Boolean = findConversation(sessionId)?.busy == true

    /** 这个会话的对话还在进行：在跑、等确认，或者后台任务没做完。批量删除跳过这些。 */
    fun isActive(sessionId: String): Boolean = findConversation(sessionId)?.active == true

    /** 侧栏的“等你确认”标记：这个会话的对话正等着用户批准一次工具调用。 */
    fun isWaiting(sessionId: String): Boolean = findConversation(sessionId)?.pending != null

    /** 环境检查完、就绪了（[EnvController.checkSetup]）：读会话列表和 Claude Code 的默认值，第一次就绪时接回还在跑的进程。在主线程上调用。 */
    internal fun onEnvironmentReady() {
        renewBlank()
        sessionList.refresh()
        refreshClaudeDefaults()
        if (!hostsChecked) {
            hostsChecked = true
            reattachHosts()
        }
    }

    /**
     * 应用上次是被系统回收或划掉的话，Claude Code 进程可能还在后台。
     * 还在干活或等确认的重新接上；闲着的直接结束，省内存。
     */
    private fun reattachHosts() {
        val config = settings.env ?: return
        thread(name = "zeta-hosts", isDaemon = true) {
            // 没查成不等于没有：什么都不动（不结束进程、不删权限模式），下次环境就绪时再查
            val output = helper("hosts", timeoutMs = 60_000, config = config).outputOrNull
            if (output == null) {
                main.post { hostsChecked = false }
                return@thread
            }
            val hosts = output.lineSequence().mapNotNull { line ->
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
                    taskEvents = o.arr("task_events").orEmpty().filterIsInstance<JsonObject>(),
                )
            }.toList()
            main.post {
                // 这个进程里已经接着的（比如界面打开前定时任务起的对话）不再接一次，也不当闲着收掉；在主线程上看才不漏
                val known = liveConversations.mapNotNullTo(HashSet()) { it.hostKey }
                val others = hosts.filter { it.key !in known }
                // 这一轮结束了但后台任务还在跑的也接上，否则结束进程会把任务一起杀掉
                val active = others.filter { it.active }
                val idle = others - active.toSet()
                if (idle.isNotEmpty()) thread(name = "zeta-hosts", isDaemon = true) {
                    for (host in idle) {
                        runCatching { LinuxEnv.run(config, "exec bash -c " + LinuxEnv.quote(hostScript) + " zeta-host stop " + LinuxEnv.quote(host.key)) }
                    }
                }
                // 用户的权限模式只留给还接着的进程
                val live = known + active.map { it.key }
                settings.hostModes.takeIf { modes -> modes.keys.any { it !in live } }?.let { modes ->
                    settings.hostModes = modes.filterKeys { it in live }
                }
                if (active.isEmpty()) return@post
                for (host in active) {
                    val conversation = Conversation(this, host.cwd, host.sessionId, host.title, host.historyFile, attach = host)
                    if (current.items.isEmpty() && current.sessionId == null && !current.isProcessAlive) {
                        current = conversation
                    } else {
                        park(conversation)
                    }
                }
                onActivityChanged()
            }
        }
    }

    fun startNew(cwd: String? = null) =
        replaceCurrent(if (cwd == null) blank() else Conversation(this, cwd, sessionId = null, title = "", historyFile = null))

    /**
     * 结束当前对话（[Conversation.shutdown]），界面换成空的新对话。和 [startNew] 的差别只在对话还在进行时：
     * 这里一并停掉，那边留在后台跑完。在主线程上调用。
     */
    fun endCurrent() = replaceCurrent(blank(), end = true)

    /** 换了环境（[EnvController.redetect]）：原来的会话都不再接着用，进程和后台屏幕一并收掉，换上一个空的新对话。在主线程上调用。 */
    internal fun dropConversations() {
        current.shutdown()
        parked.toList().forEach { it.shutdown() }
        parked.clear()
        synchronized(phones) { phones.values.toList().also { phones.clear() } }.forEach { it.destroy() }
        sessionList.clear()
        current = blank()
    }

    /** 有对话在进行：在回答、等你确认，或还有后台任务在跑（结束进程会把任务一起杀掉）。 */
    internal val conversationsWorking: Boolean
        get() = liveConversations.any { it.active }

    /**
     * 换 Claude 账号前：关掉闲着的对话背后的 Claude Code 进程（内容不丢，下次发消息时用 --resume 接上）。
     * 有对话在进行时什么都不做，返回 false。在主线程上调用。
     */
    internal fun closeIdleConversations(): Boolean {
        if (!environment.canSwitchEnvironment) return false
        current.shutdown()
        parked.toList().forEach { it.shutdown() }
        parked.clear()
        prunePhones()
        return true
    }

    fun open(summary: SessionSummary) {
        if (current.sessionId == summary.id) return
        replaceCurrent(
            parkedFor(summary.id) ?: Conversation(
                this, summary.cwd.ifBlank { settings.env?.defaultCwd ?: "/workspace" }, summary.id,
                summary.title.ifBlank { summary.preview.take(40) }, summary.file, external = summary.external,
            ),
        )
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
        val title = branchTitle(source.title, sessionList.sessions.map { it.title })
        replaceCurrent(
            Conversation(
                this, source.cwd, sessionId = null, title = title, historyFile = null,
                fork = Fork(sourceId, plan.cut, plan.lastSeen, plan.items, source.earlier, source.shownModel, source.effort, source.permissionMode),
            ),
        )
    }

    /**
     * 胶囊上的“在 Zeta 中打开”：切到会话 [key]（sessionId，新对话用它的 key）。
     * 还在后台跑的直接接过来；已经收掉的按会话列表重新打开；都找不到就不动。在主线程上调用。
     */
    fun openConversation(key: String) {
        val running = findConversation(key)
        if (running === current) return
        if (running != null) return replaceCurrent(running)
        sessionList.sessions.firstOrNull { it.id == key }?.let(::open) ?: sessionList.lookup(key, ::open)
    }

    /** 会话 [key]（sessionId，新对话用它的 key）当前或在后台的那个对话对象；没有就是 null。 */
    internal fun findConversation(key: String): Conversation? =
        if (current.sessionId == key || current.key == key) current else parkedFor(key)

    /** 放进切走的对话（定时任务在后台起的、切走时还在进行的）：不切换界面上的当前对话，跑完照常收掉。在主线程上调用。 */
    internal fun park(conversation: Conversation) {
        if (conversation !in parked) parked += conversation
    }

    /** 会话 [ids] 要删了（[SessionList.delete]）：开着的对话收掉，后台屏幕一并收掉。在主线程上调用。 */
    internal fun closeSessions(ids: Set<String>) {
        if (current.sessionId in ids) replaceCurrent(blank(), end = true)
        for (conversation in parked.filter { it.openKey in ids }) {
            parked.remove(conversation)
            conversation.shutdown()
        }
        synchronized(phones) { ids.mapNotNull { phones.remove(it) } }.forEach { it.destroy() }
        prunePhones()
    }

    /**
     * 换上 [next] 作当前对话。原来那个：[end] 时或闲着时收掉进程（省内存），还在进行的（包括有后台任务的）留在后台跑完。
     * 在主线程上调用。
     */
    private fun replaceCurrent(next: Conversation, end: Boolean = false) {
        val leaving = current
        if (end || !leaving.active) {
            parked.remove(leaving)
            leaving.shutdown()
        } else {
            park(leaving)
        }
        parked.remove(next)
        current = next
        prunePhones()
    }

    /** 会话 [key]（sessionId，新对话用它的 key）的手机工具：登记过就接着用原来那份。在主线程上调用。 */
    internal fun phoneFor(key: String): PhoneTools = synchronized(phones) {
        phones.getOrPut(key) {
            PhoneTools(app, settings, beforeCreate = ::makeRoom, onScreenGone = ::prunePhones, isBusy = ::phoneBusy, onChanged = ::onActivityChanged)
        }
    }

    /**
     * 用手机工具 [phone] 的对话正在进行（后台任务在跑也算）。按 hub 手上的对话查，不让手机工具拿着对话：
     * 对话收掉后有后台屏幕的手机工具还被登记着，拿着对话就连它的聊天内容、图片一起留住；收掉的对话也自然算不忙。
     * 读的都是快照状态，任何线程上都能调用。
     */
    private fun phoneBusy(phone: PhoneTools): Boolean = liveConversations.any { it.phone === phone && (it.busy || it.backgroundTasks > 0) }

    /**
     * 对话 [conversation] 的会话号从 [from] 换成了 [to]（新对话拿到会话号、/clear 换了新会话；还没有会话号时用对话的 key）。
     * 按会话登记的都在这里换键：手机工具（之后重新打开才找得回那块后台屏幕）、输入栏草稿；切走的对话按 openKey 找，不用换。
     * 新加按会话登记的东西也在这里跟着换（SessionRekeyTest 核对）。在主线程上调用。
     */
    internal fun sessionChanged(conversation: Conversation, from: String, to: String) {
        rekeyPhone(conversation.phone, from, to)
        drafts.rekey(conversation, from, to)
    }

    /** 对话的会话号变了：登记跟着换。新号下面要是还挂着别的一份（不该有），它已经没人找得到，收掉。 */
    private fun rekeyPhone(phone: PhoneTools, from: String, to: String) {
        val stale = synchronized(phones) {
            // 已经不在登记里（没人用被丢掉、销毁了）就别再登记回去，否则以后打开这个会话会拿到一份建不了屏的
            if (phones[from] !== phone) return
            phones.remove(from)
            phones.put(to, phone)?.takeIf { it !== phone }
        }
        stale?.destroy()
    }

    /** 丢掉没有后台屏幕、也没有对话在用的那些，免得登记越积越多。在主线程上调用。 */
    private fun prunePhones() {
        val inUse = liveConversations.mapTo(HashSet()) { it.phone }
        val unused = synchronized(phones) {
            phones.values.filter { it !in inUse && it.slot.alive == null }.also { phones.values.removeAll(it.toSet()) }
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
        val others = synchronized(phones) { phones.values.filter { it !== phone && it.slot.alive != null } }
        val excess = others.size - MAX_SCREENS + 1
        if (excess <= 0) return
        val watching = current.phone
        others.filter { !it.slot.busy() }
            .sortedWith(compareBy<PhoneTools> { it === watching }.thenBy { it.slot.lastUse })
            .take(excess)
            .forEach { it.slot.releaseAsync() }
    }

    /** 对话的这一轮就要结束（还没清 busy 和手机工具的状态），[text] 是胶囊上停留的那一句（“已完成”）。在主线程上调用。 */
    internal fun onTurnEnding(conversation: Conversation, text: String) = alerts.turnEnding(conversation, text)

    /** 一轮结束了：切走的对话闲下来就收掉，提醒交给 [alerts]，会话列表重读。[stopped]：这一轮是用户叫停的。 */
    internal fun onTurnFinished(conversation: Conversation, stopped: Boolean = false) {
        if (conversation !== current) retireWhenIdle(conversation) else retireCurrentWhenIdle()
        alerts.turnFinished(conversation, stopped)
        sessionList.refresh()
    }

    /** 闲着的对话最后一个后台任务也结束了（这一轮早已结束）。 */
    internal fun onBackgroundTasksDone(conversation: Conversation) {
        if (conversation !== current) retireWhenIdle(conversation) else retireCurrentWhenIdle()
    }

    /**
     * Zeta 不在前台时，当前对话闲着（[Conversation.active] 为假）满 [IDLE_RETIRE_MS] 就收掉它背后的 Claude Code 进程，
     * 内容不丢，下次发消息时用 --resume 接上。不收的话，这个进程和为它常驻的前台服务（[onActivityChanged]）互相保着，
     * 用过一次 Zeta 就一直占着几百 MB 内存和一条常驻通知。切到后台、一轮结束、后台任务做完时重新计时，回到前台就不等了。
     * 到点时还在进行的不收，等它这一轮结束再计时。
     */
    private val retireCurrent = Runnable { if (!foreground && !current.active) current.shutdown() }

    private fun retireCurrentWhenIdle() {
        main.removeCallbacks(retireCurrent)
        if (!foreground) main.postDelayed(retireCurrent, IDLE_RETIRE_MS)
    }

    /**
     * 切走的对话没事了（没有进行中的一轮、没有后台任务、没有等确认的调用）就收掉进程。
     * 稍等一下再看：后台任务跑完后 Claude Code 会紧接着自己另起一轮汇报结果（实测不到 0.2 秒），
     * 立刻收掉就把这一轮掐掉了；起了新的一轮的话等它结束再说。
     */
    private fun retireWhenIdle(conversation: Conversation) {
        main.postDelayed({
            if (conversation !== current && !conversation.active) {
                parked.remove(conversation)
                conversation.shutdown()
                prunePhones()
            }
        }, RETIRE_DELAY_MS)
    }

    /** 对话停下来等用户批准一次工具调用。 */
    internal fun onAttentionNeeded(conversation: Conversation) = alerts.attentionNeeded(conversation)

    /** 有进程活着就保持前台服务，否则系统会在切到后台后很快回收本应用，连带结束 Claude Code。 */
    internal fun onActivityChanged() {
        val working = environment.working
        val active = working || liveConversations.any { it.isProcessAlive }
        // 后台任务跑着也要持有唤醒锁，否则息屏休眠时任务会被挂起
        val busy = working || conversationsWorking
        val answering = liveConversations.filter { it.busy }
        val running = answering.map { conversation ->
            conversation.title.ifBlank { "新对话" } + (conversation.phone.agentDisplayApp?.let { " · 在后台操作$it" } ?: "")
        }
        AgentService.update(app, active, busy, running, alerts.liveStatus(answering))
    }

    /** 通知栏的“停止”：打断所有正在回答的对话，和聊天页的停止键一样。 */
    internal fun interruptAll() {
        liveConversations.filter { it.busy }.forEach { it.interrupt() }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private const val RETIRE_DELAY_MS = 5_000L

        /** 和 zeta_host.sh 的看门狗一样：没人看着、闲着的进程约十分钟后结束。 */
        internal const val IDLE_RETIRE_MS = 10 * 60_000L

        /** 全应用最多同时留几块后台屏幕：每块都连着一个 root 辅助进程和屏上开着的应用，太多了内存吃不消。 */
        private const val MAX_SCREENS = 3

        @Volatile private var instance: ConversationHub? = null

        fun get(app: Application): ConversationHub = instance ?: synchronized(this) {
            instance ?: ConversationHub(app).also { instance = it }
        }
    }
}
