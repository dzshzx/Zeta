package com.dzshzx.zeta.core.diag

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagEntriesTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-11-19 10:00:00.123（北京时间）。 */
    private val at = 1_795_053_600_123L

    @Test
    fun encodeDecodeRoundTrip() {
        val entry = DiagEntry(at, DiagLevel.Warn, DiagTopic.Accessibility, "无障碍服务被关掉了\n第二行 \"引号\"")
        val line = DiagEntries.encode(entry)
        assertFalse("一条一行", line.contains('\n'))
        assertEquals(entry, DiagEntries.decode(line))
    }

    @Test
    fun badLinesAreSkipped() {
        assertNull(DiagEntries.decode(""))
        assertNull(DiagEntries.decode("{\"t\":1,\"l\":\"w\",\"c\":\"a11y\""))
        assertNull(
            "不认识的类别",
            DiagEntries.decode("{\"t\":1,\"l\":\"w\",\"c\":\"later\",\"m\":\"x\"}"),
        )
        assertNull("不认识的级别", DiagEntries.decode("{\"t\":1,\"l\":\"x\",\"c\":\"app\",\"m\":\"x\"}"))
        assertNull("缺时间", DiagEntries.decode("{\"l\":\"i\",\"c\":\"app\",\"m\":\"x\"}"))
    }

    @Test
    fun cleanTrimsAndCaps() {
        assertEquals("x", DiagEntries.clean("  x \n"))
        val long = "a".repeat(DiagEntries.MAX_MESSAGE + 50)
        val cleaned = DiagEntries.clean(long)
        assertTrue(cleaned.startsWith("a".repeat(DiagEntries.MAX_MESSAGE)))
        assertTrue(cleaned.endsWith("（截断）"))
    }

    @Test
    fun filterByTopicLevelAndText() {
        val info = DiagEntry(at, DiagLevel.Info, DiagTopic.Claude, "起 Claude 进程")
        val warn = DiagEntry(at, DiagLevel.Warn, DiagTopic.Accessibility, "无障碍服务被关掉了")
        val error = DiagEntry(at, DiagLevel.Error, DiagTopic.Helper, "helper list 出错（退出码 1）")
        val all = listOf(info, warn, error)
        assertEquals(all, all.filter { DiagEntries.matches(it, null, DiagLevel.Info) })
        assertEquals(
            listOf(warn, error),
            all.filter { DiagEntries.matches(it, null, DiagLevel.Warn) },
        )
        assertEquals(listOf(error), all.filter { DiagEntries.matches(it, null, DiagLevel.Error) })
        assertEquals(
            listOf(warn),
            all.filter { DiagEntries.matches(it, DiagTopic.Accessibility, DiagLevel.Info) },
        )
        assertEquals(
            emptyList<DiagEntry>(),
            all.filter { DiagEntries.matches(it, DiagTopic.Claude, DiagLevel.Warn) },
        )
        assertEquals(
            listOf(error),
            all.filter { DiagEntries.matches(it, null, DiagLevel.Info, " HELPER ") },
        )
    }

    @Test
    fun formatting() {
        val entry = DiagEntry(at, DiagLevel.Error, DiagTopic.Claude, "退出码 1\nstderr 第一行")
        assertEquals(
            "2026-11-19 10:00:00.123 错误 [Claude 进程] 退出码 1\n    stderr 第一行",
            DiagEntries.format(entry, zone),
        )
        assertEquals(
            "10:00:00",
            DiagEntries.shortTime(at, zone, today = LocalDate.of(2026, 11, 19)),
        )
        assertEquals(
            "11-19 10:00:00",
            DiagEntries.shortTime(at, zone, today = LocalDate.of(2026, 11, 20)),
        )
    }
}
