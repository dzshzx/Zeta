package com.dzshzx.zeta.phone.tools

import java.util.Locale
import kotlin.math.roundToInt

/** device_info：电池、温度、内存、存储、网络、位置、内存和存储大户、已保存的 Wi-Fi 密码。 */
internal object DeviceInfo {
    val WHAT = listOf("overview", "location", "top_memory", "top_storage", "wifi_passwords")

    val OVERVIEW_SCRIPT =
        section(
            "device",
            "getprop ro.product.marketname; getprop ro.product.model; getprop ro.build.version.release; getprop ro.mi.os.version.name; getprop ro.build.version.incremental",
        ) +
            section("battery", "dumpsys battery") +
            section("current", "cat /sys/class/power_supply/battery/current_now") +
            section("thermal", "dumpsys thermalservice | grep -E 'Thermal Status|Temperature\\{'") +
            section(
                "memory",
                "grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo",
            ) +
            section("storage", "dumpsys diskstats | grep -E '^Data-Free:'") +
            section("uptime", "cat /proc/uptime") +
            section(
                "screen",
                "dumpsys power | grep -m1 'mWakefulness='; dumpsys window | grep -m1 'isKeyguardShowing='",
            ) +
            section("wifi", "cmd wifi status | head -6") +
            section("cellular", "getprop gsm.operator.alpha; getprop gsm.network.type")

    fun overview(s: Map<String, String>): String = buildString {
        s["device"]?.let { append("device: ").append(device(it)) }
        battery(s["battery"].orEmpty(), s["current"]?.trim()?.toLongOrNull())?.let {
            append("\nbattery: ").append(it)
        }
        thermal(s["thermal"].orEmpty())?.let { append("\ntemperature: ").append(it) }
        memory(s["memory"].orEmpty())?.let { append("\nmemory: ").append(it) }
        storage(s["storage"].orEmpty())?.let { append("\nstorage: ").append(it) }
        s["uptime"]?.trim()?.substringBefore(' ')?.toDoubleOrNull()?.let {
            append("\nuptime: ").append(spoken((it * 1000).toLong()))
        }
        s["screen"]?.let(::screen)?.let { append("\nscreen: ").append(it) }
        s["wifi"]
            ?.let { DeviceSwitches.describe("wifi", it) }
            ?.let { append("\nwifi: ").append(it) }
        s["cellular"]?.let(::cellular)?.let { append("\ncellular: ").append(it) }
    }

    /**
     * device 一节（市场名、型号、Android 版本、HyperOS 版本、内部版本号各一行）→ `Xiaomi 17, Android 16, HyperOS 3.0 (…)`。
     */
    private fun device(text: String): String {
        val d = text.lines().map { it.trim() }
        return buildString {
            val name = d.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: d.getOrNull(1).orEmpty()
            append(name)
            d.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { append(", Android ").append(it) }
            d.getOrNull(3)
                ?.takeIf { it.isNotEmpty() }
                ?.let { append(", HyperOS ").append(it.removePrefix("OS")) }
            d.getOrNull(4)?.takeIf { it.isNotEmpty() }?.let { append(" (").append(it).append(')') }
        }
    }

    /** screen 一节 → `on, locked`、`off (Asleep)`。读不出亮灭时为 null。 */
    private fun screen(text: String): String? {
        val wake = Regex("mWakefulness=(\\w+)").find(text)?.groupValues?.get(1) ?: return null
        val locked = Regex("isKeyguardShowing=(true|false)").find(text)?.groupValues?.get(1)
        val state = if (wake == "Awake") "on" else "off ($wake)"
        if (locked == null) return state
        return state + if (locked == "true") ", locked" else ", unlocked"
    }

    /** cellular 一节（运营商、网络类型各一行，双卡时逗号分开）→ `中国移动 / 中国联通 (NR / LTE)`。没有运营商时为 null。 */
    private fun cellular(text: String): String? {
        val c = text.lines().map { it.trim() }
        val operator = distinctParts(c.getOrNull(0))
        val type = distinctParts(c.getOrNull(1))
        if (operator.isEmpty()) return null
        return operator + if (type.isNotEmpty()) " ($type)" else ""
    }

    /** 逗号分开的几项去掉空的和重复的，用 ` / ` 连起来。 */
    private fun distinctParts(line: String?): String =
        line.orEmpty().split(',').filter { it.isNotBlank() }.distinct().joinToString(" / ")

