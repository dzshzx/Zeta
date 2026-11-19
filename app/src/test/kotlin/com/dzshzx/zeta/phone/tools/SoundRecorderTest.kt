package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** 录音的文字都是编造的。 */
class SoundRecorderTest {
    private fun row(vararg pairs: Pair<String, String?>): Row = pairs.associate { (key, value) ->
        key to (value?.let(::JsonPrimitive) ?: JsonNull) as JsonElement
    }

    @Test
    fun findsTheRecordByFileNameThenTextsBySha1() {
        val record = SoundRecorder.recordOp("录音 001.mp3")
        assertEquals(SoundRecorder.RECORDS, record.str("uri"))
        assertEquals("(file_name = ?)", record.str("selection"))
        assertEquals(listOf("录音 001.mp3"), record.arr("args")!!.map { it.jsonPrimitive.content })
        val texts = SoundRecorder.textOps("abc")
        assertEquals(listOf(SoundRecorder.SUMMARY, SoundRecorder.SENTENCES, SoundRecorder.TRANSCRIPTS), texts.map { it.str("uri") })
        assertEquals(listOf("(sha1 = ?)", "(sha1 = ?)", "(file_sha1 = ?)"), texts.map { it.str("selection") })
    }

    @Test
    fun pullsTextOutOfJson() {
        assertEquals("纯文字", SoundRecorder.text(" 纯文字 "))
        assertEquals(
            "周会\n讨论排期",
            SoundRecorder.text("""{"id":"0f3a9c1e-5b7d-4c2a-9e8f-1a2b3c4d5e6f","lang":"zh_CN","time":"2026-11-19 09:00","title":"周会","items":[{"text":"讨论排期"}]}"""),
        )
        assertEquals("{坏的", SoundRecorder.text("{坏的"))
        assertEquals("", SoundRecorder.text(null))
    }

    @Test
    fun summaryTakesThemeAndBody() {
        // 旧的只有 resultContent，正文里可能还套着 JSON
        assertEquals("要点一\n要点二", SoundRecorder.summary("""{"code":"0","traceId":"x","resultContent":"[\"要点一\",\"要点二\"]"}"""))
        assertEquals("纯文字摘要", SoundRecorder.summary("纯文字摘要"))
        assertEquals("", SoundRecorder.summary(null))
    }

    @Test
    fun formatsNoteSummaryAndTranscript() {
        val text = SoundRecorder.format(
            row("remark" to "周会", "content" to null),
            listOf(row("summary_result" to """{"code":"0","traceId":"5f0c2a9b7e3d4c1a8b6f","theme":"排期","summaryContent":"定了下周的排期","aiAsks":["还有什么"]}""")),
            listOf(row("speaker" to "说话人1", "content" to "大家好"), row("speaker" to null, "content" to "开始吧"), row("speaker" to "说话人2", "content" to " "), row("speaker" to "3", "content" to "好")),
            listOf(row("original_content" to "不该用到")),
        )
        assertEquals("录音机备注：周会\n录音机摘要：\n主题：排期\n定了下周的排期\n录音机转写：\n说话人1：大家好\n开始吧\n说话人3：好", text)
        // 没有逐句的，用整段的
        assertEquals(
            "录音机转写：\n整段文字",
            SoundRecorder.format(row("content" to null), emptyList(), emptyList(), listOf(row("original_content" to "整段文字"))),
        )
        assertEquals("录音机里这条录音没有备注、摘要或转写。", SoundRecorder.format(row(), emptyList(), emptyList(), emptyList()))
    }
}
