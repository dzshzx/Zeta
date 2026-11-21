package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.LinuxEnv
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * app_control：应用详情（`dumpsys package`）、强行停止、冻结 / 解冻（`pm disable-user` / `pm enable`）， 安装、卸载、清除数据（`pm
 * install`、`pm uninstall`、`pm clear`，执行在 [AppPackages]），以及使用时长和最近打开 （`dumpsys usagestats` 里最近 24
 * 小时的事件，不需要给 Zeta 加使用情况访问权）。
 */
internal object DeviceApps {
    val ACTIONS =
        listOf(
            "info",
            "force_stop",
            "freeze",
            "unfreeze",
            "install",
            "uninstall",
            "clear_data",
            "usage",
            "recent",
        )

    /** 停掉或冻结了会让手机或 Zeta 自己用不了的包。 */
    val PROTECTED =
        setOf(
            AppVariant.RELEASE_PACKAGE,
            AppVariant.current.packageName,
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.miui.home",
            "com.android.providers.settings",
            "me.weishu.kernelsu",
            "com.sukisu.ultra",
            "com.rifsxd.ksunext",
        )

    fun infoCommand(packageName: String): String =
        "dumpsys package ${LinuxEnv.quote(packageName)} | sed -n '/^Packages:/,\$p'; echo '##pids'; pidof ${LinuxEnv.quote(packageName)} || true"

    /** 应用详情：从 `dumpsys package <包名>` 的 Packages: 一节里挑要紧的。没装返回 null。 */
    fun parseInfo(packageName: String, output: String, label: String?): String? {
        val parts = sections("##package\n" + output)
        val dump = parts["package"].orEmpty()
        if (!dump.contains("Package [$packageName]")) return null
        val user0 = Regex("(?m)^\\s+User 0: (.*)$").find(dump)?.groupValues?.get(1).orEmpty()
        val pids = parts["pids"]?.trim().orEmpty()
        return buildString {
            append("package: ").append(packageName)
            label?.let { append("\nname: ").append(it) }
            appendVersion(dump)
            field(dump, "appId")?.let { append("\nuid: ").append(it) }
            val flags = field(dump, "flags").orEmpty()
            append("\nsystem app: ")
                .append(if (Regex("\\bSYSTEM\\b").containsMatchIn(flags)) "yes" else "no")
            appendInstall(dump)
            appendUserState(user0)
            append("\nrunning: ").append(if (pids.isEmpty()) "no" else "yes (pid $pids)")
            field(dump, "codePath")?.let { append("\ncode path: ").append(it) }
            Regex("(?m)^\\s+dataDir=(.*)$").find(dump)?.groupValues?.get(1)?.let {
                append("\ndata dir: ").append(it.trim())
            }
            appendPermissions(dump)
        }
    }

    /** `dumpsys package` 里 `名字=值` 一行的值。 */
    private fun field(dump: String, name: String): String? =
        Regex("(?m)^\\s+$name=(.*)$").find(dump)?.groupValues?.get(1)?.trim()

    /** `User 0:` 那一行里的 `名字=值`。 */
    private fun userFlag(user0: String, name: String): String? =
        Regex("\\b$name=(\\S+)").find(user0)?.groupValues?.get(1)

    /** 版本名、版本号和 targetSdk、minSdk。 */
    private fun StringBuilder.appendVersion(dump: String) {
        field(dump, "versionName")?.let { append("\nversion: ").append(it) }
        val versionLine =
            Regex("(?m)^\\s+versionCode=(\\d+)(?: minSdk=(\\d+))?(?: targetSdk=(\\d+))?").find(dump)
        versionLine?.let { m ->
            append(" (code ").append(m.groupValues[1]).append(')')
            if (m.groupValues[3].isNotEmpty()) append(", targetSdk ").append(m.groupValues[3])
            if (m.groupValues[2].isNotEmpty()) append(", minSdk ").append(m.groupValues[2])
        }
    }

    /** 谁装的、什么时候首装和更新。 */
    private fun StringBuilder.appendInstall(dump: String) {
        field(dump, "installerPackageName")
            ?.takeIf { it != "null" }
            ?.let { append("\ninstaller: ").append(it) }
        // 预装后才更新的应用，首装时间记成 1970 年附近，不展示。
        Regex("(?m)^\\s+firstInstallTime=(.*)$")
            .find(dump)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeUnless { it.startsWith("19") }
            ?.let { append("\nfirst installed: ").append(it) }
        field(dump, "lastUpdateTime")?.let { append("\nlast updated: ").append(it) }
    }

