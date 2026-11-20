package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 子代理记录旁 meta.json 里界面要的几项（zeta-helper subagent 给出）。 */
@Immutable
data class SubagentMeta(
    val agentId: String,
    val agentType: String? = null,
    val description: String? = null,
    val model: String? = null,
    /** 做完了：Claude Code 在子代理结束时写 finished。 */
    val finished: Boolean = false,
    /** 被停下了：stoppedByUser。 */
    val stopped: Boolean = false,
    /** 起的时候放没放后台（requestShape）；不知道时为 null。 */
    val background: Boolean? = null,
) {
    /** 记录本身看得出它已经结束了。进程没写完就没了的，这两项都没有。 */
    val ended: Boolean get() = finished || stopped
}

/**
 * 一个子代理在做什么，归约成和聊天里一样的条目。纯逻辑，在主线程（或测试里）调用。
 *
 * 两个来源：Claude Code 写在 `<会话目录>/subagents/agent-<id>.jsonl` 的记录（zeta-helper subagent 按偏移增量读出，
 * 见 [applyRead]），这是全的；输出流里 parent_tool_use_id 是它的事件（[observeLive]），比读文件及时，
 * 但不全（前台子代理的文字不进输出流），只在文件还没读到时补上，文件里有了就丢掉。
 *
 * [toolUseId] 是发起它的 Agent 调用。最多留 [keep] 条记录事件，更早的丢掉、计进 [skipped]。
 */
class SubagentTranscript(val toolUseId: String, private val keep: Int = 800) {
    /** 记录文件（Linux 里的路径），历史图片要靠它去取；还没找到时为 null。 */
    var file: String? = null
        private set
    var meta: SubagentMeta? = null
        private set

    /** 交给它的指令（记录的第一行）。 */
    var prompt: String? = null
        private set

    /** 下次从记录的哪里接着读：字节偏移和行号（图片的位置要用行号）。 */
    var offset = 0L
        private set
    var line = 0
        private set

    /** 上次只读了一段，后面还有，接着读不用等。 */
    var more = false
        private set

    /** 找过了，没有这个子代理的记录（还没写出来，或者会话已经删了）。 */
    var missing = false
        private set

    /** 读到过记录（不管有没有找到）。 */
    var loaded = false
        private set

    /** 上次读的时候，记录已经多久没写了（毫秒）。 */
    var idleMs = 0L
        private set

    /**
     * 记录收齐了：没找到，或者已经有一会儿没写了。子代理的结局（meta 的 finished、输出流的 task_notification）
     * 可能比最后几行先写下，结束后要等记录停下来再停止读。
     */
    val settled: Boolean get() = missing || idleMs >= SETTLE_MS

    /** 没留下的更早的记录事件数。 */
    var skipped = 0
        private set

    private val fileEvents = ArrayList<JsonObject>()
    private val liveEvents = ArrayList<JsonObject>()

    /** 文件里已经有的 assistant 消息、工具调用、工具结果：输出流里同样的据此丢掉。 */
    private val fileMessages = HashSet<String>()
    private val fileToolUses = HashSet<String>()
    private val fileResults = HashSet<String>()

    /** 收下一次 `zeta-helper subagent` 的输出。返回内容有没有变。 */
    fun applyRead(stdout: String): Boolean {
        val lines = stdout.lineSequence().map { it.trim() }.mapNotNull { line ->
            if (!line.startsWith("{")) null else runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        }.toList()
        val headIndex = lines.indexOfFirst { it.str("type") == "zeta_subagent" }
        if (headIndex < 0) return false
        val head = lines[headIndex]
        val wasLoaded = loaded
        loaded = true
        if (head.bool("missing") == true) {
            // 之前读到过的留着（比如会话刚被删）；只是这次没找到
            val changed = !missing || !wasLoaded
            missing = true
            return changed
        }
        var changed = missing || !wasLoaded
        missing = false
        val path = head.str("file")
        if (head.bool("reset") == true || (file != null && path != file)) {
            reset()
            changed = true
        }
        file = path
        val agentId = head.str("agent_id")
        val read = head.obj("meta")?.let { m ->
            SubagentMeta(
                agentId = agentId.orEmpty(),
                agentType = m.str("agentType"),
                description = m.str("description"),
                model = m.str("model"),
                finished = m.bool("finished") == true,
                stopped = m.bool("stoppedByUser") == true,
                background = m.str("requestShape")?.let { it == "background" },
            )
        } ?: SubagentMeta(agentId.orEmpty())
        if (read != meta) {
            meta = read
            changed = true
        }
        offset = head.long("offset") ?: offset
        line = head.int("line") ?: line
        idleMs = head.long("idle_ms") ?: SETTLE_MS
        more = head.bool("more") == true
        skipped += head.int("skipped") ?: 0
        for (event in lines.drop(headIndex + 1)) {
            if (event.bool("prompt") == true) {
                prompt = textOf(event)
            } else {
                fileEvents.add(event)
                index(event)
            }
            changed = true
        }
        if (fileEvents.size > keep) {
            val drop = fileEvents.size - keep
            fileEvents.subList(0, drop).clear()
            skipped += drop
        }
        // 文件追上了的输出流事件不再要
        val pending = liveEvents.mapNotNull(::uncovered)
        if (pending.size != liveEvents.size) {
            liveEvents.clear()
            liveEvents.addAll(pending)
            changed = true
        }
        return changed
    }

