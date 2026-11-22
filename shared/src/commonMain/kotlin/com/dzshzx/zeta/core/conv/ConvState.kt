package com.dzshzx.zeta.core.conv

import androidx.compose.runtime.Immutable
import com.dzshzx.zeta.core.BackgroundTask
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.ChatUsage
import com.dzshzx.zeta.core.CodeRewind
import com.dzshzx.zeta.core.ContextLevel
import com.dzshzx.zeta.core.ElicitField
import com.dzshzx.zeta.core.ElicitationRequest
import com.dzshzx.zeta.core.ModelOption
import com.dzshzx.zeta.core.Occupant
import com.dzshzx.zeta.core.PermissionRequest
import com.dzshzx.zeta.core.Question
import com.dzshzx.zeta.core.QuestionOption
import com.dzshzx.zeta.core.SessionStatus
import com.dzshzx.zeta.core.SlashCommand
import com.dzshzx.zeta.core.TaskKind
import com.dzshzx.zeta.core.TaskOutput
import com.dzshzx.zeta.core.TaskState
import com.dzshzx.zeta.core.TodoEntry
import com.dzshzx.zeta.core.TodoList
import com.dzshzx.zeta.core.TodoStatus
import com.dzshzx.zeta.core.TokenCounts
import com.dzshzx.zeta.core.TurnUsage
import com.dzshzx.zeta.core.UserPrompt
import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.double
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.long
import com.dzshzx.zeta.core.modelOption
import com.dzshzx.zeta.core.obj
import com.dzshzx.zeta.core.parseSlashCommands
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 核心推来的一个对话的状态（conv.state 合并到现在的样子，字段见 docs/core.md「对话」、helper/conv/state.go 的 stateFields）。
 * 没推来的字段是默认值。 每次推送都换一份新的，拿着它不会被后来的推送改掉。
 */
@Immutable
data class ConvState(
    /** 这个对话在核心里的标识（整个核心里不重复），断线重连时按它重新打开；还没推来时是空串。 */
    val key: String = "",
    /** 会话号；新对话第一条消息之前没有。 */
    val session: String? = null,
    val title: String = "",
    val cwd: String = "",
    /** 接着 Claude Code 进程。 */
    val process: Boolean = false,
    /** 在回答。 */
    val busy: Boolean = false,
    /** 等待指示旁的一句，比如正在重试。 */
    val status: String = "",
    /** 会话正被 Zeta 之外的进程占着。 */
    val blocked: Boolean = false,
    /** [blocked] 时占着会话的那个进程的样子（核心每 3 秒查一次，变了才推）；不 blocked 或还没查到时为 null。 */
    val occupant: Occupant? = null,
    val loadingHistory: Boolean = false,
    /** 会话记录里还没载入的更早事件数，0 是全载入了。 */
    val earlier: Int = 0,
    /** 正在载入更早的一段（核心第二批的字段，没推来时为 false）。 */
    val earlierLoading: Boolean = false,
    /** Claude 当前的任务清单（核心归并好的）；没有清单时为 null。 */
    val todos: TodoList? = null,
    val tasks: List<BackgroundTask> = emptyList(),
    /** 排队等发的消息。 */
    val queued: List<ConvMessage> = emptyList(),
    /** 立即插话发出去了，还没有结果。 */
    val steering: Boolean = false,
    /** 等用户回应的请求，按到达顺序，界面弹第一个。 */
    val prompts: List<UserPrompt> = emptyList(),
    /** 撤回放回输入框、还没取走的那条（取走用 [CoreConversation.takeRecalled]）。 */
    val recalled: ConvMessage? = null,
    /** 这个对话指定的模型；null 是用默认。 */
    val model: String? = null,
    /** Claude Code 报的模型，还没报时为 null。 */
    val reportedModel: String? = null,
    /** 这个对话指定的思考强度；null 是用默认。 */
    val effort: String? = null,
    val mode: ConvMode = ConvMode(),
    /** Claude Code 报的斜杠命令；进程还没起过时是核心按工作目录记着的上一份，都没有时是空的。 */
    val commands: List<SlashCommand> = emptyList(),
    /** Claude Code 报的可选模型，进程还没起过时是空的（界面用 FallbackModels）。 */
    val models: List<ModelOption> = emptyList(),
    val usage: ChatUsage = ChatUsage(),
    /** 能还原到某条消息之前（核心第二批的字段，没推来时为 false）。 */
    val canRewind: Boolean = false,
    /** 正在等 Claude Code 剪会话或还原代码（谁发起的都算）：这期间输入栏不收字。 */
    val rewinding: Boolean = false,
    /** 最近一次有动静的时刻（Unix 毫秒，取整到秒，主机的时钟），界面据此显示「已等待 N 秒」；0 是还没有过。 */
    val lastActivity: Long = 0,
    /** 模型由 Claude Code 照会话记录定（接着已有会话或是分支，又没在这个对话里指定模型）：模型菜单这时不勾任何一项。 */
    val modelFromSession: Boolean = false,
    /** 接着的托管进程的标识（托管目录 hosts/<它>），没接着进程时为 null。 */
    val hostKey: String? = null,
)

