package com.dzshzx.zeta.phone.tools

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * clock：小米时钟（com.android.deskclock）的闹钟和倒计时，经它的 ContentProvider 读写，不打开界面。
 *
 * 实测（HyperOS 4，时钟 18.28）：
 * - 闹钟表 `content://com.android.deskclock/alarm`，daysofweek 是位掩码，周一是第 0 位、周日是第 6 位。
 * - 增、改、删要走 `alarm/alarmassist`、`alarm/<id>/alarmassist`（小爱同学用的路径）：时钟会重算下次响铃并向
 *   AlarmManager 重新登记。直接改 `alarm/<id>` 只改库，不重新登记，所以不用。改的时候 enabled、daysofweek、
 *   hour、minutes 四项要一起给，少一项时钟算不出下次响铃时间。
 * - 倒计时只有一个：`timercountdown`。insert 带 duration（毫秒）开始，update timestate=2 暂停、=1 继续，
 *   delete 取消（只是发取消广播）。timestate：1 计时中，2 暂停，0 已取消，3 已结束。
 */
internal object XiaomiClock {
    const val PACKAGE = "com.android.deskclock"
    private const val ALARMS = "content://com.android.deskclock/alarm"
    private const val TIMER = "content://com.android.deskclock/timercountdown"
    private const val DEFAULT_ALERT = "content://settings/system/alarm_alert"

    val ACTIONS = listOf("list_alarms", "add_alarm", "update_alarm", "delete_alarm", "start_timer", "pause_timer", "resume_timer", "cancel_timer", "timer_status")

    val ALARM_COLUMNS = listOf("_id", "hour", "minutes", "daysofweek", "enabled", "vibrate", "alarmtime", "message")
    val TIMER_COLUMNS = listOf("timestate", "endtime", "duration", "timerremained")

    private val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    class Alarm(val id: Long, val hour: Int, val minute: Int, val days: Int, val enabled: Boolean, val vibrate: Boolean, val nextAt: Long, val label: String)

    fun listCommand(): String = SYSTEM_ENV + "content query --uri $ALARMS --projection " + ALARM_COLUMNS.joinToString(":")

    fun parseAlarms(output: String): List<Alarm> = contentRows(output, ALARM_COLUMNS).mapNotNull { row ->
        Alarm(
            id = row["_id"]?.toLongOrNull() ?: return@mapNotNull null,
            hour = row["hour"]?.toIntOrNull() ?: return@mapNotNull null,
            minute = row["minutes"]?.toIntOrNull() ?: return@mapNotNull null,
            days = row["daysofweek"]?.toIntOrNull() ?: 0,
            enabled = row["enabled"] == "1",
            vibrate = row["vibrate"] == "1",
            nextAt = row["alarmtime"]?.toLongOrNull() ?: 0,
            label = row["message"]?.takeUnless { it == "NULL" || it == "null" }.orEmpty(),
        )
    }

    /** 「mon,wed」「weekdays」「每天」→ 位掩码；空或 once → 0（只响一次）。认不得时抛 [ToolError]。 */
    fun daysMask(value: String?): Int {
        val v = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        when (v) {
            "", "once", "none", "一次", "只响一次" -> return 0
            "everyday", "every day", "daily", "每天" -> return 0x7f
            "weekdays", "workdays", "工作日", "周一到周五" -> return 0x1f
            "weekends", "weekend", "周末" -> return 0x60
        }
        var mask = 0
        for (part in v.split(',', ' ', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }) {
            val i = DAYS.indexOf(part.take(3))
            if (i < 0) throw ToolError("days 不认识「$part」：用 mon,tue,wed,thu,fri,sat,sun，或 weekdays、weekends、everyday、once")
            mask = mask or (1 shl i)
        }
        return mask
    }

    fun daysText(mask: Int): String = when (mask and 0x7f) {
        0 -> "once"
        0x7f -> "every day"
        0x1f -> "weekdays"
        0x60 -> "weekends"
        else -> DAYS.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(",")
    }

