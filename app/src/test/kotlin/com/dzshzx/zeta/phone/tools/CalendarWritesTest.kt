package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.obj
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** 样本都是编造的。 */
class CalendarWritesTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun ms(text: String) = PersonalQueries.parseTime(text, zone)!!
    private fun utc(date: String) = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun input(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((key, value) in pairs) when (value) {
            is String -> put(key, value)
            is Number -> put(key, value)
            is Boolean -> put(key, value)
            is List<*> -> put(key, JsonArray(value.map { JsonPrimitive(it as Int) }))
            null -> put(key, JsonNull)
        }
    }

    private fun row(vararg pairs: Pair<String, Any?>): Row = pairs.associate { (key, value) ->
        key to when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        } as JsonElement
    }

    private fun JsonObject.long(key: String) = this[key]!!.jsonPrimitive.long

    @Test
    fun createTimedEventDefaultsToOneHour() {
        val values = CalendarWrites.createValues(input("title" to " Zeta 测试 ", "start" to "2026-11-20T14:00", "location" to "三楼"), 1, zone)
        assertEquals(1L, values.long("calendar_id"))
        assertEquals("Zeta 测试", values.str("title"))
        assertEquals(ms("2026-11-20T14:00"), values.long("dtstart"))
        assertEquals(ms("2026-11-20T15:00"), values.long("dtend"))
        assertEquals("Asia/Shanghai", values.str("eventTimezone"))
        assertEquals(0, values.int("allDay"))
        assertEquals("三楼", values.str("eventLocation"))
        assertNull(values["description"])
    }

    @Test
    fun createAllDayUsesUtcMidnightsAndExclusiveEnd() {
        val one = CalendarWrites.createValues(input("title" to "t", "start" to "2026-11-20", "all_day" to true), 1, zone)
        assertEquals(utc("2026-11-20"), one.long("dtstart"))
        assertEquals(utc("2026-11-21"), one.long("dtend"))
        assertEquals("UTC", one.str("eventTimezone"))
        assertEquals(1, one.int("allDay"))
        // 结束日和开始同一天时按一天算
        val same = CalendarWrites.createValues(input("title" to "t", "start" to "2026-11-20", "end" to "2026-11-20", "all_day" to true), 1, zone)
        assertEquals(utc("2026-11-21"), same.long("dtend"))
        val three = CalendarWrites.createValues(input("title" to "t", "start" to "2026-11-20", "end" to "2026-11-23", "all_day" to true), 1, zone)
        assertEquals(utc("2026-11-23"), three.long("dtend"))
    }

    @Test
    fun createRejectsBadInput() {
        assertThrows(BadInput::class.java) { CalendarWrites.createValues(input("start" to "2026-11-20T14:00"), 1, zone) }
        assertThrows(BadInput::class.java) { CalendarWrites.createValues(input("title" to "t"), 1, zone) }
        assertThrows(BadInput::class.java) { CalendarWrites.createValues(input("title" to "t", "start" to "2026-11-20T14:00", "end" to "2026-11-20T13:00"), 1, zone) }
        assertThrows(BadInput::class.java) { CalendarWrites.createValues(input("title" to "t", "start" to "next friday"), 1, zone) }
        assertThrows(BadInput::class.java) { CalendarWrites.createValues(input("title" to "x".repeat(501), "start" to "2026-11-20"), 1, zone) }
    }

    @Test
    fun remindersAreValidated() {
        assertNull(CalendarWrites.reminders(input()))
        assertEquals(emptyList<Int>(), CalendarWrites.reminders(input("reminders" to emptyList<Int>())))
        assertEquals(listOf(10, 30), CalendarWrites.reminders(input("reminders" to listOf(30, 10, 30))))
        assertThrows(BadInput::class.java) { CalendarWrites.reminders(input("reminders" to listOf(-1))) }
        assertThrows(BadInput::class.java) { CalendarWrites.reminders(input("reminders" to listOf(1, 2, 3, 4, 5, 6))) }
    }

    private val timed = row(
        "_id" to 5, "dtstart" to ms("2026-11-20T14:00"), "dtend" to ms("2026-11-20T15:30"), "duration" to null, "allDay" to 0,
        "eventTimezone" to "Asia/Shanghai", "calendar_id" to 1, "rrule" to null, "original_id" to null, "title" to "t",
    )

    @Test
    fun updateShiftsKeepingDuration() {
        val values = CalendarWrites.updateValues(input("start" to "2026-11-21T09:00"), timed, zone)
        assertEquals(ms("2026-11-21T09:00"), values.long("dtstart"))
        assertEquals(ms("2026-11-21T10:30"), values.long("dtend"))
        assertNull(values["title"])
        val endOnly = CalendarWrites.updateValues(input("end" to "2026-11-20T16:00"), timed, zone)
        assertEquals(ms("2026-11-20T14:00"), endOnly.long("dtstart"))
        assertEquals(ms("2026-11-20T16:00"), endOnly.long("dtend"))
        val titleOnly = CalendarWrites.updateValues(input("title" to "新标题"), timed, zone)
        assertEquals(setOf("title"), titleOnly.keys)
    }

    @Test
    fun updateSwitchesToAllDay() {
        assertThrows(BadInput::class.java) { CalendarWrites.updateValues(input("all_day" to true), timed, zone) }
        val values = CalendarWrites.updateValues(input("all_day" to true, "start" to "2026-11-22"), timed, zone)
        assertEquals(utc("2026-11-22"), values.long("dtstart"))
        assertEquals(utc("2026-11-23"), values.long("dtend"))
        assertEquals(1, values.int("allDay"))
    }

    @Test
    fun updateRefusesRecurringAndEmpty() {
        val recurring = timed + ("rrule" to JsonPrimitive("FREQ=DAILY"))
        assertTrue(CalendarWrites.recurring(recurring))
        assertFalse(CalendarWrites.recurring(timed))
        assertThrows(BadInput::class.java) { CalendarWrites.updateValues(input("title" to "x"), recurring, zone) }
        assertThrows(BadInput::class.java) { CalendarWrites.updateValues(input(), timed, zone) }
        // 只改提醒也算有改动
        assertTrue(CalendarWrites.updateValues(input("reminders" to listOf(15)), timed, zone).isEmpty())
    }

    @Test
    fun batchesForCreateUpdateDelete() {
        val values = CalendarWrites.createValues(input("title" to "t", "start" to "2026-11-20T14:00"), 1, zone)
        val create = CalendarWrites.createBatch(values, listOf(10, 30))
        assertEquals("batch", create.str("op"))
        assertEquals("com.android.calendar", create.str("authority"))
        val ops = create.arr("ops")!!.map { it.jsonObject }
        assertEquals(listOf("insert", "insert", "insert"), ops.map { it.str("type") })
        assertEquals(PersonalQueries.EVENTS, ops[0].str("uri"))
        assertEquals(0, ops[1].obj("refs")!!.int("event_id"))
        assertEquals(30, ops[2].obj("values")!!.int("minutes"))

        val update = CalendarWrites.updateBatch(5, buildJsonObject { put("title", "x") }, listOf(15)).arr("ops")!!.map { it.jsonObject }
        assertEquals(listOf("update", "delete", "insert"), update.map { it.str("type") })
        assertEquals("${PersonalQueries.EVENTS}/5", update[0].str("uri"))
        assertEquals(1, update[0].int("expect"))
        assertEquals("event_id = ?", update[1].str("selection"))
        assertEquals(5, update[2].obj("values")!!.int("event_id"))
        // 没给 reminders 时不动提醒
        assertEquals(listOf("update"), CalendarWrites.updateBatch(5, buildJsonObject { put("title", "x") }, null).arr("ops")!!.map { it.jsonObject.str("type") })

        val delete = CalendarWrites.deleteBatch(5).arr("ops")!!.single().jsonObject
        assertEquals("delete", delete.str("type"))
        assertEquals("${PersonalQueries.EVENTS}/5", delete.str("uri"))
    }

    @Test
    fun readsIdFromInsertedUri() {
        assertEquals(1234L, CalendarWrites.idFromUri("content://com.android.calendar/events/1234"))
        assertEquals(7L, CalendarWrites.idFromUri("content://com.android.calendar/events/7?caller_is_syncadapter=false"))
        assertNull(CalendarWrites.idFromUri(null))
        assertNull(CalendarWrites.idFromUri("content://com.android.calendar/events"))
    }
}
