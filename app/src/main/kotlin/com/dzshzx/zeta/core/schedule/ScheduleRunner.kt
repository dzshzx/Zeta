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
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.Notifier
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.core.notify.NotifyRuleStore
import com.dzshzx.zeta.core.parseSessionSummaries
import com.dzshzx.zeta.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * 在后台跑一条指令：定时任务到点（或“立即运行一次”）、通知触发的规则命中时，开一个对话发出指令，
 * 不切换界面上正显示的对话；跑完记下结果，发一条通知（标题是任务名，内容是回答的开头），点通知打开那个对话。
 * 同一项上一次还没跑完就跳过这一次，记一笔。运行记录经 [RunRecords] 写回各自的清单。状态只在主线程上改。
 */
internal object ScheduleRunner {
    private val main = Handler(Looper.getMainLooper())

    private class Run(
        val hub: ConversationHub,
        val records: RunRecords,
        val id: String,
        val conversation: Conversation,
        val prompt: String,
        val startedAt: Long,
        val manual: Boolean,
        val newSession: Boolean,
        /** 会话是这次新开的：跑完按任务名给它改名。 */
        val freshSession: Boolean,
    ) {
        val key = key(records, id)
        var savedSession: String? = null
        var notifiedRequest: String? = null
    }

    private fun key(records: RunRecords, id: String) = records.kind + ":" + id

    /** 进行中的运行，按“来源:id”。 */
    private val runs = LinkedHashMap<String, Run>()

    /** 正在查会话记录、还没真正开始的（接着同一个对话时）。 */
    private val starting = HashSet<String>()
    private var polling = false

    /** 这个对话是后台运行起的、还在跑：完成和需要确认的提醒由这里发，不用通用的那条。 */
    fun owns(conversation: Conversation): Boolean = runs.values.any { it.conversation === conversation }

    fun isRunning(taskId: String): Boolean = isRunning(ScheduleRecords, taskId)

    fun isRunning(records: RunRecords, id: String): Boolean = key(records, id).let { it in runs || it in starting }

    /** 起了这个对话、还在跑的定时任务或通知触发规则叫什么；不是后台运行起的为 null。 */
    fun taskName(conversation: Conversation): String? {
        val run = runs.values.firstOrNull { it.conversation === conversation } ?: return null
        return run.records.name(run.hub.context, run.id)
    }

    /**
     * Zeta 进程刚起来（ConversationHub 创建时）：上一个进程里没等到结果的运行记成中断；
     * 闹钟重排一遍（应用被强行停止时系统会清掉它的闹钟）。
     */
    fun onAppStart(context: Context) {
        val tasks = ScheduleStore.update(context) { list -> list.map { it.copy(lastRun = Schedules.interrupted(it.lastRun)) } }
        NotifyRuleStore.update(context) { list -> list.map { it.copy(lastRun = Schedules.interrupted(it.lastRun)) } }
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

    /** 跑一次定时任务 [task]。返回没跑成的原因（跳过或出错），开始了返回 null。在主线程上调用。 */
    fun start(app: Application, task: ScheduledTask, manual: Boolean): String? = start(app, ScheduleRecords, ScheduleRecords.spec(task), manual)

    /** 跑一次 [spec]，记录写进 [records]。返回没跑成的原因（跳过或出错），开始了返回 null。在主线程上调用。 */
    fun start(app: Application, records: RunRecords, spec: RunSpec, manual: Boolean): String? {
        val hub = ConversationHub.get(app)
        val now = System.currentTimeMillis()
        val key = key(records, spec.id)
        // 上一次其实已经结束、只是还没轮到检查：先收尾，别误判成没跑完
        runs[key]?.let { if (idle(it.conversation)) finish(it) }
        if (isRunning(records, spec.id)) return skip(app, records, spec.id, "上一次还没跑完，这次跳过了")
        val config = hub.settings.env ?: return fail(app, records, spec, manual, now, "还没有配置 Linux 环境，到设置里检查")
        keepAwake(app)
        val session = spec.sessionId
        if (spec.newSession || session == null) {
            launch(hub, records, spec, manual, now, Conversation(hub, spec.cwd ?: config.defaultCwd, sessionId = null, title = spec.name, historyFile = null), fresh = true)
            return null
        }
        val open = hub.findConversation(session)
        if (open != null) {
            if (!idle(open)) return skip(app, records, spec.id, "这个对话正在进行，这次跳过了")
            launch(hub, records, spec, manual, now, open, fresh = false)
            return null
        }
        // 接着原来的会话：先查出记录在哪、当时的工作目录（--resume 在原目录下才找得到），历史也一并载入
        starting.add(key)
        thread(name = "zeta-schedule", isDaemon = true) {
            val summary = lookup(hub, session)
            main.post {
                starting.remove(key)
                val name = records.name(app, spec.id) ?: return@post
                val conversation = if (summary != null) {
                    Conversation(hub, summary.cwd.ifBlank { spec.cwd ?: config.defaultCwd }, summary.id, name, summary.file)
                } else {
                    // 原来的会话找不到了（被删了）：另开一个，以后接着新的这个
                    Conversation(hub, spec.cwd ?: config.defaultCwd, sessionId = null, title = name, historyFile = null)
                }
                launch(hub, records, spec.copy(name = name), manual, now, conversation, fresh = summary == null)
            }
        }
        return null
    }

    private fun launch(hub: ConversationHub, records: RunRecords, spec: RunSpec, manual: Boolean, now: Long, conversation: Conversation, fresh: Boolean) {
        val app = hub.context
        conversation.preset(spec.model, spec.permissionMode, spec.hint)
        val run = Run(hub, records, spec.id, conversation, spec.prompt.trim(), now, manual, spec.newSession, fresh)
        runs[run.key] = run
        val adopt = hub.findConversation(conversation.sessionId ?: conversation.key) == null
        conversation.send(spec.prompt)
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
        records.update(app, spec.id) { it.copy(lastRun = TaskRun(now, RunOutcome.Running, conversation.sessionId, "", manual)) }
        poll()
    }

    private fun skip(app: Application, records: RunRecords, id: String, reason: String): String {
        records.update(app, id) { it.copy(skips = it.skips + 1, lastSkip = System.currentTimeMillis()) }
        return reason
    }

    private fun fail(app: Application, records: RunRecords, spec: RunSpec, manual: Boolean, now: Long, reason: String): String {
        records.update(app, spec.id) { it.copy(lastRun = TaskRun(now, RunOutcome.Failed, null, reason, manual)) }
        notify(app, key(records, spec.id), spec.name + " · 没有运行", reason, key = null)
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
            run.records.update(app, run.id) { record ->
                record.copy(
                    lastRun = record.lastRun?.takeIf { it.at == run.startedAt }?.copy(sessionId = session) ?: record.lastRun,
                    sessionId = if (record.newSession) record.sessionId else session,
                )
            }
        }
        val pending = conversation.pending
        if (pending != null && pending.requestId != run.notifiedRequest) {
            run.notifiedRequest = pending.requestId
            val name = run.records.name(app, run.id) ?: conversation.title
            if (!watching(hub, conversation)) {
                notify(app, run.key, "$name · 需要你确认", "Claude 想用 ${pending.toolName}，点这里打开对话处理", conversation.sessionId ?: conversation.key)
            }
        }
        if (idle(conversation)) finish(run)
    }

