package com.dzshzx.zeta.core

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread

/**
 * 侧栏的会话列表：Claude Code 存在 ~/.claude/projects 下的会话，经 zeta_helper.py 读、改名、归档、删除。
 * [sessions] 可观察，在主线程上读写；读写会话记录在后台线程上跑。
 * 正开着的对话对象归 [hub] 管：改名时它的标题跟着换，删除时先由 hub 收掉。
 */
class SessionList internal constructor(private val hub: ConversationHub) {
    private val main = Handler(Looper.getMainLooper())

    var sessions by mutableStateOf<List<SessionSummary>>(emptyList())
        internal set

    /** 刚自动起好、列表可能还没读到的标题，按会话。在主线程上用。 */
    private val freshTitles = HashMap<String, String>()

    fun refresh() {
        val config = hub.settings.env ?: return
        thread(name = "zeta-sessions", isDaemon = true) {
            val result = hub.helper("list", timeoutMs = 60_000, config = config) ?: return@thread
            val list = parseSessionSummaries(result.stdout)
            main.post { listed(list) }
        }
    }

    /** 读到了整份列表。自动起的标题：读到了就不再替它补；这份列表要是在标题写进去之前读的，先用记着的。在主线程上调用。 */
    internal fun listed(list: List<SessionSummary>) {
        sessions = list.map { s ->
            val fresh = freshTitles[s.id] ?: return@map s
            if (s.title == fresh) freshTitles.remove(s.id)
            s.copy(title = fresh)
        }
    }

    /** Claude Code 给会话 [sessionId] 起好了标题（已经写进会话记录）：列表里只改这一项，不整份重读。在主线程上调用。 */
    internal fun titled(sessionId: String, title: String) {
        freshTitles[sessionId] = title
        sessions = sessions.map { if (it.id == sessionId) it.copy(title = title) else it }
    }

    /**
     * 按 id 查会话概要：列表里可能没有（最近 300 个之外的，或者列表还没读出来）。返回找到的那些；
     * 没查成（没有 Linux 环境、助手出错或超时）返回 null，这时不能当作“都不在了”。耗时，别在主线程调用。
     */
    internal fun find(ids: Collection<String>, config: LinuxEnvConfig? = hub.settings.env): List<SessionSummary>? {
        val result = hub.helper("lookup", ids.toList(), timeoutMs = 60_000, config = config) ?: return null
        return if (result.exitCode == 0) parseSessionSummaries(result.stdout) else null
    }

    /** 按 id 查一个会话（列表里可能还没有，比如点通知冷启动、列表还没读出来）；查到了在主线程上交给 [then]。 */
    internal fun lookup(id: String, then: (SessionSummary) -> Unit) {
        val config = hub.settings.env ?: return
        thread(name = "zeta-sessions", isDaemon = true) {
            val summary = find(listOf(id), config)?.firstOrNull() ?: return@thread
            main.post { then(summary) }
        }
    }

    fun rename(summary: SessionSummary, title: String) {
        val config = hub.settings.env ?: return
        val clean = title.trim().replace(Regex("\\s+"), " ").take(200)
        if (clean.isEmpty()) return
        freshTitles.remove(summary.id)
        sessions = sessions.map { if (it.id == summary.id) it.copy(title = clean) else it }
        hub.findConversation(summary.id)?.title = clean
        thread(isDaemon = true) { hub.helper("rename", listOf(summary.file, clean), config = config) }
    }

    fun archive(summary: SessionSummary, archived: Boolean) = archive(listOf(summary), archived)

    /**
     * 归档或取消归档：只影响列表里显不显示，会话记录不动。归档的里面有聊天页正开着的对话时，聊天页离开它、换成空的新对话
     * （和点“新对话”一样：闲着的收掉进程，还在进行的留在后台跑完）。
     */
    fun archive(summaries: List<SessionSummary>, archived: Boolean) {
        val config = hub.settings.env ?: return
        val ids = summaries.mapTo(LinkedHashSet()) { it.id }
        if (ids.isEmpty()) return
        if (archived && hub.current.sessionId in ids) hub.startNew()
        sessions = sessions.map { if (it.id in ids) it.copy(archived = archived) else it }
        thread(isDaemon = true) { hub.helper("archive", ids.toList() + if (archived) "1" else "0", config = config) }
    }

    fun delete(summary: SessionSummary) = delete(listOf(summary))

    /** 删除会话：记录文件，连同 Claude Code 存在同名目录里的工具结果、子代理记录等；开着的对话、置顶标记、没发出的草稿一并去掉。 */
    fun delete(summaries: List<SessionSummary>) {
        val config = hub.settings.env ?: return
        val ids = summaries.mapTo(HashSet()) { it.id }
        if (ids.isEmpty()) return
        hub.closeSessions(ids)
        sessions = sessions.filterNot { it.id in ids }
        hub.settings.pinnedSessions.takeIf { pins -> pins.any { it in ids } }?.let { hub.settings.pinnedSessions = it - ids }
        hub.drafts.forget(ids)
        thread(isDaemon = true) {
            hub.helper("delete", summaries.map { it.file }, timeoutMs = 60_000, config = config)
            main.post { refresh() }
        }
    }

    /** 换了环境：原来的会话都不再列出。 */
    internal fun clear() {
        sessions = emptyList()
    }
}
