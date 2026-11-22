package com.dzshzx.zeta.core.conv

import androidx.compose.runtime.Immutable
import com.dzshzx.zeta.core.PermissionReply
import com.dzshzx.zeta.core.ServeRequests
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.str
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * 对话协议（conv.*，见 docs/core.md「对话」）的客户端层：把核心推来的 JSON 换成 Kotlin 数据，把用户的操作换成请求，管连接断了以后重新打开。
 * 对话的逻辑（归约、排队、判断能不能做）都在核心里，这里不另存规则，只照推送合并。
 */

/** 收推送的回调。推送来的在 serve 的读线程上调，打开、重新打开的在发请求的线程上调；调用方自己切到主线程，回调里不能等请求的应答（应答要靠读线程读）。 */
interface ConvListener {
    /** [conv] 的 [CoreConversation.view] 变了（状态、条目、连没连上）。 */
    fun changed(conv: CoreConversation)

    /** conv.turn：一轮开始或结束了。 */
    fun turn(conv: CoreConversation, turn: ConvTurn) {}

    /** conv.mcp、conv.reply、conv.phone_cancel 原样交来；只有 options.phone 为真的客户端才收得到。 */
    fun phone(conv: CoreConversation, message: JsonObject) {}

    /** 用 [CoreConversation.watchSubagent] 看着的子代理有了变化。 */
    fun subagent(conv: CoreConversation, subagent: ConvSubagent) {}

    /** conv.activity：这个核心里各会话的忙闲，每次整份（[ConvClient.watchActivity] 订阅以后才有）。 */
    fun activity(sessions: Map<String, SessionActivity>) {}
}

/** [ConvClient.hostCpu] 最多等多久：负载标识每 5 秒问一次，一次问不到就等下一次。 */
const val HOST_CPU_TIMEOUT_MS = 10_000L

/** system.cpu 的结果：主机整机的 CPU 忙碌百分比（读不了时为 null）和逻辑核数。 */
@Immutable data class HostCpu(val percent: Int?, val cores: Int)

/** 打开对话时给核心的（conv.open 的参数）。 */
@Immutable
data class ConvSpec(
    /** 接着的会话号，新对话为 null。 */
    val session: String? = null,
    /** 它的会话记录在主机上的路径，载入历史用。 */
    val file: String? = null,
    val cwd: String = "",
    val title: String = "",
    val options: ConvOptions = ConvOptions(),
)

/** conv.open、conv.options、conv.fork 的 options（helper/conv/conv.go 的 Options）。都可省，零值不写出。 */
@Immutable
data class ConvOptions(
    /** 只给这个 Claude Code 进程的环境变量（比如自动压缩）。 */
    val env: Map<String, String> = emptyMap(),
    /** --append-system-prompt。 */
    val systemPrompt: String? = null,
    /** --mcp-config（手机工具）。 */
    val mcpConfig: String? = null,
    /** 这个客户端处理手机工具的调用：为真才收得到 conv.mcp、conv.reply、conv.phone_cancel。 */
    val phone: Boolean = false,
    /** 一轮后请 Claude Code 起标题。 */
    val autoTitle: Boolean = false,
    /** 自动压缩的百分比（用量条用），0 是没设。 */
    val compactPercent: Int = 0,
    /** 这台主机是远程主机，不是客户端所在的设备（核心给会话开 tmux 窗口）；手机本机的 Debian 不设。 */
    val desk: Boolean = false,
) {
    fun json(): JsonObject = buildJsonObject {
        if (env.isNotEmpty()) put("env", JsonObject(env.mapValues { JsonPrimitive(it.value) }))
        systemPrompt?.let { put("system_prompt", it) }
        mcpConfig?.let { put("mcp_config", it) }
        if (phone) put("phone", true)
        if (autoTitle) put("auto_title", true)
        if (compactPercent != 0) put("compact_percent", compactPercent)
        if (desk) put("desk", true)
    }
}