    /** 用户 0 下的状态：启用与否，停止、挂起、隐藏。 */
    private fun StringBuilder.appendUserState(user0: String) {
        val enabled =
            when (userFlag(user0, "enabled")) {
                "0" -> "default (enabled)"
                "1" -> "enabled"
                "2" -> "disabled"
                "3" -> "frozen (disabled by user)"
                "4" -> "disabled until used"
                else -> userFlag(user0, "enabled")
            }
        enabled?.let { append("\nstate: ").append(it) }
        if (userFlag(user0, "stopped") == "true") append(", stopped")
        if (userFlag(user0, "suspended") == "true") append(", suspended")
        if (userFlag(user0, "hidden") == "true") append(", hidden")
    }

    /** 申请了多少权限，运行时权限里授予了和没授予的。 */
    private fun StringBuilder.appendPermissions(dump: String) {
        val requested = listSection(dump, "requested permissions:")
        val runtime = listSection(dump, "runtime permissions:")
        val granted = runtime.filter { it.contains("granted=true") }.map { it.substringBefore(':') }
        val denied = runtime.filter { it.contains("granted=false") }.map { it.substringBefore(':') }
        append("\nrequested permissions: ").append(requested.size)
        if (granted.isNotEmpty())
            append("\nruntime permissions granted: ")
                .append(granted.joinToString(", ") { it.removePrefix("android.permission.") })
        if (denied.isNotEmpty())
            append("\nruntime permissions not granted: ")
                .append(denied.joinToString(", ") { it.removePrefix("android.permission.") })
    }

    /** `dumpsys package` 里一个小标题下面缩进更深的那些行。 */
    private fun listSection(dump: String, title: String): List<String> {
        val lines = dump.lines()
        val start = lines.indexOfFirst { it.trim() == title }
        if (start < 0) return emptyList()
        val indent = lines[start].indexOfFirst { !it.isWhitespace() }
        return lines
            .drop(start + 1)
            .takeWhile { line ->
                line.isNotBlank() && line.indexOfFirst { !it.isWhitespace() } > indent
            }
            .map { it.trim() }
    }

    /** 读冻结 / 停止状态，用来核对：返回 User 0 一行里的 enabled 和 stopped。 */
    fun stateCommand(packageName: String): String =
        "dumpsys package ${LinuxEnv.quote(packageName)} | grep -m1 -E '^ +User 0: '"

    fun userState(output: String): Pair<String?, Boolean?> {
        val enabled = Regex("\\benabled=(\\d)").find(output)?.groupValues?.get(1)
        val stopped =
            Regex("\\bstopped=(true|false)")
                .find(output)
                ?.groupValues
                ?.get(1)
                ?.toBooleanStrictOrNull()
        return enabled to stopped
    }

    // ---- 安装、卸载、清除数据 ----

    /** 列出用户 0 下装着的这个包（`pm list packages` 按包名的一部分筛，要再按整个包名核对，见 [isInstalled]）。 */
    fun installedCommand(packageName: String): String =
        "pm list packages --user 0 ${LinuxEnv.quote(packageName)}"

    /** [installedCommand] 的输出里有没有正好是 [packageName] 的那一行。 */
    fun isInstalled(output: String, packageName: String): Boolean =
        output.lineSequence().any { it.trim() == "package:$packageName" }

    /** 只从用户 0 卸载：系统应用这样卸了还能用 `cmd package install-existing` 装回来，分身空间里的那份不动。 */
    fun uninstallCommand(packageName: String, keepData: Boolean): String =
        "pm uninstall " + (if (keepData) "-k " else "") + "--user 0 ${LinuxEnv.quote(packageName)}"

    fun clearCommand(packageName: String): String =
        "pm clear --user 0 ${LinuxEnv.quote(packageName)}"

    /**
     * 以 root 把 [source] 抄到 Zeta 自己建好的空文件 [target] 里：抄进已有的文件，属主和 SELinux 标签还是 Zeta 的，Zeta 才读得了。
     * 不存在时退出 4，不是普通文件退出 3。
     */
    fun copyCommand(source: String, target: String): String {
        val from = LinuxEnv.quote(source)
        return "[ -e $from ] || exit 4; [ -f $from ] || exit 3; exec /system/bin/cat $from > ${LinuxEnv.quote(target)}"
    }