    /** `dumpsys battery` → `85%, charging (AC), 31.2°C, 4.21 V, health good`。 */
    fun battery(text: String, currentMicroAmps: Long?): String? {
        fun v(key: String) = Regex("(?m)^\\s+$key: (.*)$").find(text)?.groupValues?.get(1)?.trim()
        val level = v("level")?.toIntOrNull() ?: return null
        val scale = v("scale")?.toIntOrNull()?.takeIf { it > 0 } ?: 100
        val status =
            when (v("status")) {
                "2" -> "charging"
                "3" -> "discharging"
                "4" -> "not charging"
                "5" -> "full"
                else -> "unknown"
            }
        val source =
            listOf(
                    "AC" to "AC powered",
                    "USB" to "USB powered",
                    "wireless" to "Wireless powered",
                    "dock" to "Dock powered",
                )
                .filter { v(it.second) == "true" }
                .joinToString("+") { it.first }
        val health =
            when (v("health")) {
                "2" -> "good"
                "3" -> "overheat"
                "4" -> "dead"
                "5" -> "over voltage"
                "6" -> "failure"
                "7" -> "cold"
                else -> null
            }
        return buildString {
            append(level * 100 / scale).append("%, ").append(status)
            if (source.isNotEmpty()) append(" (plugged: ").append(source).append(')')
            v("temperature")?.toIntOrNull()?.let {
                append(", ").append(String.format(Locale.ROOT, "%.1f°C", it / 10.0))
            }
            v("voltage")?.toIntOrNull()?.let {
                append(", ").append(String.format(Locale.ROOT, "%.2f V", it / 1000.0))
            }
            // current_now 的符号各家不一：小米充电时为正
            currentMicroAmps
                ?.takeIf { it != 0L }
                ?.let { append(", current ").append(it / 1000).append(" mA") }
            health?.let { append(", health ").append(it) }
        }
    }

