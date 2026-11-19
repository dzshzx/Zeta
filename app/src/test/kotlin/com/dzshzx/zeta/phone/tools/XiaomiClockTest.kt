package com.dzshzx.zeta.phone.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class XiaomiClockTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    // 真机 content query 的格式；标签是编的，故意带逗号和等号
    private val rows = """
        Row: 0 _id=1, hour=11, minutes=0, daysofweek=0, enabled=0, vibrate=1, alarmtime=1785034800000, message=
        Row: 1 _id=3, hour=13, minutes=0, daysofweek=96, enabled=1, vibrate=0, alarmtime=0, message=午睡, 别赖床, a=b
        Row: 2 _id=9, hour=3, minutes=33, daysofweek=0, enabled=1, vibrate=1, alarmtime=1795030380000, message=zeta-test
    """.trimIndent()

    @Test
    fun parsesAlarmRows() {
        val alarms = XiaomiClock.parseAlarms(rows)
        assertEquals(listOf(1L, 3L, 9L), alarms.map { it.id })
        assertEquals("", alarms[0].label)
        assertEquals("午睡, 别赖床, a=b", alarms[1].label)
        assertEquals(0x60, alarms[1].days)
        assertTrue(alarms[1].enabled)
        assertFalse(alarms[1].vibrate)
        assertEquals(1795030380000, alarms[2].nextAt)
    }

    @Test
    fun alarmLines() {
        val alarms = XiaomiClock.parseAlarms(rows)
        val now = 1795024516000
        assertEquals("#1 11:00 off, once", XiaomiClock.alarmLine(alarms[0], zone, now))
        assertEquals("#3 13:00 on, weekends, \"午睡, 别赖床, a=b\", no vibration", XiaomiClock.alarmLine(alarms[1], zone, now))
        assertEquals("#9 03:33 on, once, \"zeta-test\", next Thu 11-19 03:33", XiaomiClock.alarmLine(alarms[2], zone, now))
    }

    @Test
    fun days() {
        assertEquals(0, XiaomiClock.daysMask(null))
        assertEquals(0, XiaomiClock.daysMask("once"))
        assertEquals(0x1f, XiaomiClock.daysMask("weekdays"))
        assertEquals(0x7f, XiaomiClock.daysMask("everyday"))
        assertEquals(0b0000101, XiaomiClock.daysMask("mon, wed"))
        assertEquals(0b1000000, XiaomiClock.daysMask("Sunday"))
        assertTrue(runCatching { XiaomiClock.daysMask("mon,funday") }.exceptionOrNull() is ToolError)
        assertEquals("weekdays", XiaomiClock.daysText(0x1f))
        assertEquals("mon,wed,sun", XiaomiClock.daysText(0b1000101))
        assertEquals("once", XiaomiClock.daysText(0))
    }

    @Test
    fun commandsEscapeColonsAndQuote() {
        val add = XiaomiClock.addCommand(7, 30, 0x1f, "It's: up", vibrate = true)
        assertTrue(add, add.startsWith(SYSTEM_ENV + "content insert --uri content://com.android.deskclock/alarm/alarmassist "))
        assertTrue(add, add.contains("--bind 'hour:i:7' --bind 'minutes:i:30' --bind 'daysofweek:i:31' --bind 'enabled:b:true'"))
        assertTrue(add, add.contains("--bind 'message:s:It'\\''s\\: up'"))
        assertTrue(add, add.contains("--bind 'alert:s:content\\://settings/system/alarm_alert'"))

        val current = XiaomiClock.parseAlarms(rows)[1]
        val update = XiaomiClock.updateCommand(current, hour = null, minute = 15, days = null, enabled = false, label = null, vibrate = null)
        assertTrue(update, update.endsWith(
            "content update --uri content://com.android.deskclock/alarm/3/alarmassist " +
                "--bind 'enabled:b:false' --bind 'daysofweek:i:96' --bind 'hour:i:13' --bind 'minutes:i:15'",
        ))
        assertTrue(XiaomiClock.deleteCommand(3).endsWith("content delete --uri content://com.android.deskclock/alarm/3/alarmassist"))
        assertTrue(XiaomiClock.timerStartCommand(600).endsWith("content insert --uri content://com.android.deskclock/timercountdown --bind 'duration:l:600000'"))
        assertTrue(XiaomiClock.timerStateCommand(2).endsWith("--bind 'timestate:i:2'"))
    }

    @Test
    fun timerStates() {
        // 真机实测的四种状态
        val now = 1795024469000
        assertEquals("timer running: 9m54s left of 10m", XiaomiClock.timerText("Row: 0 timestate=1, endtime=1795025063895, duration=600000, timerremained=600000", now))
        assertEquals("timer paused: 9m46s left of 10m", XiaomiClock.timerText("Row: 0 timestate=2, endtime=1795025063895, duration=600000, timerremained=586305", now))
        assertEquals("no timer running (last one was cancelled)", XiaomiClock.timerText("Row: 0 timestate=0, endtime=1, duration=600000, timerremained=586305", now))
        assertEquals("no timer running (last one finished)", XiaomiClock.timerText("Row: 0 timestate=3, endtime=1, duration=720000, timerremained=720000", now))
        assertEquals("no timer set", XiaomiClock.timerText("No result found.", now))
    }

    @Test
    fun contentErrors() {
        assertEquals("Binding not well formed: alert:s:content://x", XiaomiClock.contentError("usage: …\n[ERROR] Binding not well formed: alert:s:content://x"))
        assertNull(XiaomiClock.contentError("Row: 0 _id=1"))
    }
}
