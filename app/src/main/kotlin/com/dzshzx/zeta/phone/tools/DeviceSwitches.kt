package com.dzshzx.zeta.phone.tools

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * device_state：各种开关和档位的读写。读写都走 root 命令（settings、cmd、svc、dumpsys），
 * 只有手电筒（[Torch]，本进程的 CameraManager）和热点（[HotspotHelper]，root 辅助进程）例外。
 * 命令拼装和输出解析都在这里，是纯函数。
 */
internal object DeviceSwitches {
    /** 能读写的项，顺序也是「全部列出」时的顺序。 */
    val TARGETS = listOf(
        "wifi", "bluetooth", "mobile_data", "airplane_mode", "hotspot", "location", "nfc", "flashlight",
        "dark_mode", "battery_saver", "auto_rotate", "auto_brightness", "brightness", "screen_timeout",
        "do_not_disturb", "ringer_mode",
    )

    /** 中文名，给聊天里的标题用。 */
    val NAMES = mapOf(
        "wifi" to "Wi-Fi", "bluetooth" to "蓝牙", "mobile_data" to "移动数据", "airplane_mode" to "飞行模式",
        "hotspot" to "个人热点", "location" to "定位", "nfc" to "NFC", "flashlight" to "手电筒",
        "dark_mode" to "深色模式", "battery_saver" to "省电模式", "auto_rotate" to "自动旋转",
        "auto_brightness" to "自动亮度", "brightness" to "亮度", "screen_timeout" to "熄屏时间",
        "do_not_disturb" to "勿扰模式", "ringer_mode" to "响铃模式",
    )

    /** 只有开和关两种状态的项。 */
    val SWITCHES = setOf(
        "wifi", "bluetooth", "mobile_data", "airplane_mode", "hotspot", "location", "nfc", "flashlight",
        "dark_mode", "battery_saver", "auto_rotate", "auto_brightness",
    )

    /** 切换后要等系统慢慢完成的项：读回时多等几次。 */
    val SLOW = setOf("wifi", "bluetooth", "mobile_data", "airplane_mode", "hotspot", "nfc", "location")

    /** 读一项的脚本（打印原始输出，由 [describe] 解读）。手电筒不在这里。 */
    fun readScript(target: String): String? = when (target) {
        "wifi" -> "cmd wifi status | head -6"
        "bluetooth" -> "settings get global bluetooth_on"
        // 双卡时数据开关按默认数据卡的订阅号存：mobile_data<subId>
        "mobile_data" -> "s=\$(settings get global multi_sim_data_call); v=\$(settings get global mobile_data\$s); " +
            "case \"\$v\" in 0|1) echo \"\$v\";; *) settings get global mobile_data;; esac"
        "airplane_mode" -> "settings get global airplane_mode_on"
        "hotspot" -> "dumpsys tethering | sed -n '/^  Tether state:/,/^  [A-Z]/p'"
        "location" -> "cmd location is-location-enabled"
        "nfc" -> "cmd nfc status"
        // HyperOS 另存一份 system/dark_mode_enable，设置页按它显示；别的系统读出 null
        "dark_mode" -> "cmd uimode night; settings get system dark_mode_enable"
        "battery_saver" -> "settings get global low_power"
        "auto_rotate" -> "settings get system accelerometer_rotation"
        "auto_brightness" -> "settings get system screen_brightness_mode"
        "brightness" -> "cmd display get-brightness --unit percentage"
        "screen_timeout" -> "settings get system screen_off_timeout"
        "do_not_disturb" -> "settings get global zen_mode"
        "ringer_mode" -> "settings get global mode_ringer"
        else -> null
    }

    /** 一次读出 [targets] 的脚本，各项用 `##名字` 分节。 */
    fun readAllScript(targets: List<String>): String =
        targets.mapNotNull { target -> readScript(target)?.let { section(target, it) } }.joinToString("")

    /** `Night mode: yes` 加上 HyperOS 的 dark_mode_enable；两边不一致时在后面注明。 */
    private fun darkMode(text: String): String? {
        val mode = NIGHT.find(text)?.groupValues?.get(1) ?: return null
        val base = when (mode.lowercase(Locale.ROOT)) {
            "yes" -> "on"
            "no" -> "off"
            "auto" -> "auto (follows sunset)"
            else -> "scheduled ($mode)"
        }
        val miui = text.lineSequence().map { it.trim() }.firstOrNull { it == "0" || it == "1" } ?: return base
        val agrees = (base == "on") == (miui == "1") || base != "on" && base != "off"
        return if (agrees) base else "$base (HyperOS dark_mode_enable=$miui)"
    }