    private fun idle(conversation: Conversation) = !conversation.busy && conversation.pending == null && !conversation.rewinding

    private fun finish(run: Run) {
        val hub = run.hub
        runs.remove(run.key)
        val app = hub.context
        val conversation = run.conversation
        val (outcome, summary) = Schedules.outcomeOf(conversation.items, run.prompt)
        val session = conversation.sessionId
        run.records.update(app, run.id) { record ->
            record.copy(
                lastRun = TaskRun(run.startedAt, outcome, session ?: record.lastRun?.takeIf { it.at == run.startedAt }?.sessionId, summary, run.manual),
                sessionId = if (!record.newSession && session != null) session else record.sessionId,
            )
        }
        val current = run.records.name(app, run.id)
        val name = current ?: conversation.title.ifBlank { "定时任务" }
        // 用户叫停的不提醒；用户正看着这个对话也不必提醒
        if (outcome != RunOutcome.Stopped && !watching(hub, conversation)) {
            notify(app, run.key, if (outcome == RunOutcome.Done) name else "$name · 没有完成", summary.ifBlank { "已完成" }, session ?: conversation.key)
        }
        if (run.freshSession && session != null && current != null) {
            rename(hub, session, if (run.newSession) name + " · " + STAMP.format(Instant.ofEpochMilli(run.startedAt).atZone(ZoneId.systemDefault())) else name)
        }
    }

    private fun watching(hub: ConversationHub, conversation: Conversation) = hub.foreground && hub.current === conversation

    // ---- 通知 ----

    private const val NOTIFY_ID = 3

    /** 每一项一条（新的替换旧的），tag 是“来源:id”。[key] 是要打开的对话，null 时只打开 Zeta。 */
    private fun notify(app: Application, tag: String, title: String, text: String, key: String?) {
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        Notifier.ensureChannels(app)
        val open = if (key == null) {
            Notifier.openAppIntent(app)
        } else {
            PendingIntent.getActivity(
                app, tag.hashCode(), MainActivity.conversationIntent(app, key),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val notification = Notification.Builder(app, Notifier.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_zeta)
            .setContentTitle(AppVariant.current.notificationTitle(title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(tag, NOTIFY_ID, notification)
    }

    /** 任务删掉时，它留下的通知一并收掉。 */
    fun clearNotification(context: Context, taskId: String) = clearNotification(context, ScheduleRecords, taskId)

    fun clearNotification(context: Context, records: RunRecords, id: String) {
        context.getSystemService(NotificationManager::class.java).cancel(key(records, id), NOTIFY_ID)
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
