package com.dzshzx.zeta.core.schedule

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.dzshzx.zeta.R
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.Notifier
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.core.parseSessionSummaries
import com.dzshzx.zeta.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * 跑定时任务：到点（或“立即运行一次”）时在后台开一个对话发出指令，不切换界面上正显示的对话；
 * 跑完记下结果，发一条通知（标题是任务名，内容是回答的开头），点通知打开那个对话。
 * 同一任务上一次还没跑完就跳过这一次，记一笔。状态只在主线程上改。
 */
internal object ScheduleRunner {
    private val main = Handler(Looper.getMainLooper())

    private class Run(
        val hub: ConversationHub,
        val taskId: String,
        val conversation: Conversation,
        val prompt: String,
        val startedAt: Long,
        val manual: Boolean,
        /** 会话是这次新开的：跑完按任务名给它改名。 */
        val freshSession: Boolean,
    ) {
        var savedSession: String? = null
        var notifiedRequest: String? = null
    }

    /** 进行中的运行，按任务 id。 */
    private val runs = LinkedHashMap<String, Run>()

    /** 正在查会话记录、还没真正开始的（接着同一个对话时）。 */
    private val starting = HashSet<String>()
    private var polling = false

    /** 这个对话是定时任务起的、还在跑：完成和需要确认的提醒由这里发，不用通用的那条。 */
    fun owns(conversation: Conversation): Boolean = runs.values.any { it.conversation === conversation }

    fun isRunning(taskId: String): Boolean = taskId in runs || taskId in starting

    /**
     * Zeta 进程刚起来（ConversationHub 创建时）：上一个进程里没等到结果的运行记成中断；
     * 闹钟重排一遍（应用被强行停止时系统会清掉它的闹钟）。
     */
    fun onAppStart(context: Context) {
        val tasks = ScheduleStore.update(context) { list ->
            list.map { task ->
                val run = task.lastRun
                if (run?.outcome == RunOutcome.Running) {
                    task.copy(lastRun = run.copy(outcome = RunOutcome.Interrupted, summary = "Zeta 在这次运行结束前被关掉了，结果在对话里"))
                } else {
                    task
                }
            }
        }
        ScheduleAlarms.sync(context, tasks)
    }

    /** 闹钟到点。先排好下一次，再跑这一次。 */
    fun fire(app: Application, id: String, plannedAt: Long) {
        ConversationHub.get(app)
        val task = ScheduleStore.find(app, id)
        if (task == null || !task.enabled) {
            // 任务删了或关了，闹钟却还在（比如上次没来得及取消）
            if (task == null) ScheduleAlarms.cancel(app, id)
            return
        }
        if (task.rule is ScheduleRule.Once) {
            ScheduleStore.updateTask(app, id) { it.copy(enabled = false) }
        } else {
            // 从排定的时刻往后算，送达晚了几秒也不会把同一次再排一遍
            val after = Instant.ofEpochMilli(maxOf(System.currentTimeMillis(), plannedAt))
            Schedules.nextTrigger(task.rule, after, ZoneId.systemDefault())?.let { ScheduleAlarms.arm(app, id, it.toEpochMilli()) }
        }
        start(app, task, manual = false)
    }