    /** 装 [apk]（[size] 字节）：经管道交给 pm，pm 读不了的位置（应用私有目录）也装得上。-r 覆盖已装的，-t 允许 testOnly 的调试包。 */
    fun installCommand(apk: String, size: Long): String =
        "/system/bin/cat ${LinuxEnv.quote(apk)} | pm install -r -t -S $size"

    /** pm install、uninstall、clear 成功时输出里有一行 Success；失败时的原因（Failure [...]）原样交回。 */
    fun pmSucceeded(output: String): Boolean = output.lineSequence().any { it.trim() == "Success" }

    // ---- 使用情况 ----

    /** 只取主用户（user=0）最近 24 小时事件里和前台有关的几种。 */
    const val EVENTS_COMMAND =
        "dumpsys usagestats | sed -n '/^user=0/,/In-memory daily stats/p' | grep -E 'type=(ACTIVITY_RESUMED|ACTIVITY_PAUSED|ACTIVITY_STOPPED) ' || true"

    class Event(
        val time: LocalDateTime,
        val type: String,
        val packageName: String,
        val instance: String,
    )

    fun parseEvents(output: String): List<Event> =
        output
            .lineSequence()
            .mapNotNull { line ->
                val m = EVENT.find(line) ?: return@mapNotNull null
                val time =
                    runCatching { LocalDateTime.parse(m.groupValues[1], EVENT_TIME) }.getOrNull()
                        ?: return@mapNotNull null
                val instance = INSTANCE.find(line)?.groupValues?.get(1) ?: m.groupValues[3]
                Event(time, m.groupValues[2], m.groupValues[3], instance)
            }
            .toList()

    class Usage(
        val packageName: String,
        var foregroundMs: Long = 0,
        var opens: Int = 0,
        var last: LocalDateTime? = null,
    )

    /**
     * 每个包在 [since] 之后的前台时长与打开次数：按 Activity 实例从 RESUMED 算到 PAUSED / STOPPED， 到现在还在前台的算到
     * [now]。从别的应用切进来算一次打开。
     */
    fun usage(events: List<Event>, since: LocalDateTime, now: LocalDateTime): List<Usage> {
        val tally = UsageTally(since)
        for (e in events) tally.add(e)
        tally.finish(now)
        return tally.result.values
            .filter { it.foregroundMs > 0 || it.opens > 0 }
            .sortedByDescending { it.foregroundMs }
    }

    /** [usage] 按事件先后累计：哪个 Activity 实例从什么时候在前台、上一个在前台的包。 */
    private class UsageTally(private val since: LocalDateTime) {
        val result = LinkedHashMap<String, Usage>()
        private val resumedAt = HashMap<String, Pair<String, LocalDateTime>>()
        private var lastPackage: String? = null

        fun add(e: Event) {
            when (e.type) {
                "ACTIVITY_RESUMED" -> resumed(e)
                else ->
                    resumedAt.remove(e.instance)?.let { (pkg, from) ->
                        foreground(pkg, from, e.time)
                    }
            }
        }

        /** 到 [now] 还在前台的算到 [now]。 */
        fun finish(now: LocalDateTime) {
            for ((pkg, from) in resumedAt.values) foreground(pkg, from, now)
        }

        /** 从别的应用切进来算一次打开。 */
        private fun resumed(e: Event) {
            resumedAt[e.instance] = e.packageName to e.time
            if (e.packageName != lastPackage && !e.time.isBefore(since)) {
                val u = result.getOrPut(e.packageName) { Usage(e.packageName) }
                u.opens++
            }
            lastPackage = e.packageName
            if (!e.time.isBefore(since))
                result.getOrPut(e.packageName) { Usage(e.packageName) }.last = e.time
        }

        /** [packageName] 从 [from] 到 [to] 在前台，[since] 之前的部分不算。 */
        private fun foreground(packageName: String, from: LocalDateTime, to: LocalDateTime) {
            val start = if (from.isBefore(since)) since else from
            if (!to.isAfter(start)) return
            result.getOrPut(packageName) { Usage(packageName) }.foregroundMs +=
                java.time.Duration.between(start, to).toMillis()
        }
    }

    /** 最近打开的应用：连续几次 RESUMED 是同一个包只算一次，新的在前。 [skip] 里的包（桌面、锁屏）不列出，也不打断连续：亮屏回到同一个应用不算又开了一次。 */
    fun recent(
        events: List<Event>,
        since: LocalDateTime,
        skip: Set<String> = emptySet(),
    ): List<Pair<LocalDateTime, String>> {
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
