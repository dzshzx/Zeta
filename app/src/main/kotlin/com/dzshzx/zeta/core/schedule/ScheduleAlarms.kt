package com.dzshzx.zeta.core.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.ui.MainActivity
import java.time.Instant
import java.time.ZoneId

/**
 * 把定时任务排进 AlarmManager。每个任务一个闹钟，按任务 id 区分（Intent 的 data），重排时原地替换。
 *
 * 用 setAlarmClock：在这台 HyperOS 上实测（dumpsys alarm），第三方应用的 setExact / setExactAndAllowWhileIdle
 * 会被系统对齐到几个固定时刻，推迟几十秒到几分钟；setAlarmClock 的闹钟不对齐，到点准时，还会把设备从深度休眠里叫醒，
 * 送达时给应用一小段可以起前台服务的豁免。代价是系统的“下一个闹钟”可能显示成 Zeta 的任务。
 * 没有精确闹钟权限时退回 setAndAllowWhileIdle（可能推迟），权限健康页里会提示去授予。
 */
internal object ScheduleAlarms {
    val ACTION_FIRE = AppVariant.current.action("SCHEDULE_FIRE")
    const val EXTRA_TASK = "task"

    /** 这个闹钟排的是哪个时刻（毫秒）：算下一次时从它往后算，不受送达早晚影响。 */
    const val EXTRA_AT = "at"

    /** 存在 SharedPreferences `zeta` 里的闹钟登记：排过闹钟的任务 id，下次重排时据此取消已经删掉的任务的闹钟。 */
    const val ARMED = "scheduleArmed"

    private fun manager(context: Context) = context.getSystemService(AlarmManager::class.java)

    /** 到点能不能准时（有精确闹钟权限）。 */
    fun exact(context: Context): Boolean = runCatching { manager(context).canScheduleExactAlarms() }.getOrDefault(false)

    private fun operation(context: Context, id: String, at: Long, create: Boolean): PendingIntent? {
        val intent = Intent(context, ScheduleReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.fromParts("zeta-schedule", id, null))
            .putExtra(EXTRA_TASK, id)
            .putExtra(EXTRA_AT, at)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /** 闹钟详情（下拉通知栏、锁屏点闹钟图标时）打开 Zeta。 */
    private fun showIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, SHOW_REQUEST, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 任务 [id] 的下一次排在 [at]（毫秒）。 */
    fun arm(context: Context, id: String, at: Long) {
        val app = context.applicationContext
        val manager = manager(app)
        val pending = operation(app, id, at, create = true) ?: return
        val exact = exact(app) && runCatching {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent(app)), pending)
        }.isSuccess
        if (!exact) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    fun cancel(context: Context, id: String) {
        val app = context.applicationContext
        operation(app, id, 0, create = false)?.let { pending ->
            manager(app).cancel(pending)
            pending.cancel()
        }
    }

    /**
     * 按清单重排全部闹钟：开着的排到下一次，关掉、删掉、已经没有下一次的取消。
     * 开机、应用更新、改时间或时区、精确闹钟权限变化、清单改动后都调一次。[now] 之后的才算下一次。
     */
    fun sync(context: Context, tasks: List<ScheduledTask>, now: Instant = Instant.now()) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("zeta", Context.MODE_PRIVATE)
        val zone = ZoneId.systemDefault()
        val armed = HashSet<String>()
        for (task in tasks) {
            val next = if (task.enabled) Schedules.nextTrigger(task.rule, now, zone) else null
            if (next == null) {
                cancel(app, task.id)
            } else {
                arm(app, task.id, next.toEpochMilli())
                armed.add(task.id)
            }
        }
        // 上次排过、现在清单里已经没有的（被删掉了）
        val before = prefs.getStringSet(ARMED, null).orEmpty()
        for (id in before - armed - tasks.map { it.id }.toSet()) cancel(app, id)
        prefs.edit().putStringSet(ARMED, armed).apply()
    }

    private const val SHOW_REQUEST = 8
}
