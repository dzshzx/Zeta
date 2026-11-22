package com.dzshzx.zeta.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把 Claude Code 的 stream-json 事件归并成聊天列表。
 *
 * 实时会话同时收到两种东西：`stream_event`（逐字增量）和 `assistant`（一个内容块的完整终稿）。
 * 增量用来即时显示，终稿到达后覆盖同一位置的内容。历史记录只有终稿。纯逻辑，不依赖 Android。
 */
class TranscriptReducer(private val idPrefix: String = "") {
    val items = ArrayList<ChatItem>()

    private class Live(
        val kind: String,
        val itemId: String,
        val stepId: String?,
        var startedAt: Long = 0L,
    )

    private var streamMessageId: String? = null
    private val liveByIndex = HashMap<Int, Live>()
    private val liveOrder = HashMap<String, MutableList<Live>>()
    private val finalizedCount = HashMap<String, Int>()
    private var localSeq = 0

    /** 从历史记录载入的内容（消息 id → 文字块），重新接上进程时用来丢掉日志里重复送来的块。 */
    private val historyTexts = HashMap<String, HashSet<String>>()
    private val knownToolIds = HashSet<String>()

    private fun nextId(prefix: String) = "$idPrefix$prefix-${++localSeq}"

    fun addUser(
        text: String,
        images: List<ImageRef> = emptyList(),
        files: List<String> = emptyList(),
        uuid: String? = null,
    ): String {
        val id = nextId("u")
        items.add(UserItem(id, text, images, files, uuid))
        return id
    }

    /** 上一条 `!` 命令那一步：等它的输出那行填结果。 */
    private var shellStep: String? = null

    /**
     * 用户在输入栏执行的命令（TUI 的 `!`，stream-json 的 bash_command）：Claude Code 把它记成两条用户消息，
     * `<bash-input>命令</bash-input>` 和
     * `<bash-stdout>…</bash-stdout><bash-stderr>…</bash-stderr><bash-exit-code>N</bash-exit-code>`
     * （退出码是 0 时不写），实时的和历史里的一样。这里画成单独一组里的一个命令步骤，标题是 `! 命令`。 不是这两种的返回 false。
     */
    fun applyShell(text: String): Boolean {
        val trimmed = text.trim()
        when {
            trimmed.startsWith("<bash-input>") -> {
                val command = tagged(trimmed, "bash-input").orEmpty()
                val id = nextId("sh")
                val step =
                    ToolStep(
                        id,
                        "Bash",
                        buildJsonObject {
                            put("command", command)
                            put(
                                "description",
                                "! " + command.lineSequence().firstOrNull().orEmpty(),
                            )
                        },
                        ToolStatus.Running,
                        null,
                    )
                items.add(WorkItem("w:$id", listOf(step)))
                shellStep = id
            }
            trimmed.startsWith("<bash-stdout>") || trimmed.startsWith("<bash-stderr>") -> {
                val id = shellStep ?: return true
                shellStep = null
                val out = tagged(trimmed, "bash-stdout").orEmpty().trimEnd()
                val err = tagged(trimmed, "bash-stderr").orEmpty().trimEnd()
                val code = tagged(trimmed, "bash-exit-code")?.trim()?.toIntOrNull() ?: 0
                val result =
                    listOf(out, err)
                        .filter { it.isNotEmpty() }
                        .joinToString("\n")
                        .ifEmpty { "（没有输出）" } + if (code != 0) "\n退出码 $code" else ""
                updateToolStep(id) {
                    it.copy(
                        status = if (code == 0) ToolStatus.Success else ToolStatus.Failed,
                        result = result,
                    )
                }
            }
            else -> return false
        }
        return true
    }

    private fun tagged(text: String, tag: String): String? =
        text
            .substringAfter("<$tag>", missingDelimiterValue = "")
            .takeIf { "<$tag>" in text }
            ?.substringBefore("</$tag>")

    fun addNotice(text: String, error: Boolean) {
        items.add(NoticeItem(nextId("n"), text, error))
    }

