package com.dzshzx.zeta.core

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread

/**
 * 侧栏的会话列表：侧栏列着的主机（[ConversationHub.listedBackends]：正看着的那台，或全部主机）上 Claude Code 存在 ~/.claude/projects
 * 下的会话， 经 zeta-helper 读、改名、归档、删除。改名、归档、删除按会话自己所在的主机（[SessionSummary.backend]）做。 [sessions]
 * 可观察，在主线程上读写；读写会话记录在后台线程上跑。 正开着的对话对象归 [hub] 管：改名时它的标题跟着换，删除时先由 hub 收掉。
 */
class SessionList internal constructor(private val hub: ConversationHub) {
    private val main = Handler(Looper.getMainLooper())

    var sessions by mutableStateOf<List<SessionSummary>>(emptyList())
        internal set

    /** 刚自动起好、列表可能还没读到的标题，按会话。在主线程上用。 */
    private val freshTitles = HashMap<String, String>()

    /** 各台主机没读成的原因（远程主机连不上之类），读成了的不在里面。 */
    private var problems by mutableStateOf<Map<String, String>>(emptyMap())

    /** 没读成的原因，都读成了是 null；列着几台主机时每条前面注明是哪台。可观察。 */
    val problem: String?
        get() {
            val listed = hub.listedBackends
            val mine = listed.mapNotNull { b -> problems[b]?.let { b to it } }
            return when {
                mine.isEmpty() -> null
                listed.size == 1 -> mine.single().second
                else -> mine.joinToString("\n") { (b, why) -> hub.backendName(b) + "：" + why }
            }
        }

    /** 重读侧栏列着的各台主机（或只是其中的 [backends]）的整份列表，各读各的。没读成的那台留着原来的，不当作一个会话都没有。 */
    fun refresh(backends: List<String> = hub.listedBackends) {
        for (backend in backends) {
            if (hub.shell(backend) == null) {
                noted(backend, hub.unavailable(backend))
                continue
            }
            thread(name = "zeta-sessions", isDaemon = true) {
                val result = hub.helper("list", timeoutMs = 60_000, backend = backend)
                main.post {
                    // 读的时候侧栏已经不列这台主机了：这份不要
                    if (backend !in hub.listedBackends) return@post
                    when (result) {
                        is HelperResult.Failed -> noted(backend, "没读到会话列表：" + result.reason)
                        is HelperResult.Ok -> {
                            noted(backend, null)
                            listedOn(backend, parseSessionSummaries(result.output, backend))
                        }
                    }
                }
            }
        }
    }

    private fun noted(backend: String, why: String?) {
        problems = if (why == null) problems - backend else problems + (backend to why)
    }

    /** 读到了主机 [backend] 的整份列表：换掉列表里它的那些，别的主机的留着，合起来按时间从新到旧排。在主线程上调用。 */
    internal fun listedOn(backend: String, list: List<SessionSummary>) {
        val others = sessions.filter { it.backend != backend }
        // 只有这一台时照助手给的顺序，不重排
        if (others.isEmpty()) listed(list) else listed(byTime(list + others))
    }

    /** 只留主机 [backend] 的：换成只列它时，已经读到的不用等重读。在主线程上调用。 */
    internal fun keepOnly(backend: String) {
        sessions = sessions.filter { it.backend == backend }
        problems = problems.filterKeys { it == backend }
    }

    private fun byTime(list: List<SessionSummary>) = list.sortedByDescending { it.updated }

    /** 读到了整份列表。自动起的标题：读到了就不再替它补；这份列表要是在标题写进去之前读的，先用记着的。在主线程上调用。 */
    internal fun listed(list: List<SessionSummary>) {
        sessions = list.map { s ->
            val fresh = freshTitles[s.id] ?: return@map s
            if (s.title == fresh) freshTitles.remove(s.id)
            s.copy(title = fresh)
        }
    }

