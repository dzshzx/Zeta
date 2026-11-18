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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
) {
    /** 界面用的稳定标识；会话还没建立（没有 sessionId）时也要能区分。 */
    val key: String = sessionId ?: "new-" + UUID.randomUUID()

    var sessionId by mutableStateOf(sessionId)
        private set
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
    var loadingHistory by mutableStateOf(historyFile != null)
        private set
    var skippedHistory by mutableStateOf(0)
        private set
    var pending by mutableStateOf<PermissionRequest?>(null)
        private set
    var model by mutableStateOf(hub.settings.model)
        private set
    var permissionMode by mutableStateOf(hub.settings.permissionMode ?: hub.defaultPermissionMode)
        private set

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

    private var reducer = TranscriptReducer()
    private var process: ClaudeProcess? = null
    /** 只在 Claude Code 不报 command_lifecycle 时（老版本）用来判断一轮是否结束。 */
    private var turnsInFlight = 0
    private var lifecycleSeen = false
    /** 撤回时要放回输入框的消息（其余的撤回直接丢弃）。 */
    private val recalling = HashSet<String>()
    private var interruptRequested = false
    private val main = Handler(Looper.getMainLooper())
    private val inbox = ConcurrentLinkedQueue<JsonObject>()
    private val drainScheduled = AtomicBoolean(false)
    private var requestSeq = 0

    val isProcessAlive: Boolean get() = process != null

    init {
        if (attach != null) {
            busy = attach.busy
            turnsInFlight = if (attach.busy) 1 else 0
            attach.permissionMode?.let { permissionMode = it }
            lastActivityAt = System.currentTimeMillis()
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
        host.pending?.let { handle(it, System.currentTimeMillis()) }
        hub.onActivityChanged()
    }

    private fun loadHistory(file: String, attach: HostInfo?) {
        val config = hub.settings.env ?: run { loadingHistory = false; return }
        thread(name = "zeta-history", isDaemon = true) {
            val loaded = TranscriptReducer("h")
            var skipped = 0
            val result = runCatching {
                LinuxEnv.run(config, "exec python3 - history " + LinuxEnv.quote(file), stdin = hub.helperScript, timeoutMs = 60_000)
            }.getOrNull()
            result?.stdout?.lineSequence()?.forEach { line ->
                val event = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@forEach
                when (event.str("type")) {
                    "zeta_history" -> skipped = event.int("skipped") ?: 0
                    "assistant" -> event.obj("message")?.let { loaded.applyAssistant(it, history = true) }
                    "user" -> event.obj("message")?.let { loaded.applyUser(it, replay = true, sessionFile = file) }
                }
            }
            loaded.finishTurn(keepRunningTools = attach?.busy == true)
            main.post {
                // 载入期间用户已经发了新消息的话，新内容接在历史后面
                loaded.items.addAll(reducer.items)
                reducer = loaded
                skippedHistory = skipped
                loadingHistory = false
                if (result == null || result.exitCode != 0) {
                    reducer.addNotice("读取历史记录失败" + result?.stderr?.takeIf { it.isNotBlank() }?.let { "：${it.trim().take(300)}" }.orEmpty(), error = true)
                }
                publish()
                // 历史就位之后再接进程，免得实时事件落在即将被替换掉的列表上
                if (attach != null) startAttached(attach)
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

    /** [files] 是已经复制进 Linux 环境的附件路径。 */
    fun send(text: String, attachments: List<Attachment> = emptyList(), files: List<String> = emptyList()) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty() && files.isEmpty()) return
        if (title.isBlank()) {
            title = trimmed.lineSequence().first().take(40).ifBlank { if (files.isNotEmpty()) files.first().substringAfterLast('/') else "图片" }
        }
        val process = ensureProcess() ?: return
        val uuid = UUID.randomUUID().toString()
        // 回答进行中发的先排队，等 Claude Code 真正取走（command_lifecycle started）才进聊天记录
        if (busy && lifecycleSeen) {
            queued = queued + QueuedMessage(uuid, trimmed, attachments, files)
        } else {
            reducer.addUser(trimmed, attachments.map { InlineImage("a:${it.id}", it.base64) }, files)
            turnsInFlight++
        }
        val outgoing = withAttachedFiles(trimmed, files)
        busy = true
        interruptRequested = false
        status = null
        lastActivityAt = System.currentTimeMillis()
        publish()
        process.send(buildJsonObject {
            put("type", "user")
            put("uuid", uuid)
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
        hub.onActivityChanged()
    }

    /** 停止：排队的消息一并撤回放回输入框（和 Claude Code 终端里按 Esc 一样），再打断当前这一轮。 */
    fun interrupt() {
        val process = process ?: return
        if (!busy) return
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

    fun chooseModel(value: String?) {
        model = value
        hub.settings.model = value
        process?.send(controlRequest { put("subtype", "set_model"); put("model", value ?: "default") })
    }

    fun choosePermissionMode(value: String) {
        permissionMode = value
        hub.settings.permissionMode = value
        process?.send(controlRequest { put("subtype", "set_permission_mode"); put("mode", value) })
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
            val reply = runCatching { hub.phone.handle(message) }.getOrElse { JsonObject(emptyMap()) }
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

    /** 结束背后的进程（对话内容保留，下次发送时用 --resume 接上）。 */
    fun shutdown() {
        val current = process ?: return
        process = null
        current.stop()
        endTurnState()
        hub.onActivityChanged()
    }

    private fun controlRequest(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
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
        val arguments = buildList {
            addAll(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                "--include-partial-messages", "--permission-prompt-tool", "stdio"))
            sessionId?.let { add("--resume"); add(it) }
            model?.let { add("--model"); add(it) }
            permissionMode?.let { add("--permission-mode"); add(it) }
            add("--append-system-prompt"); add(ClientHint)
            // 手机工具：一个不开端口的 MCP 服务器，消息走控制通道，由 handleMcp 应答
            if (hub.settings.phoneTools) { add("--mcp-config"); add(PhoneTools.MCP_CONFIG) }
        }
        lateinit var created: ClaudeProcess
        created = try {
            ClaudeProcess(
                config = config,
                hostScript = hub.hostScript,
                hostKey = UUID.randomUUID().toString(),
                launch = ClaudeProcess.Launch.Start(cwd, title, arguments),
                onEvent = { event -> enqueue(created, event) },
                onExit = { code, stderr -> main.post { onProcessExit(created, code, stderr) } },
            )
        } catch (error: Exception) {
            reducer.addNotice("启动失败：${error.message}", error = true)
            publish()
            return null
        }
        process = created
        created.send(controlRequest { put("subtype", "initialize") })
        hub.onActivityChanged()
        return created
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
        while (true) {
            val event = inbox.poll() ?: break
            changed = handle(event, now) || changed
        }
        if (changed) {
            status = null
            lastActivityAt = now
            publish()
        }
    }

    /** 返回聊天列表是否有变化。 */
    private fun handle(event: JsonObject, now: Long): Boolean {
        // 子代理内部的消息不进主列表，只显示发起它的那一步
        val fromSubagent = event["parent_tool_use_id"]?.let { it !is kotlinx.serialization.json.JsonNull } == true
        when (event.str("type")) {
            "system" -> when (event.str("subtype")) {
                "init" -> {
                    event.str("session_id")?.let { sessionId = it }
                    event.str("permissionMode")?.let { mode ->
                        if (hub.settings.permissionMode == null) hub.defaultPermissionMode = mode
                        if (permissionMode == null) permissionMode = mode
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
                    reducer.addNotice("上下文已压缩", error = false)
                    return true
                }
                "session_title_changed" -> event.str("title")?.takeIf { it.isNotBlank() }?.let { title = it }
                "api_retry" -> {
                    lastActivityAt = now
                    status = "请求出错，正在重试" + (event.int("attempt")?.let { "（第 $it 次）" } ?: "")
                }
            }
            "stream_event" -> if (!fromSubagent) {
                event.obj("event")?.let { reducer.applyStream(it, now) }
                return true
            }
            "assistant" -> if (!fromSubagent) {
                event.obj("message")?.let(reducer::applyAssistant)
                return true
            }
            "user" -> if (!fromSubagent) {
                event.obj("message")?.let { reducer.applyUser(it, replay = false) }
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
                        reducer.addUser(message.text, message.attachments.map { InlineImage("a:${it.id}", it.base64) }, message.files)
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
                    busy = false
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
                event.str("new_conversation_id")?.let { sessionId = it }
                title = ""
                reducer.addNotice("上下文已清空，下面是新的会话", error = false)
                return true
            }
            "control_response" -> {
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
        // 连接意外断开时进程可能还活着：一并结束，免得留下没人管的后台进程
        source.stop()
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

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
