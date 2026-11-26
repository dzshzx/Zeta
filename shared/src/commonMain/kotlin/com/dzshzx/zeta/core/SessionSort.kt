package com.dzshzx.zeta.core

/**
 * 侧栏会话列表按哪个时间排（设置的 [Settings.sessionSort]）：分组「今天 / 更早」和组内的先后都按它，新的在上面。 记录文件的修改时刻
 * [SessionSummary.updated] 不用来排：工具结果一写它就变，会话会跟着跳。
 */
enum class SessionSort(
    /** 存进设置、写进备份的值。 */
    val key: String,
) {
    /** 最新回答（默认）：最后一次回答完的时刻。 */
    Replied("replied"),

    /** 发起时间。 */
    Created("created");

    /** 会话 [session] 在这种排法下的时刻（毫秒）。 */
    fun timeOf(session: SessionSummary): Long =
        when (this) {
            Replied -> session.replied
            Created -> session.created
        }

    /** 按这个时间从新到旧排；同一时刻的保持原来的先后。 */
    fun ordered(list: List<SessionSummary>): List<SessionSummary> =
        list.sortedByDescending(::timeOf)

    companion object {
        val Default = Replied

        /** 存着的值 [key] 对应的排法；没存过或不认识的是 [Default]。 */
        fun of(key: String?): SessionSort = entries.firstOrNull { it.key == key } ?: Default
    }
}
