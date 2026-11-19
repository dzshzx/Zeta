package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * edit_calendar 的纯逻辑：校验参数，算出要写进 CalendarContract 的值，拼成一次事务（[ProviderHelper] 的 batch）。
 * 全天日程按 Android 的约定存 UTC 零点、结束日不含、时区 UTC；其余按 [ZoneId] 存。
 */
internal object CalendarWrites {
    const val MAX_TITLE = 500
    const val MAX_LOCATION = 500
    const val MAX_DESCRIPTION = 8_000
    const val MAX_REMINDERS = 5
    const val MAX_REMINDER_MINUTES = 40_320

    /** CalendarContract.Reminders.METHOD_ALERT */
    private const val METHOD_ALERT = 1

    private fun utcMidnight(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun text(input: JsonObject, key: String, max: Int): String? = input.str(key)?.trim()?.also {
        if (it.length > max) throw BadInput("$key 最多 $max 字")
    }

    private fun date(input: JsonObject, key: String, zone: ZoneId): LocalDate? = input.str(key)?.takeIf { it.isNotBlank() }?.let {
        PersonalQueries.parseDate(it, zone) ?: throw BadInput("$key 读不出来：全天日程用日期，例如 2026-11-19")
    }

    private fun time(input: JsonObject, key: String, zone: ZoneId): Long? = input.str(key)?.takeIf { it.isNotBlank() }?.let {
        PersonalQueries.parseTime(it, zone) ?: throw BadInput("$key 读不出来：用 ISO 8601，例如 2026-11-19T14:00 或 2026-11-19T14:00+08:00")
    }

    /** 全天日程的结束日（不含）：没给或和开始同一天时取第二天。 */
    private fun allDayEnd(start: LocalDate, end: LocalDate?): LocalDate = when {
        end == null || end == start -> start.plusDays(1)
        end < start -> throw BadInput("end 不能早于 start")
        else -> end
    }

    /** reminders：提前多少分钟提醒。没给返回 null（不动原有的提醒），空数组表示清掉。 */
    fun reminders(input: JsonObject): List<Int>? {
        val array = input["reminders"] as? JsonArray ?: return null
        if (array.size > MAX_REMINDERS) throw BadInput("reminders 最多 $MAX_REMINDERS 个")
        return array.map { element ->
            (element as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..MAX_REMINDER_MINUTES }
                ?: throw BadInput("reminders 里是提前的分钟数，0 到 $MAX_REMINDER_MINUTES")
        }.distinct().sorted()
    }

    /** 新建日程要写的值。 */
    fun createValues(input: JsonObject, calendarId: Long, zone: ZoneId): JsonObject {
        val title = text(input, "title", MAX_TITLE)?.takeIf { it.isNotEmpty() } ?: throw BadInput("create 需要 title")
        if (input.str("start").isNullOrBlank()) throw BadInput("create 需要 start")
        val allDay = input.bool("all_day") == true
        return buildJsonObject {
            put("calendar_id", calendarId)
            put("title", title)
            if (allDay) {
                val start = date(input, "start", zone)!!
                val end = allDayEnd(start, date(input, "end", zone))
                put("dtstart", utcMidnight(start))
                put("dtend", utcMidnight(end))
                put("eventTimezone", "UTC")
                put("allDay", 1)
            } else {
                val start = time(input, "start", zone)!!
                val end = time(input, "end", zone) ?: (start + PersonalQueries.HOUR)
                if (end <= start) throw BadInput("end 必须晚于 start")
                put("dtstart", start)
                put("dtend", end)
                put("eventTimezone", zone.id)
                put("allDay", 0)
            }
            text(input, "location", MAX_LOCATION)?.let { put("eventLocation", it) }
            text(input, "description", MAX_DESCRIPTION)?.let { put("description", it) }
        }
    }

    /** 重复日程（或重复日程里单独改过的一次）。 */
    fun recurring(event: Row): Boolean = !event.text("rrule").isNullOrBlank() || event.long("original_id") != null

    /**
     * 修改日程要写的值，[event] 是现有的那一行（[PersonalQueries.EVENT_COLUMNS]）。只给了 start 时保持原来的时长平移；
     * 在全天和定时之间切换时要给 start。
     */
    fun updateValues(input: JsonObject, event: Row, zone: ZoneId): JsonObject {
        if (recurring(event)) throw BadInput("这是重复日程，不支持修改：可以删掉整个系列（series: true）再新建")
        val wasAllDay = event.long("allDay") == 1L
        val allDay = input.bool("all_day") ?: wasAllDay
        val oldStart = event.long("dtstart") ?: throw BadInput("这条日程没有开始时间")
        val oldEnd = event.long("dtend") ?: (oldStart + if (wasAllDay) PersonalQueries.DAY else PersonalQueries.HOUR)
        val startGiven = !input.str("start").isNullOrBlank()
        val endGiven = !input.str("end").isNullOrBlank()
        if (allDay != wasAllDay && !startGiven) throw BadInput("在全天和定时之间切换时要给 start")
        val values = buildJsonObject {
            text(input, "title", MAX_TITLE)?.let {
                if (it.isEmpty()) throw BadInput("title 不能为空")
                put("title", it)
            }
            text(input, "location", MAX_LOCATION)?.let { put("eventLocation", it) }
            text(input, "description", MAX_DESCRIPTION)?.let { put("description", it) }
            if (startGiven || endGiven || allDay != wasAllDay) {
                if (allDay) {
                    val oldFirst = Instant.ofEpochMilli(oldStart).atZone(ZoneOffset.UTC).toLocalDate()
                    val oldDays = ((oldEnd - oldStart) / PersonalQueries.DAY).coerceAtLeast(1)
                    val start = date(input, "start", zone) ?: oldFirst
                    val end = date(input, "end", zone)
                        ?: if (startGiven) start.plusDays(oldDays) else Instant.ofEpochMilli(oldEnd).atZone(ZoneOffset.UTC).toLocalDate()
                    put("dtstart", utcMidnight(start))
                    put("dtend", utcMidnight(allDayEnd(start, end)))
                    put("eventTimezone", "UTC")
                    put("allDay", 1)
                } else {
                    val start = time(input, "start", zone) ?: oldStart
                    val end = time(input, "end", zone) ?: if (startGiven) start + (oldEnd - oldStart) else oldEnd
                    if (end <= start) throw BadInput("end 必须晚于 start")
                    put("dtstart", start)
                    put("dtend", end)
                    put("eventTimezone", if (wasAllDay) zone.id else event.text("eventTimezone") ?: zone.id)
                    put("allDay", 0)
                }
            }
        }
        if (values.isEmpty() && reminders(input) == null) throw BadInput("update 没有要改的字段")
        return values
    }

    /** 新建：插入日程，再插入提醒（event_id 取第一步的结果）。 */
    fun createBatch(values: JsonObject, reminders: List<Int>): JsonObject = batch {
        addJsonObject {
            put("type", "insert")
            put("uri", PersonalQueries.EVENTS)
            put("values", values)
        }
        for (minutes in reminders) addJsonObject {
            put("type", "insert")
            put("uri", PersonalQueries.REMINDERS)
            putJsonObject("values") { put("minutes", minutes); put("method", METHOD_ALERT) }
            putJsonObject("refs") { put("event_id", 0) }
        }
    }

    /** 修改：改日程（必须正好改到一行），给了 reminders 时整组换掉。 */
    fun updateBatch(id: Long, values: JsonObject, reminders: List<Int>?): JsonObject = batch {
        if (values.isNotEmpty()) addJsonObject {
            put("type", "update")
            put("uri", "${PersonalQueries.EVENTS}/$id")
            put("values", values)
            put("expect", 1)
        }
        if (reminders != null) {
            addJsonObject {
                put("type", "delete")
                put("uri", PersonalQueries.REMINDERS)
                put("selection", "event_id = ?")
                putJsonArray("args") { add(JsonPrimitive(id.toString())) }
            }
            for (minutes in reminders) addJsonObject {
                put("type", "insert")
                put("uri", PersonalQueries.REMINDERS)
                putJsonObject("values") { put("event_id", id); put("minutes", minutes); put("method", METHOD_ALERT) }
            }
        }
    }

    /** 删除一条日程（必须正好删到一行）。重复日程会连同全部重复一起删。 */
    fun deleteBatch(id: Long): JsonObject = batch {
        addJsonObject {
            put("type", "delete")
            put("uri", "${PersonalQueries.EVENTS}/$id")
            put("expect", 1)
        }
    }

    private fun batch(build: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("op", "batch")
        put("authority", PersonalQueries.CALENDAR)
        putJsonArray("ops", build)
    }

    /** 插入结果的 URI（content://com.android.calendar/events/123）里的编号。 */
    fun idFromUri(uri: String?): Long? = uri?.substringAfterLast('/')?.substringBefore('?')?.toLongOrNull()
}
