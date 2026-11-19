package com.dzshzx.zeta.assistant

import android.os.Bundle
import com.dzshzx.zeta.xposed.PowerKeyGate
import com.dzshzx.zeta.xposed.PowerKeyPolicy
import com.dzshzx.zeta.xposed.PowerKeyRoute
import com.dzshzx.zeta.xposed.PowerKeySignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerKeyTest {
    private val zeta = "com.dzshzx.zeta/com.dzshzx.zeta.assistant.ZetaVoiceInteractionService"
    private val xiaoai = "com.miui.voiceassist/com.xiaomi.voiceassistant.AssistInteractionService"

    @Test
    fun onlyThePowerKeyAssistantActionIsOurs() {
        assertTrue(PowerKeyPolicy.matches("launch_voice_assistant", "long_press_power_key"))
        assertTrue(PowerKeyPolicy.matches("launch_voice_assistant", "imperceptible_press_power_key"))
        assertFalse(PowerKeyPolicy.matches("launch_voice_assistant", "long_press_home_key"))
        assertFalse(PowerKeyPolicy.matches("screen_shot", "long_press_power_key"))
        assertFalse(PowerKeyPolicy.matches("mi_pay", "double_click_power_key"))
        assertFalse(PowerKeyPolicy.matches(null, null))
    }

    @Test
    fun bothZetaPackagesCount() {
        assertEquals("com.dzshzx.zeta", PowerKeyPolicy.zetaPackage(zeta))
        assertEquals("com.dzshzx.zeta.dev", PowerKeyPolicy.zetaPackage("com.dzshzx.zeta.dev/com.dzshzx.zeta.assistant.ZetaVoiceInteractionService"))
        assertEquals("com.dzshzx.zeta", PowerKeyPolicy.zetaPackage("com.dzshzx.zeta/.assistant.ZetaVoiceInteractionService"))
        assertNull(PowerKeyPolicy.zetaPackage(xiaoai))
        assertNull(PowerKeyPolicy.zetaPackage("com.dzshzx.zetaclone/x.Y"))
        assertNull(PowerKeyPolicy.zetaPackage(""))
        assertNull(PowerKeyPolicy.zetaPackage(null))
    }

    @Test
    fun routes() {
        fun route(source: String = "long_press_power_key", assistant: String? = zeta, locked: Boolean = false, duplicate: Boolean = false, function: String = "launch_voice_assistant") =
            PowerKeyPolicy.route(function, source, assistant, locked, duplicate)
        assertEquals(PowerKeyRoute.Show, route())
        assertEquals(PowerKeyRoute.NotOurs, route(function = "launch_camera"))
        assertEquals(PowerKeyRoute.NotOurs, route(source = "long_press_home_key"))
        assertEquals(PowerKeyRoute.NotZeta, route(assistant = xiaoai))
        assertEquals(PowerKeyRoute.NotZeta, route(assistant = null))
        assertEquals(PowerKeyRoute.Locked, route(locked = true))
        assertEquals(PowerKeyRoute.Duplicate, route(duplicate = true))
        // 默认助理不是 Zeta 时不吞任何一次，照样交给小爱
        assertEquals(PowerKeyRoute.NotZeta, route(assistant = xiaoai, duplicate = true))
    }

    @Test
    fun gateCoversOnePress() {
        val gate = PowerKeyGate(windowMs = 1_000)
        assertFalse(gate.duplicate(5_000))
        gate.shown(5_000)
        assertTrue(gate.duplicate(5_400))
        assertTrue(gate.duplicate(6_000))
        assertFalse(gate.duplicate(6_001))
        assertFalse(gate.duplicate(4_000))
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    private class HyperOs4 {
        fun triggerFunction(function: String?, action: String?, extras: Bundle?, haptic: Boolean): Boolean = false
        fun triggerFunction(function: String?, action: String?, extras: Bundle?, haptic: Boolean, effect: String?): Boolean = false
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    private class OnlyFour {
        fun triggerFunction(function: String?, action: String?, extras: Bundle?, haptic: Boolean): Boolean = false
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    private class Changed {
        fun triggerFunction(function: String?, action: String?, extras: Bundle?): Boolean = false
        fun triggerFunction(function: String?, action: String?, extras: Bundle?, haptic: Boolean): Unit = Unit
    }

    @Test
    fun picksTheFiveArgumentOverloadWhenThere() {
        assertEquals(5, PowerKeySignature.pick(HyperOs4::class.java)!!.parameterCount)
        assertEquals("triggerFunction(String, String, Bundle, boolean, String)", PowerKeySignature.describe(PowerKeySignature.pick(HyperOs4::class.java)!!))
        assertEquals(4, PowerKeySignature.pick(OnlyFour::class.java)!!.parameterCount)
    }

    @Test
    fun mismatchedSignaturesAreNotHooked() {
        assertNull(PowerKeySignature.pick(Changed::class.java))
        val available = PowerKeySignature.available(Changed::class.java)
        assertTrue(available, available.contains("boolean triggerFunction(String, String, Bundle)"))
        assertTrue(available, available.contains("void triggerFunction(String, String, Bundle, boolean)"))
        assertEquals("没有", PowerKeySignature.available(String::class.java))
    }

    @Test
    fun logPrefersLsposedAndReadsInstallState() {
        val output = """
            [ 2026-11-20T09:01:02.345     1000:  1650:  1650 I/ZetaPowerKey ] 装上了：ShortCutActionsUtils.triggerFunction(String, String, Bundle, boolean, String)
            [ 2026-11-20T10:11:12.000     1000:  1650:  2010 I/ZetaPowerKey ] imperceptible_press_power_key：已显示 com.dzshzx.zeta 的助理会话，消费这次动作
            [ 2026-11-20T10:11:12.200     1000:  1650:  2010 I/ZetaPowerKey ] long_press_power_key：同一次按键的重复派发，已吞掉
            ${PowerKeyLog.SPLIT}
            11-20 10:11:12.000 I/ZetaPowerKey( 1650): imperceptible_press_power_key：已显示 com.dzshzx.zeta 的助理会话，消费这次动作
        """.trimIndent()
        val status = PowerKeyLog.parse(output)
        assertEquals(true, status.installed)
        assertEquals("已装上", status.summary)
        assertEquals(3, status.recent.size)
        assertEquals("09:01:02 装上了：ShortCutActionsUtils.triggerFunction(String, String, Bundle, boolean, String)", status.recent.first())
        assertEquals("10:11:12 long_press_power_key：同一次按键的重复派发，已吞掉", status.recent.last())
    }

    @Test
    fun logFallsBackToLogcat() {
        val output = PowerKeyLog.SPLIT + "\n" +
            "11-20 10:11:12.000 I/ZetaPowerKey( 1650): 没装：triggerFunction 签名对不上，系统里有的是：没有\n" +
            "11-20 10:12:00.000 I/ZetaPowerKey( 1650): long_press_power_key：默认助理不是 Zeta，走原逻辑（默认助理 com.miui.voiceassist）\n"
        val status = PowerKeyLog.parse(output)
        assertEquals(false, status.installed)
        assertEquals("triggerFunction 签名对不上，系统里有的是：没有", status.installNote)
        assertEquals("10:12:00 long_press_power_key：默认助理不是 Zeta，走原逻辑（默认助理 com.miui.voiceassist）", status.recent.last())
    }

    @Test
    fun emptyLogSaysNoRecords() {
        val status = PowerKeyLog.parse(PowerKeyLog.SPLIT + "\n--------- beginning of main\n")
        assertNull(status.installed)
        assertTrue(status.recent.isEmpty())
        assertEquals("没有记录", status.summary)
    }
}
