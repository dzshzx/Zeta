package com.dzshzx.zeta.core

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.conv.ConvImage
import com.dzshzx.zeta.core.conv.ConvMessage
import com.dzshzx.zeta.core.conv.ConvOptions
import com.dzshzx.zeta.core.conv.ConvSpec
import com.dzshzx.zeta.core.conv.ConvState
import com.dzshzx.zeta.core.conv.ConvSubagent
import com.dzshzx.zeta.core.conv.ConvTurn
import com.dzshzx.zeta.core.conv.ConvView
import com.dzshzx.zeta.core.conv.CoreConversation
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 撤回放回输入框的一条消息：文字、图片附件、主机上的文件路径。 */
class QueuedMessage(
    val uuid: String,
    val text: String,
    val attachments: List<Attachment>,
    val files: List<String>,
)

/** 推来的变化攒多久交给界面一次。每交一次聊天页就要重组、排版一次；回答的字由逐字显现平滑地带出来，合并得粗一点看不出来。 */
private const val DRAIN_DELAY_MS = 100L

/** 跑对话请求的线程：每个对话的请求排成一队按先后发（[Conversation.enqueue]），各队之间互不等。 */
private val Workers = Executors.newCachedThreadPool { r ->
    Thread(r, "zeta-conv").apply { isDaemon = true }
}

/**
 * 一个对话：主机上核心里那个对话（docs/core.md「对话」）的镜像。核心推来的状态和条目（[ConvView]）切到主线程后写进可观察的状态， 界面照着显示；用户的操作换成 conv.*
 * 请求经 [ConvLinks] 那台主机的连接发出去。归约、排队、确认、用量这些都在核心里，这里不另算。
 *
 * 核心那边的对话在第一次要用时打开（接着已有会话的建好就打开，好载入历史；新对话等发第一条消息或 [prepare]）， [leave]、[shutdown]
 * 放开，之后再要用时按会话号重新打开。状态只在主线程上改。
 */
