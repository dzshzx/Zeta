package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.core.AppVariant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * app_control：应用详情（`dumpsys package`）、强行停止、冻结 / 解冻（`pm disable-user` / `pm enable`），
 * 以及使用时长和最近打开（`dumpsys usagestats` 里最近 24 小时的事件，不需要给 Zeta 加使用情况访问权）。
 */
internal object DeviceApps {
    val ACTIONS = listOf("info", "force_stop", "freeze", "unfreeze", "usage", "recent")

    /** 停掉或冻结了会让手机或 Zeta 自己用不了的包。 */
    val PROTECTED = setOf(
        AppVariant.RELEASE_PACKAGE, AppVariant.current.packageName, "android", "com.android.systemui", "com.android.phone", "com.miui.home",
        "com.android.providers.settings", "me.weishu.kernelsu", "com.sukisu.ultra", "com.rifsxd.ksunext",
    )

    fun infoCommand(packageName: String): String =
        "dumpsys package ${shQuote(packageName)} | sed -n '/^Packages:/,\$p'; echo '##pids'; pidof ${shQuote(packageName)} || true"

    /** 应用详情：从 `dumpsys package <包名>` 的 Packages: 一节里挑要紧的。没装返回 null。 */
    fun parseInfo(packageName: String, output: String, label: String?): String? {
        val parts = sections("##package\n" + output)
        val dump = parts["package"].orEmpty()
        if (!dump.contains("Package [$packageName]")) return null
        fun field(name: String): String? = Regex("(?m)^\\s+$name=(.*)$").find(dump)?.groupValues?.get(1)?.trim()
        val versionLine = Regex("(?m)^\\s+versionCode=(\\d+)(?: minSdk=(\\d+))?(?: targetSdk=(\\d+))?").find(dump)
        val user0 = Regex("(?m)^\\s+User 0: (.*)$").find(dump)?.groupValues?.get(1).orEmpty()
        fun userFlag(name: String): String? = Regex("\\b$name=(\\S+)").find(user0)?.groupValues?.get(1)
        val enabled = when (userFlag("enabled")) {
            "0" -> "default (enabled)"
            "1" -> "enabled"
            "2" -> "disabled"
            "3" -> "frozen (disabled by user)"
            "4" -> "disabled until used"
            else -> userFlag("enabled")
        }
        val flags = field("flags").orEmpty()
        val requested = listSection(dump, "requested permissions:")
        val runtime = listSection(dump, "runtime permissions:")
        val granted = runtime.filter { it.contains("granted=true") }.map { it.substringBefore(':') }
        val denied = runtime.filter { it.contains("granted=false") }.map { it.substringBefore(':') }
        val pids = parts["pids"]?.trim().orEmpty()
        return buildString {
            append("package: ").append(packageName)
            label?.let { append("\nname: ").append(it) }
            field("versionName")?.let { append("\nversion: ").append(it) }
            versionLine?.let { m ->
                append(" (code ").append(m.groupValues[1]).append(')')
                if (m.groupValues[3].isNotEmpty()) append(", targetSdk ").append(m.groupValues[3])
                if (m.groupValues[2].isNotEmpty()) append(", minSdk ").append(m.groupValues[2])
            }
            field("appId")?.let { append("\nuid: ").append(it) }
            append("\nsystem app: ").append(if (Regex("\\bSYSTEM\\b").containsMatchIn(flags)) "yes" else "no")
            field("installerPackageName")?.takeIf { it != "null" }?.let { append("\ninstaller: ").append(it) }
            // 预装后才更新的应用，首装时间记成 1970 年附近，不展示。
            Regex("(?m)^\\s+firstInstallTime=(.*)$").find(dump)?.groupValues?.get(1)?.trim()
                ?.takeUnless { it.startsWith("19") }?.let { append("\nfirst installed: ").append(it) }
            field("lastUpdateTime")?.let { append("\nlast updated: ").append(it) }
            enabled?.let { append("\nstate: ").append(it) }
            if (userFlag("stopped") == "true") append(", stopped")
            if (userFlag("suspended") == "true") append(", suspended")
            if (userFlag("hidden") == "true") append(", hidden")
            append("\nrunning: ").append(if (pids.isEmpty()) "no" else "yes (pid $pids)")
            field("codePath")?.let { append("\ncode path: ").append(it) }
            Regex("(?m)^\\s+dataDir=(.*)$").find(dump)?.groupValues?.get(1)?.let { append("\ndata dir: ").append(it.trim()) }
            append("\nrequested permissions: ").append(requested.size)
            if (granted.isNotEmpty()) append("\nruntime permissions granted: ").append(granted.joinToString(", ") { it.removePrefix("android.permission.") })
            if (denied.isNotEmpty()) append("\nruntime permissions not granted: ").append(denied.joinToString(", ") { it.removePrefix("android.permission.") })
        }
    }