    /** 温度：电池、机身、CPU / GPU 最高值，外加系统的温控等级。 */
    fun thermal(text: String): String? {
        val temps =
            TEMPERATURE.findAll(text)
                .mapNotNull { m ->
                    val value = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                    Triple(m.groupValues[2].toIntOrNull(), m.groupValues[3], value)
                }
                .toList()
        if (temps.isEmpty()) return null
        fun max(type: Int) = temps.filter { it.first == type }.maxOfOrNull { it.third }
        val parts = buildList {
            max(2)?.let { add(String.format(Locale.ROOT, "battery %.1f°C", it)) }
            max(3)?.let { add(String.format(Locale.ROOT, "skin %.1f°C", it)) }
            max(0)?.let { add(String.format(Locale.ROOT, "CPU max %.1f°C", it)) }
            max(1)?.let { add(String.format(Locale.ROOT, "GPU max %.1f°C", it)) }
        }
        val status = Regex("Thermal Status: (\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val statusText =
            when (status) {
                null -> null
                0 -> "no throttling"
                1 -> "light throttling"
                2 -> "moderate throttling"
                3 -> "severe throttling"
                4 -> "critical"
                5 -> "emergency"
                6 -> "shutdown"
                else -> "status $status"
            }
        return (parts + listOfNotNull(statusText)).joinToString(", ").takeIf { it.isNotEmpty() }
    }

    fun memory(text: String): String? {
        fun kb(key: String) =
            Regex("(?m)^$key:\\s+(\\d+) kB").find(text)?.groupValues?.get(1)?.toLongOrNull()
        val total = kb("MemTotal") ?: return null
        val available = kb("MemAvailable") ?: return null
        return buildString {
            append(bytes(available * 1024)).append(" available of ").append(bytes(total * 1024))
            val swapTotal = kb("SwapTotal")
            val swapFree = kb("SwapFree")
            if (swapTotal != null && swapFree != null && swapTotal > 0)
                append(", swap ")
                    .append(bytes((swapTotal - swapFree) * 1024))
                    .append(" used of ")
                    .append(bytes(swapTotal * 1024))
        }
    }

    /** `Data-Free: 121194420K / 500367340K total = 24% free`。 */
    fun storage(text: String): String? {
        val m = Regex("Data-Free: (\\d+)K / (\\d+)K total").find(text) ?: return null
        val free = m.groupValues[1].toLong() * 1024
        val total = m.groupValues[2].toLong() * 1024
        return "${bytes(free)} free of ${bytes(total)}"
    }

    // ---- 位置 ----

    const val LOCATION_SCRIPT =
        "cmd location is-location-enabled; cat /proc/uptime; dumpsys location | grep -E 'last location=Location\\['; true"

    class Fix(
        val provider: String,
        val latitude: String,
        val longitude: String,
        val accuracy: String?,
        val elapsedMs: Long,
    )

    fun parseFixes(text: String): List<Fix> =
        LOCATION.findAll(text)
            .mapNotNull { m ->
                Fix(
                    m.groupValues[1],
                    m.groupValues[2],
                    m.groupValues[3],
                    m.groupValues[4].takeIf { it.isNotEmpty() },
                    elapsed(m.groupValues[5]) ?: return@mapNotNull null,
                )
            }
            .toList()

    /** `+9d13h36m10s315ms` → 毫秒。 */
    fun elapsed(value: String): Long? {
        if (!value.startsWith("+")) return null
        var total = 0L
        var matched = false
        for (m in Regex("(\\d+)(ms|d|h|m|s)").findAll(value)) {
            matched = true
            val n = m.groupValues[1].toLong()
            total +=
                when (m.groupValues[2]) {
                    "d" -> n * 86_400_000
                    "h" -> n * 3_600_000
                    "m" -> n * 60_000
                    "s" -> n * 1000
                    else -> n
                }
        }
        return if (matched) total else null
    }

    fun location(text: String): String {
        val lines = text.lines()
        val enabled = lines.firstOrNull()?.trim()
        val uptimeMs =
            lines.getOrNull(1)?.trim()?.substringBefore(' ')?.toDoubleOrNull()?.let {
                (it * 1000).toLong()
            }
        val fixes =
            parseFixes(text).distinctBy {
                listOf(it.provider, it.latitude, it.longitude, it.elapsedMs)
            }
        if (fixes.isEmpty())
            return if (enabled == "false") "location is off and there is no last known fix"
            else "no last known fix yet"
        val best = fixes.maxBy { it.elapsedMs }
        return buildString {
            append("latitude ").append(best.latitude).append(", longitude ").append(best.longitude)
            best.accuracy?.let {
                append(", accuracy ±").append(it.toDoubleOrNull()?.roundToInt() ?: it).append(" m")
            }
            append(", from ").append(best.provider)
            if (uptimeMs != null)
                append(", ").append(spoken(maxOf(0, uptimeMs - best.elapsedMs))).append(" ago")
            if (enabled == "false") append("\nlocation is currently off, so this fix may be stale")
            append("\n(last known fix from the system; it is not refreshed by this call)")
        }
    }

    // ---- 内存、存储大户 ----

    fun topMemoryCommand(limit: Int): String =
        "ps -A -o RSS,PID,NAME -k -RSS | head -n ${limit + 1}"

    fun topMemory(text: String): String =
        text
            .lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 3)
                val rss = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                "${parts.getOrNull(2).orEmpty()} (pid ${parts.getOrNull(1).orEmpty()}): ${bytes(rss * 1024)}"
            }
            .joinToString("\n")
            .ifEmpty { "no process list" }

    const val STORAGE_COMMAND =
        "dumpsys diskstats | grep -E '^(Package Names|App Sizes|App Data Sizes|Cache Sizes):' || true"

    fun topStorage(text: String, limit: Int): String {
        fun list(key: String): List<String> =
            Regex("(?m)^$key: \\[(.*)]$")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.split(',')
                ?.map { it.trim().trim('"') }
                .orEmpty()
        val names = list("Package Names")
        val app = list("App Sizes").map { it.toLongOrNull() ?: 0 }
        val data = list("App Data Sizes").map { it.toLongOrNull() ?: 0 }
        val cache = list("Cache Sizes").map { it.toLongOrNull() ?: 0 }
        if (names.isEmpty()) return "the system has no per-app storage stats yet"
        return names.indices
            .map { i ->
                val a = app.getOrElse(i) { 0 }
                val d = data.getOrElse(i) { 0 }
                val c = cache.getOrElse(i) { 0 }
                Triple(names[i], a + d + c, "app ${bytes(a)}, data ${bytes(d)}, cache ${bytes(c)}")
            }
            .sortedByDescending { it.second }
            .take(limit)
            .joinToString("\n") { "${it.first}: ${bytes(it.second)} (${it.third})" }
    }

    // ---- 已保存的 Wi-Fi ----

    const val WIFI_STORE = "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml"

    class SavedWifi(val ssid: String, val password: String?, val security: String)

    /** 从 WifiConfigStore.xml 里取出每个网络的名字和密码（明文口令带引号，64 位十六进制的是原始 PSK）。 */
    fun parseWifiStore(xml: String): List<SavedWifi> =
        NETWORK.findAll(xml)
            .mapNotNull { block ->
                val body = block.groupValues[1]
                val ssid = XmlText.string(body, "SSID")?.let(::ssidText) ?: return@mapNotNull null
                val psk = XmlText.string(body, "PreSharedKey")
                val password = psk?.let {
                    if (it.length >= 2 && it.startsWith('"') && it.endsWith('"'))
                        it.substring(1, it.length - 1)
                    else it
                }
                val security =
                    Regex("<int name=\"SecurityType\" value=\"(\\d+)\"")
                        .find(body)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                SavedWifi(
                    ssid,
                    password,
                    SECURITY[security] ?: if (password != null) "WPA/WPA2" else "open",
                )
            }
            .distinctBy { it.ssid }
            .toList()

    /** SSID 存成 `"名字"`；不是 UTF-8 的存成不带引号的十六进制。 */
    private fun ssidText(value: String): String =
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"'))
            value.substring(1, value.length - 1)
        else if (
            value.length % 2 == 0 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        ) {
            String(value.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.UTF_8)
        } else value

    private val TEMPERATURE =
        Regex("Temperature\\{mValue=([-0-9.]+), mType=(-?\\d+), mName=([^,]+),")
    private val LOCATION =
        Regex("Location\\[(\\w+) (-?[0-9.]+),(-?[0-9.]+)(?: hAcc=([0-9.]+))? et=(\\+[0-9dhms]+)")
    private val NETWORK = Regex("<Network>(.*?)</Network>", RegexOption.DOT_MATCHES_ALL)
    private val SECURITY =
        mapOf(
            0 to "open",
            1 to "WEP",
            2 to "WPA/WPA2",
            3 to "802.1X",
            4 to "WPA3",
            5 to "WPA3 enterprise",
            6 to "OWE",
            9 to "WPA3 enterprise 192-bit",
        )
}
