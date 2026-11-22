package com.dzshzx.zeta.core.schedule

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.Notifier
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.core.diag.DiagLevel
import com.dzshzx.zeta.core.diag.DiagLog
import com.dzshzx.zeta.core.diag.DiagTopic
import com.dzshzx.zeta.core.notice
import com.dzshzx.zeta.core.notify.NotifyRuleStore
import com.dzshzx.zeta.phone.tools.ScheduleTools
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * 在后台跑一条指令：定时任务到点（或“立即运行一次”）、通知触发的规则命中时，开一个对话发出指令，
 * 不切换界面上正显示的对话；跑完记下结果，发一条通知（标题是任务名，内容是回答的开头），点通知打开那个对话。 只在有变化时提醒的，Claude 上报没有变化就不发（见
 * [Schedules.settle]）。 同一项上一次还没跑完就跳过这一次，记一笔。运行记录经 [RunRecords] 写回各自的清单。状态只在主线程上改。
 *
 * 跑的期间对话挂着这次运行（[UnattendedRun]，由 Conversation.sendUnattended 挂上），Claude 的上报、等没等过确认记在它上面。
 * 这次的权限模式和拦管理定时任务工具的权限规则（[ScheduleTools.unattendedRules]）随指令下发给进程，只管后台运行发的这几条：
 * 用户自己在这个对话里发下一条消息时换回用户的。接着已经开着的进程时不重起，这次的说明附在指令后面一起发。
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
        /** 挂在对话上的这次运行，Claude 的上报、等没等过确认记在里面。 */
        val scope: UnattendedRun,
    ) {
        val key = key(records, id)
        var savedSession: String? = null
    }

    private fun key(records: RunRecords, id: String) = records.kind + ":" + id

    /** 进行中的运行，按“来源:id”。 */
    private val runs = LinkedHashMap<String, Run>()

    /** 正在查会话记录、还没真正开始的（接着同一个对话时）。 */
    private val starting = HashSet<String>()
    private var polling = false

    fun isRunning(taskId: String): Boolean = isRunning(ScheduleRecords, taskId)

    fun isRunning(records: RunRecords, id: String): Boolean =
        key(records, id).let { it in runs || it in starting }

    /** 起了这个对话、还在跑的定时任务或通知触发规则叫什么；不是后台运行起的为 null。 */
    fun taskName(conversation: Conversation): String? {
        val run = runs.values.firstOrNull { it.conversation === conversation } ?: return null
        return run.records.name(run.hub.context, run.id)
    }

    /** Zeta 进程刚起来（ConversationHub 创建时）：上一个进程里没等到结果的运行记成中断； 闹钟重排一遍（应用被强行停止时系统会清掉它的闹钟）。 */
    fun onAppStart(context: Context) {
        val tasks =
            ScheduleStore.update(context) { list ->
                list.map { it.copy(lastRun = Schedules.interrupted(it.lastRun)) }
            }
        NotifyRuleStore.update(context) { list ->
            list.map { it.copy(lastRun = Schedules.interrupted(it.lastRun)) }
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
        DiagLog.info(DiagTopic.Schedule, "定时任务到点：${task.name}")
        if (task.rule is ScheduleRule.Once) {
            ScheduleStore.updateTask(app, id) { it.copy(enabled = false) }
        } else {
            // 从排定的时刻往后算，送达晚了几秒也不会把同一次再排一遍
            val after = Instant.ofEpochMilli(maxOf(System.currentTimeMillis(), plannedAt))
            Schedules.nextTrigger(task.rule, after, ZoneId.systemDefault())?.let {
                ScheduleAlarms.arm(app, id, it.toEpochMilli())
            }
        }
        start(app, task, manual = false)
    }

    /** 跑一次定时任务 [task]。返回没跑成的原因（跳过或出错），开始了返回 null。在主线程上调用。 */
    fun start(app: Application, task: ScheduledTask, manual: Boolean): String? =
        start(app, ScheduleRecords, ScheduleRecords.spec(task), manual)

    /** 跑一次 [spec]，记录写进 [records]。返回没跑成的原因（跳过或出错），开始了返回 null。在主线程上调用。 */
    fun start(app: Application, records: RunRecords, spec: RunSpec, manual: Boolean): String? {
        val hub = ConversationHub.get(app)
        val now = System.currentTimeMillis()
        val key = key(records, spec.id)
        // 上一次其实已经结束、只是还没轮到检查：先收尾，别误判成没跑完
        runs[key]?.let { if (idle(it.conversation)) finish(it) }
        if (isRunning(records, spec.id)) return skip(app, records, spec.id, "上一次还没跑完，这次跳过了")
        val backend = spec.backend
        if (hub.shell(backend) == null)
            return fail(
                app,
                records,
                spec,
                manual,
                now,
                hub.unavailable(backend) ?: "这台设备现在用不了",
            )
        val defaultCwd = hub.defaultCwd(backend)
        keepAwake(app)
        val session = spec.sessionId
        if (spec.newSession || session == null) {
            launch(
                hub,
                records,
                spec,
                manual,
                now,
                Conversation(
                    hub,
                    spec.cwd ?: defaultCwd,
                    sessionId = null,
                    title = spec.name,
                    historyFile = null,
                    backend = backend,
                ),
                fresh = true,
            )
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
            val found = hub.sessionList.find(listOf(session), backend)
            main.post {
                starting.remove(key)
                launchResumed(app, hub, records, spec, manual, now, found, defaultCwd)
            }
        }
        return null
    }

    /** 在主线程上：查过了原来的会话 [found]（null 是没查成），接着它跑；被删了就另开一个，以后接着新的这个。 任务这期间被删了就不跑。 */
    private fun launchResumed(
        app: Application,
        hub: ConversationHub,
        records: RunRecords,
        spec: RunSpec,
        manual: Boolean,
        now: Long,
        found: List<SessionSummary>?,
        defaultCwd: String,
    ) {
        val named = spec.copy(name = records.name(app, spec.id) ?: return)
        // 没查成不等于被删了：这次不跑，下次再查，不另开对话
        if (found == null) {
            fail(app, records, named, manual, now, "查不到原来的对话（那台设备没有响应）")
            return
        }
        val summary = found.firstOrNull()
        val conversation =
            if (summary != null) {
                Conversation(
                    hub,
                    summary.cwd.ifBlank { spec.cwd ?: defaultCwd },
                    summary.id,
                    named.name,
                    summary.file,
                    backend = spec.backend,
                )
            } else {
                // 原来的会话找不到了（被删了）：另开一个，以后接着新的这个
                Conversation(
                    hub,
                    spec.cwd ?: defaultCwd,
                    sessionId = null,
                    title = named.name,
                    historyFile = null,
                    backend = spec.backend,
                )
            }
        launch(hub, records, named, manual, now, conversation, fresh = summary == null)
    }

    private fun launch(
        hub: ConversationHub,
        records: RunRecords,
        spec: RunSpec,
        manual: Boolean,
        now: Long,
        conversation: Conversation,
        fresh: Boolean,
    ) {
        val app = hub.context
        // 接着已经开着的进程：说明进不了系统提示词（起进程时才给），附在指令后面一起发
        val prompt =
            spec.prompt.trim().let {
                if (conversation.isProcessAlive) it + "\n\n[Zeta] " + spec.hint else it
            }
        conversation.preset(model = spec.model, effort = null, hint = spec.hint)
        DiagLog.info(
            DiagTopic.Schedule,
            "开始跑后台任务（${records.kind}）：${spec.name}" +
                (if (manual) "（手动）" else "") +
                if (fresh) "，新对话" else "，接着原对话",
        )
        val run =
            Run(
                hub,
                records,
                spec.id,
                conversation,
                prompt,
                now,
                manual,
                spec.newSession,
                fresh,
                UnattendedRun(spec.onlyOnChange),
            )
        runs[run.key] = run
        val adopt = hub.findConversation(conversation.openKey) == null
        conversation.sendUnattended(
            prompt,
            spec.permissionMode,
            ScheduleTools.unattendedRules,
            run.scope,
        )
        if (!conversation.busy) {
            // 没发出去（进程起不来之类），原因在对话里的提示上；这个对话不留在后台
            finish(run)
            return
        }
        // 发出去了才放进后台（事件是投递到主线程的，在这之后才到）；再报一次活动，前台服务把它算进去
        if (adopt) {
            hub.park(conversation)
            hub.onActivityChanged()
        }
        records.update(app, spec.id) {
            it.copy(lastRun = TaskRun(now, RunOutcome.Running, conversation.sessionId, "", manual))
        }
        poll()
    }

    private fun skip(app: Application, records: RunRecords, id: String, reason: String): String {
        records.update(app, id) {
            it.copy(skips = it.skips + 1, lastSkip = System.currentTimeMillis())
        }
        DiagLog.warn(
            DiagTopic.Schedule,
            "后台任务（${records.kind}）${records.name(app, id) ?: id} 跳过：$reason",
        )
        return reason
    }

    private fun fail(
        app: Application,
        records: RunRecords,
        spec: RunSpec,
        manual: Boolean,
        now: Long,
        reason: String,
    ): String {
        DiagLog.error(DiagTopic.Schedule, "后台任务（${records.kind}）${spec.name} 没有运行：$reason")
        records.update(app, spec.id) {
            it.copy(lastRun = TaskRun(now, RunOutcome.Failed, null, reason, manual))
        }
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
                    lastRun =
                        record.lastRun?.takeIf { it.at == run.startedAt }?.copy(sessionId = session)
                            ?: record.lastRun,
                    sessionId = if (run.newSession) record.sessionId else session,
                )
            }
        }
        val pending = conversation.pending
        if (pending != null && pending.requestId != run.scope.notifiedRequest) {
            run.scope.notifiedRequest = pending.requestId
            val name = run.records.name(app, run.id) ?: conversation.title
            if (!hub.isWatching(conversation)) {
                notify(
                    app,
                    run.key,
                    "$name · 需要你确认",
                    "${pending.notice}，点这里打开对话处理",
                    conversation.openKey,
                )
            }
        }
        if (idle(conversation)) finish(run)
    }

    private fun idle(conversation: Conversation) =
        !conversation.busy && conversation.pending == null && !conversation.rewinding

    private fun finish(run: Run) {
        val hub = run.hub
        runs.remove(run.key)
        val app = hub.context
        val conversation = run.conversation
        if (conversation.unattended === run.scope) conversation.unattended = null
        val (raw, answer) = Schedules.outcomeOf(conversation.items, run.prompt)
        val (outcome, summary, alert) = run.scope.settle(raw, answer)
        val session = conversation.sessionId
        run.records.update(app, run.id) { record ->
            record.copy(
                lastRun =
                    TaskRun(
                        run.startedAt,
                        outcome,
                        session ?: record.lastRun?.takeIf { it.at == run.startedAt }?.sessionId,
                        summary,
                        run.manual,
                    ),
                sessionId = if (!run.newSession && session != null) session else record.sessionId,
            )
        }
        val current = run.records.name(app, run.id)
        val name = current ?: conversation.title.ifBlank { run.records.fallbackName }
        DiagLog.log(
            outcomeLevel(outcome),
            DiagTopic.Schedule,
            // 不记结果摘要：那是对话内容
            "后台任务（${run.records.kind}）$name 结束：" + outcome.label,
        )
        // 用户叫停的、没有变化的不提醒（上一次的结果通知留着：用户可能还没看到）；用户正看着这个对话也不必提醒；
        // 屏幕底部弹了结果卡片的不响铃
        if (alert && !hub.isWatching(conversation)) {
            notify(
                app,
                run.key,
                if (outcome == RunOutcome.Done || outcome == RunOutcome.NoChange) name
                else "$name · 没有完成",
                summary.ifBlank { "已完成" },
                session ?: conversation.key,
                silent = hub.alerts.showedResultCard(conversation),
            )
        }
        if (run.freshSession && session != null && current != null) {
            // 新开的会话按任务名起标题（侧栏里认得出是哪个任务、哪一次）
            val title = freshTitle(run, name)
            hub.sessionList.lookup(session) { hub.sessionList.rename(it, title) }
        }
    }

    /** 诊断日志里一次运行的结局记成什么级别：做完、没变化、被叫停是信息，别的是警告。 */
    private fun outcomeLevel(outcome: RunOutcome): DiagLevel =
        if (
            outcome == RunOutcome.Done ||
                outcome == RunOutcome.NoChange ||
                outcome == RunOutcome.Stopped
        )
            DiagLevel.Info
        else DiagLevel.Warn

    /** 这次新开的会话的标题：每次都开新会话的带上开始的时刻，好分出是哪一次。 */
    private fun freshTitle(run: Run, name: String): String =
        if (run.newSession)
            name +
                " · " +
                STAMP.format(Instant.ofEpochMilli(run.startedAt).atZone(ZoneId.systemDefault()))
        else name

    // ---- 通知 ----

    /** 每一项一条（新的替换旧的），tag 是“来源:id”。[key] 是要打开的对话，null 时只打开 Zeta。[silent] 见 [Notifier.post]。 */
    private fun notify(
        app: Application,
        tag: String,
        title: String,
        text: String,
        key: String?,
        silent: Boolean = false,
    ) {
        val open =
            if (key == null) Notifier.openAppIntent(app) else Notifier.conversationOpen(app, key)
        Notifier.post(
            app,
            tag,
            Notifier.ID_SCHEDULE,
            title,
            text,
            open,
            bigText = text,
            silent = silent,
        )
    }

    /** 任务删掉时，它留下的通知一并收掉。 */
    fun clearNotification(context: Context, taskId: String) =
        clearNotification(context, ScheduleRecords, taskId)

    fun clearNotification(context: Context, records: RunRecords, id: String) {
        Notifier.cancel(context, key(records, id), Notifier.ID_SCHEDULE)
    }

    // ---- 小工具 ----

    /** 闹钟送达到前台服务拿到自己的唤醒锁之间，别让设备睡回去。 */
    private fun keepAwake(context: Context) {
        runCatching {
            context
                .getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zeta:schedule")
                .acquire(2 * 60_000L)
        }
    }

    private const val POLL_MS = 2_000L
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
}
