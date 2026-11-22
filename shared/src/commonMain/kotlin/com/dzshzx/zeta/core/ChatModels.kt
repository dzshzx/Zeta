package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** 聊天列表里的一行。全部不可变，更新时整项替换。 */
@Immutable
sealed interface ChatItem {
    val id: String
}

/** [files] 是随消息送进 Linux 环境的附件路径。[uuid] 是这条消息在会话记录里的 uuid，改写和分支时用；不知道时为 null。 */
@Immutable
data class UserItem(
    override val id: String,
    val text: String,
    val images: List<ImageRef> = emptyList(),
    val files: List<String> = emptyList(),
    val uuid: String? = null,
) : ChatItem

/** 聊天里的一张图片，显示时才载入；[key] 兼作缓存键。 */
@Immutable
sealed interface ImageRef {
    val key: String
}

/** 数据就在手边：刚发出的附件、实时收到的工具结果、data: 地址。 */
@Immutable class InlineImage(override val key: String, val base64: String) : ImageRef

/** 存在会话记录里的图片，[ref] 是助手脚本给出的位置。 */
@Immutable
data class SessionImage(val file: String, val ref: String) : ImageRef {
    override val key: String
        get() = "s:$file:$ref"
}

/** Linux 环境里的一个图片文件（回答里用 Markdown 引用的）。 */
@Immutable
data class FileImage(val path: String) : ImageRef {
    override val key: String
        get() = "f:$path"
}

/** 回答里引用的网络图片。 */
@Immutable
data class RemoteImage(val url: String) : ImageRef {
    override val key: String
        get() = "u:$url"
}

/** Claude Code 报告的一个斜杠命令或技能。 */
@Immutable
data class SlashCommand(val name: String, val description: String, val argumentHint: String)

/** 斜杠命令清单：Claude Code 在 initialize 的应答里报的 commands，也是存进设置的各主机那份（[slashCommandsJson]）。没有名字的项跳过。 */
fun parseSlashCommands(list: JsonArray): List<SlashCommand> = list.mapNotNull {
    (it as? JsonObject)?.let { c ->
        SlashCommand(
            c.str("name") ?: return@let null,
            c.str("description").orEmpty(),
            c.str("argumentHint").orEmpty(),
        )
    }
}

/** 斜杠命令清单写回和 Claude Code 报的同样形状的 JSON 数组，[parseSlashCommands] 读回。 */
fun slashCommandsJson(list: List<SlashCommand>): JsonArray = buildJsonArray {
    for (c in list) add(
        buildJsonObject {
            put("name", c.name)
            put("description", c.description)
            put("argumentHint", c.argumentHint)
        },
    )
}

/** 各主机的斜杠命令清单存进设置的写法：JSON 对象，主机（[Backends]）→ [slashCommandsJson] 的数组，[decodeHostCommands] 读回。 */
fun encodeHostCommands(hosts: Map<String, List<SlashCommand>>): String =
    JsonObject(hosts.mapValues { (_, list) -> slashCommandsJson(list) }).toString()

/** [encodeHostCommands] 的逆操作；存坏了读成空的，各主机下次起进程时再存。 */
fun decodeHostCommands(text: String?): Map<String, List<SlashCommand>> = runCatching {
    (Json.parseToJsonElement(text ?: return emptyMap()) as JsonObject).entries.associate {
        (backend, list) ->
        backend to parseSlashCommands(list as JsonArray)
    }
}
    .getOrDefault(emptyMap())

/** 发送时把附件（见 [AttachedPaths]）写成消息开头的几行，Claude 据此去读。 */
fun withAttachedFiles(text: String, files: List<String>): String =
    if (files.isEmpty()) text
    else
        files.joinToString("\n") { AttachedPaths.header(it) } +
            if (text.isEmpty()) "" else "\n\n$text"

