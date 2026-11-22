package com.dzshzx.zeta.core

/*
 * 编辑、重新生成、删除一轮、分支、还原代码由核心向 Claude Code 要（conv.rewrite、conv.drop、conv.fork、conv.restore_code，
 * 见 docs/core.md「对话」）。各轮能做什么、操作行挂在哪一项也由核心算好，标在用户消息上（[UserItem] 的 rewindable、forkable、
 * roundLast）；这里只数每一轮之后还有几轮，确认框里说一起丢掉多少（协议上归客户端数）。
 */

/** 聊天列表里的一轮，从用户消息 [user] 起到下一条用户消息之前；[laterTurns] 是之后还有几轮。 */
data class Turn(val user: UserItem, val laterTurns: Int)

/** 列表里的各轮，按顺序。 */
fun userTurns(items: List<ChatItem>): List<Turn> {
    val users = items.filterIsInstance<UserItem>()
    return users.mapIndexed { i, user -> Turn(user, users.size - i - 1) }
}

/** conv.code_changes 的结果：还原代码会动到的文件、加减行数。 */
object CodeRewind {
    data class Changes(val files: List<String>, val insertions: Int, val deletions: Int)
}