    /**
     * 侧栏列着的主机 [backend] 上的会话 [sessionId] 刚答完一轮：只重读这一项（新开的对话加进列表，时间、预览换新），不整份重读，
     * 一轮只改了这一个会话。没查成就不动，下次打开侧栏时整份重读。在主线程上调用。
     */
    internal fun touched(sessionId: String, backend: String) {
        if (hub.shell(backend) == null) return
        thread(name = "zeta-sessions", isDaemon = true) {
            val found = find(listOf(sessionId), backend) ?: return@thread
            main.post { if (backend in hub.listedBackends) merged(found) }
        }
    }

    /** 重读了其中几项（[found]）：换掉列表里的同一会话，没有的加进去，照旧按时间从新到旧排。在主线程上调用。 */
    internal fun merged(found: List<SessionSummary>) {
        val ids = found.mapTo(HashSet()) { it.id }
        listed(byTime(found + sessions.filterNot { it.id in ids }))
    }

    /** Claude Code 给会话 [sessionId] 起好了标题（已经写进会话记录）：列表里只改这一项，不整份重读。在主线程上调用。 */
    internal fun titled(sessionId: String, title: String) {
        freshTitles[sessionId] = title
        sessions = sessions.map { if (it.id == sessionId) it.copy(title = title) else it }
    }

    /**
     * 在主机 [backend] 上按 id 查会话概要：列表里可能没有（最近 300 个之外的，或者列表还没读出来）。返回找到的那些； 没查成（那台用不了、助手出错或超时、连不上）返回
     * null，这时不能当作“都不在了”。耗时，别在主线程调用。
     */
    internal fun find(ids: Collection<String>, backend: String): List<SessionSummary>? =
        hub.helper("lookup", ids.toList(), timeoutMs = 60_000, backend = backend)
            .outputOrNull
            ?.let { parseSessionSummaries(it, backend) }

    /**
     * 按 id 查一个会话（列表里可能还没有，比如点通知冷启动、列表还没读出来）；查到了在主线程上交给 [then]。
     * 先查侧栏所在的主机，再查手机和其余的远程主机：通知里的会话可能在别的主机上。
     */
    internal fun lookup(id: String, then: (SessionSummary) -> Unit) {
        val order = (listOf(hub.backend) + hub.backends).distinct().filter { hub.shell(it) != null }
        if (order.isEmpty()) return
        thread(name = "zeta-sessions", isDaemon = true) {
            for (backend in order) {
                val summary = find(listOf(id), backend)?.firstOrNull() ?: continue
                main.post { then(summary) }
                return@thread
            }
        }
    }

    fun rename(summary: SessionSummary, title: String) {
        if (hub.shell(summary.backend) == null) return
        val clean = title.trim().replace(Regex("\\s+"), " ").take(200)
        if (clean.isEmpty()) return
        freshTitles.remove(summary.id)
        sessions = sessions.map { if (it.id == summary.id) it.copy(title = clean) else it }
        val shown = hub.findConversation(summary.id)?.title
        hub.findConversation(summary.id)?.title = clean
        write(
            "改名",
            summary.backend,
            "rename",
            listOf(summary.file, clean),
            undo = {
                // 这期间又改过的不动
                sessions = sessions.map {
                    if (it.id == summary.id && it.title == clean) it.copy(title = summary.title)
                    else it
                }
                if (shown != null)
                    hub.findConversation(summary.id)?.takeIf { it.title == clean }?.title = shown
            },
        )
    }

    fun archive(summary: SessionSummary, archived: Boolean) = archive(listOf(summary), archived)