    /** 输出流里一条 parent_tool_use_id 是 [toolUseId] 的 assistant / user 事件。返回内容有没有变。 */
    fun observeLive(event: JsonObject): Boolean {
        val kept = uncovered(event) ?: return false
        liveEvents.add(kept)
        return true
    }

    /** 归约出的条目。[running] 时最后还没有结果的工具调用显示为进行中，否则收口成已中断。 */
    fun items(running: Boolean): List<ChatItem> {
        val reducer = TranscriptReducer("sa-")
        for (event in fileEvents) apply(reducer, event, file)
        for (event in liveEvents) apply(reducer, event, null)
        reducer.finishTurn(keepRunningTools = running)
        return reducer.items.toList()
    }

    /** 读到的工具调用一共几次（含没留下的更早的那些）。 */
    fun toolUses(): Int = fileToolUses.size + liveEvents.sumOf { e -> e.obj("message")?.arr("content").orEmpty().count { (it as? JsonObject)?.str("type") == "tool_use" } }

    private fun reset() {
        fileEvents.clear()
        fileMessages.clear()
        fileToolUses.clear()
        fileResults.clear()
        prompt = null
        skipped = 0
    }

    private fun index(event: JsonObject) {
        val message = event.obj("message") ?: return
        val blocks = message.arr("content").orEmpty().mapNotNull { it as? JsonObject }
        when (event.str("type")) {
            "assistant" -> {
                message.str("id")?.let(fileMessages::add)
                blocks.filter { it.str("type") == "tool_use" }.mapNotNullTo(fileToolUses) { it.str("id") }
            }
            "user" -> blocks.filter { it.str("type") == "tool_result" }.mapNotNullTo(fileResults) { it.str("tool_use_id") }
        }
    }

    /**
     * 输出流事件里文件还没有的部分：assistant 留文件里没有的工具调用和别的消息的文字、思考；
     * user 只留工具结果（文字是交给它的指令，文件里有）。什么都不剩时返回 null。
     */
    private fun uncovered(event: JsonObject): JsonObject? {
        val message = event.obj("message") ?: return null
        val blocks = message.arr("content").orEmpty().mapNotNull { it as? JsonObject }
        val kept = when (event.str("type")) {
            "assistant" -> {
                val seen = message.str("id")?.let { it in fileMessages } == true
                blocks.filter { block ->
                    when (block.str("type")) {
                        "tool_use" -> block.str("id") !in fileToolUses
                        "text", "thinking" -> !seen
                        else -> false
                    }
                }
            }
            "user" -> blocks.filter { it.str("type") == "tool_result" && it.str("tool_use_id") !in fileResults }
            else -> emptyList()
        }
        if (kept.isEmpty()) return null
        if (kept.size == blocks.size) return event
        return JsonObject(event + ("message" to JsonObject(message + ("content" to JsonArray(kept)))))
    }

    private fun apply(reducer: TranscriptReducer, event: JsonObject, sessionFile: String?) {
        val message = event.obj("message") ?: return
        when (event.str("type")) {
            "assistant" -> reducer.applyAssistant(message)
            // 记录里的文字是之后续派给它的消息（第一条指令已经拿出去了）
            "user" -> reducer.applyUser(message, replay = sessionFile != null, sessionFile = sessionFile, toolUseResult = event.obj("tool_use_result"))
        }
    }

    private fun textOf(event: JsonObject): String? = event.obj("message")?.arr("content").orEmpty()
        .mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }
        .joinToString("\n").ifBlank { null }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        const val SETTLE_MS = 2_000L
    }
}

/**
 * 子代理查看页看的一个子代理（可观察），由 [Conversation] 读记录、喂输出流事件，状态只在主线程上改。
 * [toolUseId] 是发起它的 Agent 调用。
 */
@Stable
class SubagentLog internal constructor(val toolUseId: String) {
    internal val transcript = SubagentTranscript(toolUseId)

    var items by mutableStateOf<List<ChatItem>>(emptyList())
        private set
    var meta by mutableStateOf<SubagentMeta?>(null)
        private set
    var prompt by mutableStateOf<String?>(null)
        private set
    var file by mutableStateOf<String?>(null)
        private set
    var skipped by mutableStateOf(0)
        private set
    var toolUses by mutableStateOf(0)
        private set

    /** 读过一次记录了（找没找到都算）。 */
    var loaded by mutableStateOf(false)
        private set

    /** 没找到记录。 */
    var missing by mutableStateOf(false)
        private set

    /** 上次没读成的原因；读成了为 null。 */
    var failure by mutableStateOf<String?>(null)
        internal set

    /** 还在跑（[Conversation] 每次更新时按记录和事件判断）。 */
    var running by mutableStateOf(false)
        private set

    /** 怎么结束的；还在跑或不知道时为 null。 */
    var outcome by mutableStateOf<TaskState?>(null)
        private set

    /** 正在读记录：同一个子代理不同时读两份，偏移才接得上。 */
    internal var reading = false

    /** 开着的查看页数。没人看时不收输出流的事件，再打开时从记录接着读就全了。 */
    internal var watchers = 0

    internal fun publish(running: Boolean, outcome: TaskState?) {
        this.running = running
        this.outcome = outcome
        items = transcript.items(running)
        meta = transcript.meta
        prompt = transcript.prompt
        file = transcript.file
        skipped = transcript.skipped
        toolUses = transcript.toolUses()
        loaded = transcript.loaded
        missing = transcript.missing && transcript.meta == null
    }
}
