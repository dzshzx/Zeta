package com.dzshzx.zeta.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechInputTest {
    @Test
    fun segmentsJoinFinishedSentencesAndTheOneInProgress() {
        val segments = SpeechSegments()
        segments.partial("今天")
        assertEquals("今天", segments.text())
        segments.partial("今天天气")
        segments.close("今天天气不错。")
        segments.partial("出去走走")
        assertEquals("今天天气不错。出去走走", segments.text())
        // 服务结束时没给最后结果（没听清、超时），用最后一次中间结果
        segments.close(null)
        assertEquals("今天天气不错。出去走走", segments.text())
    }

    @Test
    fun emptyPartialsDoNotEraseWhatWasHeard() {
        val segments = SpeechSegments()
        segments.partial("打开设置")
        segments.partial("")
        segments.partial(null)
        assertEquals("打开设置", segments.text())
        segments.close("")
        assertEquals("打开设置", segments.text())
        segments.close(null)
        assertEquals("打开设置", segments.text())
    }

    @Test
    fun latinWordsAcrossSegmentsGetASpace() {
        assertEquals("run go test", SpeechSegments.join(listOf("run", "go test")))
        assertEquals("用 Go写", SpeechSegments.join(listOf("用 Go", "写")))
        assertEquals("你好世界", SpeechSegments.join(listOf("你好", "世界")))
    }

    @Test
    fun levelIsClampedToZeroOne() {
        assertEquals(0f, SpeechSegments.level(-10f))
        assertEquals(1f, SpeechSegments.level(30f))
        assertEquals(0.5f, SpeechSegments.level(4f))
    }

    @Test
    fun configuredServiceWinsThenXiaomiThenFirst() {
        val xiaomi = SpeechBackend.XIAOMI_NAME
        val other = "com.example.asr/com.example.asr.Service"
        assertEquals(other, SpeechBackend.choose(listOf(xiaomi, other), configured = other))
        assertEquals(xiaomi, SpeechBackend.choose(listOf(other, xiaomi), configured = null))
        // 设置里指定的服务没装：不用它
        assertEquals(xiaomi, SpeechBackend.choose(listOf(other, xiaomi), configured = "gone/gone.Service"))
        assertEquals(other, SpeechBackend.choose(listOf(other), configured = null))
        assertNull(SpeechBackend.choose(emptyList(), configured = other))
    }

    @Test
    fun neverPicksZetasOwnProxy() {
        // 数字助理是 Zeta 时系统默认识别是 Zeta 的代理：按住说话和代理转给谁都跳过它，正式版、测试版不互相转
        val dev = "com.dzshzx.zeta.dev/com.dzshzx.zeta.assistant.ZetaRecognitionService"
        val release = "com.dzshzx.zeta/com.dzshzx.zeta.assistant.ZetaRecognitionService"
        val claude = "com.anthropic.claude/com.anthropic.claude.bell.assist.ClaudeRecognitionService"
        val xiaomi = SpeechBackend.XIAOMI_NAME
        assertEquals(xiaomi, SpeechBackend.choose(listOf(dev, release, claude, xiaomi), configured = dev))
        assertEquals(claude, SpeechBackend.choose(listOf(dev, release, claude), configured = release))
        assertNull(SpeechBackend.choose(listOf(dev, release), configured = dev))
    }
}
