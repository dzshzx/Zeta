package com.dzshzx.zeta.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * 改写、重新生成、删除一轮和分支：都靠 Claude Code 自己的两样东西。
 *
 * - stream-json 的控制请求 rewind_conversation（2.1.327 实测）：给一条用户消息的 uuid，Claude Code 把会话剪到这条消息之前，
 *   连同它一起丢掉之后的全部内容。会话号不变，记录文件只追加一行剪切记号（last-prompt，explicit + rewound），
 *   被剪掉的内容还留在文件里，只是不在当前这条线上，和终端里的 /rewind 一样。剪到第一条消息之前也行（剪成空对话）。
 *   改写就是剪完再发新消息；删除一轮就是只剪不发。
 * - 分支：`--resume <原会话> --fork-session` 起一个新进程，它在内存里复制原会话，发出第一条消息时才写出新的记录文件，
 *   原会话一字不动。从中间某一轮分支时，先在新进程里把下一轮剪掉，再发消息。
 *
 * 这里是不碰 Android 的部分：哪一轮从哪到哪、剪哪条、请求和启动参数怎么拼、应答怎么读。
 */

/** 聊天列表里的一轮：从一条用户消息开始，到下一条用户消息之前。[start]、[end] 是列表下标，[end] 不含。 */
internal data class Turn(val start: Int, val end: Int)

/** 列表里的各轮。第一条用户消息之前的内容（比如载入历史时的提示）不属于任何一轮。 */
internal fun turnsOf(items: List<ChatItem>): List<Turn> {
    val starts = items.indices.filter { items[it] is UserItem }
    return starts.mapIndexed { i, start -> Turn(start, starts.getOrElse(i + 1) { items.size }) }
}

/**
 * 最后一条 Claude Code 认得的用户消息（有 uuid 的）。剪的时候一并告诉它：它据此确认界面没有漏看别处新加的轮次，
 * 不带这一项、目标后面又还有别的轮次时它会拒绝（stale_target）。
 */
internal fun lastSeenUser(items: List<ChatItem>): String? =
    items.asReversed().firstNotNullOfOrNull { (it as? UserItem)?.uuid }

/** 一轮能做的操作。挂在这一轮的用户消息（长按菜单）和最后一项（操作行）上。 */
internal data class TurnActions(
    /** 这一轮的用户消息。 */
    val user: UserItem,
    /**
     * 操作行挂在哪一项下面：这一轮最后一段回答、工作过程或出错提示。末尾的“已停止”这类灰色提示不算；
     * 这一轮除了用户消息只有这类提示时为 null，只能从用户消息的长按菜单操作。
     */
    val lastItemId: String?,
    /** 之后还有几轮，确认框里说会一起丢掉多少。 */
    val laterTurns: Int,
    /** 从这一轮之后分支时：要在新会话里剪掉的下一条用户消息（最后一轮没有）。 */
    val branchCut: String?,
) {
    /** 这条用户消息 Claude Code 认得（有 uuid），才能编辑、重新生成、删除。 */
    val rewindable: Boolean get() = user.uuid != null

    /** 最后一轮整个复制；中间的轮次要剪掉下一轮，下一轮的用户消息得有 uuid。 */
    val branchable: Boolean get() = laterTurns == 0 || branchCut != null
}

/** 每一轮的操作，按列表顺序。 */
internal fun turnActions(items: List<ChatItem>): List<TurnActions> {
    val turns = turnsOf(items)
    return turns.mapIndexed { index, turn ->
        TurnActions(
            user = items[turn.start] as UserItem,
            lastItemId = (turn.end - 1 downTo turn.start + 1).map { items[it] }
                .firstOrNull { it !is NoticeItem || it.error }?.id,
            laterTurns = turns.size - index - 1,
            branchCut = (items.getOrNull(turn.end) as? UserItem)?.uuid,
        )
    }
}

/** 从某一轮之后分支：新对话先显示的内容、要剪掉的消息，以及剪的时候报给 Claude Code 的最后一条用户消息。 */
internal class BranchPlan(val items: List<ChatItem>, val cut: String?, val lastSeen: String?)

/**
 * 一个还没发过消息的分支：新进程从会话 [source] 复制，发第一条消息前先剪掉 [cut]（null 表示整个复制）。
 * [items] 是先显示出来的内容，[skipped] 是原对话没载入的更早记录条数。
 * [model]、[effort] 是原对话分支时用的模型和思考强度，新对话接着用（null 同样是 Claude Code 的默认）。
 */