    /** `dumpsys package` 里一个小标题下面缩进更深的那些行。 */
    private fun listSection(dump: String, title: String): List<String> {
        val lines = dump.lines()
        val start = lines.indexOfFirst { it.trim() == title }
        if (start < 0) return emptyList()
        val indent = lines[start].indexOfFirst { !it.isWhitespace() }
        return lines.drop(start + 1)
            .takeWhile { line -> line.isNotBlank() && line.indexOfFirst { !it.isWhitespace() } > indent }
            .map { it.trim() }
    }

    /** 读冻结 / 停止状态，用来核对：返回 User 0 一行里的 enabled 和 stopped。 */
    fun stateCommand(packageName: String): String = "dumpsys package ${shQuote(packageName)} | grep -m1 -E '^ +User 0: '"

    fun userState(output: String): Pair<String?, Boolean?> {
        val enabled = Regex("\\benabled=(\\d)").find(output)?.groupValues?.get(1)
        val stopped = Regex("\\bstopped=(true|false)").find(output)?.groupValues?.get(1)?.toBooleanStrictOrNull()
        return enabled to stopped
    }

    // ---- 使用情况 ----

    /** 只取主用户（user=0）最近 24 小时事件里和前台有关的几种。 */
    const val EVENTS_COMMAND =
        "dumpsys usagestats | sed -n '/^user=0/,/In-memory daily stats/p' | grep -E 'type=(ACTIVITY_RESUMED|ACTIVITY_PAUSED|ACTIVITY_STOPPED) ' || true"

    class Event(val time: LocalDateTime, val type: String, val packageName: String, val instance: String)

    fun parseEvents(output: String): List<Event> = output.lineSequence().mapNotNull { line ->
        val m = EVENT.find(line) ?: return@mapNotNull null
        val time = runCatching { LocalDateTime.parse(m.groupValues[1], EVENT_TIME) }.getOrNull() ?: return@mapNotNull null
        val instance = INSTANCE.find(line)?.groupValues?.get(1) ?: m.groupValues[3]
        Event(time, m.groupValues[2], m.groupValues[3], instance)
    }.toList()

    class Usage(val packageName: String, var foregroundMs: Long = 0, var opens: Int = 0, var last: LocalDateTime? = null)

    /**
     * 每个包在 [since] 之后的前台时长与打开次数：按 Activity 实例从 RESUMED 算到 PAUSED / STOPPED，
     * 到现在还在前台的算到 [now]。从别的应用切进来算一次打开。
     */
    fun usage(events: List<Event>, since: LocalDateTime, now: LocalDateTime): List<Usage> {
        val result = LinkedHashMap<String, Usage>()
        val resumedAt = HashMap<String, Pair<String, LocalDateTime>>()
        var lastPackage: String? = null
        fun add(packageName: String, from: LocalDateTime, to: LocalDateTime) {
            val start = if (from.isBefore(since)) since else from
            if (!to.isAfter(start)) return
            result.getOrPut(packageName) { Usage(packageName) }.foregroundMs += java.time.Duration.between(start, to).toMillis()
        }
        for (e in events) {
            when (e.type) {
                "ACTIVITY_RESUMED" -> {
                    resumedAt[e.instance] = e.packageName to e.time
                    if (e.packageName != lastPackage && !e.time.isBefore(since)) {
                        val u = result.getOrPut(e.packageName) { Usage(e.packageName) }
                        u.opens++
                    }
                    lastPackage = e.packageName
                    if (!e.time.isBefore(since)) result.getOrPut(e.packageName) { Usage(e.packageName) }.last = e.time
                }
                else -> resumedAt.remove(e.instance)?.let { (pkg, from) -> add(pkg, from, e.time) }
            }
        }
        for ((pkg, from) in resumedAt.values) add(pkg, from, now)
        return result.values.filter { it.foregroundMs > 0 || it.opens > 0 }.sortedByDescending { it.foregroundMs }
    }

    /**
     * 最近打开的应用：连续几次 RESUMED 是同一个包只算一次，新的在前。
     * [skip] 里的包（桌面、锁屏）不列出，也不打断连续：亮屏回到同一个应用不算又开了一次。
     */
    fun recent(events: List<Event>, since: LocalDateTime, skip: Set<String> = emptySet()): List<Pair<LocalDateTime, String>> {
        val opens = ArrayList<Pair<LocalDateTime, String>>()
        var last: String? = null
        for (e in events) {
            if (e.type != "ACTIVITY_RESUMED" || e.packageName in skip) continue
            if (e.packageName != last && !e.time.isBefore(since)) opens += e.time to e.packageName
            last = e.packageName
        }
        return opens.asReversed()
    }

    private val EVENT = Regex("time=\"([^\"]+)\" type=([A-Z_]+) package=(\\S+)")
    private val INSTANCE = Regex("instanceId=(\\S+)")
    private val EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
}
