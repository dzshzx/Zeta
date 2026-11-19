package com.dzshzx.zeta.phone.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSwitchesTest {
    // 真机 `cmd wifi status | head -6`，网络名、MAC、IP 换成了假的
    private val wifiStatus = """
        Wifi is enabled
        Wifi scanning is always available
        ==== ClientModeManager instance: ConcreteClientModeManager{id=458689365 iface=wlan0 role=ROLE_CLIENT_PRIMARY} ====
        Wifi is connected to "Home_5G"
        WifiInfo: SSID: "Home_5G", BSSID: 00:11:22:33:44:55, MAC: 02:00:00:00:00:00, IP: /192.168.1.20, Security type: 2, Supplicant state: COMPLETED, Wi-Fi standard: 11ax, RSSI: -41, Link speed: 1200Mbps
    """.trimIndent()

    @Test
    fun describesWifi() {
        assertEquals("on (connected to \"Home_5G\", -41 dBm)", DeviceSwitches.describe("wifi", wifiStatus))
        assertEquals("on (not connected)", DeviceSwitches.describe("wifi", "Wifi is enabled\nWifi scanning is always available"))
        assertEquals("off", DeviceSwitches.describe("wifi", "Wifi is disabled\n"))
        assertNull(DeviceSwitches.describe("wifi", "Can't find service: wifi"))
    }

    @Test
    fun describesSettingsBackedTargets() {
        assertEquals("on", DeviceSwitches.describe("bluetooth", "1\n"))
        assertEquals("off", DeviceSwitches.describe("airplane_mode", "0"))
        assertEquals("on", DeviceSwitches.describe("mobile_data", "1"))
        assertNull(DeviceSwitches.describe("battery_saver", "null"))
        assertEquals("on", DeviceSwitches.describe("location", "true"))
        assertEquals("on", DeviceSwitches.describe("nfc", "Nfc is enabled"))
        assertEquals("off", DeviceSwitches.describe("dark_mode", "Night mode: no"))
        assertEquals("on", DeviceSwitches.describe("dark_mode", "Night mode: yes"))
        assertEquals("on", DeviceSwitches.describe("dark_mode", "Night mode: yes\n1"))
        assertEquals("off", DeviceSwitches.describe("dark_mode", "Night mode: no\nnull"))
        assertEquals("off (HyperOS dark_mode_enable=1)", DeviceSwitches.describe("dark_mode", "Night mode: no\n1"))
        assertEquals("10%", DeviceSwitches.describe("brightness", "10.07708"))
        assertEquals("600s", DeviceSwitches.describe("screen_timeout", "600000"))
        assertEquals("never", DeviceSwitches.describe("screen_timeout", "2147483647"))
        assertEquals("priority", DeviceSwitches.describe("do_not_disturb", "1"))
        assertEquals("alarms", DeviceSwitches.describe("do_not_disturb", "3"))
        assertEquals("vibrate", DeviceSwitches.describe("ringer_mode", "1"))
    }

    @Test
    fun hotspotFromTetherState() {
        // 真机 dumpsys tethering 的 Tether state 一节（热点关着）
        val off = "  Tether state:\n    p2p0 - AvailableState - lastError = 0\n    Upstream wanted: false\n    Current upstream interface(s): null\n  Hardware offload:"
        assertEquals("off", DeviceSwitches.describe("hotspot", off))
        val on = "  Tether state:\n    p2p0 - AvailableState - lastError = 0\n    wlan1 - TetheredState - lastError = 0\n    Upstream wanted: true"
        assertEquals("on", DeviceSwitches.describe("hotspot", on))
        // USB 共享不算热点
        assertFalse(DeviceSwitches.hotspotOn("  Tether state:\n    rndis0 - TetheredState - lastError = 0")!!)
        assertNull(DeviceSwitches.hotspotOn(""))
    }

    @Test
    fun switchValues() {
        assertEquals(true, DeviceSwitches.switchValue("ON"))
        assertEquals(true, DeviceSwitches.switchValue("true"))
        assertEquals(false, DeviceSwitches.switchValue(" off "))
        assertEquals(false, DeviceSwitches.switchValue("0"))
        assertNull(DeviceSwitches.switchValue("maybe"))
        assertNull(DeviceSwitches.switchValue(null))
    }

    @Test
    fun setCommands() {
        fun cmd(target: String, value: String) = DeviceSwitches.setPlan(target, value).command
        assertEquals("cmd wifi set-wifi-enabled disabled", cmd("wifi", "off"))
        assertEquals("cmd wifi set-wifi-enabled enabled", cmd("wifi", "on"))
        assertEquals("cmd bluetooth_manager disable", cmd("bluetooth", "off"))
        assertEquals("svc data disable", cmd("mobile_data", "off"))
        assertEquals("cmd connectivity airplane-mode enable", cmd("airplane_mode", "on"))
        assertEquals("cmd location set-location-enabled false", cmd("location", "off"))
        assertEquals("cmd nfc disable-nfc persist", cmd("nfc", "off"))
        assertEquals("cmd nfc enable-nfc", cmd("nfc", "on"))
        assertEquals("cmd uimode night yes && settings put system dark_mode_enable 1", cmd("dark_mode", "on"))
        assertEquals("cmd uimode night no && settings put system dark_mode_enable 0", cmd("dark_mode", "off"))
        assertEquals("cmd power set-mode 1", cmd("battery_saver", "on"))
        assertEquals("settings put system accelerometer_rotation 0", cmd("auto_rotate", "off"))
        assertEquals("settings put system screen_brightness_mode 1", cmd("auto_brightness", "on"))
        assertEquals(
            "settings put system screen_brightness_mode 0 && cmd display set-brightness 0.1410",
            cmd("brightness", "45%"),
        )
        assertEquals("45%", DeviceSwitches.setPlan("brightness", "45").expect)
        // 量过的点原样落回，两点之间线性插值，两头夹住
        assertEquals("0.0300", DeviceSwitches.brightnessValue(22.38))
        assertEquals("1.0000", DeviceSwitches.brightnessValue(100.0))
        assertEquals("0.0000", DeviceSwitches.brightnessValue(0.0))
        assertEquals("0.0567", DeviceSwitches.brightnessValue(28.43))
        assertTrue(DeviceSwitches.reached("brightness", "33%", "30%"))
        assertFalse(DeviceSwitches.reached("brightness", "34%", "30%"))
        assertTrue(DeviceSwitches.reached("wifi", "on (connected)", "on"))
        assertEquals("settings put system screen_off_timeout 30000", cmd("screen_timeout", "30"))
        assertEquals("settings put system screen_off_timeout 2147483647", cmd("screen_timeout", "never"))
        assertEquals("cmd notification set_dnd priority", cmd("do_not_disturb", "on"))
        assertEquals("cmd notification set_dnd none", cmd("do_not_disturb", "silence"))
        assertEquals("silence", DeviceSwitches.setPlan("do_not_disturb", "silence").expect)
        assertEquals("cmd notification set_dnd off", cmd("do_not_disturb", "off"))
        assertEquals("cmd audio set-ringer-mode VIBRATE", cmd("ringer_mode", "vibrate"))
        assertEquals("on", DeviceSwitches.setPlan("hotspot", "on").expect)
    }

    @Test
    fun rejectsBadValues() {
        for ((target, value) in listOf("wifi" to "maybe", "brightness" to "120", "screen_timeout" to "2", "do_not_disturb" to "loud", "ringer_mode" to "x")) {
            assertTrue("$target=$value", runCatching { DeviceSwitches.setPlan(target, value) }.exceptionOrNull() is ToolError)
        }
    }

    @Test
    fun readAllHasEverySectionExceptFlashlight() {
        val targets = DeviceSwitches.TARGETS.filter { it != "flashlight" }
        val script = DeviceSwitches.readAllScript(targets)
        for (t in targets) assertTrue(t, script.contains("echo '##$t'"))
        assertFalse(script.contains("##flashlight"))
    }
}