/** conv.answer 的 reply（helper/prompt/reply.go 的 Reply）。 */
internal fun replyJson(reply: PermissionReply): JsonObject = buildJsonObject {
    when (reply) {
        PermissionReply.Allow -> put("kind", "allow")
        PermissionReply.AllowAlways -> put("kind", "allow_always")
        is PermissionReply.Deny -> {
            put("kind", "deny")
            reply.feedback?.let { put("feedback", it) }
        }
        is PermissionReply.ApprovePlan -> {
            put("kind", "approve_plan")
            put("mode", reply.mode)
        }
        is PermissionReply.Answer -> {
            put("kind", "answer")
            putAnswers(reply.answers, reply.notes)
        }
        is PermissionReply.Clarify -> {
            put("kind", "clarify")
            putAnswers(reply.answers, reply.notes)
        }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putAnswers(
    answers: Map<String, String>,
    notes: Map<String, String>,
) {
    if (answers.isNotEmpty())
        put("answers", JsonObject(answers.mapValues { JsonPrimitive(it.value) }))
    if (notes.isNotEmpty()) put("notes", JsonObject(notes.mapValues { JsonPrimitive(it.value) }))
}

/**
 * 一条 serve 连接上打开的各个对话。连接（[link]，通常是 [com.dzshzx.zeta.core.HelperServe]）由调用方管：推来的每行交给 [handle]， 断了调
 * [disconnected]，连上（第一次和重连）调 [connected]，打开过的对话按原名和最新的会话号重新打开。 方法可以在任何线程调，发请求的（[open]
 * 和各对话的操作）会阻塞，别在主线程、也别在 [listener] 里调。
 */
class ConvClient(private val link: ServeRequests, internal val listener: ConvListener) {
    /** 这个客户端和它的各对话共用的锁。 */
    internal val lock = Any()
    private var seq = 0
    private val convs = LinkedHashMap<String, CoreConversation>()

    /** 连接断过几次：请求发出去以后它变了，就说不准请求落在了哪条连接上。 */
    internal var epoch = 0L
        private set

    /** 连接现在在（[connected] 以后、[disconnected] 以前）。 */
    internal var linkUp = false
        private set

    private var watchingActivity = false

    /** 现在打开着的对话，按打开的先后。 */
    val conversations: List<CoreConversation>
        get() = synchronized(lock) { convs.values.toList() }

    /**
     * 打开一个对话：先登记名字再发 conv.open（核心可能在应答之前就推来整份状态）。核心里已有这个会话时拿到的就是它。 打不开时抛出
     * [com.dzshzx.zeta.core.ServeError]，对话不留。
     */
    fun open(spec: ConvSpec): CoreConversation = register(spec).also { it.openFirst() }

    /** 起名字、登记一个对话，还没发请求。 */
    internal fun register(spec: ConvSpec): CoreConversation =
        synchronized(lock) {
            seq++
            CoreConversation(this, "k$seq", spec).also { convs[it.name] = it }
        }

    internal fun forget(conv: CoreConversation) {
        synchronized(lock) { if (convs[conv.name] === conv) convs.remove(conv.name) }
    }

    internal fun call(method: String, params: JsonObject): JsonElement? = link.call(method, params)

    internal fun call(method: String, params: JsonObject, timeoutMs: Long): JsonElement? =
        link.call(method, params, timeoutMs)

    /**
     * 问一次这台主机整机的 CPU 使用率（system.cpu，和对话无关）。还在连时等连上，一共最多等 [HOST_CPU_TIMEOUT_MS]； 连不上、核心不认得这个方法时抛
     * [com.dzshzx.zeta.core.ServeError]。阻塞，别在主线程上调。
     */
    fun hostCpu(): HostCpu {
        val reply =
            link.call("system.cpu", JsonObject(emptyMap()), HOST_CPU_TIMEOUT_MS) as? JsonObject
        return HostCpu(percent = reply?.int("percent"), cores = reply?.int("cores") ?: 0)
    }

    /** 订阅（[watch] 为真）或退订各会话的忙闲（conv.activity，经 [ConvListener.activity] 交来）；重连后照样再订阅。 */
    fun watchActivity(watch: Boolean) {
        synchronized(lock) { watchingActivity = watch }
        link.call("conv.activity", buildJsonObject { put("watch", watch) })
    }

    /** 处理 serve 推来的一行。是对话的推送时返回 true（对话已经关了的照样返回 true，什么也不做），别的（会话列表等）返回 false。 */
    fun handle(message: JsonObject): Boolean {
        val type = message.str("type") ?: return false
        if (!type.startsWith("conv.")) return false
        if (type == "conv.activity") {
            listener.activity(sessionActivitiesOf(message))
            return true
        }
        val conv = synchronized(lock) { convs[message.str("conv")] } ?: return true
        conv.receive(type, message)
        return true
    }

    /** 连接断了：各对话标成没连上，请求直接报错，等 [connected]。 */
    fun disconnected() {
        val all =
            synchronized(lock) {
                linkUp = false
                epoch++
                convs.values.filter { it.lost() }
            }
        all.forEach { listener.changed(it) }
    }

    /**
     * 连接连上了（第一次或重连，新的 serve 进程）：打开过的对话在另一条线程上按原名重新打开，核心推来整份状态和条目（断开期间核心也可能重启过）；
     * 订阅过忙闲的再订阅一次。还在第一次打开的不动，它自己的 conv.open 正等着这条连接。可以在读线程上调。
     */
    fun connected() {
        val (reopen, activity) =
            synchronized(lock) {
                linkUp = true
                convs.values.filter { it.claimReopen() } to watchingActivity
            }
        if (reopen.isEmpty() && !activity) return
        thread(name = "zeta-conv-reopen", isDaemon = true) {
            if (activity)
                runCatching { link.call("conv.activity", buildJsonObject { put("watch", true) }) }
            reopen.forEach { it.reopen(close = false) }
        }
    }
}