/**
 * 权限模式（conv.state 的 mode）：[user] 是用户选的，[process] 是进程此刻实际在用的（计划模式批准后等会变），[shown] 是输入栏显示的
 * （[user]，没有时是 Claude Code 设置里的默认，核心算好的）；不知道时是空串。
 */
@Immutable
data class ConvMode(val user: String = "", val process: String = "", val shown: String = "")

/** 排队的、撤回的一条消息（helper/conv/state.go 的 messageView）。 */
@Immutable
data class ConvMessage(
    val uuid: String,
    val text: String,
    val images: List<ConvImage> = emptyList(),
    /** 主机上的路径。 */
    val files: List<String> = emptyList(),
)

/** 随消息发的一张图：base64 的数据和它的 MIME 类型（helper/conv/message.go 的 Image）。 */
@Immutable data class ConvImage(val mediaType: String, val data: String)

/** 一个字段的读法：把推来的值放进状态。 */
private typealias FieldReader = (ConvState, JsonElement) -> ConvState

/** conv.state 各字段怎么读，键是字段名。没列的字段（核心以后加的）不管。Go 一侧把空的模型、思考强度写成空串， 这里读成 null（用默认）。 */
private val fieldReaders: Map<String, FieldReader> =
    mapOf(
        "key" to { s, v -> s.copy(key = v.text().orEmpty()) },
        "session" to { s, v -> s.copy(session = v.text()) },
        "title" to { s, v -> s.copy(title = v.text().orEmpty()) },
        "cwd" to { s, v -> s.copy(cwd = v.text().orEmpty()) },
        "process" to { s, v -> s.copy(process = v.flag()) },
        "busy" to { s, v -> s.copy(busy = v.flag()) },
        "status" to { s, v -> s.copy(status = v.text().orEmpty()) },
        "blocked" to { s, v -> s.copy(blocked = v.flag()) },
        "occupant" to { s, v -> s.copy(occupant = (v as? JsonObject)?.let(Occupant::of)) },
        "loading_history" to { s, v -> s.copy(loadingHistory = v.flag()) },
        "earlier" to { s, v -> s.copy(earlier = (v as? JsonPrimitive)?.intOrNull ?: 0) },
        "earlier_loading" to { s, v -> s.copy(earlierLoading = v.flag()) },
        "todos" to { s, v -> s.copy(todos = todoListOf(v.objects())) },
        "tasks" to { s, v -> s.copy(tasks = v.objects().map(::backgroundTaskOf)) },
        "queued" to { s, v -> s.copy(queued = v.objects().map(::convMessageOf)) },
        "steering" to { s, v -> s.copy(steering = v.flag()) },
        "prompts" to { s, v -> s.copy(prompts = v.objects().mapNotNull(::userPromptOf)) },
        "recalled" to { s, v -> s.copy(recalled = (v as? JsonObject)?.let(::convMessageOf)) },
        "model" to { s, v -> s.copy(model = v.text()) },
        "reported_model" to { s, v -> s.copy(reportedModel = v.text()) },
        "effort" to { s, v -> s.copy(effort = v.text()) },
        "mode" to { s, v -> s.copy(mode = modeOf(v as? JsonObject)) },
        "commands" to
            { s, v ->
                s.copy(commands = (v as? JsonArray)?.let(::parseSlashCommands).orEmpty())
            },
        "models" to { s, v -> s.copy(models = v.objects().mapNotNull(::modelOption)) },
        "usage" to
            { s, v ->
                s.copy(usage = (v as? JsonObject)?.let(::chatUsageOf) ?: ChatUsage())
            },
        "can_rewind" to { s, v -> s.copy(canRewind = v.flag()) },
        "rewinding" to { s, v -> s.copy(rewinding = v.flag()) },
        "last_activity" to
            { s, v ->
                s.copy(lastActivity = (v as? JsonPrimitive)?.longOrNull ?: 0)
            },
        "model_from_session" to { s, v -> s.copy(modelFromSession = v.flag()) },
        "host_key" to { s, v -> s.copy(hostKey = v.text()) },
    )