    /** 跑一次 [task]。返回没跑成的原因（跳过或出错），开始了返回 null。在主线程上调用。 */
    fun start(app: Application, task: ScheduledTask, manual: Boolean): String? {
        val hub = ConversationHub.get(app)
        val now = System.currentTimeMillis()
        // 上一次其实已经结束、只是还没轮到检查：先收尾，别误判成没跑完
        runs[task.id]?.let { if (idle(it.conversation)) finish(it) }
        if (isRunning(task.id)) return skip(app, task, "上一次还没跑完，这次跳过了")
        val config = hub.settings.env ?: return fail(app, task, manual, now, "还没有配置 Linux 环境，到设置里检查")
        keepAwake(app)
        val session = task.sessionId
        if (task.newSession || session == null) {
            launch(hub, task, manual, now, Conversation(hub, task.cwd ?: config.defaultCwd, sessionId = null, title = task.name, historyFile = null), fresh = true)
            return null
        }
        val open = hub.findConversation(session)
        if (open != null) {
            if (!idle(open)) return skip(app, task, "这个对话正在进行，这次跳过了")
            launch(hub, task, manual, now, open, fresh = false)
            return null
        }
        // 接着原来的会话：先查出记录在哪、当时的工作目录（--resume 在原目录下才找得到），历史也一并载入
        starting.add(task.id)
        thread(name = "zeta-schedule", isDaemon = true) {
            val summary = lookup(hub, session)
            main.post {
                starting.remove(task.id)
                val latest = ScheduleStore.find(app, task.id) ?: return@post
                val conversation = if (summary != null) {
                    Conversation(hub, summary.cwd.ifBlank { latest.cwd ?: config.defaultCwd }, summary.id, latest.name, summary.file)
                } else {
                    // 原来的会话找不到了（被删了）：另开一个，以后接着新的这个
                    Conversation(hub, latest.cwd ?: config.defaultCwd, sessionId = null, title = latest.name, historyFile = null)
                }
                launch(hub, latest, manual, now, conversation, fresh = summary == null)
            }
        }
        return null
    }

    private fun launch(hub: ConversationHub, task: ScheduledTask, manual: Boolean, now: Long, conversation: Conversation, fresh: Boolean) {
        val app = hub.context
        conversation.preset(task.model, task.permissionMode, hint(task.name))
        val run = Run(hub, task.id, conversation, task.prompt.trim(), now, manual, fresh)
        runs[task.id] = run
        val adopt = hub.findConversation(conversation.sessionId ?: conversation.key) == null
        conversation.send(task.prompt)
        if (!conversation.busy) {
            // 没发出去（进程起不来之类），原因在对话里的提示上；这个对话不留在后台
            finish(run)
            return
        }
        // 发出去了才放进后台（事件是投递到主线程的，在这之后才到）；再报一次活动，前台服务把它算进去
        if (adopt) {
            hub.adoptBackground(conversation)
            hub.onActivityChanged()
        }
        ScheduleStore.updateTask(app, task.id) { it.copy(lastRun = TaskRun(now, RunOutcome.Running, conversation.sessionId, "", manual)) }
        poll()
    }

    private fun skip(app: Application, task: ScheduledTask, reason: String): String {
        ScheduleStore.updateTask(app, task.id) { it.copy(skips = it.skips + 1, lastSkip = System.currentTimeMillis()) }
        return reason
    }

    private fun fail(app: Application, task: ScheduledTask, manual: Boolean, now: Long, reason: String): String {
        ScheduleStore.updateTask(app, task.id) { it.copy(lastRun = TaskRun(now, RunOutcome.Failed, null, reason, manual)) }
        notify(app, task.id, task.name + " · 没有运行", reason, key = null)
        return reason
    }

    /** 跑完才收的进度：一轮结束、进程退出、被用户叫停都体现在 busy 上，这样不用分别接每条路径。 */
    private fun poll() {
        if (polling) return
        polling = true
        main.postDelayed(::tick, POLL_MS)
    }

    private fun tick() {
        polling = false
        for (run in runs.values.toList()) check(run)
        if (runs.isNotEmpty()) poll()
    }

    private fun check(run: Run) {
        val hub = run.hub
        val app = hub.context
        val conversation = run.conversation
        // 会话号一到就记下来：“上次运行”点进去、接着同一个对话都靠它（/clear 换了会话也跟着换）
        val session = conversation.sessionId
        if (session != null && session != run.savedSession) {
            run.savedSession = session
            ScheduleStore.updateTask(app, run.taskId) { task ->
                task.copy(
                    lastRun = task.lastRun?.takeIf { it.at == run.startedAt }?.copy(sessionId = session) ?: task.lastRun,
                    sessionId = if (task.newSession) task.sessionId else session,
                )
            }
        }
        val pending = conversation.pending
        if (pending != null && pending.requestId != run.notifiedRequest) {
            run.notifiedRequest = pending.requestId
            val name = ScheduleStore.find(app, run.taskId)?.name ?: conversation.title
            if (!watching(hub, conversation)) {
                notify(app, run.taskId, "$name · 需要你确认", "Claude 想用 ${pending.toolName}，点这里打开对话处理", conversation.sessionId ?: conversation.key)
            }
        }
        if (idle(conversation)) finish(run)
    }