    fun applyStream(event: JsonObject, now: Long) {
        when (event.str("type")) {
            "message_start" -> {
                streamMessageId = event.obj("message")?.str("id")
                liveByIndex.clear()
            }
            "content_block_start" -> {
                val messageId = streamMessageId ?: return
                val index = event.int("index") ?: return
                val block = event.obj("content_block") ?: return
                val live =
                    when (block.str("type")) {
                        "text" -> {
                            val id = "$messageId:$index"
                            items.add(AnswerItem(id, block.str("text").orEmpty(), streaming = true))
                            Live("text", id, null)
                        }
                        "thinking" -> {
                            val stepId = "$messageId:$index"
                            Live(
                                "thinking",
                                appendStep(
                                    ThinkingStep(
                                        stepId,
                                        block.str("thinking").orEmpty(),
                                        running = true,
                                        seconds = null,
                                    ),
                                ),
                                stepId,
                                now,
                            )
                        }
                        "tool_use" -> {
                            val stepId = block.str("id") ?: "$messageId:$index"
                            if (!knownToolIds.add(stepId)) return
                            Live(
                                "tool_use",
                                appendStep(
                                    ToolStep(
                                        stepId,
                                        block.str("name").orEmpty(),
                                        null,
                                        ToolStatus.Running,
                                        null,
                                    ),
                                ),
                                stepId,
                            )
                        }
                        else -> return
                    }
                liveByIndex[index] = live
                liveOrder.getOrPut(messageId) { ArrayList() }.add(live)
            }
            "content_block_delta" -> {
                val live = liveByIndex[event.int("index") ?: return] ?: return
                val delta = event.obj("delta") ?: return
                when (delta.str("type")) {
                    "text_delta" ->
                        updateAnswer(live.itemId) {
                            it.copy(text = it.text + delta.str("text").orEmpty())
                        }
                    "thinking_delta" ->
                        updateStep(live) { step ->
                            (step as? ThinkingStep)?.let {
                                it.copy(text = it.text + delta.str("thinking").orEmpty())
                            } ?: step
                        }
                }
            }
            "content_block_stop" -> {
                val live = liveByIndex[event.int("index") ?: return] ?: return
                when (live.kind) {
                    "text" -> updateAnswer(live.itemId) { it.copy(streaming = false) }
                    "thinking" ->
                        updateStep(live) { step ->
                            (step as? ThinkingStep)
                                ?.takeIf { it.running }
                                ?.copy(
                                    running = false,
                                    seconds =
                                        ((now - live.startedAt) / 1000L).toInt().coerceAtLeast(1),
                                ) ?: step
                        }
                }
            }
        }
    }

    /** 一条完整的 assistant 消息（实时模式下每个内容块各来一条）。 [history] 表示来自会话记录；之后实时送来的同一块内容会被认出来并丢掉。 */
    fun applyAssistant(message: JsonObject, history: Boolean = false) {
        val messageId = message.str("id") ?: nextId("m")
        val order = liveOrder[messageId]
        for (element in message.arr("content").orEmpty()) {
            val block = element as? JsonObject ?: continue
            val kind = block.str("type") ?: continue
            val live = order?.let { claimLive(messageId, it, kind) }
            when (kind) {
                "text" -> finalizeText(messageId, block, live, history)
                "thinking" -> finalizeThinking(messageId, block, live)
                "tool_use" -> finalizeToolUse(messageId, block, live)
            }
        }
    }

    /** 这条消息里下一个还没收到终稿、类型为 [kind] 的增量块；找到就记下已经对到了哪里。 */
    private fun claimLive(messageId: String, list: List<Live>, kind: String): Live? {
        var cursor = finalizedCount[messageId] ?: 0
        while (cursor < list.size && list[cursor].kind != kind) cursor++
        return list.getOrNull(cursor)?.also { finalizedCount[messageId] = cursor + 1 }
    }

    private fun finalizeText(messageId: String, block: JsonObject, live: Live?, history: Boolean) {
        val text = block.str("text").orEmpty()
        val duplicate = !history && historyTexts[messageId]?.contains(text) == true
        if (history) historyTexts.getOrPut(messageId) { HashSet() }.add(text)
        if (live != null) {
            if (duplicate) items.removeAll { it.id == live.itemId }
            else updateAnswer(live.itemId) { it.copy(text = text, streaming = false) }
        } else if (text.isNotBlank() && !duplicate) {
            items.add(AnswerItem(nextId(messageId), text, streaming = false))
        }
    }

    private fun finalizeThinking(messageId: String, block: JsonObject, live: Live?) {
        val text = block.str("thinking").orEmpty()
        if (live != null) {
            updateStep(live) { step ->
                (step as? ThinkingStep)?.let { if (text.isNotEmpty()) it.copy(text = text) else it }
                    ?: step
            }
        } else if (text.isNotBlank()) {
            appendStep(ThinkingStep(nextId(messageId), text, running = false, seconds = null))
        }
    }

    private fun finalizeToolUse(messageId: String, block: JsonObject, live: Live?) {
        val input = block.obj("input")
        if (live != null) {
            updateStep(live) { step -> (step as? ToolStep)?.copy(input = input) ?: step }
            return
        }
        val stepId = block.str("id") ?: nextId(messageId)
        if (knownToolIds.add(stepId)) {
            appendStep(
                ToolStep(stepId, block.str("name").orEmpty(), input, ToolStatus.Running, null),
            )
        } else {
            updateToolStep(stepId) { it.copy(input = it.input ?: input) }
        }
    }

