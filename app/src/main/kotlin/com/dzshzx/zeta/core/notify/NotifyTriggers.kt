package com.dzshzx.zeta.core.notify

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.schedule.RunRecord
import com.dzshzx.zeta.core.schedule.RunRecords
import com.dzshzx.zeta.core.schedule.RunSpec
import com.dzshzx.zeta.core.schedule.ScheduleRunner
import java.time.ZoneId

/** 通知触发的规则的运行记录写在规则清单里。每次都开新对话，没有接着用的会话。 */
internal object NotifyRecords : RunRecords {
    override val kind = "notify"

    override fun name(context: Context, id: String): String? = NotifyRuleStore.find(context, id)?.name

    override fun update(context: Context, id: String, change: (RunRecord) -> RunRecord) {
        NotifyRuleStore.updateRule(context, id) { rule ->
            val next = change(RunRecord(rule.lastRun, rule.skips, rule.lastSkip, newSession = true, sessionId = null))
            rule.copy(lastRun = next.lastRun, skips = next.skips, lastSkip = next.lastSkip)
        }
    }
}

/**
 * 通知到了以后：挑出该触发的规则（应用、关键词、时段、冷却、每天上限），记一次触发，交给 [ScheduleRunner] 在后台跑。
 * 跑完的通知、点进去打开对话，都和定时任务一样。在主线程上调用。
 */
internal object NotifyTriggers {
    private val seen = SeenNotifications()

    fun onPosted(app: Application, n: PostedNotification) {
        if (AppVariant.isZetaPackage(n.packageName)) return
        val rules = NotifyRuleStore.load(app)
        // 绝大多数通知和哪条规则都无关：先按包名筛掉，不碰别的
        if (rules.none { it.enabled && it.packageName == n.packageName }) return
        if (!seen.firstTime(n.key, n.postTime)) return
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        for (rule in NotifyRules.triggered(rules, n, now, zone)) {
            NotifyRuleStore.updateRule(app, rule.id) { NotifyRules.recordFire(it, now, zone) }
            val reason = ScheduleRunner.start(app, NotifyRecords, spec(rule, n), manual = false)
            if (reason != null) Log.i(TAG, "rule ${rule.id}: $reason")
        }
    }

    private fun spec(rule: NotifyRule, n: PostedNotification) = RunSpec(
        id = rule.id,
        name = rule.name,
        prompt = NotifyRules.render(rule.prompt, n, rule.appLabel, NotifyRules.nonce()),
        cwd = rule.cwd,
        model = rule.model,
        permissionMode = rule.permissionMode,
        newSession = true,
        sessionId = null,
        hint = NotifyRules.hint(rule.name),
    )

    /** 从系统给的通知里取规则要看的那些。正文先取展开后的长文本，没有时取聊天消息或多行摘要的最后几条。 */
    fun read(sbn: StatusBarNotification): PostedNotification {
        val n = sbn.notification
        val extras = n.extras ?: Bundle.EMPTY
        fun text(key: String) = extras.getCharSequence(key)?.toString().orEmpty().trim()
        val messages = runCatching {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)?.mapNotNull { message ->
                val bundle = message as? Bundle ?: return@mapNotNull null
                val body = bundle.getCharSequence("text")?.toString() ?: return@mapNotNull null
                val sender = bundle.getCharSequence("sender")?.toString()
                if (sender.isNullOrBlank()) body else "$sender: $body"
            }?.takeLast(5)?.joinToString("\n")
        }.getOrNull().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.takeLast(5)?.joinToString("\n").orEmpty()
        val body = text(Notification.EXTRA_BIG_TEXT).ifBlank { text(Notification.EXTRA_TEXT) }.ifBlank { messages.ifBlank { lines } }
        return PostedNotification(
            key = sbn.key,
            postTime = sbn.postTime,
            packageName = sbn.packageName,
            title = text(Notification.EXTRA_TITLE).ifBlank { text(Notification.EXTRA_TITLE_BIG) },
            text = body,
            ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        )
    }

    private const val TAG = "ZetaNotify"
}

/**
 * 通知使用权（系统设置里的“通知使用权”）：别的应用发通知时系统实时回调这里，通知触发的规则靠它。
 * 回调在主线程上。组件名随包名变，正式版和测试版各是各的。
 */
class ZetaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = this
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 一条通知出错不能让监听停掉，别的通知照常处理
        runCatching { NotifyTriggers.onPosted(application, NotifyTriggers.read(sbn)) }
            .onFailure { Log.w("ZetaNotify", "notification ${sbn.packageName}: ${it.javaClass.simpleName}") }
    }

    companion object {
        /** 本包的通知监听组件名（“包名/完整类名”）。 */
        val COMPONENT: String = AppVariant.current.component(ZetaNotificationListener::class.java.name)

        @Volatile private var connected: ZetaNotificationListener? = null

        /** 系统里给了 Zeta 通知使用权。 */
        fun granted(context: Context): Boolean = runCatching {
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName.unflattenFromString(COMPONENT)!!)
        }.getOrDefault(false)

        /**
         * 经 root 授予（`$2` 组件名，`$3` 用户）：先放开“受限设置”（侧载的应用不放开的话，系统设置里也开不了），
         * 再把 Zeta 加进通知使用权，别的应用的不动。
         */
        const val GRANT_SCRIPT = "cmd appops set --user \"\$3\" \"\${2%%/*}\" ACCESS_RESTRICTED_SETTINGS allow; " +
            "cmd notification allow_listener \"\$2\" \"\$3\""

        /** 通知栏里现在有通知的应用（包名），选应用时排在前面。没连上时为空。 */
        fun activePackages(): List<String> = runCatching {
            connected?.activeNotifications.orEmpty().map { it.packageName }.filterNot(AppVariant::isZetaPackage).distinct()
        }.getOrDefault(emptyList())
    }
}