/** [withAttachedFiles] 的逆操作：从历史消息里拆出附件和正文。 */
fun splitAttachedFiles(text: String): Pair<List<String>, String> {
    if (!text.startsWith("[Attached ")) return emptyList<String>() to text
    val files = ArrayList<String>()
    val lines = text.lines()
    var index = 0
    while (index < lines.size) {
        files.add(AttachedPaths.parseHeader(lines[index]) ?: break)
        index++
    }
    return files to lines.drop(index).joinToString("\n").trim()
}

@Immutable
data class AnswerItem(override val id: String, val text: String, val streaming: Boolean) : ChatItem

/** 连续的思考与工具调用折叠成一组，对应 Eta 的“已完成 N 个步骤”。 */
@Immutable
data class WorkItem(override val id: String, val steps: List<WorkStep>) : ChatItem {
    val running: Boolean
        get() = steps.any { it.running }
}

@Immutable
data class NoticeItem(override val id: String, val text: String, val error: Boolean) : ChatItem

@Immutable
sealed interface WorkStep {
    val id: String
    val running: Boolean
}

@Immutable
data class ThinkingStep(
    override val id: String,
    val text: String,
    override val running: Boolean,
    val seconds: Int?,
) : WorkStep

enum class ToolStatus {
    Running,
    Success,
    Failed,
}

@Immutable
data class ToolStep(
    override val id: String,
    val name: String,
    val input: JsonObject?,
    val status: ToolStatus,
    val result: String?,
    /** 工具结果里带的图片（读图片文件、截图类工具）。 */
    val images: List<ImageRef> = emptyList(),
    /** 改文件工具的结果里 Claude Code 给出的改动（带真实行号）。 */
    val patch: EditPatch? = null,
) : WorkStep {
    override val running: Boolean
        get() = status == ToolStatus.Running
}

/** 模型选择器里的一项（Claude Code 报的）。 */
@Immutable
data class ModelOption(
    val value: String,
    val displayName: String,
    val description: String,
    /** 这一项现在对应的完整模型 ID（initialize 的 resolvedModel）；按模型存的默认思考强度以它为键。没报时为 null。 */
    val resolvedModel: String? = null,
    /** 能选的思考强度（supportedEffortLevels，不支持时是空的）；没报时为 null，按 [EffortLevels] 全部几档算。 */
    val effortLevels: List<String>? = null,
)

/** initialize 应答 models 里的一项；没有 value 的不算。supportsEffort 为 false 时能选的思考强度是空的。 */
fun modelOption(m: JsonObject): ModelOption? =
    ModelOption(
        value = m.str("value") ?: return null,
        displayName = m.str("displayName").orEmpty(),
        description = m.str("description").orEmpty(),
        resolvedModel = m.str("resolvedModel")?.takeIf { it.isNotBlank() },
        effortLevels =
            if (m.bool("supportsEffort") == false) emptyList()
            else
                m.arr("supportedEffortLevels")?.mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull
                },
    )

/** 还没连上进程、拿不到 Claude Code 的清单时先给的几个常用别名（Claude Code 自己解析）。 */
val FallbackModels =
    listOf(
        ModelOption(Conversation.DEFAULT_MODEL, "默认", ""),
        ModelOption("fable", "Fable", ""),
        ModelOption("opus", "Opus", ""),
        ModelOption("sonnet", "Sonnet", ""),
        ModelOption("haiku", "Haiku", ""),
    )

/**
 * 选择器里模型 [value] 这一项的名字，模型菜单、设置页用它：默认模型（null 或 "default"）写明它现在指向哪个，比如「默认（Opus 5.5）」， 清单 [models]
 * 还没报它指向谁时只写「默认」；别的项清单里有显示名的用它，完整 ID 写成「Opus 5.5」，别的原样。
 */
fun modelLabel(value: String?, models: List<ModelOption>): String =
    when {
        value == null || value == Conversation.DEFAULT_MODEL ->
            modelName(Conversation.DEFAULT_MODEL, models)?.let { "默认（$it）" } ?: "默认"
        else ->
            models.firstOrNull { it.value == value }?.displayName?.ifBlank { null }
                ?: readableModelId(value)
    }

