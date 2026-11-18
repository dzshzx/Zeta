package io.github.dzshzx.zeta.core

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

/**
 * 一个对话：界面状态 + 背后的 Claude Code 进程。进程在第一次发送时才启动；
 * 历史对话用 `--resume` 接着原来的会话记录继续。状态只在主线程上改。
 */
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

    /** 进行中的临时状态（比如网络出错正在重试），显示在等待指示旁边。 */
    var status by mutableStateOf<String?>(null)
        private set

    /** 最近一次收到进程输出（或发出消息）的时间，界面据此显示“已等待多久”。 */
    var lastActivityAt by mutableStateOf(0L)
        private set

    private var reducer = TranscriptReducer()
    private var process: ClaudeProcess? = null
    private var turnsInFlight = 0
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
                    "user" -> event.obj("message")?.let { loaded.applyUser(it, replay = true) }
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

    fun send(text: String, attachments: List<Attachment> = emptyList()) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        if (title.isBlank()) title = trimmed.lineSequence().first().take(40).ifBlank { "图片" }
        val process = ensureProcess() ?: return
        reducer.addUser(trimmed, attachments.map { it.preview })
        turnsInFlight++
        busy = true
        interruptRequested = false
        status = null
        lastActivityAt = System.currentTimeMillis()
        publish()
        process.send(buildJsonObject {
            put("type", "user")
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
                    if (trimmed.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", trimmed) })
                }
            }
        })
        hub.onActivityChanged()
    }

    fun interrupt() {
        val process = process ?: return
        if (!busy) return
        interruptRequested = true
        process.send(controlRequest { put("subtype", "interrupt") })
        // 正在等用户批准的调用一并拒绝，否则进程会一直等
        pending?.let { respond(it, allow = false) }
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
                "status" -> event.str("permissionMode")?.let { permissionMode = it }
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
            "result" -> {
                turnsInFlight = (turnsInFlight - 1).coerceAtLeast(0)
                reducer.finishTurn()
                val failed = event.bool("is_error") == true || event.str("subtype") != "success"
                when {
                    interruptRequested -> reducer.addNotice("已停止", error = false)
                    failed -> reducer.addNotice(
                        event.str("result")?.takeIf { it.isNotBlank() }
                            ?: event.arr("errors")?.joinToString("\n") { (it as? JsonPrimitive)?.content.orEmpty() }?.takeIf { it.isNotBlank() }
                            ?: "这一轮没有正常结束（${event.str("subtype")}）",
                        error = true,
                    )
                }
                interruptRequested = false
                if (turnsInFlight == 0) {
                    busy = false
                    hub.onTurnFinished(this)
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
                }
            }
            "control_cancel_request" -> if (pending?.requestId == event.str("request_id")) pending = null
            "control_response" -> {
                val response = event.obj("response")?.obj("response") ?: return false
                response.arr("models")?.let { list ->
                    hub.models = list.mapNotNull { (it as? JsonObject)?.let { m ->
                        ModelOption(m.str("value") ?: return@let null, m.str("displayName").orEmpty(), m.str("description").orEmpty())
                    } }
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