class Conversation(
    private val hub: ConversationHub,
    cwd: String,
    sessionId: String?,
    title: String,
    /** 会话记录在主机上的路径；可省，核心按会话号自己找。 */
    private val historyFile: String?,
    /** Claude Code 跑在哪台主机上（[Backends]）：手机里的 Debian 或某台远程主机。一个对话从头到尾都在同一台上。 */
    val backend: String = Backends.LOCAL,
    /** 从别的对话分支出来的新对话：核心那边由 conv.fork 打开。 */
    private val fork: Fork? = null,
) {
    /** 分支的来处：从 [source] 的条目 [last] 所在那一轮之后分支。 */
    class Fork(val source: Conversation, val last: String)

    /** 界面用的稳定标识；会话还没建立（没有 sessionId）时也要能区分。 */
    val key: String = sessionId ?: "new-" + UUID.randomUUID()

    private val initialCwd = cwd
    private val initialSession = sessionId

    /** 核心推来的、合并到现在的样子（[mirror] 换掉它）。 */
    var view by mutableStateOf(ConvView())
        private set

    private val state: ConvState
        get() = view.state

    /** 核心里的那个对话；还没打开、已经放开时为 null。只在请求线程上改。 */
    @Volatile private var core: CoreConversation? = null

    /** 打开过一次（之后断线才算离线）。 */
    private var everOpened by mutableStateOf(false)

    /** 放开了核心里的对话（[leave]、[shutdown]），再要用时重新打开。 */
    private var released by mutableStateOf(false)

    /** 正在打开：接着已有会话的建好就打开，打开前算作在载入历史。 */
    private var opening by mutableStateOf(sessionId != null || fork != null)

    /** 上次通知 hub 时的会话号，换了要告诉它（[ConversationHub.sessionChanged]）。 */
    private var knownSession: String? = sessionId

    val cwd: String
        get() = state.cwd.ifEmpty { initialCwd }

    /** 会话号：核心报的；还没打开时是打开用的那个。新对话第一条消息之前没有。 */
    val sessionId: String?
        get() = state.session ?: initialSession

    /** 打开时列表里的标题：核心报标题之前显示它。 */
    private val initialTitle = title

    /** 标题：核心报的（改名后核心推来新的），还没报时是打开时列表里的。 */
    val title: String
        get() = state.title.ifEmpty { initialTitle }

    /**
     * 会话正被别处（比如终端里的 Claude Code）占着：不让发送，等用户把它接过来（[handoff]）或那边自己退出。以核心 conv.state 的 blocked 为准：
     * 打开时正被占着、起进程时才发现被占着都由核心置上，接过来以后由核心解开。
     */
    val blockedByExternal: Boolean
        get() = state.blocked

    /** [blockedByExternal] 时占着会话的那边的样子（核心盯着、推来的），「别处运行」卡片照它说话；还没查到时为 null。 */
    val occupant: Occupant?
        get() = state.occupant

    /** 把会话接过来（「别处运行」卡片的「接管」），经这个对话的 conv.handoff。 */
    val handoff = SessionHandoff { core }

    /** 请求没成的提示（核心没收到，所以不在核心的条目里），接在条目后面。 */
    private var localNotices by mutableStateOf<List<NoticeItem>>(emptyList())

    val items: List<ChatItem>
        get() = if (localNotices.isEmpty()) view.items else view.items + localNotices

    /** 有没有条目。只在空和非空之间翻转时变：界面上只看有没有的地方读它，不随流式回答的每次更新重组。 */
    val hasItems by derivedStateOf { items.isNotEmpty() }

    /** 发出了消息、核心还没答：这期间也算在回答。 */
    private var sending by mutableStateOf(false)

    /** 核心推过 conv.turn started、还没推 finished：这一轮的结束先交给提醒（[ConversationHub.onTurnEnding]）再算闲下来。 */
    private var turnRunning by mutableStateOf(false)

    /** 在回答：核心说的，加上刚发出还没答的、这一轮的结束还没处理完的。 */
    val busy: Boolean
        get() = !released && (state.busy || sending || turnRunning)

    /** 还在跑的后台任务数（后台命令、后台子代理、监视器等）。 */
    val backgroundTasks: Int
        get() = if (released) 0 else state.tasks.count { it.running }

    /** 后台任务的清单和读到的输出，给后台任务面板看。 */
    val tasks = BackgroundTasks { if (released) emptyList() else state.tasks }

    val loadingHistory: Boolean
        get() = state.loadingHistory || (opening && !everOpened && initialSession != null)

    /** 会话记录里还没载入的更早事件数；全载入了（或是新对话）为 null。往上翻时用 [loadEarlier] 一页页接到前面。 */
    val earlier: Int?
        get() = state.earlier.takeIf { it > 0 }

    /** 还没载入的更早记录条数。 */
    val skippedHistory: Int
        get() = state.earlier

    var earlierState by mutableStateOf<EarlierState>(EarlierState.Idle)
        private set

    /** Claude 当前的任务清单（核心归并好的，连同没载入的更早历史）；没有清单时为 null。 */
    val todos: TodoList?
        get() = state.todos

    /** 在等用户回应的请求（工具确认、计划审批、问题、MCP 表单），按到达顺序，界面一次弹第一个（[pending]）。 */
    val prompts: List<UserPrompt>
        get() = if (released) emptyList() else state.prompts

    /** 界面上现在要弹的那个请求。 */
    val pending: UserPrompt?
        get() = prompts.firstOrNull()

    /** 对话在进行：在回答、等你确认，或者还有后台任务在跑。结束进程会打断它，批量删除、切环境、换账号都要等它。 */
    val active: Boolean
        get() = busy || pending != null || backgroundTasks > 0

    /** 还没开始的新对话：没有会话号、没有内容、没在跑。 */
    val notStarted: Boolean
        get() = sessionId == null && items.isEmpty() && !active

    /** 打开、登记这个对话用的标识：会话号，新对话还没有会话号时用 [key]。 */
    val openKey: String
        get() = sessionId ?: key

    /** 这个对话指定的模型（"default" 是 Claude Code 的默认模型）；null 是不指定。界面显示的是 [shownModel]。 */
    val model: String?
        get() = state.model

    /** 模型由 Claude Code 照会话记录定：接着已有的会话，用户也没在这个对话里指定过（核心说的）。 */
    val modelFromSession: Boolean
        get() = state.modelFromSession

    /** Claude Code 报的、这个对话实际在用的模型；还不知道时是 null。 */
    val reportedModel: String?
        get() = state.reportedModel

    /** 顶栏显示的模型：指定了的就是它，没指定时是 Claude Code 报的；都不知道时是 null，即所在主机的默认模型。 */
    val shownModel: String?
        get() = model ?: reportedModel

    /**
     * 顶栏写的模型名，是具体的模型（Opus 5.5），不写「默认」：[shownModel] 没有时是所在主机 Claude Code 设置里的默认模型，别名按清单 [models]
     * 换成它指向的那个。那台还没起过进程、清单没到时，默认模型说不出是哪个，才写「默认」。
     */
    fun shownModelName(models: List<ModelOption>): String =
        modelName(shownModel ?: hub.claudeDefaults(backend)?.model ?: DEFAULT_MODEL, models) ?: "默认"

    /** 用户在这个对话里的权限模式；null 是还没选过、也还没报过。 */
    val permissionMode: String?
        get() = state.mode.user.ifEmpty { null }

    /** 输入栏显示的权限模式：核心算好的（这个对话的，没有时是 Claude Code 设置里的默认）。核心还没推来（新对话还没打开）、 或它读不到默认值时，用这边读到的那台主机的默认。 */
    val shownPermissionMode: String?
        get() = state.mode.shown.ifEmpty { null } ?: hub.defaultPermissionMode(backend)

    /** 进程现在用的权限模式。 */
    val processMode: String?
        get() = state.mode.process.ifEmpty { null }

    /** 思考强度；null 表示不指定，沿用 Claude Code 自己的设置。 */
    val effort: String?
        get() = state.effort

    /** 上下文占用与用量统计（核心算好的）。 */
    val usage: ChatUsage
        get() = state.usage

    /** 排队中的消息。 */
    val queued: List<ConvMessage>
        get() = state.queued

    /** 用户点了“立即插话”，还没有结果。 */
    val steering: Boolean
        get() = state.steering

    /** 输入栏提示的斜杠命令和技能：核心给的，还没起过进程时是核心按工作目录记着的上一份。 */
    val commands: List<SlashCommand>
        get() = state.commands

    /** 请求没成、要放回输入框的消息（核心没收到它）。 */
    private var localRecalled by mutableStateOf<QueuedMessage?>(null)

    /** 有撤回的消息等输入框取走（[takeRecalled]）；界面拿它当重取的键，没有时为 null。 */
    val recalled: Any?
        get() = localRecalled ?: state.recalled

    /** 和主机的连接断了、正在重连。这时那边的进程照样在跑，这边只是等着：不为它持有唤醒锁（[ConversationHub.onActivityChanged]）。 */
    val offline: Boolean
        get() = everOpened && !released && !view.connected

    /** 进行中的临时状态（比如网络出错正在重试、连接断了），显示在等待指示旁边。 */
    val status: String?
        get() = state.status.ifEmpty { null } ?: offlineText()

    private fun offlineText(): String? {
        if (!offline) return null
        val reason = view.problem ?: hub.convLinks.problem(backend)
        return "和「${hub.backendName(backend)}」的连接断了，正在重连" +
            reason?.takeIf { it.isNotEmpty() }?.let { "：$it" }.orEmpty()
    }

    /** 最近一次有动静的时刻（核心记的，Unix 毫秒），界面据此显示“已等待多久”。还没有过、或刚发出消息核心还没答（这时核心那份还是上一次的）时为 null。 */
    val lastActivityAt: Long?
        get() = state.lastActivity.takeIf { it > 0 && !sending }

    /** 正在等 Claude Code 剪会话或还原代码（核心说的，谁发起的都算）：这期间输入栏不收字。 */
    val rewinding: Boolean
        get() = !released && state.rewinding

    /** 接着的托管进程的标识（核心报的），没接着进程时为 null。 */
    val hostKey: String?
        get() = if (released) null else state.hostKey

    /** 现在能不能编辑、重新生成、删除一轮或分支（核心说的）。 */
    val canRewind: Boolean
        get() = !released && state.canRewind

    /** 这个会话的手机工具（各有各的后台屏幕）；没有时是 null。向 hub 要：重新打开的会话接着用原来那份， 进程结束过屏幕也还在。 */
    val phoneSession: PhoneSession? = hub.platform.phoneFor(openKey, backend)

    /** 平台给这个对话附的东西（手机上是顶部状态胶囊看到的这个对话），见 [HubPlatform.attachConversation]。 */
    val platformState: Any? = hub.platform.attachConversation(this)

    /** 接着 Claude Code 进程。 */
    val isProcessAlive: Boolean
        get() = !released && state.process

    /**
     * 正跑着这个对话的后台运行（定时任务、通知触发）：[sendUnattended] 挂上，ScheduleRunner 跑完摘掉；平常为 null。 挂着时完成和确认提醒由
     * ScheduleRunner 发（带任务名），TurnAlerts 只看这个标记。手机工具上同步挂一份（[PhoneSession.unattended]）。在主线程上读写。
     */
    var unattended: Unattended? = null
        set(value) {
            field = value
            phoneSession?.unattended = value
        }

    /** 看着的子代理（发起它的 Agent 调用 → 核心推来的样子）。 */
    private var subagents by mutableStateOf<Map<String, ConvSubagent>>(emptyMap())

    /** 排着的请求（[enqueue]）。放在 init 之前：init 里就要排。 */
    private val queue = ArrayDeque<() -> Unit>()
    private var draining = false

    private val main = MainHandler()
    private val drainScheduled = AtomicBoolean(false)

    /** 分支：原对话那边 fork 出来的核心对话（建好就开始 fork，原对话随后被放开也不碍事）。 */
    private val forked: CompletableFuture<CoreConversation>? = fork?.let {
        it.source.forkFor(this, it.last)
    }

    // ---- 打开、放开

    /** 这个对话现在打开着的核心对话；没打开就打开。在请求线程上调。打不开时抛出。 */
    private fun open(): CoreConversation {
        core?.let {
            return it
        }
        main.post { opening = true }
        val opened =
            try {
                forkResult() ?: hub.convLinks.open(this, spec())
            } catch (e: Exception) {
                main.post { opening = false }
                throw e
            }
        core = opened
        main.post {
            opening = false
            released = false
            everOpened = true
            mirror(opened.view)
        }
        return opened
    }

    /** 分支只 fork 一次：之后放开再打开就按它自己的会话号（或核心给的 key）。 */
    @Volatile private var everForked = false

    /** 分支第一次打开：等原对话那边 fork 出来的（[forked]）；没建成时作为空的新对话打开，提示原因。 */
    private fun forkResult(): CoreConversation? {
        val pending = forked?.takeIf { !everForked } ?: return null
        everForked = true
        return try {
            pending.get()
        } catch (e: ExecutionException) {
            val reason = e.cause?.message ?: "原对话没有开着"
            main.post { notice("分支没有建成：$reason") }
            null
        }
    }

    /** 在这个对话的请求队列里为分支 [child] fork（conv.fork）：排在这边之后的放开前面，fork 时原对话一定开着。 */
    private fun forkFor(child: Conversation, last: String): CompletableFuture<CoreConversation> {
        val result = CompletableFuture<CoreConversation>()
        enqueue {
            try {
                result.complete(hub.convLinks.fork(child, open(), last, child.options()))
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    private fun spec(): ConvSpec {
        val session = state.session ?: initialSession
        return ConvSpec(
            session = session,
            file = historyFile?.takeIf { session == initialSession },
            cwd = cwd,
            title = title,
            options = options(),
        )
    }

    /** 起进程用的设置：自动压缩和自动标题照这台主机的设置，手机工具由这个客户端处理。 */
    private fun options(): ConvOptions {
        val claude = hub.settings.claude(backend)
        val phone = phoneSession
        return ConvOptions(
            env = AutoCompact.env(claude.autoCompact, claude.autoCompactPercent),
            systemPrompt = hub.platform.clientHint,
            mcpConfig = phone?.mcpConfig?.takeIf { hub.settings.phoneTools },
            phone = phone != null,
            autoTitle = claude.autoTitle,
            compactPercent = claude.autoCompactPercent?.takeIf { claude.autoCompact } ?: 0,
            desk = backend != Backends.LOCAL,
            imageRefs = true,
        )
    }

    /** 设置在打开以后改过（自动压缩、手机工具开关）：发消息前告诉核心，下次起进程时生效。 */
    private var sentOptions: ConvOptions? = null

    private fun syncOptions(target: CoreConversation) {
        val now = options()
        if (sentOptions == null) sentOptions = now
        if (now == sentOptions) return
        target.setOptions(now)
        sentOptions = now
    }

    /**
     * 提前起好进程、不发消息（conv.prepare，用户开始输入斜杠命令、设置页等模型清单时）：命令和模型清单随 Claude Code 的初始化应答推来。
     * 已有进程、历史还在载入时核心什么也不做；没起成的原因核心写在列表里，这边不另提示。
     */
    fun prepare() {
        if (blockedByExternal) return
        call(failed = {}) {
            syncOptions(it)
            it.prepare()
        }
    }

    /** 结束背后的进程，等价于终端里的 /exit（conv.end）：用户结束对话、删会话、换账号、删主机这些要真结束的时候。对话内容保留，下次发送时接上；后台屏幕也留着，只是睡下。 */
    fun shutdown() = release(end = true)

    /** 同 [shutdown]，结束进程的请求（conv.end）答了以后在请求线程上接着做 [after]（可以阻塞）。在主线程上调用。 */
    fun shutdown(after: () -> Unit) {
        release(end = true)
        enqueue(after)
    }

    /** 这边不再拿着核心里的对话（conv.close）：闲着收掉、切走、换连法、换环境时。核心那边没人拿着、闲下来后断开进程。 */
    fun leave() = release(end = false)

    private fun release(end: Boolean) {
        if (released || (!everOpened && !opening && core == null)) return
        released = true
        subagents = emptyMap()
        phoneSession?.detach()
        enqueue {
            val target = core ?: return@enqueue
            core = null
            hub.convLinks.close(target, end)
        }
        hub.onActivityChanged()
    }

    // ---- 请求

    /** 排一个请求：同一个对话的按先后发，打开总在前面。 */
    private fun enqueue(task: () -> Unit) {
        synchronized(queue) {
            queue.addLast(task)
            if (draining) return
            draining = true
        }
        Workers.execute(::drainQueue)
    }

    private fun drainQueue() {
        while (true) {
            val task =
                synchronized(queue) {
                    queue.removeFirstOrNull().also { if (it == null) draining = false }
                } ?: return
            runCatching(task)
        }
    }

    /**
     * 打开着时发请求 [request]：成了在主线程上把核心那份照到界面上再做 [done]；没成（连不上、核心拒绝）做 [failed]， 不给 [failed] 时在列表末尾提示原因。
     */
    private fun call(
        failed: ((Exception) -> Unit)? = null,
        done: () -> Unit = {},
        request: (CoreConversation) -> Unit,
    ) {
        enqueue {
            try {
                val target = open()
                request(target)
                main.post {
                    if (target === core) mirror(target.view)
                    done()
                }
            } catch (e: Exception) {
                main.post { failed?.invoke(e) ?: notice(e.message ?: "没有做成") }
            }
        }
    }

    /** 在列表末尾说一句（请求没成、核心收不到的事）。 */
    private fun notice(text: String) {
        localNotices = localNotices + NoticeItem("local-" + UUID.randomUUID(), text, error = true)
    }

    // ---- 发消息

    /** 用户自己发的消息。[files] 是已经复制进对话所在主机的附件路径。 */
    fun send(
        text: String,
        attachments: List<Attachment> = emptyList(),
        files: List<String> = emptyList(),
    ) = submit(text, attachments, files, mode = null, rules = null)

    /** 后台运行 [run] 发指令：对话挂上它（[unattended]），这一条按 [mode] 和叠加的 [rules] 跑（核心管模式、规则什么时候换回来）。 */
    fun sendUnattended(text: String, mode: String, rules: JsonObject, run: Unattended) {
        unattended = run
        submit(text, emptyList(), emptyList(), mode, rules)
    }

    private fun submit(
        text: String,
        attachments: List<Attachment>,
        files: List<String>,
        mode: String?,
        rules: JsonObject?,
    ) {
        if (text.isBlank() && attachments.isEmpty() && files.isEmpty()) return
        localNotices = emptyList()
        sending = true
        hub.onActivityChanged()
        val images = attachments.map { ConvImage(it.mediaType, it.base64) }
        call(
            failed = { e ->
                sending = false
                putBack(QueuedMessage(UUID.randomUUID().toString(), text, attachments, files))
                notice("没有发出：${e.message}")
                hub.onActivityChanged()
            },
            done = { sending = false },
        ) {
            syncOptions(it)
            it.send(text, images, files, mode, rules)
        }
    }

    /** 放回输入框（连续几条时按顺序拼起来）。 */
    private fun putBack(message: QueuedMessage) {
        val before = localRecalled
        localRecalled =
            if (before == null) message
            else
                QueuedMessage(
                    before.uuid,
                    listOf(before.text, message.text).filter { it.isNotBlank() }.joinToString("\n"),
                    before.attachments + message.attachments,
                    before.files + message.files,
                )
    }

    /** 取走撤回放回输入框的那条（核心的经 conv.take_recalled 取，图片解出预览）；没有时是 null。 */
    suspend fun takeRecalled(): QueuedMessage? {
        localRecalled?.let {
            localRecalled = null
            return it
        }
        if (state.recalled == null) return null
        val target = core ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { target.takeRecalled() }.getOrNull()?.let(::queuedOf)
        }
    }

    private fun queuedOf(message: ConvMessage): QueuedMessage =
        QueuedMessage(
            message.uuid,
            message.text,
            message.images.mapNotNull { image ->
                runCatching { Base64.getDecoder().decode(image.data) }
                    .getOrNull()
                    ?.let { Attachments.restore(UUID.randomUUID().toString(), image.mediaType, it) }
            },
            message.files,
        )

    /** 停止：排队的消息一并撤回放回输入框，打断当前这一轮，等着的确认和手机操作一并作废（都由核心做）。 */
    fun interrupt() {
        if (core != null) call { it.interrupt() }
    }

    /** 立即插话，同终端里排队提示上的 send now。 */
    fun steerNow() {
        if (busy && queued.isNotEmpty() && !steering) call { it.steer() }
    }

    /** 撤回一条排队消息；[recall] 时放回输入框，否则直接丢掉。 */
    fun cancelQueued(uuid: String, recall: Boolean) = call { it.cancel(uuid, recall) }

    /** 一键压缩：和在输入框里发 /compact 一样，只是聊天里不留这条命令。 */
    fun compact() = call { it.compact() }

    // ---- 编辑、重新生成、删除、还原代码

    /** 编辑：丢掉 [target] 这条用户消息和之后的内容，从那里用新消息接着跑。没剪成时核心把新消息放回输入框。 */
    fun rewrite(
        target: UserItem,
        text: String,
        attachments: List<Attachment> = emptyList(),
        files: List<String> = emptyList(),
    ) {
        if (text.isBlank() && attachments.isEmpty() && files.isEmpty()) return
        val images = attachments.map { ConvImage(it.mediaType, it.base64) }
        call { it.rewrite(target.id, text, images, files) }
    }

    /** 删除 [target] 开始的这一轮和之后的内容（只剪不发）。 */
    fun dropFrom(target: UserItem) = call { it.drop(target.id) }

    /** 还原 [target] 之前的代码会动到哪些文件（只看不改），结果交给 [onResult]（主线程）；没有可还原的、现在不能还原时给 null。 */
    fun previewCodeRewind(target: UserItem, onResult: (CodeRewind.Changes?) -> Unit) {
        var changes: CodeRewind.Changes? = null
        call(failed = { onResult(null) }, done = { onResult(changes) }) {
            changes = it.codeChanges(target.id)
        }
    }

    /** 把文件改回 [target] 这条消息之前的样子（TUI /rewind 的 “Restore code”）；[drop] 时还原成了接着删这一轮。结果以列表里的提示告知。 */
    fun restoreCode(target: UserItem, drop: Boolean = false) = call {
        it.restoreCode(target.id, drop)
    }

    /**
     * 重新生成：照 [target] 原样重发（resend：文字、图片、附件路径都由核心从那条消息取，图从会话记录里取回），丢掉它之后的内容。 [withCode]
     * 时先把文件改回这条消息之前，还原成了才剪、才发；没还原成时不剪。结果以列表里的提示告知。
     */
    fun regenerate(target: UserItem, withCode: Boolean = false) = call {
        if (withCode) it.restoreCode(target.id, resend = true) else it.resend(target.id)
    }

    // ---- 模型、思考强度、权限模式

    /** 用户在这个对话里选了模型（[value] 是选择器里的值，null 或 "default" 是默认模型），只管这个对话。 */
    fun chooseModel(value: String?) = call { it.setModel(value) }

    /** 思考强度：只管这个对话，null 回到 Claude Code 自己的设置。 */
    fun chooseEffort(value: String?) = call { it.setEffort(value) }

    /** 用户在输入栏选的权限模式：只管这个对话，之后按它跑。 */
    fun choosePermissionMode(value: String) = call { it.setMode(value) }

    /** 定时任务、数字助理给这个对话定的模型和思考强度：只改这个对话；null 的那项照旧。[hint] 追加进系统提示，下次起进程时生效。 */
    fun preset(model: String?, effort: String?, hint: String? = null) = call {
        it.preset(model, effort, hint)
    }

    // ---- 回应确认、提问、MCP 表单

    /** 回应一次工具确认、计划审批或提问。 */
    fun reply(request: PermissionRequest, reply: PermissionReply) = call {
        it.answer(request.requestId, reply)
    }

    /**
     * 回应一次 MCP 服务器的表单或网址：[action] 是 accept、decline 或 cancel，[values] 是各项输入框里的字。 核心说填得不对的各项交给
     * [onErrors]（主线程），表单留着改了再交。
     */
    fun answerElicitation(
        request: ElicitationRequest,
        action: String,
        values: Map<String, List<String>>? = null,
        onErrors: (Map<String, String>) -> Unit = {},
    ) {
        var errors = emptyMap<String, String>()
        call(done = { if (errors.isNotEmpty()) onErrors(errors) }) {
            errors = it.elicit(request.requestId, action, values)
        }
    }

    // ---- 后台任务、更早的历史、额度、子代理

    /** 停掉后台任务 [id]。结果看随后的状态。 */
    fun stopTask(id: String) {
        if (isProcessAlive) call { it.stopTask(id) }
    }

    /**
     * 任务详情开着（[watching]）时核心推后台命令、监视器 [id] 输出的最后 8 KiB（变了才推），收到的进 [BackgroundTasks.outputs]；关了就停。
     * 进程不在时不看（界面说读不到了）；开始看没成时把原因当作读到的输出显示。不为它打开已经放开的对话。
     */
    fun watchTaskOutput(id: String, watching: Boolean) {
        val target = (if (watching) core?.takeIf { isProcessAlive } else core) ?: return
        enqueue {
            try {
                target.watchTaskOutput(id, watching)
            } catch (e: Exception) {
                if (watching)
                    main.post {
                        tasks.outputRead(
                            id,
                            TaskOutput("", 0, false, error = e.message ?: "没有读到输出"),
                        )
                    }
            }
        }
    }

    /** 载入更早的一页，接到列表前面（往上翻到顶附近时）；[rest] 时一次载完剩下的全部（对话内查找时）。已经在读、或没有更早的了就不动。 */
    fun loadEarlier(rest: Boolean = false) {
        if (earlier == null || earlierState == EarlierState.Loading || loadingHistory) return
        val target = core ?: return
        earlierState = EarlierState.Loading
        thread(name = "zeta-history-page", isDaemon = true) {
            val failure = runCatching { target.loadEarlier(rest) }.exceptionOrNull()
            main.post {
                earlierState =
                    when (failure) {
                        null -> EarlierState.Idle
                        else ->
                            EarlierState.Failed(
                                failure.message ?: "没有读到更早的记录",
                                retry = (failure as? ServeError)?.code != HISTORY_CHANGED,
                            )
                    }
            }
        }
    }

    /** 借这个对话开着的进程问一次所在主机的订阅额度，应答交给那台的 QuotaService；没有进程时返回 false。 */
    fun queryQuota(): Boolean {
        val target = core?.takeIf { isProcessAlive } ?: return false
        thread(name = "zeta-quota", isDaemon = true) {
            val response = runCatching { target.quota() }.getOrNull() ?: return@thread
            main.post {
                hub.quota(backend).usageAnswered(JsonObject(mapOf("response" to response)))
            }
        }
        return true
    }

    /** 子代理查看页要看的那个子代理（[toolUseId] 是发起它的 Agent 调用）此刻的样子；还没推来时为 null。可观察。 */
    fun subagent(toolUseId: String): ConvSubagent? = subagents[toolUseId]

    /** 查看页开着（[watching]）时核心推这个子代理的记录，关了就停。 */
    fun watchSubagent(toolUseId: String, watching: Boolean) {
        if (!watching) subagents = subagents - toolUseId
        call(failed = {}) { it.watchSubagent(toolUseId, watching) }
    }

    // ---- 核心推来的（在连接的读线程上调，见 ConvLinks）

    /** 核心那边的样子变了：攒一会儿再在主线程上照到界面上。 */
    internal fun coreChanged(source: CoreConversation) {
        if (!drainScheduled.compareAndSet(false, true)) return
        main.postDelayed(
            {
                drainScheduled.set(false)
                if (source === core) mirror(source.view)
            },
            DRAIN_DELAY_MS,
        )
    }

    /** 一轮开始或结束了：先把核心那份照上，再交给 hub 提醒。 */
    internal fun coreTurn(source: CoreConversation, turn: ConvTurn) {
        main.post {
            if (source !== core) return@post
            mirror(source.view)
            if (turn.finished) turnFinished(turn) else turnStarted()
        }
    }

    /** 手机工具的事：conv.mcp 在工具线程上处理完用 conv.mcp_reply 答，conv.reply、conv.phone_cancel 交给手机工具。 */
    internal fun corePhone(source: CoreConversation, message: JsonObject) {
        val phone = phoneSession ?: return
        when (message.str("type")) {
            "conv.mcp" -> {
                val id = message.str("request_id") ?: return
                val body = message.obj("message") ?: return
                thread(name = "zeta-phone", isDaemon = true) {
                    val reply: JsonElement =
                        runCatching { phone.handle(body) }.getOrNull() ?: JsonObject(emptyMap())
                    runCatching { source.mcpReply(id, reply) }
                }
            }
            "conv.reply" -> message.str("message_id")?.let { id -> main.post { phone.onReply(id) } }
            "conv.phone_cancel" -> main.post { phone.cancelWaiting() }
        }
    }

    /** 看着的子代理有了变化。 */
    internal fun coreSubagent(subagent: ConvSubagent) {
        main.post { subagents = subagents + (subagent.toolUseId to subagent) }
    }

    /** 看着的后台任务输出有了变化。 */
    internal fun coreTaskOutput(task: String, output: TaskOutput) {
        main.post { tasks.outputRead(task, output) }
    }

    private fun turnStarted() {
        turnRunning = true
        hub.onActivityChanged()
    }

    private fun turnFinished(turn: ConvTurn) {
        val text = turn.text.ifEmpty { "已完成" }
        // 先于这一轮算闲下来：胶囊要据此判断结束的是不是正显示着的那个
        hub.onTurnEnding(this, text)
        turnRunning = false
        sending = false
        phoneSession?.onTurnEnded()
        hub.onTurnFinished(this, stopped = turn.outcome == ConvTurn.Outcome.Stopped, text = text)
        hub.onActivityChanged()
    }

    /**
     * 把核心那份 [next] 照到界面上（主线程）。核心说的变化里有几样要告诉别处：会话号换了（按会话登记的跟着换）、等着的请求换了（提醒）、
     * 忙闲变了（前台服务、胶囊）、模型清单（按主机记下，下次还没起进程时先用）。测试和截图也经它摆出对话的样子。
     */
    fun mirror(next: ConvView) {
        val before = view
        if (next === before) return
        view = next
        if (next.connected && !before.connected && !next.state.busy) turnRunning = false
        followSession()
        followTitle(before.state, next.state)
        rememberModels(next.state)
        noticeChanges(before.state, next.state, before.connected != next.connected)
    }

    private fun followSession() {
        val now = sessionId
        val was = knownSession
        if (now == was) return
        knownSession = now
        hub.sessionChanged(this, was ?: key, now ?: key)
    }

    private fun followTitle(before: ConvState, next: ConvState) {
        if (next.title == before.title || next.title.isEmpty()) return
        sessionId?.let { hub.sessionList.titled(it, next.title) }
    }

    /** 按主机记：各台主机上的 Claude Code 版本不一样，可选的模型也不一样。 */
    private fun rememberModels(next: ConvState) {
        if (next.models.isNotEmpty() && hub.models[backend] != next.models)
            hub.models += backend to next.models
    }

    private fun noticeChanges(before: ConvState, next: ConvState, linkChanged: Boolean) {
        if (before.prompts.firstOrNull()?.requestId != next.prompts.firstOrNull()?.requestId)
            hub.onPendingChanged(this)
        if (before.process && !next.process) phoneSession?.detach()
        if (before.occupant != next.occupant) handoff.occupantChanged()
        val runningBefore = before.tasks.count { it.running }
        val runningNow = next.tasks.count { it.running }
        if (runningBefore > 0 && runningNow == 0 && !busy) hub.onBackgroundTasksDone(this)
        if (
            linkChanged ||
                before.busy != next.busy ||
                before.process != next.process ||
                runningBefore != runningNow
        )
            hub.onActivityChanged()
    }

    // 放在全部属性之后：打开在别的线程上跑，用到的属性这时都要已经初始化好
    init {
        if (opening) enqueue { open() }
    }

    companion object {
        /** 选择器里 Claude Code 默认模型那一项的值，也是 set_model、--model 认的写法。 */
        const val DEFAULT_MODEL = "default"

        /** conv.earlier 答出错、重试也没用（会话记录在别处改过）时的 code。 */
        private const val HISTORY_CHANGED = "history_changed"
    }
}

/** 往上翻载入更早记录的状态。 */
sealed interface EarlierState {
    data object Idle : EarlierState

    data object Loading : EarlierState

    /** 没读到。[retry] 为 false 时重试也没用（会话记录在别处改过，要重新打开对话）。 */
    data class Failed(val message: String, val retry: Boolean) : EarlierState
}