    /**
     * 归档或取消归档：只影响列表里显不显示，会话记录不动。归档的里面有聊天页正开着的对话时，聊天页离开它、换成空的新对话 （和点“新对话”一样：闲着的收掉进程，还在进行的留在后台跑完）。
     */
    fun archive(summaries: List<SessionSummary>, archived: Boolean) {
        val ids = summaries.mapTo(LinkedHashSet()) { it.id }
        if (ids.isEmpty()) return
        if (archived && hub.current.sessionId in ids) hub.startNew()
        sessions = sessions.map { if (it.id in ids) it.copy(archived = archived) else it }
        // 归档标记存在各自主机上 Zeta 的目录里；归档的会话在电脑那边的窗口一并关掉
        for ((backend, group) in summaries.groupBy { it.backend }) {
            writeArchived(backend, group, archived)
            if (archived) DeskWindows.close(hub, backend, group.map { it.id })
        }
    }

    /** 把 [group]（都在 [backend] 上）的归档标记写到那台主机；没写成时只把这次改了的改回去。 */
    private fun writeArchived(backend: String, group: List<SessionSummary>, archived: Boolean) {
        val changed = group.filter { it.archived != archived }.mapTo(HashSet()) { it.id }
        write(
            if (archived) "归档" else "取消归档",
            backend,
            "archive",
            group.map { it.id }.distinct() + if (archived) "1" else "0",
            undo = {
                sessions = sessions.map {
                    if (it.id in changed && it.archived == archived) it.copy(archived = !archived)
                    else it
                }
            },
        )
    }

    fun delete(summary: SessionSummary) = delete(listOf(summary))

    /** 删除会话：记录文件，连同 Claude Code 存在同名目录里的工具结果、子代理记录等；开着的对话、置顶标记、没发出的草稿一并去掉。 */
    fun delete(summaries: List<SessionSummary>) {
        val ids = summaries.mapTo(HashSet()) { it.id }
        if (ids.isEmpty()) return
        hub.closeSessions(ids)
        sessions = sessions.filterNot { it.id in ids }
        val pinKeys = summaries.mapTo(HashSet()) { it.pinKey }
        hub.settings.pinnedSessions
            .takeIf { pins -> pins.any { it in pinKeys } }
            ?.let { hub.settings.pinnedSessions = it - pinKeys }
        hub.drafts.forget(ids)
        // 开着的对话、置顶、草稿已经去掉，撤不回来；没删掉的会话由重读列表放回去。电脑那边的窗口一并关掉
        for ((backend, group) in summaries.groupBy { it.backend }) {
            DeskWindows.close(hub, backend, group.map { it.id })
            write(
                "删除",
                backend,
                "delete",
                group.map { it.file },
                timeoutMs = 60_000,
                done = ::refresh,
            )
        }
    }

    /**
     * 结束 [backend] 上正占着会话 [sessionId] 的、Zeta 之外的 Claude Code 进程（zeta-helper end：先 SIGTERM，不退再
     * SIGKILL）， 完了重读列表，侧栏不再标它在别处运行。
     */
    internal fun endElsewhere(sessionId: String, backend: String) =
        write("结束别处的 Claude Code", backend, "end", listOf(sessionId), done = ::refresh)

    /**
     * 把界面上已经改了的写进 [backend] 上的会话记录：界面先改，操作马上看得到；写在后台跑。 没写成时在主线程上做 [undo]（把界面改回去，和会话记录对上），Zeta
     * 在前台时说一声“[what]没成功”和原因。 [done] 在写完（成没成都算）后在主线程上调用。
     */
    private fun write(
        what: String,
        backend: String,
        subcommand: String,
        arguments: List<String>,
        timeoutMs: Long = 30_000,
        undo: () -> Unit = {},
        done: () -> Unit = {},
    ) {
        thread(name = "zeta-sessions", isDaemon = true) {
            val result = hub.helper(subcommand, arguments, timeoutMs, backend)
            main.post {
                if (result is HelperResult.Failed) {
                    undo()
                    if (hub.foreground)
                        Toast.makeText(
                                hub.context,
                                "${what}没成功：${result.reason}",
                                Toast.LENGTH_LONG,
                            )
                            .show()
                }
                done()
            }
        }
    }

    /** 换了环境或主机：原来的会话都不再列出。 */
    internal fun clear() {
        sessions = emptyList()
        problems = emptyMap()
    }
}