/** 把一条 conv.state 里的字段放进 [base]（整份时 [base] 是空的 [ConvState]）。type、conv、full 这几个键不是字段，读的时候跳过。 */
internal fun mergeState(base: ConvState, push: JsonObject): ConvState =
    push.entries.fold(base) { state, (key, value) ->
        fieldReaders[key]?.invoke(state, value) ?: state
    }

/** 字符串值；空串、null、不是字符串时是 null。 */
private fun JsonElement.text(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.ifEmpty { null }

private fun JsonElement.flag(): Boolean = (this as? JsonPrimitive)?.booleanOrNull == true

private fun JsonElement.objects(): List<JsonObject> =
    (this as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

private fun JsonObject.strings(key: String): List<String> =
    arr(key).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun JsonObject.textOrNull(key: String): String? = str(key)?.ifEmpty { null }

private fun modeOf(o: JsonObject?): ConvMode =
    ConvMode(o?.str("user").orEmpty(), o?.str("process").orEmpty(), o?.str("shown").orEmpty())

internal fun convMessageOf(o: JsonObject): ConvMessage =
    ConvMessage(
        uuid = o.str("uuid").orEmpty(),
        text = o.str("text").orEmpty(),
        images =
            o.arr("images").orEmpty().filterIsInstance<JsonObject>().map {
                ConvImage(it.str("media_type").orEmpty(), it.str("data").orEmpty())
            },
        files = o.strings("files"),
    )

/** 任务清单（helper/conv/todos.go 的 todo）；空的是没有清单。认不出的状态当作还没做。 */
private fun todoListOf(entries: List<JsonObject>): TodoList? =
    entries
        .map {
            TodoEntry(
                key = it.str("key").orEmpty(),
                content = it.str("content").orEmpty(),
                activeForm = it.str("active_form").orEmpty(),
                status =
                    when (it.str("status")) {
                        "in_progress" -> TodoStatus.InProgress
                        "completed" -> TodoStatus.Completed
                        else -> TodoStatus.Pending
                    },
            )
        }
        .ifEmpty { null }
        ?.let(::TodoList)

/** 后台任务（helper/tasks/task.go 的 Task）。running、readable 两项 [BackgroundTask] 自己算，不读。 */
internal fun backgroundTaskOf(o: JsonObject): BackgroundTask =
    BackgroundTask(
        id = o.str("id").orEmpty(),
        type = o.str("type").orEmpty(),
        kind =
            TaskKind.entries.firstOrNull { it.name.lowercase() == o.str("kind") } ?: TaskKind.Other,
        description = o.str("description").orEmpty(),
        command = o.textOrNull("command"),
        agentType = o.textOrNull("agent_type"),
        toolUseId = o.textOrNull("tool_use_id"),
        startedAt = o.long("started_at") ?: 0,
        endedAt = o.long("ended_at"),
        state =
            TaskState.entries.firstOrNull { it.name.lowercase() == o.str("state") }
                ?: TaskState.Running,
        stopping = o.bool("stopping") == true,
        progress = o.textOrNull("progress"),
        toolUses = o.int("tool_uses"),
        summary = o.textOrNull("summary"),
        error = o.textOrNull("error"),
    )

/** conv.task_output 的 result（helper/tasks/task.go 的 Output）。 */
internal fun taskOutputOf(o: JsonObject): TaskOutput =
    TaskOutput(
        text = o.str("text").orEmpty(),
        totalBytes = o.long("total_bytes") ?: 0,
        truncated = o.bool("truncated") == true,
        error = o.textOrNull("error"),
    )

/** 用量（helper/usage/snapshot.go 的 Usage）：合计、费用、比例、提醒档位都照核心算好的读。 */
internal fun chatUsageOf(o: JsonObject): ChatUsage =
    ChatUsage(
        contextTokens = o.long("context_tokens"),
        contextEstimated = o.bool("context_estimated") == true,
        contextWindow = o.long("context_window"),
        model = o.textOrNull("model"),
        turns = o.arr("turns").orEmpty().filterIsInstance<JsonObject>().map(::turnUsageOf),
        compactions = o.int("compactions") ?: 0,
        appliedEffort = o.textOrNull("applied_effort"),
        autoCompact = o.bool("auto_compact"),
        compactAt = o.long("compact_at"),
        total = tokensOf(o.obj("total")),
        totalCost = o.double("total_cost"),
        costPartial = o.bool("cost_partial") == true,
        costEstimated = o.bool("cost_estimated") == true,
        fraction = o.double("fraction")?.toFloat(),
        compactFraction = o.double("compact_fraction")?.toFloat(),
        level =
            ContextLevel.entries.firstOrNull { it.name.lowercase() == o.str("level") }
                ?: ContextLevel.Normal,
    )

private fun tokensOf(t: JsonObject?): TokenCounts =
    TokenCounts(
        t?.long("input") ?: 0,
        t?.long("output") ?: 0,
        t?.long("cache_read") ?: 0,
        t?.long("cache_write") ?: 0,
        t?.long("total") ?: 0,
    )

private fun turnUsageOf(o: JsonObject): TurnUsage =
    TurnUsage(
        tokens = tokensOf(o.obj("tokens")),
        costUsd = o.double("cost_usd"),
        durationMs = o.long("duration_ms"),
        costEstimated = o.bool("cost_estimated") == true,
    )

/**
 * 等用户回应的请求（helper/prompt/prompt.go 的 Prompt）。notice、asks_input、is_plan、suggestion_lines、takes_notes
 * 照核心给的读。认不出的种类是 null。
 */
internal fun userPromptOf(o: JsonObject): UserPrompt? {
    val id = o.str("request_id") ?: return null
    return when (o.str("kind")) {
        "permission" ->
            PermissionRequest(
                requestId = id,
                toolName = o.str("tool_name").orEmpty(),
                input = o.obj("input") ?: JsonObject(emptyMap()),
                questions =
                    o.arr("questions").orEmpty().filterIsInstance<JsonObject>().map(::questionOf),
                suggestionLines = o.strings("suggestion_lines"),
                reason = o.textOrNull("reason"),
                agentLabel = o.textOrNull("agent_label"),
                toolUseId = o.textOrNull("tool_use_id"),
                isPlan = o.bool("is_plan") == true,
                notice = o.str("notice").orEmpty(),
                asksInput = o.bool("asks_input") == true,
            )
        "elicitation" ->
            ElicitationRequest(
                requestId = id,
                server = o.str("server").orEmpty(),
                message = o.str("message").orEmpty(),
                url = o.textOrNull("url"),
                fields =
                    o.arr("fields").orEmpty().filterIsInstance<JsonObject>().map(::elicitFieldOf),
                notice = o.str("notice").orEmpty(),
                asksInput = o.bool("asks_input") != false,
            )
        else -> null
    }
}

private fun questionOf(o: JsonObject): Question =
    Question(
        question = o.str("question").orEmpty(),
        header = o.str("header").orEmpty(),
        options =
            o.arr("options").orEmpty().filterIsInstance<JsonObject>().map {
                // preview 为 null 是没有预览，和空字符串的预览不同
                QuestionOption(
                    it.str("label").orEmpty(),
                    it.str("description").orEmpty(),
                    it.str("preview"),
                )
            },
        multiSelect = o.bool("multi_select") == true,
        takesNotes = o.bool("takes_notes") == true,
    )

private fun elicitFieldOf(o: JsonObject): ElicitField =
    ElicitField(
        key = o.str("key").orEmpty(),
        title = o.str("title").orEmpty(),
        description = o.str("description").orEmpty(),
        required = o.bool("required") == true,
        kind =
            ElicitField.Kind.entries.firstOrNull { it.name.lowercase() == o.str("kind") }
                ?: ElicitField.Kind.Text,
        choices =
            o.arr("choices").orEmpty().filterIsInstance<JsonObject>().map {
                ElicitField.Choice(it.str("value").orEmpty(), it.str("label").orEmpty())
            },
        default = o.strings("default"),
        min = o.double("min"),
        max = o.double("max"),
        minLength = o.int("min_length"),
        maxLength = o.int("max_length"),
        format = o.textOrNull("format"),
    )

/** conv.turn：一轮的起止。[outcome]、[text] 只有结束时才有。 */
@Immutable
data class ConvTurn(val finished: Boolean, val outcome: Outcome? = null, val text: String = "") {
    enum class Outcome {
        Done,
        Stopped,
        Failed,
        /** 进程意外没了。 */
        Interrupted,
    }
}

internal fun convTurnOf(o: JsonObject): ConvTurn =
    ConvTurn(
        finished = o.str("phase") == "finished",
        outcome = ConvTurn.Outcome.entries.firstOrNull { it.name.lowercase() == o.str("outcome") },
        text = o.str("text").orEmpty(),
    )

/** conv.subagent 里的 meta：子代理记录旁 meta.json 里界面要的几项。 */
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
)

/** 看着的一个子代理（conv.subagent 合并到现在的样子）。条目以外的字段每次推送都是整份。 */
@Immutable
data class ConvSubagent(
    /** 发起它的 Agent 调用。 */
    val toolUseId: String,
    /** 记录旁 meta.json 里的几项，还没读到时为 null。 */
    val meta: SubagentMeta? = null,
    /** 结局：Completed、Failed、Stopped；还没结束时为 null。 */
    val outcome: TaskState? = null,
    /** 交给它的任务。 */
    val prompt: String = "",
    val toolUses: Int = 0,
    val running: Boolean = false,
    val loaded: Boolean = false,
    /** 记录文件还没有。 */
    val missing: Boolean = false,
    /** 读失败的原因。 */
    val failure: String? = null,
    val file: String = "",
    /** 只留最近一段，前面略去了几条。 */
    val skipped: Int = 0,
    val items: List<ChatItem> = emptyList(),
)

/** conv.subagent 里条目以外的字段；条目由调用方按整份或替换合并后给 [items]。 */
internal fun convSubagentOf(toolUseId: String, o: JsonObject, items: List<ChatItem>): ConvSubagent =
    ConvSubagent(
        toolUseId = toolUseId,
        meta = o.obj("meta")?.let(::subagentMetaOf),
        outcome = TaskState.entries.firstOrNull { it.name.lowercase() == o.str("outcome") },
        prompt = o.str("prompt").orEmpty(),
        toolUses = o.int("tool_uses") ?: 0,
        running = o.bool("running") == true,
        loaded = o.bool("loaded") == true,
        missing = o.bool("missing") == true,
        failure = o.textOrNull("failure"),
        file = o.str("file").orEmpty(),
        skipped = o.int("skipped") ?: 0,
        items = items,
    )

private fun subagentMetaOf(o: JsonObject): SubagentMeta =
    SubagentMeta(
        agentId = o.str("agent_id").orEmpty(),
        agentType = o.textOrNull("agent_type"),
        description = o.textOrNull("description"),
        model = o.textOrNull("model"),
        finished = o.bool("finished") == true,
        stopped = o.bool("stopped") == true,
        background = o.bool("background"),
    )

/**
 * conv.activity 里一个会话的忙闲（helper/conv/activity.go 的 Activity）。[activity] 为 null 是进程没了；[turnEnd]
 * 是最后一轮结束的时刻 （Unix 毫秒），这个核心里还没结束过一轮时为 null。「有新回答没看」由调用方拿 [turnEnd] 算，这里不判断。
 */
@Immutable
data class SessionActivity(
    val activity: SessionStatus.Activity?,
    val tasks: Int = 0,
    val turnEnd: Long? = null,
)

/** conv.activity 的 sessions：会话号 → 忙闲。认不出的 activity 当作没有。 */
internal fun sessionActivitiesOf(o: JsonObject): Map<String, SessionActivity> =
    o.obj("sessions")
        .orEmpty()
        .mapNotNull { (session, value) ->
            (value as? JsonObject)?.let { a ->
                session to
                    SessionActivity(
                        activity =
                            SessionStatus.Activity.entries.firstOrNull {
                                it != SessionStatus.Activity.Unread &&
                                    it.name.lowercase() == a.str("activity")
                            },
                        tasks = a.int("tasks") ?: 0,
                        turnEnd = a.long("turn_end")?.takeIf { it > 0 },
                    )
            }
        }
        .toMap()

/** conv.code_changes 的 result（helper/conv/rewind.go 的 CodeChanges）。 */
internal fun codeChangesOf(o: JsonObject): CodeRewind.Changes =
    CodeRewind.Changes(o.strings("files"), o.int("insertions") ?: 0, o.int("deletions") ?: 0)
