package com.dzshzx.zeta.core

import com.dzshzx.zeta.phone.PhoneTools
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 回答进行中发出、Claude Code 还没取走的消息。 */
class QueuedMessage(val uuid: String, val text: String, val attachments: List<Attachment>, val files: List<String>)

/**
 * 一个对话：界面状态 + 背后的 Claude Code 进程。进程在第一次发送时才启动；
 * 历史对话用 `--resume` 接着原来的会话记录继续。状态只在主线程上改。
 */
/** 追加给 Claude Code 的一句说明：让它知道对面是什么界面、怎样把图片给用户看。 */
private const val ClientHint =
    "The user is reading this conversation in Zeta, a chat app on their Android phone. It renders Markdown. " +
        "To show the user an image file from this machine, include it in your reply as a Markdown image with an absolute path, " +
        "for example ![chart](/home/user/out/chart.png); the app displays it inline. Remote (http/https) Markdown images are shown too, after the user taps them. " +
        "Lines of the form [Attached file: /path] at the start of a user message are files the user attached; read them with your tools."

class Conversation(
    private val hub: ConversationHub,
    val cwd: String,
    sessionId: String?,
    title: String,
    private val historyFile: String?,
    /** 上次退出时留下、现在要重新接上的后台进程。 */
    attach: HostInfo? = null,
    /** 打开时这个会话正被别的 Claude Code 进程占用（比如终端里开着）。 */
    external: Boolean = false,
    /** 从别的会话分支出来、还没发过消息的新对话。 */
    private val fork: Fork? = null,
) {
    /** 界面用的稳定标识；会话还没建立（没有 sessionId）时也要能区分。 */
    val key: String = sessionId ?: "new-" + UUID.randomUUID()

    private var session by mutableStateOf(sessionId)

    var sessionId: String?
        get() = session
        private set(value) {
            val before = session
            if (value == before) return
            session = value
            // 手机工具按会话登记：新对话拿到会话号、/clear 换了新会话时跟着改，之后重新打开才找得回这块后台屏幕
            hub.rekeyPhone(phone, before ?: key, value ?: key)
            hub.rekeyBackground(this, before ?: key, value ?: key)
            // 输入栏的草稿也按会话号存：新对话发出第一条后还在输入的，重新打开时才找得回来
            hub.drafts.rekey(this, before ?: key, value ?: key)
        }
    var title by mutableStateOf(title)
        internal set

    /** 会话在别处运行时先不让发送，用户明确选择接管后才放开。 */
    var blockedByExternal by mutableStateOf(external)
        private set

    fun takeOver() {
        blockedByExternal = false
    }
    var items by mutableStateOf<List<ChatItem>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set

    /**
     * 还在跑的后台任务数（后台命令、后台子代理、监视器等），以 Claude Code 最近一次 background_tasks_changed 为准。
     * 这一轮结束后它们照样在跑，跑完时 Claude Code 会自己另起一轮汇报结果，所以进程不能当闲着收掉。
     */
    var backgroundTasks by mutableStateOf(0)
        private set

    /** 后台任务的清单和输出，给后台任务面板看。 */
    val tasks = BackgroundTasks()

    /** 子代理查看页看过的子代理，按发起它的 Agent 调用；只留最近几个。 */
    private val subagentLogs = object : LinkedHashMap<String, SubagentLog>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SubagentLog>?) = size > MAX_SUBAGENT_LOGS
    }

    /** Agent 调用 → 子代理 id（task_started 的 task_id、工具结果里的 agentId）：读记录时不用逐个翻 meta。 */
    private val subagentIds = LinkedHashMap<String, String>()

    /** 输出流里报了结局的子代理：子代理 id → 结局。 */
    private val subagentOutcomes = LinkedHashMap<String, TaskState>()

    /** /clear 之前用过的会话号：那之前的子代理记录在那些会话的目录里。 */
    private val pastSessions = ArrayList<String>()
    var loadingHistory by mutableStateOf(historyFile != null)
        private set

    /** 会话记录里还没载入的更早部分；全载入了（或是新对话）为 null。往上翻时用 [loadEarlier] 一页页接到前面。 */
    var earlier by mutableStateOf<EarlierHistory?>(fork?.earlier)
        private set

    /** 还没载入的更早记录条数。 */
    val skippedHistory: Int get() = earlier?.skipped ?: 0

    var earlierState by mutableStateOf<EarlierState>(EarlierState.Idle)
        private set

    /** 剪会话的应答回来之前读回来的一页：等剪完再接上（剪不成要恢复成剪之前的列表，不能夹在中间）。 */
    private var deferredEarlier: (() -> Unit)? = null

    /** 没载入的更早历史里的清单工具调用，任务清单面板据此接上更早建的清单。 */
    var todoSeed by mutableStateOf<List<ToolStep>>(emptyList())
        private set
    var pending by mutableStateOf<PermissionRequest?>(null)
        private set

    /** 对话在进行：在回答、等你确认，或者还有后台任务在跑。结束进程会打断它，批量删除、切环境、换账号都要等它。 */
    val active: Boolean get() = busy || pending != null || backgroundTasks > 0

    /** 还没开始的新对话：没有会话号、没有内容、没在跑。设置页改新对话的默认值时它跟着换。 */
    val notStarted: Boolean get() = sessionId == null && items.isEmpty() && !active

    /** 打开、登记这个对话用的标识：会话号，新对话还没有会话号时用 [key]。 */
    val openKey: String get() = sessionId ?: key

    /**
     * Zeta 要 Claude Code 用的模型（启动时的 --model、进程里的 set_model），"default" 是 Claude Code 的默认模型；
     * null 是不指定。新对话取新对话的默认值。接着已有的会话（打开旧对话、分支）时不指定，由 Claude Code 照会话记录恢复它上次用的模型，
     * 直到用户在这个对话里亲手选一个（或定时任务、数字助理 [preset] 一个）。界面显示的是 [shownModel]。
     */
    var model by mutableStateOf(if (sessionId != null || fork != null) null else hub.settings.model)
        private set

    /** 接着已有的会话（打开时有会话号，或是分支）。 */
    private val resumed = sessionId != null || fork != null

    /** 模型由 Claude Code 照会话记录定：接着已有的会话，用户也没在这个对话里指定过。 */
    val modelFromSession: Boolean get() = resumed && model == null

    /**
     * Claude Code 报的、这个对话实际在用的模型（system init 里的完整 ID）。进程起来之前是会话记录里最后一次真实回答用的，
     * 分支是原对话当时显示的；都没有时是 null。
     */
    var reportedModel by mutableStateOf(fork?.shownModel)
        private set

    /** 顶栏显示的模型：指定了的就是它，没指定时是 Claude Code 报的（或会话记录里的）；都不知道时是 null，即默认模型。 */
    val shownModel: String? get() = model ?: reportedModel

    /** 进程现在用的权限模式，输入栏显示它。后台运行发指令后是那次运行的模式，用户自己的记在 [ownMode]。 */
    var permissionMode by mutableStateOf(hub.settings.permissionMode ?: hub.defaultPermissionMode)
        private set

    /**
     * 用户自己的消息用的权限模式，只在进程切到了后台运行的模式时记着，平时为 null（这时就是 [permissionMode]）。
     * 每条消息按发的人的权限跑（见 [authorize]）：后台运行发指令时切到任务的模式，把用户的记在这里；用户自己发下一条或改写时切回来。
     * 用户在输入栏亲手选了模式就不再切回，这一轮剩下的部分也按用户选的跑。只记在内存里：Zeta 在后台运行还没跑完时被结束、
     * 重启后接回这个对话，进程停在任务的模式上（输入栏照实显示），用户选一次就回来。
     */
    private var ownMode: String? = null

    /**
     * 思考强度（Claude Code 的 effort）；null 表示不指定，沿用 Claude Code 自己的设置。新对话取新对话的默认值，分支接着用原对话的；
     * Claude Code 接旧会话时不恢复它，所以旧对话载入历史后照最后一次真实回答恢复（[restoreEffort]）。
     */
    var effort by mutableStateOf(if (fork != null) fork.effort else hub.settings.effort)
        private set

    /** 上下文占用与用量统计，由 [UsageTracker] 从进程输出和会话记录里累计。 */
    var usage by mutableStateOf(ChatUsage())
        private set
    private val meter = UsageTracker()

    /** 新对话第一轮结束后请 Claude Code 起标题。 */
    private val autoTitle = AutoTitle()

    /**
     * 排队中的消息。Claude Code 会在下一个工具调用结束时把它并进正在进行的这一轮，
     * 没有工具调用可等时就在这一轮结束后另起一轮。
     */
    var queued by mutableStateOf<List<QueuedMessage>>(emptyList())
        private set

    /** 用户点了“立即插话”：当前这一轮已叫停，等排队的消息开始。 */
    var steering by mutableStateOf(false)
        private set

    /** 撤回的排队消息，等输入框取走（[takeRecalled]）。 */
    var recalled by mutableStateOf<QueuedMessage?>(null)
        private set

    /** 进行中的临时状态（比如网络出错正在重试），显示在等待指示旁边。 */
    var status by mutableStateOf<String?>(null)
        private set

    /** 最近一次收到进程输出（或发出消息）的时间，界面据此显示“已等待多久”。 */
    var lastActivityAt by mutableStateOf(0L)
        private set

    // 分支先显示原对话的内容，新内容的编号要和那些错开
    private var reducer = TranscriptReducer(if (fork != null) "b" + UUID.randomUUID().toString().take(8) + "-" else "")
    private var process: ClaudeProcess? = null

    /** 正在跑的 Claude Code 进程的托管标识（zeta_host.sh 的 key）：看它的挂载命名空间、认领后台进程都用它；没有进程时为 null。 */
    val hostKey: String? get() = process?.hostKey

    /** 等 Claude Code 剪会话（rewind_conversation）的那个请求；答应了做 onDone，没答应做 onFailed。 */
    private class PendingRewind(val requestId: String, val onDone: () -> Unit, val onFailed: (String) -> Unit)

    private var rewind: PendingRewind? = null

    /** 正在等 Claude Code 剪会话：这期间不收新消息。 */
    var rewinding by mutableStateOf(false)
        private set

    /** 分支在这个进程里已经剪过了（换了进程要重新剪）。 */
    private var forkCut = false
    /** 只在 Claude Code 不报 command_lifecycle 时（老版本）用来判断一轮是否结束。 */
    private var turnsInFlight = 0
    private var lifecycleSeen = false
    /** 撤回时要放回输入框的消息（其余的撤回直接丢弃）。 */
    private val recalling = HashSet<String>()
    private var interruptRequested = false
    private val main = Handler(Looper.getMainLooper())
    private val inbox = ConcurrentLinkedQueue<JsonObject>()

    /**
     * 这个会话的手机工具（各有各的后台屏幕）。向 hub 要：重新打开的会话接着用原来那份，进程结束过屏幕也还在。
     * Claude Code 还有后台任务在跑也算忙：后台屏幕不休眠、不被腾掉。
     */
    val phone = hub.phoneFor(openKey).also { it.attach(busy = { busy || backgroundTasks > 0 }, onChanged = { hub.onActivityChanged() }) }

    /** 顶部状态胶囊看到的这个对话。 */
    internal val overlay = ConversationFocus(hub, this)
    private val drainScheduled = AtomicBoolean(false)
    private var requestSeq = 0

    val isProcessAlive: Boolean get() = process != null

    init {
        if (attach != null) {
            busy = attach.busy
            backgroundTasks = attach.tasks
            // 接回时还在跑的后台任务：助手脚本从输出日志里摘出来的那几条事件
            attach.taskEvents.forEach { tasks.observe(it, System.currentTimeMillis()) }
            turnsInFlight = if (attach.busy) 1 else 0
            attach.permissionMode?.let { permissionMode = it }
            lastActivityAt = System.currentTimeMillis()
        }
        if (fork != null) {
            reducer.items.addAll(fork.items)
            reducer.addNotice("从这里分支出的新对话，原对话不变。发出消息后保存", error = false)
            publish()
        }
        when {
            historyFile != null -> loadHistory(historyFile, attach)
            attach != null -> startAttached(attach)
        }
    }

    /** 接上后台进程：从日志里上次读到的位置继续，并恢复还没答复的确认请求。 */
    private fun startAttached(host: HostInfo) {
        val config = hub.settings.env ?: return
        lateinit var created: ClaudeProcess
        created = try {
            ClaudeProcess(
                config = config,
                hostScript = hub.hostScript,
                hostKey = host.key,
                launch = ClaudeProcess.Launch.Attach(host.lines + 1),
                onEvent = { event -> enqueue(created, event) },
                onExit = { code, stderr -> main.post { onProcessExit(created, code, stderr) } },
            )
        } catch (error: Exception) {
            endTurnState()
            reducer.addNotice("重新接上后台进程失败：${error.message}", error = true)
            publish()
            return
        }
        process = created
        created.send(controlRequest { put("subtype", "initialize") })
        // 接上的进程是以前起的，不知道当时给的百分比，按现在的设置算（设置在那之后改过才会对不上）
        meter.processStarted(fresh = false, compactPercent = hub.settings.autoCompactPercent.takeIf { hub.settings.autoCompact })
        queryUsage(created)
        host.pending?.let { handle(it, System.currentTimeMillis()) }
        hub.onActivityChanged()
    }

    private fun loadHistory(file: String, attach: HostInfo?) {
        val config = hub.settings.env ?: run { loadingHistory = false; return }
        thread(name = "zeta-history", isDaemon = true) {
            val loaded = TranscriptReducer("h")
            val result = hub.helper("history", listOf(file), timeoutMs = 60_000, config = config)
            val read = HistoryPaging.read(result?.stdout.orEmpty(), file, loaded, keepRunningTools = attach?.busy == true)
            main.post {
                // 载入期间用户已经发了新消息的话，新内容接在历史后面
                loaded.items.addAll(reducer.items)
                reducer = loaded
                meter.prependHistory(read.usage)
                publishUsage()
                earlier = read.earlier
                todoSeed = read.todoSeed
                loadingHistory = false
                if (result == null || result.exitCode != 0) {
                    reducer.addNotice("读取历史记录失败" + result?.stderr?.takeIf { it.isNotBlank() }?.let { "：${it.trim().take(300)}" }.orEmpty(), error = true)
                } else {
                    if (reportedModel == null) reportedModel = read.model
                    restoreEffort(read.effort)
                }
                publish()
                // 历史就位之后再接进程，免得实时事件落在即将被替换掉的列表上
                if (attach != null) startAttached(attach)
            }
        }
    }

    /**
     * 载入更早的一页，接到列表前面（往上翻到顶附近时）；[rest] 时一次载完剩下的全部（对话内查找时）。
     * 已经在读、或没有更早的了就不动，所以同一时间只有一个请求，读回来的就是现在没载入的那段（别的地方不改 [earlier]）。
     * 在主线程上调用，读在后台。新到的实时内容只接在列表末尾，和往前接的这页不相干。
     */
    fun loadEarlier(rest: Boolean = false) {
        val window = earlier ?: return
        if (earlierState == EarlierState.Loading || loadingHistory) return
        val config = hub.settings.env ?: return
        earlierState = EarlierState.Loading
        thread(name = "zeta-history-page", isDaemon = true) {
            // 各页的条目编号按页的位置错开（每页的位置都不同）
            val page = TranscriptReducer("p${window.skipped}-")
            val result = hub.helper("history", HistoryPaging.pageArguments(window, rest), timeoutMs = 60_000, config = config)
            val read = result?.takeIf { it.exitCode == 0 }?.let { HistoryPaging.read(it.stdout, window.file, page) }
            val failure = when {
                read == null -> "没有读到更早的记录" + result?.stderr?.trim()?.takeIf { it.isNotEmpty() }?.let { "：${it.takeLast(200)}" }.orEmpty()
                else -> null
            }
            main.post { applyEarlier(page.items, read, failure) }
        }
    }

    private fun applyEarlier(items: List<ChatItem>, read: HistoryRead?, failure: String?) {
        if (rewinding) {
            deferredEarlier = { applyEarlier(items, read, failure) }
            return
        }
        when {
            read == null -> earlierState = EarlierState.Failed(failure ?: "没有读到更早的记录", retry = true)
            read.stale -> earlierState = EarlierState.Failed("会话记录在别处改过，重新打开这个对话再看更早的内容", retry = false)
            else -> {
                // 只接在最前面：正看着的那条由列表按 key 保持在原处（见 ui/chat/EarlierHistory.kt）
                reducer.items.addAll(0, items)
                earlier = read.earlier
                todoSeed = read.todoSeed
                earlierState = EarlierState.Idle
                meter.prependHistory(read.usage)
                publishUsage()
                publish()
            }
        }
    }

    private fun publish() {
        items = reducer.items.toList()
    }

    /** 提前把进程起好（不产生请求）：用户开始输入斜杠命令时调用，好拿到命令清单。 */
    fun prepare() {
        if (!blockedByExternal && !loadingHistory) ensureProcess()
    }

    /** 用户自己发的消息。[files] 是已经复制进 Linux 环境的附件路径。 */
    fun send(text: String, attachments: List<Attachment> = emptyList(), files: List<String> = emptyList()) = send(text, attachments, files, run = null)

    /**
     * 后台运行（定时任务、通知触发）发指令：进程先切到这次运行的权限模式 [mode]、加上 [rules]，再发。
     * 两样都留到用户自己发下一条消息时才换回来，见 [authorize]。
     */
    internal fun sendUnattended(text: String, mode: String, rules: JsonObject) = send(text, emptyList(), emptyList(), RunPermissions(mode, rules))

    /** 后台运行这一条用的权限：权限模式，和叠在用户规则上的规则（见 [restrict]）。 */
    private class RunPermissions(val mode: String, val rules: JsonObject)

    private fun send(text: String, attachments: List<Attachment>, files: List<String>, run: RunPermissions?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty() && files.isEmpty()) return
        // 剪会话的应答回来之前不收：这时发出的消息可能被 Claude Code 算在剪之前
        if (rewinding) return
        if (title.isBlank()) {
            title = trimmed.lineSequence().first().take(40).ifBlank { if (files.isNotEmpty()) files.first().substringAfterLast('/') else "图片" }
            autoTitle.arm(title)
        }
        val process = authorize(run) ?: return
        val message = stage(trimmed, attachments, files)
        val cut = fork?.cut?.takeIf { sessionId == null && !forkCut }
        if (cut == null) {
            transmit(process, message)
        } else {
            // 分支的第一条消息：先在新进程里剪掉原会话的后几轮，再发
            requestRewind(process, cut, fork.lastSeen,
                onDone = {
                    forkCut = true
                    transmit(process, message)
                },
                onFailed = { reason ->
                    reducer.items.removeAll { it.id == message.itemId }
                    abandon(message, "分支没有建成：$reason")
                },
            )
        }
        hub.onActivityChanged()
    }

    /**
     * 编辑或重新生成：丢掉 [target] 这条用户消息和之后的内容，从那里用新消息接着跑。会话号不变。
     * 先请 Claude Code 剪会话，它答应了才把消息交出去；没答应就恢复原样，新消息放回输入框。
     */
    fun rewrite(target: UserItem, text: String, attachments: List<Attachment> = emptyList(), files: List<String> = emptyList()) {
        val uuid = target.uuid ?: return
        if (!canRewind || (text.isBlank() && attachments.isEmpty() && files.isEmpty())) return
        val process = authorize(null) ?: return
        val before = reducer.items.toList()
        if (!truncateBefore(reducer.items, target.id)) return
        val message = stage(text.trim(), attachments, files)
        requestRewind(process, uuid, lastSeen(before),
            onDone = {
                // 在还没发过消息的分支里改写：这一刀已经把分支要剪的部分一起剪掉了
                forkCut = true
                transmit(process, message)
            },
            onFailed = { reason ->
                restore(before)
                abandon(message, reason)
            },
        )
    }

    /** 删除 [target] 开始的这一轮和之后的内容（只剪不发）。 */
    fun dropFrom(target: UserItem) {
        val uuid = target.uuid ?: return
        if (!canRewind) return
        val process = ensureProcess() ?: return
        val before = reducer.items.toList()
        if (!truncateBefore(reducer.items, target.id)) return
        publish()
        requestRewind(process, uuid, lastSeen(before),
            onDone = {
                forkCut = true
                hub.refreshSessions()
            },
            onFailed = { reason ->
                restore(before)
                reducer.addNotice("没有删除：$reason", error = true)
            },
        )
    }

    /** 剪的时候报给 Claude Code 的最后一条用户消息。还没剪过的分支，进程里是原会话的全部内容，要报原会话的。 */
    private fun lastSeen(items: List<ChatItem>): String? =
        if (fork != null && sessionId == null && !forkCut) fork.lastSeen else lastSeenUser(items)

    /** 现在能不能编辑、重新生成、删除一轮或分支：这一轮已经结束，也没有别的事在等。 */
    val canRewind: Boolean
        get() = !busy && pending == null && !rewinding && !loadingHistory && !blockedByExternal && queued.isEmpty()

    /** 一条要发的消息：先进列表（或排队），能发了再交给进程。[itemId] 是它在列表里的那一项（排队的没有）。 */
    private class Outgoing(val uuid: String, val text: String, val attachments: List<Attachment>, val files: List<String>, val itemId: String?)

    private fun stage(trimmed: String, attachments: List<Attachment>, files: List<String>): Outgoing {
        val uuid = UUID.randomUUID().toString()
        var itemId: String? = null
        // 回答进行中发的先排队，等 Claude Code 真正取走（command_lifecycle started）才进聊天记录
        if (busy && lifecycleSeen) {
            queued = queued + QueuedMessage(uuid, trimmed, attachments, files)
        } else {
            itemId = reducer.addUser(trimmed, attachments.map { InlineImage("a:${it.id}", it.base64) }, files, uuid)
            turnsInFlight++
        }
        busy = true
        interruptRequested = false
        status = null
        lastActivityAt = System.currentTimeMillis()
        publish()
        return Outgoing(uuid, trimmed, attachments, files, itemId)
    }

    /** 没能发出的消息：这一轮作罢，文字和附件放回输入框。 */
    private fun abandon(message: Outgoing, reason: String) {
        turnsInFlight = 0
        busy = false
        addRecalled(QueuedMessage(message.uuid, message.text, message.attachments, message.files))
        reducer.addNotice("没有发出：$reason", error = true)
        publish()
        hub.onActivityChanged()
    }

    private fun restore(items: List<ChatItem>) {
        reducer.items.clear()
        reducer.items.addAll(items)
        publish()
    }

    /**
     * 这一条按发的人的权限跑：后台运行（[run]）切到它的权限模式、加上它的规则；用户自己的切回 [ownMode]、撤掉规则。
     * 模式在起进程前就切好，进程还没起来时按切好的模式起。返回进程，起不来时为 null。
     */
    private fun authorize(run: RunPermissions?): ClaudeProcess? {
        if (run != null) {
            // 用户的模式还不知道（Claude Code 还没报过）：之后切回逐项确认，宁可多问
            if (ownMode == null) ownMode = permissionMode ?: "default"
            switchMode(run.mode)
        } else {
            ownMode?.let { mode -> ownMode = null; switchMode(mode) }
        }
        val process = ensureProcess() ?: return null
        restrict(process, run?.rules)
        return process
    }

    /** 把进程切到 [mode]，已经是了就不发；进程还没起来时只记下，起进程时带上。 */
    private fun switchMode(mode: String) {
        if (mode == permissionMode) return
        permissionMode = mode
        process?.send(controlRequest { put("subtype", "set_permission_mode"); put("mode", mode) })
    }

    /**
     * 后台运行的权限规则：后台运行发指令前加上 [rules]，用户自己发消息、改写前撤掉（null）。用户发的每条都撤一次，不记加没加过：
     * 没加过时撤了什么都不变，进程是新起的、重新接上的还是一直开着的都一样。走 apply_flag_settings 的 permissions，
     * 是单独的一层：和用户 settings.json 里的规则叠加，撤时只撤这一层，思考强度（同一层的 effortLevel）不受影响。
     * 控制请求按顺序处理，新起的进程紧跟在 initialize 后面发，第一条消息就生效。这些都由 UnattendedRulesContractTest 钉住。
     */
    private fun restrict(process: ClaudeProcess, rules: JsonObject?) = applyFlags(process) { put("permissions", rules ?: JsonNull) }

    /** Claude Code 的 apply_flag_settings：只改这个进程、叠在 settings.json 上的一层（思考强度、后台运行的权限规则）。 */
    private fun applyFlags(process: ClaudeProcess, settings: JsonObjectBuilder.() -> Unit) =
        process.send(controlRequest { put("subtype", "apply_flag_settings"); putJsonObject("settings", settings) })

    /** 请 Claude Code 把会话剪到 [target] 这条用户消息之前。应答在 [handle] 里收。 */
    private fun requestRewind(process: ClaudeProcess, target: String, lastSeen: String?, onDone: () -> Unit, onFailed: (String) -> Unit) {
        val request = controlRequest { for ((key, value) in rewindRequest(target, lastSeen)) put(key, value) }
        rewind = PendingRewind(request.str("request_id").orEmpty(), onDone, onFailed)
        rewinding = true
        process.send(request)
        hub.onActivityChanged()
    }

    private fun finishRewind(failure: String?) {
        val request = rewind ?: return
        rewind = null
        rewinding = false
        if (failure == null) request.onDone() else request.onFailed(failure)
        publish()
        // 剪的期间读回来的更早一页：列表已经是剪完（或恢复）的样子，现在接上
        deferredEarlier?.also { deferredEarlier = null }?.invoke()
        hub.onActivityChanged()
    }

    private fun transmit(process: ClaudeProcess, message: Outgoing) {
        if (process !== this.process) return
        val attachments = message.attachments
        val outgoing = withAttachedFiles(message.text, message.files)
        process.send(buildJsonObject {
            put("type", "user")
            put("uuid", message.uuid)
            putJsonObject("message") {
                put("role", "user")
                putJsonArray("content") {
                    for (attachment in attachments) {
                        add(buildJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", attachment.mediaType)
                                put("data", attachment.base64)
                            }
                        })
                    }
                    if (outgoing.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", outgoing) })
                }
            }
        })
    }

    /** 停止：排队的消息一并撤回放回输入框（和 Claude Code 终端里按 Esc 一样），再打断当前这一轮。 */
    fun interrupt() {
        // 暂停、接管着的手机操作一并作废，否则放开后它会接着点下去
        phone.cancelWaiting()
        val process = process ?: return
        // 改写的消息还在等 Claude Code 剪会话，没有交出去，这时没有可打断的
        if (!busy || rewinding) return
        for (message in queued) cancelQueued(message.uuid, recall = true)
        steering = false
        interruptRequested = true
        process.send(controlRequest { put("subtype", "interrupt") })
        // 正在等用户批准的调用一并拒绝，否则进程会一直等
        pending?.let { respond(it, allow = false) }
    }

    /** 立即插话：叫停当前这一轮，排队的消息随即开始，不必等手上的工具跑完。 */
    fun steerNow() {
        val process = process ?: return
        if (!busy || queued.isEmpty() || steering) return
        phone.cancelWaiting()
        steering = true
        interruptRequested = true
        process.send(controlRequest { put("subtype", "interrupt") })
        pending?.let { respond(it, allow = false) }
    }

    /** 撤回一条排队消息；[recall] 时放回输入框，否则直接丢掉。已被取走的撤不回，会照常出现在记录里。 */
    fun cancelQueued(uuid: String, recall: Boolean) {
        val process = process ?: return
        if (queued.none { it.uuid == uuid }) return
        if (recall) recalling.add(uuid)
        process.send(controlRequest {
            put("subtype", "cancel_async_message")
            put("message_uuid", uuid)
        })
    }

    fun takeRecalled(): QueuedMessage? = recalled.also { recalled = null }

    /** 撤回的消息并进待放回输入框的那份（连续撤回几条时按顺序拼起来）。 */
    private fun addRecalled(message: QueuedMessage) {
        val before = recalled
        recalled = if (before == null) message else QueuedMessage(
            before.uuid,
            listOf(before.text, message.text).filter { it.isNotBlank() }.joinToString("\n"),
            before.attachments + message.attachments,
            before.files + message.files,
        )
    }

    /**
     * 用户选了模型（[value] 是选择器里的值，null 是默认模型），也作为之后新对话的默认值。
     * 新对话选默认就是不指定（Claude Code 照它自己的设置）；接着已有会话的对话要明说 "default"，否则 Claude Code 会照会话记录恢复原来的模型。
     */
    fun chooseModel(value: String?) {
        val chosen = value?.takeIf { it != DEFAULT_MODEL }
        model = chosen ?: DEFAULT_MODEL.takeIf { resumed }
        // 上次 init 报的模型作废：新对话选回默认时顶栏写「默认」，下一轮的 init 再报实际用的
        reportedModel = null
        hub.rememberDefaultModel(chosen)
        process?.send(controlRequest { put("subtype", "set_model"); put("model", chosen ?: DEFAULT_MODEL) })
        process?.let(::queryUsage)
    }

    /** 思考强度：这个对话立即生效（apply_flag_settings），也作为之后新对话的默认值。null 回到 Claude Code 自己的设置。 */
    fun chooseEffort(value: String?) {
        hub.rememberDefaultEffort(value)
        applyEffort(value)
    }

    /** 这个对话的思考强度：进程已经起来就当场改，再要一次生效的设置；没起来时启动参数 --effort 带上。 */
    private fun applyEffort(value: String?) {
        effort = value
        val process = process ?: return
        applyFlags(process) { put("effortLevel", value) }
        process.send(controlRequest { put("subtype", "get_settings") })
    }

    /**
     * 载入历史以后：思考强度照这个对话主线上最后一次真实回答用的（[recorded]）恢复，只改这个对话，不动新对话的默认值。
     * 不是 Claude Code 认的那几档就照旧。进程已经起来了（定时任务、数字助理不等历史载完就发出消息）不改，它已经按当时的设置在跑；
     * 载入期间用户改不了（思考强度胶囊这时不能点）。
     */
    internal fun restoreEffort(recorded: String?) {
        if (process != null) return
        recorded?.takeIf { value -> EffortLevels.any { it.first == value } }?.let { effort = it }
    }

    /** 一键压缩：和在输入框里发 /compact 一样，只是聊天里不留这条命令；进行中显示在等待指示旁，完成后有一条提示。 */
    fun compact() {
        if (busy || blockedByExternal || loadingHistory || items.isEmpty()) return
        val process = ensureProcess() ?: return
        turnsInFlight++
        busy = true
        interruptRequested = false
        status = "正在压缩上下文"
        lastActivityAt = System.currentTimeMillis()
        process.send(buildJsonObject {
            put("type", "user")
            put("uuid", UUID.randomUUID().toString())
            putJsonObject("message") {
                put("role", "user")
                putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "/compact") }) }
            }
        })
        hub.onActivityChanged()
    }

    /** 问 Claude Code 上下文窗口和实际生效的思考强度（应答由 [UsageTracker] 接住）。 */
    private fun queryUsage(target: ClaudeProcess) {
        target.send(controlRequest { put("subtype", "get_context_usage") })
        target.send(controlRequest { put("subtype", "get_settings") })
        // 订阅额度（应用级，见 QuotaService）：隔几分钟才顺便问一次
        if (hub.quota.claimAutoQuery()) target.send(quotaRequest())
    }

    /** 借这个对话开着的进程问一次订阅额度，应答由 QuotaService 接住；没有进程时返回 false。 */
    internal fun queryQuota(): Boolean {
        val target = process ?: return false
        target.send(quotaRequest())
        return true
    }

    private fun quotaRequest() = controlRequest { put("subtype", "get_usage"); put("skip_behaviors", true) }

    private fun publishUsage() {
        meter.learnedWindow?.let { (name, window) ->
            val known = hub.settings.contextWindows
            if (known[name] != window) hub.settings.contextWindows = known + (name to window)
        }
        usage = meter.snapshot(hub.settings.contextWindows)
    }

    /** 之后启动进程时追加给 Claude Code 的说明（定时任务告诉它这是后台自动发的）。 */
    private var launchHint: String? = null

    /**
     * 定时任务、数字助理给这个对话定的模型和思考强度：只改这个对话，不动全局设置；null 的那项照旧。
     * 权限模式不在这里定：后台运行的模式只管它发的那几条，见 [sendUnattended]。
     * [hint] 追加进系统提示，进程已经起来的话下次重启进程才生效。
     */
    internal fun preset(model: String?, effort: String?, hint: String? = null) {
        launchHint = hint
        model?.let { value ->
            this.model = value
            process?.send(controlRequest { put("subtype", "set_model"); put("model", value) })
        }
        effort?.let(::applyEffort)
    }

    /** 用户在输入栏选的：这个对话之后按它跑（后台运行这一轮剩下的部分也是），也是新对话的默认。 */
    fun choosePermissionMode(value: String) {
        ownMode = null
        hub.settings.permissionMode = value
        switchMode(value)
    }

    /** 回答一次 can_use_tool。[answers] 只用于 AskUserQuestion：问题文字 → 选中的选项。 */
    fun respond(request: PermissionRequest, allow: Boolean, answers: Map<String, String>? = null) {
        if (pending?.requestId == request.requestId) pending = null
        process?.send(buildJsonObject {
            put("type", "control_response")
            putJsonObject("response") {
                put("subtype", "success")
                put("request_id", request.requestId)
                putJsonObject("response") {
                    if (allow) {
                        put("behavior", "allow")
                        put("updatedInput", if (answers == null) request.input else JsonObject(request.input + ("answers" to JsonObject(answers.mapValues { JsonPrimitive(it.value) }))))
                    } else {
                        put("behavior", "deny")
                        put("message", "用户拒绝了这次操作")
                    }
                }
            }
        })
        hub.onActivityChanged()
    }

    /** Claude Code 发给手机工具的一条 MCP 消息：在后台处理（读屏、点按都要时间），再把应答送回去。 */
    private fun handleMcp(requestId: String, message: JsonObject) {
        val target = process ?: return
        thread(name = "zeta-phone", isDaemon = true) {
            val reply = runCatching { phone.handle(message) }.getOrElse { JsonObject(emptyMap()) }
            target.send(buildJsonObject {
                put("type", "control_response")
                putJsonObject("response") {
                    put("subtype", "success")
                    put("request_id", requestId)
                    putJsonObject("response") { put("mcp_response", reply) }
                }
            })
        }
    }

    /** 停掉后台任务 [id]。结果看随后的 task_updated / task_notification。 */
    fun stopTask(id: String) {
        val process = process ?: return
        val request = controlRequest { put("subtype", "stop_task"); put("task_id", id) }
        tasks.stopRequested(request.str("request_id").orEmpty(), id)
        process.send(request)
    }

    /** 读后台命令、监视器 [id] 输出的最后 8 KiB，应答进 [BackgroundTasks.outputs]。进程不在时什么也不做。 */
    fun readTaskOutput(id: String) {
        val process = process ?: return
        val request = controlRequest { put("subtype", "get_task_output"); put("task_id", id) }
        tasks.outputRequested(request.str("request_id").orEmpty(), id)
        process.send(request)
    }

    /** 子代理查看页要看的那个子代理：[toolUseId] 是发起它的 Agent 调用。记录由 [refreshSubagent] 读。 */
    fun subagent(toolUseId: String): SubagentLog = subagentLogs.getOrPut(toolUseId) { SubagentLog(toolUseId) }

    /** 查看页开着（[watching]）的子代理才收输出流里它的事件。 */
    fun watchSubagent(log: SubagentLog, watching: Boolean) {
        log.watchers = (log.watchers + if (watching) 1 else -1).coerceAtLeast(0)
    }

    /**
     * 增量读一次子代理 [log] 的记录（Linux 一侧的助手脚本从上次读到的地方接着读），再更新它的状态。
     * 在主线程上调用，阻塞的部分在 IO 线程。正在读的不重复读。
     */
    suspend fun refreshSubagent(log: SubagentLog) {
        if (log.reading) return
        val config = hub.settings.env
        val sessions = (listOfNotNull(sessionId, fork?.source) + pastSessions).distinct()
        if (config == null || sessions.isEmpty()) {
            publishSubagent(log)
            return
        }
        val transcript = log.transcript
        val arguments = listOf(
            transcript.offset.toString(), transcript.line.toString(), log.toolUseId,
            transcript.meta?.agentId?.takeIf { it.isNotEmpty() } ?: subagentIds[log.toolUseId] ?: "-",
        ) + sessions
        log.reading = true
        val result = try {
            withContext(Dispatchers.IO) { hub.helper("subagent", arguments, timeoutMs = 30_000, config = config) }
        } finally {
            log.reading = false
        }
        if (result == null || result.exitCode != 0) {
            log.failure = result?.stderr?.trim()?.takeLast(300)?.ifEmpty { null } ?: "没有读到子代理的记录"
        } else {
            log.failure = null
            log.transcript.applyRead(result.stdout)
        }
        publishSubagent(log)
    }

    /** 记录上次只读了一段，后面还有：接着读不用等。 */
    fun subagentHasMore(log: SubagentLog): Boolean = log.transcript.more

    /** 子代理 [log] 不用再读了：不在跑，记录也收齐了（读不了时也算）。 */
    fun subagentSettled(log: SubagentLog): Boolean = !log.running && (!log.transcript.loaded || log.transcript.settled || log.failure != null)

    private fun publishSubagent(log: SubagentLog) {
        val meta = log.transcript.meta
        val id = meta?.agentId?.takeIf { it.isNotEmpty() } ?: subagentIds[log.toolUseId]
        val outcome = id?.let(subagentOutcomes::get) ?: when {
            meta?.stopped == true -> TaskState.Stopped
            meta?.finished == true -> TaskState.Completed
            else -> null
        }
        // 子代理在跑的话，这个对话不是在回答（前台的）就是有后台任务（后台的）
        val running = outcome == null && process != null && (busy || backgroundTasks > 0)
        log.publish(running, outcome)
    }

    /** 子代理相关的事件：记下 Agent 调用和子代理 id 的对应、子代理的结局；子代理内部的事件交给开着的查看页。 */
    private fun observeSubagents(event: JsonObject, parent: String?) {
        when (event.str("type")) {
            "system" -> when (event.str("subtype")) {
                "task_started" -> if (event.str("task_type") == "local_agent") {
                    val use = event.str("tool_use_id") ?: return
                    putRecent(subagentIds, use, event.str("task_id") ?: return)
                }
                "task_notification" -> {
                    val id = event.str("task_id") ?: return
                    val state = when (event.str("status")) {
                        "completed" -> TaskState.Completed
                        "failed" -> TaskState.Failed
                        "stopped" -> TaskState.Stopped
                        else -> return
                    }
                    putRecent(subagentOutcomes, id, state)
                    subagentLogs.values.filter { it.watchers > 0 && (it.transcript.meta?.agentId == id || subagentIds[it.toolUseId] == id) }.forEach(::publishSubagent)
                }
            }
            "assistant", "user" -> if (parent != null) {
                val log = subagentLogs[parent]?.takeIf { it.watchers > 0 } ?: return
                if (log.transcript.observeLive(event)) publishSubagent(log)
            } else if (event.str("type") == "user") {
                // Agent 调用的结果里带着子代理 id（前台的做完时、后台的一起来就有）
                val agent = event.obj("tool_use_result")?.str("agentId") ?: return
                val use = event.obj("message")?.arr("content")?.firstNotNullOfOrNull { (it as? JsonObject)?.str("tool_use_id") } ?: return
                putRecent(subagentIds, use, agent)
            }
        }
    }

    private fun <V> putRecent(map: LinkedHashMap<String, V>, key: String, value: V) {
        map.remove(key)
        map[key] = value
        while (map.size > MAX_SUBAGENT_IDS) map.remove(map.keys.first())
    }

    /** 结束背后的进程（对话内容保留，下次发送时用 --resume 接上；后台屏幕也留着，只是睡下）。 */
    fun shutdown() {
        val current = process ?: return
        process = null
        finishRewind("对话已关闭")
        current.stop()
        phone.detach()
        endTurnState()
        hub.onActivityChanged()
    }

    private fun controlRequest(build: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("type", "control_request")
        put("request_id", "zeta-${++requestSeq}")
        putJsonObject("request", build)
    }

    private fun ensureProcess(): ClaudeProcess? {
        process?.let { return it }
        val config = hub.settings.env
        if (config == null) {
            reducer.addNotice("还没有配置 Linux 环境，请到设置里检查", error = true)
            publish()
            return null
        }
        val arguments = launchArguments()
        val autoCompact = hub.settings.autoCompact
        val compactPercent = hub.settings.autoCompactPercent
        lateinit var created: ClaudeProcess
        created = try {
            ClaudeProcess(
                config = config,
                hostScript = hub.hostScript,
                hostKey = UUID.randomUUID().toString(),
                launch = ClaudeProcess.Launch.Start(cwd, title, arguments, AutoCompact.env(autoCompact, compactPercent)),
                onEvent = { event -> enqueue(created, event) },
                onExit = { code, stderr -> main.post { onProcessExit(created, code, stderr) } },
            )
        } catch (error: Exception) {
            reducer.addNotice("启动失败：${error.message}", error = true)
            publish()
            return null
        }
        process = created
        forkCut = false
        created.send(controlRequest { put("subtype", "initialize") })
        meter.processStarted(fresh = sessionId == null, compactPercent = compactPercent.takeIf { autoCompact })
        queryUsage(created)
        hub.onActivityChanged()
        return created
    }

    /** 起 Claude Code 进程的参数。没指定模型（[model] 为 null）时不带 --model：接着已有会话时 Claude Code 照记录用原来的模型。 */
    internal fun launchArguments(): List<String> = buildList {
        addAll(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
            "--include-partial-messages", "--permission-prompt-tool", "stdio"))
        // 接着原会话；分支则从原会话复制出新会话（发出第一条消息时才写出记录）
        addAll(sessionArguments(sessionId, fork?.source, title))
        model?.let { add("--model"); add(it) }
        permissionMode?.let { add("--permission-mode"); add(it) }
        // 只是允许之后切到完全放行（没有它 set_permission_mode 切不过去），不放行任何东西；发给模型的内容不变（UnattendedRulesContractTest）
        add("--allow-dangerously-skip-permissions")
        effort?.let { add("--effort"); add(it) }
        add("--append-system-prompt"); add(launchHint?.let { ClientHint + " " + it } ?: ClientHint)
        // 手机工具：一个不开端口的 MCP 服务器，消息走控制通道，由 handleMcp 应答
        if (hub.settings.phoneTools) { add("--mcp-config"); add(PhoneTools.MCP_CONFIG) }
    }

    private fun enqueue(source: ClaudeProcess, event: JsonObject) {
        if (source !== process) return
        inbox.add(event)
        // 增量事件按约 25 帧每秒合并处理，避免每个字都触发一次重组
        if (drainScheduled.compareAndSet(false, true)) main.postDelayed(::drain, 40)
    }

    private fun drain() {
        drainScheduled.set(false)
        var changed = false
        val now = System.currentTimeMillis()
        val usageBefore = meter.version
        while (true) {
            val event = inbox.poll() ?: break
            meter.observe(event)
            hub.quota.observe(event)
            changed = handle(event, now) || changed
        }
        if (meter.version != usageBefore) publishUsage()
        if (changed) {
            status = null
            lastActivityAt = now
            publish()
        }
    }

    /** 返回聊天列表是否有变化。 */
    private fun handle(event: JsonObject, now: Long): Boolean {
        // 子代理内部的消息不进主列表，只显示发起它的那一步；点开那一步看（子代理查看页）
        val parent = (event["parent_tool_use_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val fromSubagent = parent != null
        tasks.observe(event, now)
        observeSubagents(event, parent)
        when (event.str("type")) {
            "system" -> when (event.str("subtype")) {
                "init" -> {
                    event.str("session_id")?.let { sessionId = it }
                    // 实际在用的模型：没指定时（接着旧会话）是 Claude Code 照会话记录恢复的那个
                    event.str("model")?.takeIf { it.isNotBlank() }?.let { reportedModel = it }
                    // 起进程时没指定模式：报来的是 Claude Code 自己设置里的，记下当新对话的默认（指定了的报来的是指定的那个）
                    event.str("permissionMode")?.takeIf { permissionMode == null }?.let { mode ->
                        permissionMode = mode
                        if (hub.settings.permissionMode == null) hub.defaultPermissionMode = mode
                    }
                    // 每一轮开头都有 init。闲着时来的是 Claude Code 自己起的一轮：后台任务跑完（或后台子代理交回结果）后
                    // 它会接着处理，这一轮没有 Zeta 发的消息，也没有 command_lifecycle，同样以 result 结束
                    if (!busy) {
                        busy = true
                        turnsInFlight++
                        lastActivityAt = now
                        hub.onActivityChanged()
                    }
                }
                "background_tasks_changed" -> {
                    // 每次都是完整的清单（不含前台运行的命令），数一下就是当前还在跑的
                    val count = event.arr("tasks")?.size ?: 0
                    if (count != backgroundTasks) {
                        backgroundTasks = count
                        if (count == 0 && !busy) hub.onBackgroundTasksDone(this)
                        hub.onActivityChanged()
                    }
                }
                "status" -> {
                    event.str("permissionMode")?.let { permissionMode = it }
                    if (event.str("status") == "compacting") {
                        lastActivityAt = now
                        status = "正在压缩上下文"
                    }
                }
                "compact_boundary" -> {
                    reducer.addNotice(compactNotice(event), error = false)
                    // 压缩后的 post_tokens 不含系统提示和工具，问 Claude Code 要含这些的估算
                    process?.let(::queryUsage)
                    return true
                }
                "session_title_changed" -> event.str("title")?.takeIf { it.isNotBlank() }?.let { title = it }
                "api_retry" -> {
                    lastActivityAt = now
                    status = "请求出错，正在重试" + (event.int("attempt")?.let { "（第 $it 次）" } ?: "")
                }
            }
            "stream_event" -> {
                // 新回复开始：手机工具的控件编号从这里起换成上一批结果里的那份（子代理的回复也算）
                event.obj("event")?.takeIf { it.str("type") == "message_start" }?.obj("message")?.str("id")?.let(phone::onReply)
                if (!fromSubagent) {
                    event.obj("event")?.let { reducer.applyStream(it, now) }
                    return true
                }
            }
            "assistant" -> {
                event.obj("message")?.str("id")?.let(phone::onReply)
                if (!fromSubagent) {
                    event.obj("message")?.let(reducer::applyAssistant)
                    return true
                }
            }
            "user" -> if (!fromSubagent) {
                event.obj("message")?.let { reducer.applyUser(it, replay = false, toolUseResult = event.obj("tool_use_result")) }
                return true
            }
            "command_lifecycle" -> {
                lifecycleSeen = true
                val uuid = event.str("command_uuid") ?: return false
                when (event.str("state")) {
                    "started" -> {
                        busy = true
                        val message = queued.firstOrNull { it.uuid == uuid } ?: return false
                        queued = queued - message
                        recalling.remove(uuid)
                        if (queued.isEmpty()) steering = false
                        reducer.addUser(message.text, message.attachments.map { InlineImage("a:${it.id}", it.base64) }, message.files, message.uuid)
                        return true
                    }
                    "cancelled" -> {
                        val message = queued.firstOrNull { it.uuid == uuid } ?: return false
                        queued = queued - message
                        if (recalling.remove(uuid)) addRecalled(message)
                        if (queued.isEmpty()) steering = false
                    }
                }
            }
            "result" -> {
                turnsInFlight = (turnsInFlight - 1).coerceAtLeast(0)
                reducer.finishTurn()
                val failed = event.bool("is_error") == true || event.str("subtype") != "success"
                when {
                    interruptRequested && steering -> reducer.addNotice("已打断，先处理插话", error = false)
                    interruptRequested -> reducer.addNotice("已停止", error = false)
                    failed -> reducer.addNotice(
                        event.str("result")?.takeIf { it.isNotBlank() }
                            ?: event.arr("errors")?.joinToString("\n") { (it as? JsonPrimitive)?.content.orEmpty() }?.takeIf { it.isNotBlank() }
                            ?: "这一轮没有正常结束（${event.str("subtype")}）",
                        error = true,
                    )
                }
                val stopped = interruptRequested
                interruptRequested = false
                // 有 command_lifecycle 时：插话会并进同一轮、只得到一个 result，按条数计会对不上；
                // 还有排队的消息就会接着另起一轮
                val turnOver = if (lifecycleSeen) queued.isEmpty() else turnsInFlight == 0
                if (turnOver) {
                    // 先于 busy 和手机工具的状态清掉：胶囊要据此判断结束的是不是正显示着的那个
                    hub.onTurnEnding(this, if (stopped) "已停止" else if (failed) "出错了" else "已完成")
                    busy = false
                    phone.onTurnEnded()
                    if (!stopped && !failed) requestTitle()
                    hub.onTurnFinished(this, stopped)
                }
                hub.onActivityChanged()
                return true
            }
            "control_request" -> {
                val request = event.obj("request") ?: return false
                val requestId = event.str("request_id") ?: return false
                if (request.str("subtype") == "can_use_tool") {
                    val input = request.obj("input") ?: JsonObject(emptyMap())
                    pending = PermissionRequest(
                        requestId = requestId,
                        toolName = request.str("tool_name").orEmpty(),
                        input = input,
                        questions = if (request.str("tool_name") == "AskUserQuestion") parseQuestions(input.arr("questions")) else emptyList(),
                    )
                    hub.onAttentionNeeded(this)
                } else if (request.str("subtype") == "mcp_message") {
                    request.obj("message")?.let { handleMcp(requestId, it) }
                }
            }
            "control_cancel_request" -> if (pending?.requestId == event.str("request_id")) pending = null
            // /clear：Claude Code 换了一个新会话，之前的内容它已经看不到了
            "conversation_reset" -> {
                sessionId?.let(pastSessions::add)
                event.str("new_conversation_id")?.let { sessionId = it }
                title = ""
                reducer.addNotice("上下文已清空，下面是新的会话", error = false)
                return true
            }
            "control_response" -> {
                if (tasks.onResponse(event.obj("response"))) return false
                if (autoTitle.onResponse(event.obj("response"), title) { title = it; sessionId?.let { id -> hub.titled(id, it) } }) return false
                if (rewind != null && event.obj("response")?.str("request_id") == rewind?.requestId) {
                    finishRewind(rewindFailure(event.obj("response")))
                    return true
                }
                val response = event.obj("response")?.obj("response") ?: return false
                response.arr("models")?.let { list ->
                    hub.models = list.mapNotNull { (it as? JsonObject)?.let { m ->
                        ModelOption(m.str("value") ?: return@let null, m.str("displayName").orEmpty(), m.str("description").orEmpty())
                    } }
                }
                response.arr("commands")?.let { list ->
                    hub.updateCommands(list.mapNotNull { (it as? JsonObject)?.let { c ->
                        SlashCommand(c.str("name") ?: return@let null, c.str("description").orEmpty(), c.str("argumentHint").orEmpty())
                    } })
                }
                if (permissionMode == null) response.str("current_permission_mode")?.let { permissionMode = it }
            }
        }
        return false
    }

    private fun requestTitle() {
        val target = process ?: return
        autoTitle.next(hub.settings.autoTitle, title, reducer.items.toList(), "zeta-title-${++requestSeq}")?.let(target::send)
    }

    private fun parseQuestions(list: JsonArray?): List<Question> = list.orEmpty().mapNotNull { element ->
        val q = element as? JsonObject ?: return@mapNotNull null
        Question(
            question = q.str("question").orEmpty(),
            header = q.str("header").orEmpty(),
            options = q.arr("options").orEmpty().mapNotNull { (it as? JsonObject)?.let { o -> QuestionOption(o.str("label").orEmpty(), o.str("description").orEmpty()) } },
            multiSelect = q.bool("multiSelect") == true,
        )
    }

    private fun endTurnState() {
        turnsInFlight = 0
        // 进程没了，排队的消息也不会再被处理：放回输入框
        for (message in queued) addRecalled(message)
        queued = emptyList()
        recalling.clear()
        steering = false
        busy = false
        // 进程没了，它的后台任务也跟着没了
        backgroundTasks = 0
        tasks.processEnded(System.currentTimeMillis())
        subagentLogs.values.filter { it.watchers > 0 }.forEach(::publishSubagent)
        status = null
        pending = null
        interruptRequested = false
        reducer.finishTurn()
        publish()
    }

    private fun onProcessExit(source: ClaudeProcess, code: Int, stderr: String) {
        if (source !== process) return
        drain()
        process = null
        finishRewind("Claude Code 进程退出了")
        // 连接意外断开时进程可能还活着：一并结束，免得留下没人管的后台进程
        source.stop()
        if (busy) hub.onTurnEnding(this, "已中断")
        phone.detach()
        val wasBusy = busy
        endTurnState()
        if (wasBusy || code != 0) {
            val detail = stderr.trim().takeLast(600)
            reducer.addNotice(
                when {
                    detail.contains("zeta: rootfs not found") -> "找不到 Debian 环境，请到设置里检查 rootfs 路径"
                    detail.contains("zeta: busybox not found") -> "找不到 root 管理器自带的 BusyBox"
                    code == 1 && detail.isEmpty() && sessionId == null -> "Claude Code 没能启动。请确认已在 root 管理器里给 Zeta 授权"
                    code == 143 || code == 137 -> "Claude Code 进程被结束了"
                    else -> "Claude Code 进程已退出（$code）" + if (detail.isNotEmpty()) "\n$detail" else ""
                },
                error = true,
            )
            publish()
        }
        hub.onActivityChanged()
    }

    companion object {
        /** 选择器里 Claude Code 默认模型那一项的值，也是 set_model、--model 认的写法。 */
        const val DEFAULT_MODEL = "default"
        private const val MAX_SUBAGENT_LOGS = 8
        private const val MAX_SUBAGENT_IDS = 128
    }
}
