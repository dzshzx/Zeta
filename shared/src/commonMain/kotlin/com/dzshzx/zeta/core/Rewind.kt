package com.dzshzx.zeta.core

/*
 * 编辑、重新生成、删除一轮、分支、还原代码由核心向 Claude Code 要（conv.rewrite、conv.drop、conv.fork、conv.restore_code，
 * 见 docs/core.md「对话」）。这里只是界面上按轮挂操作键要的：列表里哪几项是一轮、每轮能做什么。
 */

/** 聊天列表里的一轮：从一条用户消息开始，到下一条用户消息之前。[start]、[end] 是列表下标，[end] 不含。 */
data class Turn(val start: Int, val end: Int)

/** 列表里的各轮。第一条用户消息之前的内容（比如载入历史时的提示）不属于任何一轮。 */
fun turnsOf(items: List<ChatItem>): List<Turn> {
    val starts = items.indices.filter { items[it] is UserItem }
    return starts.mapIndexed { i, start -> Turn(start, starts.getOrElse(i + 1) { items.size }) }
}

/** 一轮能做的操作。挂在这一轮的用户消息（长按菜单）和最后一项（操作行）上。 */
data class TurnActions(
    /** 这一轮的用户消息。 */
    val user: UserItem,
    /** 操作行挂在哪一项下面：这一轮最后一段回答、工作过程或出错提示。末尾的“已停止”这类灰色提示不算； 这一轮除了用户消息只有这类提示时为 null，只能从用户消息的长按菜单操作。 */
    val lastItemId: String?,
    /** 之后还有几轮，确认框里说会一起丢掉多少。 */
    val laterTurns: Int,
    /** 下一轮的用户消息的 uuid（最后一轮没有）：从这一轮之后分支要剪掉它。 */
    val branchCut: String?,
) {
    /** 这条用户消息 Claude Code 认得（有 uuid），才能编辑、重新生成、删除。 */
    val rewindable: Boolean
        get() = user.uuid != null

    /** 最后一轮整个复制；中间的轮次要剪掉下一轮，下一轮的用户消息得有 uuid。 */
    val branchable: Boolean
        get() = laterTurns == 0 || branchCut != null
}

/** 每一轮的操作，按列表顺序。 */
fun turnActions(items: List<ChatItem>): List<TurnActions> {
    val turns = turnsOf(items)
    return turns.mapIndexed { index, turn ->
        TurnActions(
            user = items[turn.start] as UserItem,
            lastItemId =
                (turn.end - 1 downTo turn.start + 1)
                    .map { items[it] }
                    .firstOrNull { it !is NoticeItem || it.error }
                    ?.id,
            laterTurns = turns.size - index - 1,
            branchCut = (items.getOrNull(turn.end) as? UserItem)?.uuid,
        )
    }
}

/** conv.code_changes 的结果：还原代码会动到的文件、加减行数。 */
object CodeRewind {
    data class Changes(val files: List<String>, val insertions: Int, val deletions: Int)
}