class Fork(
    val source: String,
    val cut: String?,
    val lastSeen: String?,
    val items: List<ChatItem>,
    val skipped: Int,
    val model: String?,
    val effort: String?,
)

/** 从 [lastItemId] 所在那一轮的末尾分支。那一轮之后的内容丢掉；剪不准（下一轮的用户消息没有 uuid）时返回 null。 */
internal fun branchPlan(items: List<ChatItem>, lastItemId: String): BranchPlan? {
    val index = items.indexOfFirst { it.id == lastItemId }
    if (index < 0) return null
    val turn = turnsOf(items).firstOrNull { index in it.start until it.end } ?: return null
    val next = items.getOrNull(turn.end) as? UserItem
    if (turn.end < items.size && next?.uuid == null) return null
    return BranchPlan(items.subList(0, turn.end).toList(), next?.uuid, lastSeenUser(items))
}

/** 把列表剪到 [itemId] 这一项之前（它和之后的都去掉）。找不到这一项时不动，返回 false。 */
internal fun truncateBefore(items: MutableList<ChatItem>, itemId: String): Boolean {
    val index = items.indexOfFirst { it.id == itemId }
    if (index < 0) return false
    items.subList(index, items.size).clear()
    return true
}

/** rewind_conversation 控制请求的正文（request 一项）。 */
internal fun rewindRequest(target: String, lastSeen: String?): JsonObject = buildJsonObject {
    put("subtype", "rewind_conversation")
    put("target_message_uuid", target)
    if (lastSeen != null) put("last_seen_user_message_uuid", lastSeen)
}

/**
 * 读 rewind_conversation 的应答（control_response 里的 response 一项）。剪成了返回 null，没剪成返回给用户看的原因。
 * 剪不成时 Claude Code 照样答 success，只是 rewound 为 false，原因在 reason 里。
 */
internal fun rewindFailure(response: JsonObject?): String? {
    if (response == null) return "Claude Code 没有回应"
    if (response.str("subtype") != "success") return "Claude Code 拒绝了这次操作：" + (response.str("error") ?: "未知错误")
    val body = response.obj("response") ?: return "Claude Code 没有说剪没剪"
    if (body.bool("rewound") == true) return null
    return when (val reason = body.str("reason")) {
        "turn_running", "commands_queued", "prompt_pending" -> "Claude 还在处理消息，等这一轮结束再试"
        "target_not_found" -> "会话记录里找不到这条消息（上下文压缩之前的消息改不了）"
        "stale_target", "unseen_later_turn", "state_changed" -> "这个会话在别处有了新内容，重新打开它再试"
        "target_splits_tool_call", "poll_tool_result_target" -> "这条消息在工具调用中间，不能从这里改"
        "persist_failed" -> "写不进会话记录"
        else -> "Claude Code 没有剪：" + (body.str("error") ?: reason ?: "未知原因")
    }
}

/** 启动 Claude Code 时接上哪个会话：接着原会话；或者从 [forkFrom] 复制出一个新会话，标题是 [title]；新对话什么都不加。 */
internal fun sessionArguments(sessionId: String?, forkFrom: String?, title: String): List<String> = when {
    sessionId != null -> listOf("--resume", sessionId)
    forkFrom != null -> listOf("--resume", forkFrom, "--fork-session") + if (title.isBlank()) emptyList() else listOf("--name", title)
    else -> emptyList()
}

private val BranchPrefixPattern = Regex("^分支(?: \\d+)? · ")

/**
 * 分支的标题：原标题前加“分支 · ”，重名时编号成“分支 2 · ”；从分支再分支不叠前缀（Claude Code 自己的 /branch 也是这样）。
 * 标记放在前面，是因为侧栏和标题栏都窄，长标题的尾巴会被省略号吃掉，放后面就看不出是分支。
 */
internal fun branchTitle(title: String, taken: Collection<String>): String {
    val base = title.trim().replace(BranchPrefixPattern, "").ifBlank { "新对话" }
    return generateSequence(1) { it + 1 }
        .map { if (it == 1) "分支 · $base" else "分支 $it · $base" }
        .first { it !in taken }
}

/** 从图片数据的开头认出类型；认不出返回 null。 */
internal fun imageMediaType(bytes: ByteArray): String? {
    fun at(index: Int) = bytes.getOrNull(index)?.toInt()?.and(0xFF)
    fun ascii(index: Int, text: String) = text.indices.all { at(index + it) == text[it].code }
    return when {
        at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
        at(0) == 0x89 && ascii(1, "PNG") -> "image/png"
        ascii(0, "GIF8") -> "image/gif"
        ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
        else -> null
    }
}
