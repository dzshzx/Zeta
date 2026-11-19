package com.dzshzx.zeta.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AssistantRoleTest {
    private val ours = "com.dzshzx.zeta.dev/com.dzshzx.zeta.assistant.ZetaRecognitionService"

    @Test
    fun recognizerCommandOnlyWhenNotAlreadyOurs() {
        assertNull(AssistantRole.recognizerCommand(ours, ours))
        // 短写和全写是同一个组件
        assertNull(AssistantRole.recognizerCommand("com.dzshzx.zeta/.assistant.ZetaRecognitionService", "com.dzshzx.zeta/com.dzshzx.zeta.assistant.ZetaRecognitionService"))
        assertEquals("settings put secure voice_recognition_service '$ours'", AssistantRole.recognizerCommand(null, ours))
        assertEquals("settings put secure voice_recognition_service '$ours'", AssistantRole.recognizerCommand("", ours))
        assertEquals(
            "settings put secure voice_recognition_service '$ours'",
            AssistantRole.recognizerCommand("com.xiaomi.mibrain.speech/.asr.AsrService", ours),
        )
    }

    @Test
    fun keepsOnBootWhenZetaAssistantHasItsOwnRecognizer() {
        val dev = "com.dzshzx.zeta.dev"
        assertTrue(AssistantOwner(self = true, packageName = dev, label = null, recognizerPackage = dev).keepsOnBoot)
        // 默认助理是 Zeta、识别服务空着：开机时系统会把默认助理重选掉
        assertFalse(AssistantOwner(self = true, packageName = dev, label = null, recognizerPackage = null).keepsOnBoot)
        assertFalse(AssistantOwner(self = false, packageName = "com.dzshzx.zeta", label = null, recognizerPackage = dev).keepsOnBoot)
        // 默认助理不是 Zeta：不归 Zeta 管
        assertTrue(AssistantOwner(self = false, packageName = "com.miui.voiceassist", label = null, recognizerPackage = null).keepsOnBoot)
        assertEquals("没有设置", AssistantOwner(true, dev, null, null).recognizerSummary)
        assertEquals("是 Zeta 测试版", AssistantOwner(true, dev, null, dev).recognizerSummary)
        assertEquals("是 Zeta 正式版", AssistantOwner(false, "com.dzshzx.zeta", null, null).summary)
    }
}