/**
 * 模型 [value] 实际是哪个模型，写成「Opus 5.5」这样的名字，顶栏和设置首页用它：别名（opus、default）按清单 [models] 换成它指向的完整 ID
 * 再写；清单里没有它指向谁的别名写显示名，这时默认模型说不出是哪个，是 null。
 */
fun modelName(value: String, models: List<ModelOption>): String? {
    val option = models.firstOrNull { it.value == value }
    return when {
        option?.resolvedModel != null -> readableModelId(option.resolvedModel)
        value == Conversation.DEFAULT_MODEL -> null
        else -> option?.displayName?.ifBlank { null } ?: readableModelId(value)
    }
}

private val ModelName = Regex("([A-Z][a-z]+) (\\d+(?:\\.\\d{1,2})*)")

/**
 * [readableModelId] 反过来：终端里 /model 打出来的显示名（Fable 5.2）对应的模型 ID（claude-fable-5-2）；不是这个样子的（别家的模型、带 1M
 * 之类说明的）是 null。
 */
fun modelIdOfName(name: String): String? {
    val (family, version) = ModelName.matchEntire(name)?.destructured ?: return null
    return "claude-" + family.lowercase() + "-" + version.replace('.', '-')
}

private val ModelId = Regex("claude-([a-z]+)-(\\d+(?:-\\d{1,2})*)(?:-\\d{8})?(\\[1m])?")

/**
 * Claude Code 报的完整模型
 * ID（claude-opus-5-5、claude-haiku-4-5-20251001、claude-opus-5-5[1m]）写成人看的名字；认不出的原样。
 */
fun readableModelId(id: String): String {
    val match = ModelId.matchEntire(id) ?: return id
    val (family, version, wide) = match.destructured
    return family.replaceFirstChar { it.uppercase() } +
        " " +
        version.replace('-', '.') +
        if (wide.isNotEmpty()) " (1M)" else ""
}

/**
 * 完整模型 ID 的系列和版本：claude-opus-4-6 是 opus、[4, 6]，claude-opus-5-5[1m] 是 opus[1m]、[5,
 * 5]；别名（opus、default）和认不出的是 null。
 */
private fun modelSeries(id: String): Pair<String, List<Int>>? {
    val match = ModelId.matchEntire(id) ?: return null
    val (family, version, wide) = match.destructured
    return family + wide to version.split('-').map { it.toInt() }
}

private val VersionOrder =
    Comparator<List<Int>> { a, b ->
        a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) }
            ?: a.size.compareTo(b.size)
    }

/** 模型菜单的两层：[latest] 直接列出，[older] 收进「更多模型」。 */
@Immutable
data class ModelMenu<T>(val latest: List<T>, val older: List<T>) {
    fun <R> map(transform: (T) -> R): ModelMenu<R> =
        ModelMenu(latest.map(transform), older.map(transform))
}

/**
 * 模型菜单怎么分层，和 Claude 应用一样只直接列各系列最新的：「默认」和 Claude Code 报的别名（opus、fable、sonnet、haiku 等，
 * 总是指向那个系列最新的）照它给的顺序放上层；钉死版本的完整 ID（claude-opus-4-6）是旧版本，收进 [ModelMenu.older]，
 * 按系列（上层的顺序）、版本从新到旧排。没有别名的系列，最新的那个完整 ID 也放上层；和别名指向同一个模型的完整 ID 不重复列。
 */
