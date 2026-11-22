package com.dzshzx.zeta.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.conv.SessionActivity
import com.dzshzx.zeta.core.conv.sessionActivitiesOf
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 侧栏的会话列表：侧栏列着的主机（[ConversationHub.listedBackends]：正看着的那台，或全部主机）上 Claude Code 存在 ~/.claude/projects
 * 下的会话：列表经那边的 `zeta-helper serve` 订阅（[refresh]，那边一变就推来），改名、归档、删除、按 id 查各跑一次 zeta-helper
 * 的子命令。改名、归档、删除按会话自己所在的主机（[SessionSummary.backend]）做。 [sessions] 可观察，在主线程上读写；读写会话记录在后台线程上跑。
 * 正开着的对话对象归 [hub] 管：改名时它的标题跟着换，删除时先由 hub 收掉。
 */
class SessionList constructor(private val hub: ConversationHub) {
    private val main = MainHandler()

    var sessions by mutableStateOf<List<SessionSummary>>(emptyList())

    /** 刚自动起好、列表可能还没读到的标题，按会话。在主线程上用。 */
    private val freshTitles = HashMap<String, String>()

    /**
     * 各台主机的核心报的会话忙闲（[SessionActivity]）：主机 → 会话号 → 样子。和列表同一条连接订阅（`conv.activity`），推来就整份换掉，
     * 断开订阅时去掉那台的（不留过时的）。会话列表的进行中、等确认、有新回答这些标记用它（[ConversationHub.sessionStatus]）。
     */
    private var activity by mutableStateOf<Map<String, Map<String, SessionActivity>>>(emptyMap())

    /** 会话 [sessionId] 在核心那边的样子；侧栏列着的主机上没有它时为 null。可观察。 */
    fun activityOf(sessionId: String): SessionActivity? =
        activity.values.firstNotNullOfOrNull { it[sessionId] }

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

    /**
     * 订阅着的各台主机（[HelperServe] 到那边的 `zeta-helper serve`）：订阅上就收到整份列表，之后那边的会话目录一变就推来变了的几项，
     * 答完一轮、改名、删除、终端里开的会话都不用另外重读。只在主线程上碰。
     */
    private val watches = HashMap<String, HelperServe>()

    /** 界面退到后台时断开了订阅（[pause]）：回到前台时再订阅（[resume]）。 */
    private var paused = false

    /**
     * 让侧栏列着的各台主机（或只是其中的 [backends]）的列表跟上那边：还没订阅的订阅上，订阅着但连不上的马上重连（不等退避），不再列着的断开。
     * 没读成的那台留着原来的，不当作一个会话都没有。在主线程上调用。
     */
    fun refresh(backends: List<String> = hub.listedBackends) {
        paused = false
        val listed = hub.listedBackends
        (watches.keys - listed.toSet()).forEach(::unwatch)
        for (backend in backends) {
            if (backend !in listed) continue
            if (hub.shell(backend) == null) {
                unwatch(backend)
                noted(backend, hub.unavailable(backend))
                continue
            }
            if (live(backend)) continue
            unwatch(backend)
            watches[backend] = watch(backend)
        }
    }

    /** 主机 [backend] 的列表订阅着、而且连着：那边一变就会推来，不用自己去查。 */
    private fun live(backend: String): Boolean = backend in watches && backend !in problems

    private fun watch(backend: String): HelperServe {
        lateinit var self: HelperServe
        fun current() = watches[backend] === self && backend in hub.listedBackends
        self =
            HelperServe(
                name = backend,
                open = {
                    val shell =
                        hub.shell(backend) ?: error(hub.unavailable(backend) ?: "还没有可用的 Linux 环境")
                    HelperServe.open(shell, hub.helperProgram) {
                        hub.linkProblem(backend, it.exitCode, it.stderr)
                    }
                },
                subscriptions = listOf("sessions.watch", "conv.activity"),
                onMessage = { o -> pushed(backend, o) { current() } },
                onProblem = { why ->
                    main.post {
                        if (current()) noted(backend, why?.let { "没读到会话列表：$it" })
                    }
                },
            )
        return self
    }

    /** 主机 [backend] 的订阅推来一条（在连接自己的线程上）：解析后交到主线程，那时这条订阅还 [current] 才用。 */
    private fun pushed(backend: String, o: JsonObject, current: () -> Boolean) {
        when (o.str("type")) {
            "conv.activity" -> {
                val sessions = sessionActivitiesOf(o)
                main.post { if (current()) activityReceived(backend, sessions) }
            }
            "zeta_sessions" -> {
                val full = o.bool("full") == true
                val upsert =
                    o.arr("upsert").orEmpty().mapNotNull { item ->
                        (item as? JsonObject)?.let { sessionSummaryOf(it, backend) }
                    }
                val remove =
                    o.arr("remove").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                main.post { if (current()) received(backend, full, upsert, remove) }
            }
        }
    }

    private fun unwatch(backend: String) {
        watches.remove(backend)?.close()
        activity = activity - backend
    }

    /** 主机 [backend] 的核心推来了各会话的忙闲（整份）：换掉那台的，结束了一轮的交给 hub 记成有新回答（不在眼前的才记）。在主线程上调用。 */
    fun activityReceived(backend: String, sessions: Map<String, SessionActivity>) {
        val before = activity[backend].orEmpty()
        // 两份里都有、结束时刻往后挪了的；第一次见的不算（不知道它是不是刚结束）
        val ended = sessions.mapNotNull { (session, now) ->
            val was = before[session] ?: return@mapNotNull null
            session.takeIf { (now.turnEnd ?: 0) > (was.turnEnd ?: 0) }
        }
        activity = activity + (backend to sessions)
        if (ended.isNotEmpty()) hub.onTurnsEnded(ended)
    }

