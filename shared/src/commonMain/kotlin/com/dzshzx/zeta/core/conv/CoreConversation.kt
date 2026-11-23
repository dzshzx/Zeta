package com.dzshzx.zeta.core.conv

import androidx.compose.runtime.Immutable
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.CodeRewind
import com.dzshzx.zeta.core.HandoffResult
import com.dzshzx.zeta.core.PermissionReply
import com.dzshzx.zeta.core.ServeError
import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.chatItemOf
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.obj
import com.dzshzx.zeta.core.str
import com.dzshzx.zeta.i18n.Strings
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 一个对话此刻的样子。每次变化换一份新的，拿着它不会被后来的推送改掉。 */
@Immutable
data class ConvView(
    val state: ConvState = ConvState(),
    val items: List<ChatItem> = emptyList(),
    /** 连接在、核心那边这个对话开着。断线重连期间为假：界面照旧显示，操作会报错。 */
    val connected: Boolean = false,
    /** 上次重新打开没成的原因，成了以后为 null。 */
    val problem: String? = null,
)

/**
 * [ConvClient] 上打开的一个对话（核心里的那个，见 docs/core.md「对话」）。[view] 是推送合并到现在的样子，变了经 [ConvListener.changed]
 * 通知；各操作发一个 conv.* 请求，阻塞到应答，没成时抛 [ServeError]，成了以后的效果照样经推送回来。没连上时请求不发、直接报错。
 */