    /** 把 [readScript] 的原始输出说成一句：`on`、`off (…)`、`45%`、`vibrate`。读不懂时返回 null。 */
    fun describe(target: String, raw: String): String? {
        val text = raw.trim()
        val flag = text.lineSequence().firstOrNull()?.trim()
        return when (target) {
            "wifi" -> {
                val enabled = when {
                    text.startsWith("Wifi is enabled") -> true
                    text.startsWith("Wifi is disabled") -> false
                    else -> return null
                }
                if (!enabled) "off"
                else {
                    val ssid = WIFI_CONNECTED.find(text)?.groupValues?.get(1)
                    val rssi = WIFI_RSSI.find(text)?.groupValues?.get(1)
                    if (ssid == null) "on (not connected)" else "on (connected to \"$ssid\"" + (rssi?.let { ", $it dBm" } ?: "") + ")"
                }
            }
            "location" -> when (flag) { "true" -> "on"; "false" -> "off"; else -> null }
            "nfc" -> when {
                text.contains("is enabled", ignoreCase = true) -> "on"
                text.contains("is disabled", ignoreCase = true) -> "off"
                else -> null
            }
            "hotspot" -> hotspotOn(text)?.let { if (it) "on" else "off" }
            "dark_mode" -> darkMode(text)
            "bluetooth", "airplane_mode", "battery_saver", "auto_rotate", "auto_brightness", "mobile_data" ->
                when (flag) { "1" -> "on"; "0" -> "off"; else -> null }
            "brightness" -> flag?.toDoubleOrNull()?.let { "${it.roundToInt()}%" }
            "screen_timeout" -> flag?.toLongOrNull()?.let { ms ->
                if (ms >= Int.MAX_VALUE.toLong()) "never" else "${ms / 1000}s"
            }
            "do_not_disturb" -> when (flag) {
                "0" -> "off"; "1" -> "priority"; "2" -> "silence"; "3" -> "alarms"; else -> null
            }
            "ringer_mode" -> when (flag) { "0" -> "silent"; "1" -> "vibrate"; "2" -> "normal"; else -> null }
            else -> null
        }
    }

    /** `dumpsys tethering` 的 Tether state 一节里有 Wi-Fi 网口处于 TetheredState 就是热点开着。 */
    fun hotspotOn(tetherState: String): Boolean? {
        if (tetherState.isBlank()) return null
        return tetherState.lineSequence().any { line ->
            val m = TETHER_IFACE.find(line) ?: return@any false
            WIFI_IFACE.matches(m.groupValues[1]) && m.groupValues[2] == "TetheredState"
        }
    }

    /** 开关类的值：on/off 的各种说法。不是开关语义返回 null。 */
    fun switchValue(value: String?): Boolean? = when (value?.trim()?.lowercase(Locale.ROOT)) {
        "on", "true", "1", "yes", "enable", "enabled", "open", "开", "打开", "开启" -> true
        "off", "false", "0", "no", "disable", "disabled", "close", "关", "关闭" -> false
        else -> null
    }

