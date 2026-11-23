package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import com.dzshzx.zeta.i18n.CoreStrings
import com.dzshzx.zeta.i18n.jsonArgs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** 聊天列表里的一行。全部不可变，更新时整项替换。 */
@Immutable
sealed interface ChatItem {
    val id: String
}

/**
 * [files] 是随消息送进 Linux 环境的附件路径。[uuid] 是这条消息在会话记录里的 uuid；不知道时为 null。
 *
 * 这一轮能做什么由核心算好（docs/core.md 的 conv.items）：[rewindable] 能编辑、重新生成、删除这一轮、还原代码；[forkable]
 * 能从这一轮之后分支；[roundLast] 是这一轮的操作行挂在哪一项下面，为 null 时只能从这条消息的菜单操作。真能做还要 conv.state 的 can_rewind。
 *
 * [peer] 不为 null 时这条是另一个 Claude Code 会话发来的（会话互通），值是发信会话的名字，[text] 是去掉外壳的正文；聊天页画成会话消息卡片，不挂用户消息的操作。
 */
@Immutable
data class UserItem(
    override val id: String,
    val text: String,
    val images: List<ImageRef> = emptyList(),
    val files: List<String> = emptyList(),
    val uuid: String? = null,
    val rewindable: Boolean = false,
    val forkable: Boolean = false,
    val roundLast: String? = null,
    val peer: String? = null,
) : ChatItem

/** 聊天里的一张图片，显示时才载入；[key] 兼作缓存键。 */
@Immutable
sealed interface ImageRef {
    val key: String
}

/** 数据就在手边：刚发出的附件、实时收到的工具结果、data: 地址。 */
@Immutable class InlineImage(override val key: String, val base64: String) : ImageRef

/**
 * 实时收到、条目里只带了键没带数据的图（打开对话时声明了 [ConvOptions.imageRefs]，核心推来的就是这样）：显示时经核心的 `conv.image` 按对话在核心里的标识
 * [conv]（conv.state 的 key）和图的键 [image] 取。[key] 就是图的键，和带着数据时的 [InlineImage] 一样。
 */
@Immutable
data class CoreImage(val conv: String, val image: String) : ImageRef {
    override val key: String
        get() = image
}

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

/** 斜杠命令清单：核心 conv.state 里的 commands（Claude Code 在 initialize 的应答里报的那份）。没有名字的项跳过。 */
fun parseSlashCommands(list: JsonArray): List<SlashCommand> = list.mapNotNull {
    (it as? JsonObject)?.let { c ->
        SlashCommand(
            c.str("name") ?: return@let null,
            c.str("description").orEmpty(),
            c.str("argumentHint").orEmpty(),
        )
    }
}

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

/**
 * 写给用户看的中间说明：带文字的思考块（Claude 在工具调用之间说它在做什么、接下来做什么），不折进工作组，同回答一样排正文， 但不带复制、朗读这些回答的操作（见
 * helper/transcript 的 KindThought）。
 */
@Immutable
data class ThoughtItem(override val id: String, val text: String, val streaming: Boolean) : ChatItem

/** 连续的思考与工具调用折叠成一组，对应 Eta 的“已完成 N 个步骤”。 */
@Immutable
data class WorkItem(override val id: String, val steps: List<WorkStep>) : ChatItem {
    val running: Boolean
        get() = steps.any { it.running }
}

/**
 * 一条提示（出错、已停止等）。[code]、[args] 是核心自己说的提示的种类码和参数（核心文字表的键，docs/i18n.md），有码时按界面语言出字（[said]）； [text]
 * 是核心附带的源语言文字，没有码（Claude Code 给的原文）或这边不认识这个码时显示它。
 */
@Immutable
data class NoticeItem(
    override val id: String,
    val text: String,
    val error: Boolean,
    val code: String = "",
    val args: JsonObject? = null,
) : ChatItem {
    /** 用界面语言说出来的文字。 */
    fun said(): String =
        code.takeIf { it.isNotEmpty() }?.let { CoreStrings.catalog.sayOrNull(it, jsonArgs(args)) }
            ?: text
}

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
    /** 参数里有长字符串被核心截断了（会话记录里读来的，截断处是 "\n…"），改文件的对比只画得出一部分。 */
    val clipped: Boolean = false,
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
    /** 有还活着的 Claude Code 进程（Zeta 托管的、终端里的都算）；为假是已结束的会话，侧栏里灰着显示。 */
    val live: Boolean = true,
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

/** 会话概要的一项（serve 推来的 zeta_sessions、核心的 sessions.lookup 里的一项）；缺 id 或文件的是 null。 */
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
        // 核心没给这一项时（比它旧）当作还在运行，不把整个列表都灰掉
        live = o.bool("live") != false,
    )

/** 核心的 `sessions.lookup` 的结果（会话摘要的数组）里的会话（[backend] 是在哪台主机上查的）；结果不成样子时是 null。 */
fun lookedUpSessions(result: JsonElement, backend: String): List<SessionSummary>? =
    (result as? JsonArray)?.mapNotNull { item ->
        (item as? JsonObject)?.let { sessionSummaryOf(it, backend) }
    }

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
