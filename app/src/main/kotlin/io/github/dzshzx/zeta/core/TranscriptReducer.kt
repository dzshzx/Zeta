package io.github.dzshzx.zeta.core

import kotlinx.serialization.json.JsonObject

/**
 * 把 Claude Code 的 stream-json 事件归并成聊天列表。
 *
 * 实时会话同时收到两种东西：`stream_event`（逐字增量）和 `assistant`（一个内容块的完整终稿）。
 * 增量用来即时显示，终稿到达后覆盖同一位置的内容。历史记录只有终稿。纯逻辑，不依赖 Android。
 */
class TranscriptReducer(private val idPrefix: String = "") {
    val items = ArrayList<ChatItem>()

    private class Live(val kind: String, val itemId: String, val stepId: String?, var startedAt: Long = 0L)

    private var streamMessageId: String? = null
    private val liveByIndex = HashMap<Int, Live>()
    private val liveOrder = HashMap<String, MutableList<Live>>()
    private val finalizedCount = HashMap<String, Int>()
    private var localSeq = 0

    /** 从历史记录载入的内容（消息 id → 文字块），重新接上进程时用来丢掉日志里重复送来的块。 */
    private val historyTexts = HashMap<String, HashSet<String>>()
    private val knownToolIds = HashSet<String>()

    private fun nextId(prefix: String) = "$idPrefix$prefix-${++localSeq}"