fun modelMenu(models: List<ModelOption>): ModelMenu<ModelOption> {
    val parsed = models.associateWith { modelSeries(it.value) }
    val (pinned, aliases) = models.partition { parsed[it] != null }
    val aliasTargets = aliases.mapNotNullTo(HashSet()) { it.resolvedModel }
    // 系列：完整 ID 看它自己；别名看它指向的完整 ID，没报就按别名本身（opus 就是 opus 系列）
    fun seriesOf(option: ModelOption): String? =
        parsed[option]?.first
            ?: option.resolvedModel?.let(::modelSeries)?.first
            ?: option.value.takeIf { it != Conversation.DEFAULT_MODEL }
    fun version(option: ModelOption): List<Int> = parsed.getValue(option)!!.second
    val covered = aliases.mapNotNullTo(HashSet(), ::seriesOf)
    val newestUncovered =
        pinned
            .filter { seriesOf(it) !in covered }
            .groupBy(::seriesOf)
            .values
            .mapTo(HashSet()) { group -> group.maxWith(compareBy(VersionOrder, ::version)) }
    val latest = models.filter { it in aliases || it in newestUncovered }
    // 旧版本的系列都在上层出现过：有别名，或者那个系列最新的在上层
    val order = latest.mapNotNull(::seriesOf).distinct()
    val older =
        pinned
            .filter { it !in newestUncovered && it.value !in aliasTargets }
            .sortedWith(
                compareBy<ModelOption> { order.indexOf(seriesOf(it)) }
                    .thenByDescending(VersionOrder, ::version),
            )
    return ModelMenu(latest, older)
}

/** 历史会话的概要（来自 ~/.claude/projects 下的记录文件）。 */
@Immutable
data class SessionSummary(
    val id: String,
    val file: String,
    val cwd: String,
    val title: String,
    val preview: String,
    val updated: Long,
    /** 正被别的 Claude Code 进程占用（比如终端里开着）。 */
    val external: Boolean = false,
    /** 已归档：不在主列表里显示。 */
    val archived: Boolean = false,
    /** 会话记录在哪台主机上（[Backends]）。 */
    val backend: String = Backends.LOCAL,
) {
    /** 置顶的设置里记它用的键（[pinKey]）。 */
    val pinKey: String
        get() = pinKey(backend, id)

    companion object {
        /** 置顶记的键（[HostKeys]），各台主机的置顶分得开。 */
        fun pinKey(backend: String, id: String): String = HostKeys.key(backend, id)

        /** 置顶的键 [key] 是主机 [backend] 上的会话的话，那个会话号；别的主机的是 null。 */
        fun pinnedId(key: String, backend: String): String? = HostKeys.nameOn(key, backend)
    }
}

/** 各台主机的东西记在同一份设置里时用的键（置顶的会话、各模型的上下文窗口）：手机本机的就是名字本身，远程主机的前面加主机编号和 /。 */
object HostKeys {
    fun key(backend: String, name: String): String =
        if (backend == Backends.LOCAL) name else "$backend/$name"

    /** 键 [key] 是主机 [backend] 的话，去掉主机那一段的名字；别的主机的是 null。 */
    fun nameOn(key: String, backend: String): String? =
        if (backend == Backends.LOCAL) key.takeIf { '/' !in it }
        else key.removePrefix("$backend/").takeIf { it.length < key.length }

    /** 键 [key] 是哪台主机的。 */
    fun hostOf(key: String): String = key.substringBefore('/', Backends.LOCAL)
}

private val summaryJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** zeta-helper 的 list / lookup 输出：一行一个会话概要，别的行跳过。[backend] 是在哪台主机上读的。 */
fun parseSessionSummaries(stdout: String, backend: String): List<SessionSummary> =
    stdout
        .lineSequence()
        .mapNotNull { line ->
            runCatching { summaryJson.parseToJsonElement(line) as? JsonObject }
                .getOrNull()
                ?.let { sessionSummaryOf(it, backend) }
        }
        .toList()

/** 会话概要的一项（list、lookup 的一行，serve 推来的 zeta_sessions 里的一项）；缺 id 或文件的是 null。 */
fun sessionSummaryOf(o: JsonObject, backend: String): SessionSummary? =
    SessionSummary(
        id = o.str("id") ?: return null,
        file = o.str("file") ?: return null,
        cwd = o.str("cwd").orEmpty(),
        title = o.str("title").orEmpty(),
        preview = o.str("preview").orEmpty(),
        updated = (o["updated"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
        external = o.bool("external") == true,
        archived = o.bool("archived") == true,
        backend = backend,
    )

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

/** 工具结果的 content：字符串，或若干 text 块。 */
fun JsonElement?.flattenText(): String =
    when (this) {
        is JsonPrimitive -> contentOrNull.orEmpty()
        is JsonArray -> mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n")
        else -> ""
    }