    /** user 消息：工具结果回填到对应步骤；[replay] 时文字内容是用户当时的输入。 */
    /**
     * [sessionFile] 是这条消息所在的会话记录；历史里的图片只带位置，要靠它去取。[uuid] 是它在记录里的 uuid。 [toolUseResult] 是事件上的
     * `tool_use_result`（工具的结构化结果，改文件的工具带 structuredPatch）。
     */
    fun applyUser(
        message: JsonObject,
        replay: Boolean,
        sessionFile: String? = null,
        uuid: String? = null,
        toolUseResult: JsonObject? = null,
    ) {
        val blocks = message.arr("content").orEmpty()
        // 结构化结果是整条消息的；只有一个工具结果时才知道它属于谁
        val patch =
            EditPatch.parse(toolUseResult)?.takeIf {
                blocks.count { (it as? JsonObject)?.str("type") == "tool_result" } == 1
            }
        if (replay) {
            val images = blocks.mapNotNull { imageRef(it as? JsonObject, sessionFile, "") }
            val raw =
                blocks
                    .mapNotNull {
                        (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text")
                    }
                    .joinToString("\n")
                    .trim()
            if (images.isEmpty() && applyShell(raw)) return
            val (files, text) = splitAttachedFiles(raw)
            if (images.isNotEmpty() || files.isNotEmpty() || text.isNotEmpty())
                addUser(text, images, files, uuid)
        }
        for (element in blocks) {
            val block = element as? JsonObject ?: continue
            when (block.str("type")) {
                "tool_result" -> {
                    val toolUseId = block.str("tool_use_id") ?: continue
                    val failed = block.bool("is_error") == true
                    val result = block["content"].flattenText()
                    val images =
                        (block["content"] as? JsonArray).orEmpty().mapIndexedNotNull { index, inner
                            ->
                            imageRef(inner as? JsonObject, sessionFile, "$toolUseId:$index")
                        }
                    updateToolStep(toolUseId) {
                        it.copy(
                            status = if (failed) ToolStatus.Failed else ToolStatus.Success,
                            result = result,
                            images = images,
                            patch = patch,
                        )
                    }
                }
            }
        }
    }

    /** 历史里的图片块只有位置，实时事件里的带着数据。 */
    private fun imageRef(block: JsonObject?, sessionFile: String?, liveKey: String): ImageRef? {
        if (block?.str("type") != "image") return null
        block.str("ref")?.let { ref ->
            return sessionFile?.let { SessionImage(it, ref) }
        }
        val data = block.obj("source")?.str("data") ?: return null
        return InlineImage("$idPrefix$liveKey", data)
    }

    /** 一轮结束（或进程退出）：把还显示为进行中的内容收口。 [keepRunningTools] 用于载入历史后还要接着跟读的情况：最后那几步其实还在跑。 */
    fun finishTurn(keepRunningTools: Boolean = false) {
        for (i in items.indices) {
            when (val item = items[i]) {
                is AnswerItem -> if (item.streaming) items[i] = item.copy(streaming = false)
                is WorkItem ->
                    if (item.running) {
                        items[i] =
                            item.copy(steps = item.steps.map { finishStep(it, keepRunningTools) })
                    }
                else -> Unit
            }
        }
        items.removeAll { it is AnswerItem && it.text.isBlank() }
    }

    /** 一轮结束时收口一步：思考停下；还在跑的工具算中断（[keepRunningTools] 时不动）。 */
    private fun finishStep(step: WorkStep, keepRunningTools: Boolean): WorkStep =
        when {
            step is ThinkingStep && step.running -> step.copy(running = false)
            step is ToolStep && step.running && !keepRunningTools ->
                step.copy(status = ToolStatus.Failed, result = step.result ?: "已中断")
            else -> step
        }

    /** 把步骤并入末尾的工作组（没有就新开一组），返回所在工作组的 id。 */
    private fun appendStep(step: WorkStep): String {
        val last = items.lastOrNull()
        if (last is WorkItem) {
            items[items.lastIndex] = last.copy(steps = last.steps + step)
            return last.id
        }
        val id = "w:${step.id}"
        items.add(WorkItem(id, listOf(step)))
        return id
    }

    private inline fun updateAnswer(itemId: String, transform: (AnswerItem) -> AnswerItem) {
        for (i in items.indices.reversed()) {
            val item = items[i]
            if (item is AnswerItem && item.id == itemId) {
                items[i] = transform(item)
                return
            }
        }
    }

    private inline fun updateStep(live: Live, transform: (WorkStep) -> WorkStep) {
        for (i in items.indices.reversed()) {
            val item = items[i]
            if (item is WorkItem && item.id == live.itemId) {
                items[i] =
                    item.copy(
                        steps = item.steps.map { if (it.id == live.stepId) transform(it) else it },
                    )
                return
            }
        }
    }

    private inline fun updateToolStep(toolUseId: String, transform: (ToolStep) -> ToolStep) {
        for (i in items.indices.reversed()) {
            val item = items[i] as? WorkItem ?: continue
            if (item.steps.any { it.id == toolUseId }) {
                items[i] =
                    item.copy(
                        steps =
                            item.steps.map {
                                if (it is ToolStep && it.id == toolUseId) transform(it) else it
                            },
                    )
                return
            }
        }
    }
}