    private fun idle(conversation: Conversation) = !conversation.busy && conversation.pending == null && !conversation.rewinding

    private fun finish(run: Run) {
        val hub = run.hub
        runs.remove(run.taskId)
        val app = hub.context
        val conversation = run.conversation
        val (outcome, summary) = Schedules.outcomeOf(conversation.items, run.prompt)
        val session = conversation.sessionId
        ScheduleStore.updateTask(app, run.taskId) { task ->
            task.copy(
                lastRun = TaskRun(run.startedAt, outcome, session ?: task.lastRun?.takeIf { it.at == run.startedAt }?.sessionId, summary, run.manual),
                sessionId = if (!task.newSession && session != null) session else task.sessionId,
            )
        }
        val task = ScheduleStore.find(app, run.taskId)
        val name = task?.name ?: conversation.title.ifBlank { "定时任务" }
        // 用户叫停的不提醒；用户正看着这个对话也不必提醒
        if (outcome != RunOutcome.Stopped && !watching(hub, conversation)) {
            notify(app, run.taskId, if (outcome == RunOutcome.Done) name else "$name · 没有完成", summary.ifBlank { "已完成" }, session ?: conversation.key)
        }
        if (run.freshSession && session != null && task != null) {
            rename(hub, session, if (task.newSession) name + " · " + STAMP.format(Instant.ofEpochMilli(run.startedAt).atZone(ZoneId.systemDefault())) else name)
        }
    }

    private fun watching(hub: ConversationHub, conversation: Conversation) = hub.foreground && hub.current === conversation

    /** 让 Claude 知道这一轮没人看着，结果先看通知里那一段。 */
    private fun hint(name: String) =
        "Messages in this conversation may be sent automatically by the user's scheduled task \"$name\" while nobody is watching. " +
            "The user first sees the opening of your final reply (about 200 characters) in a phone notification, so lead with the result."

    // ---- 通知 ----

    private const val NOTIFY_ID = 3

    private fun tag(taskId: String) = "schedule:$taskId"

    /** 每个任务一条（新的替换旧的）。[key] 是要打开的对话，null 时只打开 Zeta。 */
    private fun notify(app: Application, taskId: String, title: String, text: String, key: String?) {
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        Notifier.ensureChannels(app)
        val open = if (key == null) {
            Notifier.openAppIntent(app)
        } else {
            PendingIntent.getActivity(
                app, taskId.hashCode(), MainActivity.conversationIntent(app, key),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val notification = Notification.Builder(app, Notifier.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_zeta)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(tag(taskId), NOTIFY_ID, notification)
    }

    /** 任务删掉时，它留下的通知一并收掉。 */
    fun clearNotification(context: Context, taskId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(tag(taskId), NOTIFY_ID)
    }

    // ---- 小工具 ----

    /** 闹钟送达到前台服务拿到自己的唤醒锁之间，别让设备睡回去。 */
    private fun keepAwake(context: Context) {
        runCatching {
            context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zeta:schedule")
                .acquire(2 * 60_000L)
        }
    }

    private fun lookup(hub: ConversationHub, id: String): SessionSummary? {
        val config = hub.settings.env ?: return null
        val result = runCatching {
            LinuxEnv.run(config, "exec python3 - lookup " + LinuxEnv.quote(id), stdin = hub.helperScript, timeoutMs = 60_000)
        }.getOrNull() ?: return null
        return parseSessionSummaries(result.stdout).firstOrNull { it.id == id }
    }

    /** 新开的会话按任务名起标题（侧栏里认得出是哪个任务、哪一次）。 */
    private fun rename(hub: ConversationHub, id: String, title: String) {
        thread(name = "zeta-schedule", isDaemon = true) {
            val summary = lookup(hub, id) ?: return@thread
            main.post { hub.rename(summary, title) }
        }
    }

    private const val POLL_MS = 2_000L
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
}