class CoreConversation
internal constructor(
    private val client: ConvClient,
    /** 这个对话在这条连接上的名字（请求和推送里的 conv）。 */
    val name: String,
    private var spec: ConvSpec,
) {
    @Volatile
    var view: ConvView = ConvView()
        private set

    // 下面几项拿着 client.lock 改
    /** 打开成过：断线重连后要重新打开的只有这些。 */
    private var opened = false
    /** 正在（重新）打开，[ConvClient.connected] 不再另开一次。 */
    private var reopening = false
    private var closed = false
    /** 带 key 重开时核心说它已经不在了（conversation_gone）：之后照打开时的参数新开，不再带 key。 */
    private var gone = false
    /** 条目和核心那份对不上了，在等整份：这期间的替换不套。 */
    private var staleItems = false
    /** 看着的子代理：发起它的 Agent 调用 → 合并到现在的样子（还没收到整份时没有）。 */
    private val subagents = HashMap<String, ConvSubagent?>()
    private val staleSubagents = HashSet<String>()
    /** 看着输出的后台任务（[watchTaskOutput]）。 */
    private val taskOutputs = HashSet<String>()

    // ---- 打开与重连

    /** 第一次打开；没成时从客户端去掉、抛出。 */
    internal fun openFirst() {
        val failure = openLoop(close = false) ?: return
        val keep = synchronized(client.lock) { opened && !closed }
        if (keep) return
        client.forget(this)
        throw failure as? ServeError ?: ServeError(failure.message ?: Strings.coreConvCantOpen())
    }

    /** 重连后、条目对不上时重新打开（[close] 时先放开核心里同名的那个），在 [ConvClient] 起的线程上跑。 */
    internal fun reopen(close: Boolean) {
        openLoop(close)
    }

    /** 发 conv.open，直到落定：中间断过线时说不准这次打开落在了哪条连接上，连接又在了就先放开同名的再开一次。返回最后一次的失败，成了是 null。 */
    private fun openLoop(close: Boolean): Throwable? {
        var withClose = close
        while (true) {
            val (epoch, params) = synchronized(client.lock) { client.epoch to openParams() }
            val failure = runCatching {
                if (withClose) runCatching { client.call("conv.close", nameOnly()) }
                client.call("conv.open", params)
            }
                .exceptionOrNull()
            if ((failure as? ServeError)?.code == GONE) {
                // 核心里已经没有它了（核心重启过，或没人拿着时收掉了），也没有会话号可退：照打开时给的重新开一个
                synchronized(client.lock) { gone = true }
                continue
            }
            val again = synchronized(client.lock) { settle(epoch, failure) }
            client.listener.changed(this)
            if (!again) {
                if (failure == null) rewatch()
                return failure
            }
            withClose = failure == null
        }
    }

    /** 拿着锁：记下一次打开的结果，返回要不要再开一次。 */
    private fun settle(epoch: Long, failure: Throwable?): Boolean {
        if (closed) {
            reopening = false
            return false
        }
        if (failure == null) opened = true
        val sameLink = client.epoch == epoch
        val again = !sameLink && client.linkUp && (opened || failure != null)
        reopening = again
        view =
            view.copy(
                connected = sameLink && failure == null,
                problem = failure?.message?.takeIf { sameLink && opened },
            )
        return again
    }

    /**
     * conv.open 的参数：重新打开时带上核心给的 key（还没发过消息的分支没有会话号，只能按它找回），核心里没有了再按会话号；
     * 会话号用最新的（新对话第一轮以后、换了会话以后都和打开时不同）；会话号变了时原来的会话记录不再对得上，不带。
     */
    private fun openParams(): JsonObject {
        val session = view.state.session ?: spec.session
        val key = view.state.key.takeIf { opened && !gone && it.isNotEmpty() }
        return JsonObject(
            buildMap {
                put("conv", JsonPrimitive(name))
                key?.let { put("key", JsonPrimitive(it)) }
                session?.let { put("session", JsonPrimitive(it)) }
                spec.file
                    ?.takeIf { session == spec.session }
                    ?.let { put("file", JsonPrimitive(it)) }
                if (spec.cwd.isNotEmpty()) put("cwd", JsonPrimitive(spec.cwd))
                if (spec.title.isNotEmpty()) put("title", JsonPrimitive(spec.title))
                put("options", spec.options.json())
            },
        )
    }

    private fun nameOnly(): JsonObject = JsonObject(mapOf("conv" to JsonPrimitive(name)))

    /** 拿着锁：连接断了。返回 view 变了没有。 */
    internal fun lost(): Boolean {
        if (closed || !view.connected) return false
        view = view.copy(connected = false)
        return true
    }

    /** 拿着锁：连接连上了，要不要由这边重新打开。 */
    internal fun claimReopen(): Boolean {
        if (!opened || closed || view.connected || reopening) return false
        reopening = true
        return true
    }

    /** 重新打开以后，看着的子代理和后台任务输出再看一次（放开对话时核心停了推送）：子代理收到整份前的替换不套，输出开始看时核心先推一份。 */
    private fun rewatch() {
        val (ids, tasks) =
            synchronized(client.lock) {
                staleSubagents.addAll(subagents.keys)
                subagents.keys.toList() to taskOutputs.toList()
            }
        ids.forEach { id -> runCatching { request("conv.subagent", watchParams(id, true)) } }
        tasks.forEach { task ->
            runCatching { request("conv.task_output", taskParams(task, true)) }
        }
    }

    // ---- 推送

    internal fun receive(type: String, o: JsonObject) {
        when (type) {
            "conv.state" ->
                update {
                    it.copy(
                        state =
                            mergeState(if (o.bool("full") == true) ConvState() else it.state, o),
                    )
                }
            "conv.items" -> receiveItems(o)
            "conv.subagent" -> receiveSubagent(o)
            "conv.task_output" -> receiveTaskOutput(o)
            "conv.turn" -> if (!isClosed()) client.listener.turn(this, convTurnOf(o))
            "conv.mcp",
            "conv.reply",
            "conv.phone_cancel" -> if (!isClosed()) client.listener.phone(this, o)
        }
    }

    /** 这个对话在核心里的标识（conv.state 的 key）：只带键的实时图（[CoreImage]）靠它去取。核心先推状态再推条目，所以条目到时已经有了。 */
    private fun coreKey(): String = synchronized(client.lock) { view.state.key }

    private fun isClosed(): Boolean = synchronized(client.lock) { closed }

    private inline fun update(change: (ConvView) -> ConvView) {
        synchronized(client.lock) {
            if (closed) return
            view = change(view)
        }
        client.listener.changed(this)
    }

    private enum class Splice {
        Applied,
        Ignored,
        Reopen,
    }

    private fun receiveItems(o: JsonObject) {
        val change = itemsChange(o, coreKey())
        val result =
            synchronized(client.lock) { if (closed) Splice.Ignored else applyItems(change) }
        when (result) {
            Splice.Applied -> client.listener.changed(this)
            Splice.Reopen ->
                thread(name = "zeta-conv-resync", isDaemon = true) { reopen(close = true) }
            Splice.Ignored -> {}
        }
    }

    /** 拿着锁：套上一次 conv.items。对不上（不该发生）时关掉再打开，核心再推整份。 */
    private fun applyItems(change: ItemsChange): Splice {
        if (change.full) {
            staleItems = false
            view = view.copy(items = change.items)
            return Splice.Applied
        }
        if (staleItems) return Splice.Ignored
        val next = change.applyTo(view.items)
        if (next != null) {
            view = view.copy(items = next)
            return Splice.Applied
        }
        staleItems = true
        if (!view.connected || reopening) return Splice.Ignored
        reopening = true
        return Splice.Reopen
    }

    private fun receiveSubagent(o: JsonObject) {
        val id = o.str("tool_use_id") ?: return
        val change = itemsChange(o, coreKey())
        var rewatch = false
        val merged =
            synchronized(client.lock) {
                if (closed || id !in subagents) return
                val stale = id in staleSubagents
                val items =
                    if (change.full) change.items else subagents[id]?.items?.let(change::applyTo)
                when {
                    change.full -> staleSubagents.remove(id)
                    stale -> return
                    items == null -> rewatch = staleSubagents.add(id)
                }
                items?.let { convSubagentOf(id, o, it).also { s -> subagents[id] = s } }
            }
        if (merged != null) client.listener.subagent(this, merged)
        if (rewatch)
            thread(name = "zeta-conv-subagent", isDaemon = true) {
                runCatching { request("conv.subagent", watchParams(id, true)) }
            }
    }

    private fun receiveTaskOutput(o: JsonObject) {
        val task = o.str("task") ?: return
        synchronized(client.lock) { if (closed || task !in taskOutputs) return }
        client.listener.taskOutput(this, task, taskOutputOf(o))
    }

    // ---- 请求

    /** 发 [method]，带上 conv；没连上、已经关了时不发，直接报错。 */
    private fun request(
        method: String,
        params: Map<String, JsonElement> = emptyMap(),
        timeoutMs: Long? = null,
    ): JsonElement? {
        synchronized(client.lock) {
            if (closed) throw ServeError(Strings.coreConvClosed())
            if (!view.connected) throw ServeError(Strings.coreConvHostLost())
        }
        val body = JsonObject(params + ("conv" to JsonPrimitive(name)))
        return if (timeoutMs == null) client.call(method, body)
        else client.call(method, body, timeoutMs)
    }

    /** 放开这个对话：之后不再收它的推送，重连后也不再打开。连接断着时不发（那条连接一断，核心那边就放开了）。 */
    fun close() {
        client.forget(this)
        val send =
            synchronized(client.lock) {
                closed = true
                view.connected.also { view = view.copy(connected = false) }
            }
        if (send) client.call("conv.close", nameOnly())
    }

    /** 改起进程用的设置，下次起进程时生效；重连后重新打开时也用它。 */
    fun setOptions(options: ConvOptions) {
        synchronized(client.lock) { spec = spec.copy(options = options) }
        request("conv.options", mapOf("options" to options.json()))
    }

    /**
     * 发一条消息；回答进行中时核心让它排队。[files] 是主机上的路径。[mode]、[rules] 是后台运行（定时任务、通知触发）这一条用的权限模式和叠加的规则， 用户自己发的不带。
     */
    fun send(
        text: String,
        images: List<ConvImage> = emptyList(),
        files: List<String> = emptyList(),
        mode: String? = null,
        rules: JsonObject? = null,
    ) {
        request(
            "conv.send",
            messageParams(text, images, files) +
                listOfNotNull(
                    mode?.let { "mode" to JsonPrimitive(it) },
                    rules?.let { "rules" to it },
                ),
        )
    }

    /** 停止这一轮，排队的消息撤回。 */
    fun interrupt() {
        request("conv.interrupt")
    }

    /** 立即插话（终端里的 send now）。 */
    fun steer() {
        request("conv.steer")
    }

    /** 撤回一条排队的消息；[recall] 时放回输入框（[ConvState.recalled]）。 */
    fun cancel(uuid: String, recall: Boolean) {
        request(
            "conv.cancel",
            mapOf("uuid" to JsonPrimitive(uuid), "recall" to JsonPrimitive(recall)),
        )
    }

    /** 输入框取走撤回放回的那条；没有时是 null。 */
    fun takeRecalled(): ConvMessage? =
        (request("conv.take_recalled") as? JsonObject)?.let(::convMessageOf)

    /** 回应工具确认、计划审批或提问。 */
    fun answer(requestId: String, reply: PermissionReply) {
        request(
            "conv.answer",
            mapOf("request_id" to JsonPrimitive(requestId), "reply" to replyJson(reply)),
        )
    }

    /**
     * 回应 MCP 表单或网址：[action] 是 accept、decline、cancel；[values] 是字段键 → 输入框里的字（多选是选中的各值）。
     * 返回核心说填得不对的各项（键 → 原因），表单留着改了再交；交出去了是空的。
     */
    fun elicit(
        requestId: String,
        action: String,
        values: Map<String, List<String>>? = null,
    ): Map<String, String> {
        val params = buildMap {
            put("request_id", JsonPrimitive(requestId))
            put("action", JsonPrimitive(action))
            values?.let { v ->
                put("values", JsonObject(v.mapValues { JsonArray(it.value.map(::JsonPrimitive)) }))
            }
        }
        val errors =
            (request("conv.elicit", params) as? JsonObject)?.obj("errors") ?: return emptyMap()
        return errors
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it.content } }
            .toMap()
    }

    /** 只管这个对话的模型；null 回到默认。 */
    fun setModel(value: String?) {
        request("conv.model", valueParams(value))
    }

    /** 只管这个对话的思考强度；null 回到默认。 */
    fun setEffort(value: String?) {
        request("conv.effort", valueParams(value))
    }

    /** 只管这个对话的权限模式；null 回到默认。 */
    fun setMode(value: String?) {
        request("conv.mode", valueParams(value))
    }

    /** 定时任务、数字助理给这个对话预设模型、思考强度，[hint] 追加进 --append-system-prompt；null 的那项照旧。 */
    fun preset(model: String?, effort: String?, hint: String? = null) {
        request(
            "conv.preset",
            listOfNotNull(
                    model?.let { "model" to JsonPrimitive(it) },
                    effort?.let { "effort" to JsonPrimitive(it) },
                    hint?.let { "hint" to JsonPrimitive(it) },
                )
                .toMap(),
        )
    }

    fun compact() {
        request("conv.compact")
    }

    /** 只起好进程、不发消息（conv.prepare）：斜杠命令和模型清单随后经推送来。已有进程、会话被别处占着时核心什么也不做。 */
    fun prepare() {
        request("conv.prepare")
    }

    fun stopTask(task: String) {
        request("conv.stop_task", mapOf("task" to JsonPrimitive(task)))
    }

    /**
     * 开始（[watch] 为真）或停止看后台命令、监视器 [task] 的输出：看着时核心先推一份，之后输出变了才推（conv.task_output），经
     * [ConvListener.taskOutput] 交来；任务结束后核心不再问。
     */
    fun watchTaskOutput(task: String, watch: Boolean) {
        synchronized(client.lock) { if (watch) taskOutputs.add(task) else taskOutputs.remove(task) }
        request("conv.task_output", taskParams(task, watch))
    }

    /** 结束 Claude Code 进程（等于终端里的 /exit）；对话内容留着，下次发消息时接上。 */
    fun end() {
        request("conv.end")
    }

    /** 把会话从别处接过来（conv.handoff），要等那边退出，最多十几秒；交出来了核心就解开 blocked 并推 conv.state。 */
    fun handoff(): HandoffResult = HandoffResult.of(request("conv.handoff"))

    /** 手机工具的应答（回 conv.mcp 的 request_id）。 */
    fun mcpReply(requestId: String, response: JsonElement) {
        request(
            "conv.mcp_reply",
            mapOf("request_id" to JsonPrimitive(requestId), "response" to response),
        )
    }

    /**
     * 载入更早的一页接到最前面（[rest] 时载完剩下的全部，等得久些），读完才返回；新的一页经推送来。没读成时抛的 [ServeError] 带核心给的 code（比如
     * history_changed）。
     */
    fun loadEarlier(rest: Boolean = false) {
        request(
            "conv.earlier",
            mapOf("rest" to JsonPrimitive(rest)),
            if (rest) REST_TIMEOUT_MS else PAGE_TIMEOUT_MS,
        )
    }

    /** 编辑：[target] 是那条用户消息的条目 id，剪掉它和之后的再发这条。 */
    fun rewrite(
        target: String,
        text: String,
        images: List<ConvImage> = emptyList(),
        files: List<String> = emptyList(),
    ) {
        request(
            "conv.rewrite",
            messageParams(text, images, files) + ("target" to JsonPrimitive(target)),
        )
    }

    /** 重新生成：剪掉 [target] 和之后的，照它原样重发（resend，文字、图片、附件路径都由核心取）。 */
    fun resend(target: String) {
        request(
            "conv.rewrite",
            mapOf("target" to JsonPrimitive(target), "resend" to JsonPrimitive(true)),
        )
    }

    /** 删掉 [target] 这一轮和之后的（只剪不发）。 */
    fun drop(target: String) {
        request("conv.drop", mapOf("target" to JsonPrimitive(target)))
    }

    /** 把文件改回 [target] 这条消息之前会动到哪些（只看不改）；没有可还原的是 null。 */
    fun codeChanges(target: String): CodeRewind.Changes? =
        (request("conv.code_changes", mapOf("target" to JsonPrimitive(target))) as? JsonObject)
            ?.let(::codeChangesOf)

    /**
     * 把文件改回 [target] 这条消息之前，还原成了再接着做：[resend] 时剪掉这一轮、照原样重发（同 conv.rewrite 带 resend），否则 [drop]
     * 时删这一轮。 没还原成时不剪。结果以列表里的提示告知。
     */
    fun restoreCode(target: String, drop: Boolean = false, resend: Boolean = false) {
        val params = mapOf("target" to JsonPrimitive(target), "drop" to JsonPrimitive(drop))
        request(
            "conv.restore_code",
            if (resend) params + ("resend" to JsonPrimitive(true)) else params,
        )
    }

    /**
     * 从条目 [last] 所在那一轮之后分支：新对话由这个客户端拿着（先登记名字，核心在应答前就推它的整份状态，登记好就交给 [registered]）， 原对话不动。 [options]
     * 为 null 时沿用给原对话的。
     */
    fun fork(
        last: String,
        options: ConvOptions? = null,
        registered: (CoreConversation) -> Unit = {},
    ): CoreConversation {
        val inherited =
            synchronized(client.lock) {
                ConvSpec(cwd = view.state.cwd, options = options ?: spec.options)
            }
        val child = client.register(inherited)
        registered(child)
        val epoch = synchronized(client.lock) { client.epoch }
        val params = buildMap {
            put("as", JsonPrimitive(child.name))
            put("last", JsonPrimitive(last))
            options?.let { put("options", it.json()) }
        }
        try {
            request("conv.fork", params)
        } catch (e: ServeError) {
            client.forget(child)
            throw e
        }
        child.forked(epoch)
        return child
    }

    /** 新分支的 conv.fork 答了：和打开成了一样（中间断过线、连接又在了时同样关掉再打开）。 */
    private fun forked(epoch: Long) {
        val again = synchronized(client.lock) { settle(epoch, null) }
        client.listener.changed(this)
        if (again) thread(name = "zeta-conv-reopen", isDaemon = true) { reopen(close = true) }
    }

    /** 借这个对话开着的进程问一次订阅额度，返回应答里的 response 原样（交给 quota.usage）。 */
    fun quota(): JsonElement? = request("conv.quota")

    /** 开始（[watch] 为真）或停止看一个子代理（[toolUseId] 是发起它的 Agent 调用），变化经 [ConvListener.subagent] 交来。 */
    fun watchSubagent(toolUseId: String, watch: Boolean) {
        synchronized(client.lock) {
            if (watch) {
                subagents.putIfAbsent(toolUseId, null)
                staleSubagents.add(toolUseId)
            } else {
                subagents.remove(toolUseId)
                staleSubagents.remove(toolUseId)
            }
        }
        request("conv.subagent", watchParams(toolUseId, watch))
    }

    private companion object {
        /** conv.open 带 key 而核心里已经没有那个对话时的 code。 */
        const val GONE = "conversation_gone"

        /** 载入更早的一页最多等多久；载完剩下的全部（对话内查找）可能要读整份很长的记录，另给长些。 */
        const val PAGE_TIMEOUT_MS = 60_000L
        const val REST_TIMEOUT_MS = 10 * 60_000L

        fun valueParams(value: String?): Map<String, JsonElement> =
            mapOf("value" to (value?.let(::JsonPrimitive) ?: JsonNull))

        fun watchParams(toolUseId: String, watch: Boolean): Map<String, JsonElement> =
            mapOf("tool_use_id" to JsonPrimitive(toolUseId), "watch" to JsonPrimitive(watch))

        fun taskParams(task: String, watch: Boolean): Map<String, JsonElement> =
            mapOf("task" to JsonPrimitive(task), "watch" to JsonPrimitive(watch))

        fun messageParams(
            text: String,
            images: List<ConvImage>,
            files: List<String>,
        ): Map<String, JsonElement> = buildMap {
            put("text", JsonPrimitive(text))
            if (images.isNotEmpty())
                put(
                    "images",
                    JsonArray(
                        images.map {
                            JsonObject(
                                mapOf(
                                    "media_type" to JsonPrimitive(it.mediaType),
                                    "data" to JsonPrimitive(it.data),
                                ),
                            )
                        },
                    ),
                )
            if (files.isNotEmpty()) put("files", JsonArray(files.map(::JsonPrimitive)))
        }

        fun itemsChange(o: JsonObject, conv: String = ""): ItemsChange =
            ItemsChange(
                full = o.bool("full") == true,
                at = o.int("at") ?: 0,
                remove = o.int("remove") ?: 0,
                items =
                    o.arr("items").orEmpty().filterIsInstance<JsonObject>().map {
                        chatItemOf(it, conv)
                    },
            )
    }
}

/** 条目的一次变化（conv.items、conv.subagent）：[full] 时 [items] 是整份，否则从 [at] 起去掉 [remove] 项、换成 [items]。 */
internal class ItemsChange(
    val full: Boolean,
    val at: Int,
    val remove: Int,
    val items: List<ChatItem>,
) {
    /** 套到 [list] 上；不是整份又越界时是 null。 */
    fun applyTo(list: List<ChatItem>): List<ChatItem>? {
        if (full) return items
        if (at < 0 || remove < 0 || at + remove > list.size) return null
        return buildList(list.size - remove + items.size) {
            addAll(list.subList(0, at))
            addAll(items)
            addAll(list.subList(at + remove, list.size))
        }
    }
}