    /**
     * 写入一项的命令。返回 [Plan]：要执行的命令和期望读回的说法（[describe] 的结果以它开头即算成功）；
     * 参数不对时抛 [ToolError]。手电筒和热点不走命令，由调用方另行处理。
     */
    fun setPlan(target: String, value: String?): Plan {
        if (target in SWITCHES) {
            val on = switchValue(value) ?: throw ToolError("$target 的 value 要用 on 或 off")
            val word = if (on) "on" else "off"
            val command = when (target) {
                "wifi" -> "cmd wifi set-wifi-enabled " + if (on) "enabled" else "disabled"
                "bluetooth" -> "cmd bluetooth_manager " + if (on) "enable" else "disable"
                "mobile_data" -> "svc data " + if (on) "enable" else "disable"
                "airplane_mode" -> "cmd connectivity airplane-mode " + if (on) "enable" else "disable"
                "location" -> "cmd location set-location-enabled $on"
                "nfc" -> if (on) "cmd nfc enable-nfc" else "cmd nfc disable-nfc persist"
                // HyperOS 自己会跟着改 dark_mode_enable，这里再写一次，免得设置页对不上
                "dark_mode" -> "cmd uimode night " + (if (on) "yes" else "no") +
                    " && settings put system dark_mode_enable " + if (on) 1 else 0
                "battery_saver" -> "cmd power set-mode " + if (on) 1 else 0
                "auto_rotate" -> "settings put system accelerometer_rotation " + if (on) 1 else 0
                "auto_brightness" -> "settings put system screen_brightness_mode " + if (on) 1 else 0
                else -> "" // flashlight、hotspot
            }
            return Plan(command, word)
        }
        val v = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (target) {
            "brightness" -> {
                val percent = v.removeSuffix("%").trim().toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
                    ?: throw ToolError("brightness 的 value 是 0 到 100 的百分比")
                // 自动亮度开着时手动设的值会被马上调走：先关自动亮度，和在控制中心拖动滑块一样。
                // HyperOS 上 `set-brightness N --unit percentage` 不论 N 是多少都设成最暗，只能给 0–1 的数值。
                val p = percent.roundToInt()
                Plan("settings put system screen_brightness_mode 0 && cmd display set-brightness " + brightnessValue(percent), "$p%")
            }
            "screen_timeout" -> {
                val ms = if (v == "never") Int.MAX_VALUE.toLong()
                else (v.removeSuffix("s").trim().toLongOrNull()?.takeIf { it in 5..86_400 }
                    ?: throw ToolError("screen_timeout 的 value 是 5 到 86400 的秒数，或 never")) * 1000
                Plan("settings put system screen_off_timeout $ms", if (ms >= Int.MAX_VALUE) "never" else "${ms / 1000}s")
            }
            "do_not_disturb" -> {
                val (arg, word) = when (v) {
                    "off" -> "off" to "off"
                    // MIUI 控制中心里的勿扰就是「重要打扰」这一档
                    "on", "priority" -> "priority" to "priority"
                    "alarms" -> "alarms" to "alarms"
                    "silence", "none", "total_silence" -> "none" to "silence"
                    else -> throw ToolError("do_not_disturb 的 value 是 off、priority、alarms 或 silence")
                }
                Plan("cmd notification set_dnd $arg", word)
            }
            "ringer_mode" -> {
                val mode = when (v) {
                    "normal", "ring", "sound" -> "normal"
                    "vibrate", "vibration" -> "vibrate"
                    "silent", "mute" -> "silent"
                    else -> throw ToolError("ringer_mode 的 value 是 normal、vibrate 或 silent")
                }
                Plan("cmd audio set-ringer-mode " + mode.uppercase(Locale.ROOT), mode)
            }
            else -> throw ToolError("不认识的 target：$target")
        }
    }

    class Plan(val command: String, val expect: String)

    /** 读回的说法 [described] 是否已到 [expect]。亮度按滑块折算，差 3 个百分点以内都算到了。 */
    fun reached(target: String, described: String, expect: String): Boolean {
        if (target != "brightness") return described.startsWith(expect)
        val got = described.substringBefore('%').toIntOrNull() ?: return false
        val want = expect.substringBefore('%').toIntOrNull() ?: return false
        return abs(got - want) <= 3
    }

    /**
     * 滑块百分比 → `cmd display set-brightness` 的 0–1 数值。HyperOS 的滑块曲线没有公开，
     * 这几个点是在小米 17 上设数值、再用 `get-brightness --unit percentage` 读滑块量出来的，中间线性插值。
     */
    fun brightnessValue(percent: Double): String {
        val p = percent.coerceIn(0.0, 100.0)
        val upper = SLIDER.indexOfFirst { it.first >= p }.coerceAtLeast(1)
        val (p0, v0) = SLIDER[upper - 1]
        val (p1, v1) = SLIDER[upper]
        val value = v0 + (v1 - v0) * (p - p0) / (p1 - p0)
        return String.format(Locale.ROOT, "%.4f", value.coerceIn(0.0, 1.0))
    }

    private val SLIDER = listOf(
        0.0 to 0.0, 7.09 to 0.003333, 22.38 to 0.03, 34.48 to 0.083333,
        48.62 to 0.160784, 61.24 to 0.301961, 100.0 to 1.0,
    )

    private val WIFI_CONNECTED = Regex("Wifi is connected to \"(.*)\"")
    private val WIFI_RSSI = Regex("RSSI: (-?\\d+)")
    private val NIGHT = Regex("Night mode: (\\S+)", RegexOption.IGNORE_CASE)
    private val TETHER_IFACE = Regex("^\\s+(\\S+) - (\\w+)")
    private val WIFI_IFACE = Regex("(wlan|softap|ap_br_wlan|ap_br_softap|swlan)\\d*")
}