    fun alarmLine(alarm: Alarm, zone: ZoneId, now: Long): String = buildString {
        append("#").append(alarm.id).append(' ')
        append(String.format(Locale.ROOT, "%02d:%02d", alarm.hour, alarm.minute))
        append(if (alarm.enabled) " on" else " off")
        append(", ").append(daysText(alarm.days))
        if (alarm.label.isNotEmpty()) append(", \"").append(alarm.label).append('"')
        if (!alarm.vibrate) append(", no vibration")
        if (alarm.enabled && alarm.nextAt > now) append(", next ").append(TIME.format(Instant.ofEpochMilli(alarm.nextAt).atZone(zone)))
    }

    /** content 的 --bind 值里冒号要转义。 */
    private fun bind(column: String, type: Char, value: String): String = "--bind " + shQuote("$column:$type:" + value.replace(":", "\\:"))

    fun addCommand(hour: Int, minute: Int, days: Int, label: String, vibrate: Boolean): String =
        SYSTEM_ENV + "content insert --uri $ALARMS/alarmassist " + listOf(
            bind("hour", 'i', "$hour"), bind("minutes", 'i', "$minute"), bind("daysofweek", 'i', "$days"),
            bind("enabled", 'b', "true"), bind("vibrate", 'b', "$vibrate"), bind("message", 's', label),
            bind("alert", 's', DEFAULT_ALERT), bind("deleteAfterUse", 'b', "false"),
        ).joinToString(" ")

    /** 改一个闹钟：四个必需项照新值（没改的沿用 [current]），标签和振动只在给了时带上。 */
    fun updateCommand(current: Alarm, hour: Int?, minute: Int?, days: Int?, enabled: Boolean?, label: String?, vibrate: Boolean?): String =
        SYSTEM_ENV + "content update --uri $ALARMS/${current.id}/alarmassist " + buildList {
            add(bind("enabled", 'b', "${enabled ?: current.enabled}"))
            add(bind("daysofweek", 'i', "${days ?: current.days}"))
            add(bind("hour", 'i', "${hour ?: current.hour}"))
            add(bind("minutes", 'i', "${minute ?: current.minute}"))
            if (label != null) add(bind("message", 's', label))
            if (vibrate != null) add(bind("vibrate", 'b', "$vibrate"))
        }.joinToString(" ")

    fun deleteCommand(id: Long): String = SYSTEM_ENV + "content delete --uri $ALARMS/$id/alarmassist"

    fun timerStatusCommand(): String = SYSTEM_ENV + "content query --uri $TIMER --projection " + TIMER_COLUMNS.joinToString(":")

    fun timerStartCommand(seconds: Int): String = SYSTEM_ENV + "content insert --uri $TIMER " + bind("duration", 'l', "${seconds * 1000L}")

    fun timerStateCommand(state: Int): String = SYSTEM_ENV + "content update --uri $TIMER " + bind("timestate", 'i', "$state")

    fun timerCancelCommand(): String = SYSTEM_ENV + "content delete --uri $TIMER"

    /** 计时器状态说成一句。 */
    fun timerText(output: String, now: Long): String {
        val row = contentRows(output, TIMER_COLUMNS).firstOrNull() ?: return "no timer set"
        val state = row["timestate"]?.toIntOrNull()
        val duration = row["duration"]?.toLongOrNull() ?: 0
        val end = row["endtime"]?.toLongOrNull() ?: 0
        val remained = row["timerremained"]?.toLongOrNull() ?: 0
        return when (state) {
            1 -> "timer running: ${spoken(maxOf(0, end - now))} left of ${spoken(duration)}"
            2 -> "timer paused: ${spoken(remained)} left of ${spoken(duration)}"
            0 -> "no timer running (last one was cancelled)"
            3 -> "no timer running (last one finished)"
            else -> "timer state $state, duration ${spoken(duration)}"
        }
    }

    /** `content` 出错时退出码也是 0，错误写在输出里。 */
    fun contentError(output: String): String? {
        val line = output.lineSequence().firstOrNull { it.startsWith("[ERROR]") || it.startsWith("Error while accessing provider") || it.contains("Exception:") }
        return line?.removePrefix("[ERROR]")?.trim()?.take(300)
    }

    private val TIME = DateTimeFormatter.ofPattern("EEE MM-dd HH:mm", Locale.ROOT)
}
