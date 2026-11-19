package com.dzshzx.zeta.core.schedule

import android.app.AlarmManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 定时任务的闹钟到点（[ScheduleAlarms.ACTION_FIRE]），以及要重排闹钟的几种系统广播：
 * 开机（闹钟随关机清空）、应用更新、改时间或时区（闹钟按绝对时间排，本地时刻会变）、精确闹钟权限给了。
 * Zeta 不在运行时系统会为这个广播把它拉起来。
 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as Application
        when (intent.action) {
            ScheduleAlarms.ACTION_FIRE -> {
                val id = intent.getStringExtra(ScheduleAlarms.EXTRA_TASK) ?: return
                ScheduleRunner.fire(app, id, plannedAt = intent.getLongExtra(ScheduleAlarms.EXTRA_AT, System.currentTimeMillis()))
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> ScheduleAlarms.sync(app, ScheduleStore.load(app))
        }
    }
}