    fun addUser(text: String, images: List<androidx.compose.ui.graphics.ImageBitmap> = emptyList(), imageCount: Int = images.size): String {
        val id = nextId("u")
        items.add(UserItem(id, text, images, imageCount))
        return id
    }

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
                val live = when (block.str("type")) {
                    "text" -> {
                        val id = "$messageId:$index"
                        items.add(AnswerItem(id, block.str("text").orEmpty(), streaming = true))
                        Live("text", id, null)
                    }
                    "thinking" -> {
                        val stepId = "$messageId:$index"
                        Live("thinking", appendStep(ThinkingStep(stepId, block.str("thinking").orEmpty(), running = true, seconds = null)), stepId, now)
                    }
                    "tool_use" -> {
                        val stepId = block.str("id") ?: "$messageId:$index"
                        if (!knownToolIds.add(stepId)) return
                        Live("tool_use", appendStep(ToolStep(stepId, block.str("name").orEmpty(), null, ToolStatus.Running, null)), stepId)
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
                    "text_delta" -> updateAnswer(live.itemId) { it.copy(text = it.text + delta.str("text").orEmpty()) }
                    "thinking_delta" -> updateStep(live) { step ->
                        (step as? ThinkingStep)?.let { it.copy(text = it.text + delta.str("thinking").orEmpty()) } ?: step
                    }
                }
            }
            "content_block_stop" -> {
                val live = liveByIndex[event.int("index") ?: return] ?: return
                when (live.kind) {
                    "text" -> updateAnswer(live.itemId) { it.copy(streaming = false) }
                    "thinking" -> updateStep(live) { step ->
                        (step as? ThinkingStep)?.takeIf { it.running }?.copy(
                            running = false,
                            seconds = ((now - live.startedAt) / 1000L).toInt().coerceAtLeast(1),
                        ) ?: step
                    }
                }
            }
        }
    }

    /**
     * 一条完整的 assistant 消息（实时模式下每个内容块各来一条）。
     * [history] 表示来自会话记录；之后实时送来的同一块内容会被认出来并丢掉。
     */
    fun applyAssistant(message: JsonObject, history: Boolean = false) {
        val messageId = message.str("id") ?: nextId("m")
        val order = liveOrder[messageId]
        for (element in message.arr("content").orEmpty()) {
            val block = element as? JsonObject ?: continue
            val kind = block.str("type") ?: continue
            val live = order?.let { list ->
                var cursor = finalizedCount[messageId] ?: 0
                while (cursor < list.size && list[cursor].kind != kind) cursor++
                list.getOrNull(cursor)?.also { finalizedCount[messageId] = cursor + 1 }
            }
            when (kind) {
                "text" -> {
                    val text = block.str("text").orEmpty()
                    val duplicate = !history && historyTexts[messageId]?.contains(text) == true
                    if (history) historyTexts.getOrPut(messageId) { HashSet() }.add(text)
                    if (live != null) {
                        if (duplicate) items.removeAll { it.id == live.itemId } else updateAnswer(live.itemId) { it.copy(text = text, streaming = false) }
                    } else if (text.isNotBlank() && !duplicate) {
                        items.add(AnswerItem(nextId(messageId), text, streaming = false))
                    }
                }
                "thinking" -> {
                    val text = block.str("thinking").orEmpty()
                    if (live != null) {
                        updateStep(live) { step -> (step as? ThinkingStep)?.let { if (text.isNotEmpty()) it.copy(text = text) else it } ?: step }
                    } else if (text.isNotBlank()) {
                        appendStep(ThinkingStep(nextId(messageId), text, running = false, seconds = null))
                    }
                }
                "tool_use" -> {
                    val input = block.obj("input")
                    if (live != null) {
                        updateStep(live) { step -> (step as? ToolStep)?.copy(input = input) ?: step }
                    } else {
                        val stepId = block.str("id") ?: nextId(messageId)
                        if (knownToolIds.add(stepId)) {
                            appendStep(ToolStep(stepId, block.str("name").orEmpty(), input, ToolStatus.Running, null))
                        } else {
                            updateToolStep(stepId) { it.copy(input = it.input ?: input) }
                        }
                    }
                }
            }
        }
    }

    /** user 消息：工具结果回填到对应步骤；[replay] 时文字内容是用户当时的输入。 */
    fun applyUser(message: JsonObject, replay: Boolean) {
        val blocks = message.arr("content").orEmpty()
        if (replay) {
            val images = blocks.count { (it as? JsonObject)?.str("type") == "image" }
            val text = blocks.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }.joinToString("\n").trim()
            if (images > 0 || text.isNotEmpty()) addUser(text, imageCount = images)
        }
        for (element in blocks) {
            val block = element as? JsonObject ?: continue
            when (block.str("type")) {
                "tool_result" -> {
                    val toolUseId = block.str("tool_use_id") ?: continue
                    val failed = block.bool("is_error") == true
                    val result = block["content"].flattenText()
                    updateToolStep(toolUseId) { it.copy(status = if (failed) ToolStatus.Failed else ToolStatus.Success, result = result) }
                }
            }
        }
    }

    /**
     * 一轮结束（或进程退出）：把还显示为进行中的内容收口。
     * [keepRunningTools] 用于载入历史后还要接着跟读的情况：最后那几步其实还在跑。
     */
    fun finishTurn(keepRunningTools: Boolean = false) {
        for (i in items.indices) {
            when (val item = items[i]) {
                is AnswerItem -> if (item.streaming) items[i] = item.copy(streaming = false)
                is WorkItem -> if (item.running) {
                    items[i] = item.copy(steps = item.steps.map { step ->
                        when {
                            step is ThinkingStep && step.running -> step.copy(running = false)
                            step is ToolStep && step.running && !keepRunningTools -> step.copy(status = ToolStatus.Failed, result = step.result ?: "已中断")
                            else -> step
                        }
                    })
                }
                else -> Unit
            }
        }
        items.removeAll { it is AnswerItem && it.text.isBlank() }
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
                items[i] = item.copy(steps = item.steps.map { if (it.id == live.stepId) transform(it) else it })
                return
            }
        }
    }

    private inline fun updateToolStep(toolUseId: String, transform: (ToolStep) -> ToolStep) {
        for (i in items.indices.reversed()) {
            val item = items[i] as? WorkItem ?: continue
            if (item.steps.any { it.id == toolUseId }) {
                items[i] = item.copy(steps = item.steps.map { if (it is ToolStep && it.id == toolUseId) transform(it) else it })
                return
            }
        }
    }
}