    /** 界面退到后台：断开全部订阅，那边的 serve 随之退出，后台不留连接。在主线程上调用。 */
    fun pause() {
        if (watches.isEmpty()) return
        watches.keys.toList().forEach(::unwatch)
        paused = true
    }

    /** 回到前台：退到后台时订阅着的话，重新订阅（重连后先收到整份）。在主线程上调用。 */
    fun resume() {
        if (paused) refresh()
    }

    /**
     * 主机 [backend] 推来了列表：[full] 时是那台的整份（同 [listedOn]），否则 [upsert] 是新出现或变了的项、[remove] 是不再列出的
     * id，那台别的项和别的主机的都留着。在主线程上调用。
     */
    fun received(
        backend: String,
        full: Boolean,
        upsert: List<SessionSummary>,
        remove: List<String>,
    ) {
        if (full) return listedOn(backend, upsert)
        val dropped = remove.toHashSet().apply { upsert.mapTo(this) { it.id } }
        listed(byTime(upsert + sessions.filterNot { it.backend == backend && it.id in dropped }))
    }

    private fun noted(backend: String, why: String?) {
        problems = if (why == null) problems - backend else problems + (backend to why)
    }

    /** 读到了主机 [backend] 的整份列表：换掉列表里它的那些，别的主机的留着，合起来按时间从新到旧排。在主线程上调用。 */
    fun listedOn(backend: String, list: List<SessionSummary>) {
        val others = sessions.filter { it.backend != backend }
        // 只有这一台时照助手给的顺序，不重排
        if (others.isEmpty()) listed(list) else listed(byTime(list + others))
    }

    /** 只留主机 [backend] 的：换成只列它时，已经读到的不用等重读。在主线程上调用。 */
    fun keepOnly(backend: String) {
        (watches.keys - backend).forEach(::unwatch)
        sessions = sessions.filter { it.backend == backend }
        problems = problems.filterKeys { it == backend }
    }

    private fun byTime(list: List<SessionSummary>) = list.sortedByDescending { it.updated }

    /** 读到了整份列表。自动起的标题：读到了就不再替它补；这份列表要是在标题写进去之前读的，先用记着的。在主线程上调用。 */
    fun listed(list: List<SessionSummary>) {
        sessions = list.map { s ->
            val fresh = freshTitles[s.id] ?: return@map s
            if (s.title == fresh) freshTitles.remove(s.id)
            s.copy(title = fresh)
        }
    }

    /**
     * 侧栏列着的主机 [backend] 上的会话 [sessionId] 刚答完一轮：订阅着的话那边已经推来了，什么也不做；没订阅着（界面在后台、
     * 连不上）时只重读这一项（新开的对话加进列表，时间、预览换新），不整份重读。没查成就不动，下次打开侧栏时再订阅。在主线程上调用。
     */
    fun touched(sessionId: String, backend: String) {
        // 订阅着的话那边已经推来了
        if (live(backend) || hub.shell(backend) == null) return
        thread(name = "zeta-sessions", isDaemon = true) {
            val found = find(listOf(sessionId), backend) ?: return@thread
            main.post { if (backend in hub.listedBackends) merged(found) }
        }
    }

    /** 重读了其中几项（[found]）：换掉列表里的同一会话，没有的加进去，照旧按时间从新到旧排。在主线程上调用。 */
    fun merged(found: List<SessionSummary>) {
        val ids = found.mapTo(HashSet()) { it.id }
        listed(byTime(found + sessions.filterNot { it.id in ids }))
    }

    /** Claude Code 给会话 [sessionId] 起好了标题（已经写进会话记录）：列表里只改这一项，不整份重读。在主线程上调用。 */
    fun titled(sessionId: String, title: String) {
        freshTitles[sessionId] = title
        sessions = sessions.map { if (it.id == sessionId) it.copy(title = title) else it }
    }

    /**
     * 在主机 [backend] 上按 id 查会话概要：列表里可能没有（最近 300 个之外的，或者列表还没读出来）。返回找到的那些； 没查成（那台用不了、助手出错或超时、连不上）返回
     * null，这时不能当作“都不在了”。耗时，别在主线程调用。
     */
    fun find(ids: Collection<String>, backend: String): List<SessionSummary>? =
        hub.helper("lookup", ids.toList(), timeoutMs = 60_000, backend = backend)
            .outputOrNull
            ?.let { parseSessionSummaries(it, backend) }

    /**
     * 按 id 查一个会话（列表里可能还没有，比如点通知冷启动、列表还没读出来）；查到了在主线程上交给 [then]。
     * 先查侧栏所在的主机，再查手机和其余的远程主机：通知里的会话可能在别的主机上。
     */
    fun lookup(id: String, then: (SessionSummary) -> Unit) {
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
    fun endElsewhere(sessionId: String, backend: String) =
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
                    if (hub.foreground) hub.platform.toast("${what}没成功：${result.reason}")
                }
                done()
            }
        }
    }

    /** 换了环境或主机：原来的会话都不再列出。 */
    fun clear() {
        watches.keys.toList().forEach(::unwatch)
        sessions = emptyList()
        problems = emptyMap()
    }
}
