package com.dzshzx.zeta.phone.tools

import android.content.Context
import android.content.pm.PackageManager
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.clipChars
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 设备与系统一类的手机工具：开关和档位、音量与播放、闹钟和计时器、系统设置、应用、设备信息、日志。
 * 都不碰屏幕，优先用 root 命令；各领域的命令拼装和解析在同目录的 Device*.kt、XiaomiClock.kt 里。
 */
internal object DeviceTools : PhoneToolGroup {
    override val tools: List<JsonElement> = listOf(
        tool(
            "device_state",
            "Read or change phone switches and levels. get without target lists them all. " +
                "Turning off wifi or mobile_data, or turning on airplane_mode, can cut this conversation's connection; do it only when the user asks.",
        ) {
            schema(required = listOf("action")) {
                enumProp("action", listOf("get", "set"), "get or set.")
                enumProp("target", DeviceSwitches.TARGETS, "What to read or change.")
                prop(
                    "value", "string",
                    "For set. Switches: on|off. brightness: 0-100 (turns auto_brightness off). screen_timeout: seconds or never. " +
                        "do_not_disturb: off|priority|alarms|silence. ringer_mode: normal|vibrate|silent.",
                )
            }
        },
        tool("media", "Volumes, ringer, audio output and what is playing (status); set a stream's volume; play/pause/skip the current media.") {
            schema(required = listOf("action")) {
                enumProp("action", listOf("status", "set_volume") + DeviceAudio.KEYS.keys, "What to do.")
                enumProp("stream", DeviceAudio.STREAMS.keys.toList(), "For set_volume.")
                prop("percent", "integer", "For set_volume: 0-100.")
            }
        },
        tool(
            "clock",
            "Alarms and the countdown timer of the Clock app, without opening it. list_alarms shows the alarm_id that update_alarm and delete_alarm need.",
        ) {
            schema(required = listOf("action")) {
                enumProp("action", XiaomiClock.ACTIONS, "What to do.")
                prop("alarm_id", "integer", "For update_alarm, delete_alarm.")
                prop("hour", "integer", "0-23, local time.")
                prop("minute", "integer", "0-59.")
                prop("days", "string", "Repeat days: comma-separated mon..sun, or weekdays, weekends, everyday, once (default).")
                prop("label", "string", "Alarm label.")
                prop("enabled", "boolean", "update_alarm: turn the alarm on or off.")
                prop("vibrate", "boolean", "Vibrate when ringing (default true).")
                prop("seconds", "integer", "start_timer: duration, 2-86399.")
            }
        },
        tool("system_setting", "Read or write Android settings (the system, secure and global tables of the `settings` command).") {
            schema(required = listOf("action", "namespace")) {
                enumProp("action", listOf("get", "put", "delete", "list"), "What to do.")
                enumProp("namespace", listOf("system", "secure", "global"), "Settings table.")
                prop("key", "string", "Setting name, for get, put, delete.")
                prop("value", "string", "For put.")
                prop("filter", "string", "For list: only names containing this.")
            }
        },
        tool(
            "app_control",
            "Installed apps: info (version, install, state, permissions), force_stop, freeze (disable), unfreeze, " +
                "usage (foreground time per app) and recent (apps opened, newest first) over the last hours.",
        ) {
            schema(required = listOf("action")) {
                enumProp("action", DeviceApps.ACTIONS, "What to do.")
                prop("package", "string", "Package name (find_apps lists them). Required except for usage and recent, where it filters.")
                prop("hours", "integer", "usage, recent: look back 1-24 hours (default 24).")
                prop("limit", "integer", "usage, recent: most entries to return (default 20).")
            }
        },
        tool(
            "device_info",
            "Phone facts: overview (battery, temperatures, memory, storage, network, screen), location (last known fix), " +
                "top_memory and top_storage (heaviest apps), wifi_passwords (saved networks with passwords; share only with the user who asked).",
            readOnly = true,
        ) {
            schema(required = listOf("what")) {
                enumProp("what", DeviceInfo.WHAT, "What to read.")
                prop("ssid", "string", "wifi_passwords: only networks whose name contains this.")
                prop("limit", "integer", "top_memory, top_storage: how many (default 15).")
            }
        },
        tool("logcat", "Read recent Android logs (threadtime format, newest last), narrowed by package, tag, level, text or time.", readOnly = true) {
            schema {
                prop("lines", "integer", "Most lines to return (default ${DeviceLogcat.DEFAULT_LINES}, max ${DeviceLogcat.MAX_LINES}).")
                prop("package", "string", "Only logs from this app's processes.")
                prop("tag", "string", "Only these tags, comma-separated.")
                enumProp("level", DeviceLogcat.LEVELS, "Lowest priority to include (default V).")
                prop("grep", "string", "Only messages matching this regular expression.")
                prop("minutes", "integer", "Only the last N minutes.")
                enumProp("buffer", DeviceLogcat.BUFFERS, "Log buffer (default main, system and crash).")
            }
        },
    )

    override fun call(context: Context, name: String, input: JsonObject): JsonObject = toolCall {
        toolText(
            when (name) {
                "device_state" -> deviceState(context, input)
                "media" -> media(input)
                "clock" -> clock(input)
                "system_setting" -> systemSetting(input)
                "app_control" -> appControl(context, input)
                "device_info" -> deviceInfo(input)
                "logcat" -> logcat(input)
                else -> throw ToolError("没有这个工具：$name")
            },
        )
    }

    // ---- device_state ----

    private val sh: Shell = RootShell

    private fun deviceState(context: Context, a: JsonObject): String {
        val target = a.str("target")?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        if (target != null && target !in DeviceSwitches.TARGETS) throw ToolError("不认识的 target：$target。可用：" + DeviceSwitches.TARGETS.joinToString(", "))
        return when (a.str("action") ?: "get") {
            "get" -> if (target == null) readAllStates(context) else "$target: " + readState(context, target)
            "set" -> setState(context, target ?: throw ToolError("set 要给 target"), anyString(a, "value"))
            else -> throw ToolError("action 只能是 get 或 set")
        }
    }

    private fun readState(context: Context, target: String): String {
        if (target == "flashlight") return Torch.read(context)
        val script = DeviceSwitches.readScript(target) ?: throw ToolError("不认识的 target：$target")
        val raw = sh.run(script, 15_000).let { it.stdout.ifBlank { it.stderr } }
        return DeviceSwitches.describe(target, raw) ?: ("unknown: " + raw.trim().lineSequence().firstOrNull().orEmpty().take(200))
    }

    private fun readAllStates(context: Context): String {
        val targets = DeviceSwitches.TARGETS.filter { it != "flashlight" }
        val parts = sections(sh.run(DeviceSwitches.readAllScript(targets), 30_000).stdout)
        return DeviceSwitches.TARGETS.joinToString("\n") { target ->
            val value = if (target == "flashlight") Torch.read(context)
            else parts[target]?.let { DeviceSwitches.describe(target, it) } ?: "unknown"
            "$target: $value"
        }
    }

    private fun setState(context: Context, target: String, value: String?): String {
        if (target == "flashlight") {
            val on = DeviceSwitches.switchValue(value) ?: throw ToolError("flashlight 的 value 要用 on 或 off")
            return "flashlight: " + Torch.set(context, on)
        }
        val plan = DeviceSwitches.setPlan(target, value)
        val before = readState(context, target)
        // 亮度即使数值相同也要照做：顺带关掉自动亮度
        if (target != "brightness" && DeviceSwitches.reached(target, before, plan.expect)) return "$target: $before (already)"
        if (target == "hotspot") HotspotHelper.run(context, plan.expect == "on")?.let { throw ToolError("没能切换热点：$it") }
        else sh.out(plan.command)
        // 有的开关要过一会儿才切换完
        var after = readState(context, target)
        repeat(if (target in DeviceSwitches.SLOW) 12 else 4) {
            if (DeviceSwitches.reached(target, after, plan.expect)) return@repeat
            Thread.sleep(500)
            after = readState(context, target)
        }
        return buildString {
            append(target).append(": ").append(after)
            if (!DeviceSwitches.reached(target, after, plan.expect)) append("\n（命令已执行，但读回来还不是 ").append(plan.expect).append("，可能还在切换，稍后用 get 再看）")
            if (target == "brightness") append("\nauto_brightness: off")
        }
    }

    // ---- media ----

    private fun media(a: JsonObject): String {
        val action = a.str("action") ?: "status"
        return when (action) {
            "status" -> DeviceAudio.status(sections(sh.out(DeviceAudio.STATUS_SCRIPT, 20_000)))
            "set_volume" -> {
                val streamName = a.str("stream") ?: throw ToolError("set_volume 要给 stream")
                val stream = DeviceAudio.STREAMS[streamName] ?: throw ToolError("stream 只能是 " + DeviceAudio.STREAMS.keys.joinToString("、"))
                val percent = a.int("percent")?.takeIf { it in 0..100 } ?: throw ToolError("set_volume 要给 0 到 100 的 percent")
                val before = DeviceAudio.parseVolumes(sh.out(DeviceAudio.VOLUMES_SCRIPT))[stream] ?: throw ToolError("读不到 $streamName 的音量")
                val level = DeviceAudio.levelFor(percent, before.min, before.max)
                sh.out("cmd audio set-volume $stream $level")
                val after = DeviceAudio.parseVolumes(sh.out(DeviceAudio.VOLUMES_SCRIPT))[stream]
                "$streamName: " + (after?.let { "${it.level}/${it.max} (${it.percent}%)" + if (it.muted) " muted" else "" } ?: "set to $level")
            }
            in DeviceAudio.KEYS -> {
                sh.out("cmd media_session dispatch " + DeviceAudio.KEYS.getValue(action))
                Thread.sleep(600)
                val sessions = DeviceAudio.parseSessions(sh.out("dumpsys media_session", 15_000))
                "sent $action" + if (sessions.isEmpty()) "\n（没有媒体会话，可能没有应用在放）"
                else "\n" + sessions.joinToString("\n") { "${it.packageName}: ${it.state ?: "no state"}" + (it.metadata?.let { m -> " — $m" } ?: "") }
            }
            else -> throw ToolError("不认识的 action：$action")
        }
    }

    // ---- clock ----

    private fun contentOut(command: String): String {
        val output = sh.out(command)
        XiaomiClock.contentError(output)?.let { throw ToolError("时钟没接受：$it") }
        return output
    }

    private fun alarms(): List<XiaomiClock.Alarm> {
        val output = sh.run(XiaomiClock.listCommand(), 15_000)
        if (output.exitCode != 0 || XiaomiClock.contentError(output.stdout) != null) {
            throw ToolError("读不到小米时钟的闹钟（${XiaomiClock.PACKAGE}）：" + (XiaomiClock.contentError(output.stdout) ?: output.stderr.trim().take(200)))
        }
        return XiaomiClock.parseAlarms(output.stdout)
    }

    private fun clock(a: JsonObject): String {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        fun line(alarm: XiaomiClock.Alarm) = XiaomiClock.alarmLine(alarm, zone, now)
        fun hour() = a.int("hour")?.also { if (it !in 0..23) throw ToolError("hour 是 0 到 23") }
        fun minute() = a.int("minute")?.also { if (it !in 0..59) throw ToolError("minute 是 0 到 59") }
        fun alarmId() = a.int("alarm_id")?.toLong() ?: throw ToolError("要给 alarm_id（先用 list_alarms 查）")
        return when (val action = a.str("action")) {
            "list_alarms" -> alarms().sortedWith(compareBy({ it.hour }, { it.minute })).joinToString("\n") { line(it) }.ifEmpty { "no alarms" }
            "add_alarm" -> {
                val hour = hour() ?: throw ToolError("add_alarm 要给 hour")
                val minute = minute() ?: throw ToolError("add_alarm 要给 minute")
                val days = XiaomiClock.daysMask(a.str("days"))
                val before = alarms().map { it.id }.toSet()
                contentOut(XiaomiClock.addCommand(hour, minute, days, a.str("label").orEmpty().take(100), a.bool("vibrate") ?: true))
                val added = alarms().filter { it.id !in before }.maxByOrNull { it.id } ?: throw ToolError("时钟没有报错，但读回来没有新闹钟")
                "added " + line(added)
            }
            "update_alarm" -> {
                val id = alarmId()
                val current = alarms().firstOrNull { it.id == id } ?: throw ToolError("没有 #$id 这个闹钟")
                val days = a.str("days")?.let { XiaomiClock.daysMask(it) }
                contentOut(XiaomiClock.updateCommand(current, hour(), minute(), days, a.bool("enabled"), a.str("label")?.take(100), a.bool("vibrate")))
                val after = alarms().firstOrNull { it.id == id } ?: throw ToolError("改完读不到 #$id 了")
                "updated " + line(after)
            }
            "delete_alarm" -> {
                val id = alarmId()
                val current = alarms().firstOrNull { it.id == id } ?: throw ToolError("没有 #$id 这个闹钟")
                contentOut(XiaomiClock.deleteCommand(id))
                if (alarms().any { it.id == id }) throw ToolError("删除没有生效，#$id 还在")
                "deleted " + line(current)
            }
            "start_timer" -> {
                val seconds = a.int("seconds")?.takeIf { it in 2..86_399 } ?: throw ToolError("start_timer 要给 2 到 86399 的 seconds")
                contentOut(XiaomiClock.timerStartCommand(seconds))
                Thread.sleep(500)
                XiaomiClock.timerText(contentOut(XiaomiClock.timerStatusCommand()), System.currentTimeMillis())
            }
            "pause_timer", "resume_timer" -> {
                contentOut(XiaomiClock.timerStateCommand(if (action == "pause_timer") 2 else 1))
                Thread.sleep(300)
                XiaomiClock.timerText(contentOut(XiaomiClock.timerStatusCommand()), System.currentTimeMillis())
            }
            "cancel_timer" -> {
                contentOut(XiaomiClock.timerCancelCommand())
                Thread.sleep(300)
                XiaomiClock.timerText(contentOut(XiaomiClock.timerStatusCommand()), System.currentTimeMillis())
            }
            "timer_status" -> XiaomiClock.timerText(contentOut(XiaomiClock.timerStatusCommand()), now)
            else -> throw ToolError("不认识的 action：$action")
        }
    }

    // ---- system_setting ----

    private fun systemSetting(a: JsonObject): String {
        val namespace = a.str("namespace")?.takeIf { it in setOf("system", "secure", "global") } ?: throw ToolError("namespace 只能是 system、secure 或 global")
        fun key() = a.str("key")?.trim()?.takeIf { it.isNotEmpty() && it.none { c -> c.isWhitespace() } } ?: throw ToolError("要给 key（不含空白）")
        fun get(key: String) = sh.out("settings get $namespace ${LinuxEnv.quote(key)}").removeSuffix("\n").let { if (it == "null") "(not set)" else it }
        return when (val action = a.str("action")) {
            "get" -> key().let { "$namespace/$it = " + get(it) }
            "put" -> {
                val key = key()
                val value = anyString(a, "value") ?: throw ToolError("put 要给 value")
                val before = get(key)
                sh.out("settings put $namespace ${LinuxEnv.quote(key)} ${LinuxEnv.quote(value)}")
                "$namespace/$key = " + get(key) + " (was $before)"
            }
            "delete" -> {
                val key = key()
                val before = get(key)
                sh.out("settings delete $namespace ${LinuxEnv.quote(key)}")
                "$namespace/$key deleted (was $before)"
            }
            "list" -> {
                val filter = a.str("filter")?.trim().orEmpty()
                val rows = sh.out("settings list $namespace", 20_000).lineSequence()
                    .filter { it.isNotBlank() && (filter.isEmpty() || it.substringBefore('=').contains(filter, ignoreCase = true)) }
                    .map { it.clipChars(300, "…") }
                    .toList()
                if (rows.isEmpty()) "no settings" + if (filter.isNotEmpty()) " matching \"$filter\"" else ""
                else rows.take(300).joinToString("\n") + if (rows.size > 300) "\n…(${rows.size - 300} more; use filter)" else ""
            }
            else -> throw ToolError("action 只能是 get、put、delete 或 list")
        }
    }

    // ---- app_control ----

    private fun label(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() && it != packageName }

    private fun named(context: Context, packageName: String): String = label(context, packageName)?.let { "$it ($packageName)" } ?: packageName

    private fun appControl(context: Context, a: JsonObject): String {
        val action = a.str("action") ?: throw ToolError("要给 action")
        val packageName = a.str("package")?.trim()?.takeIf { it.isNotEmpty() }
        if (packageName != null && !PACKAGE_NAME.matches(packageName)) throw ToolError("包名格式不对：$packageName")
        fun required() = packageName ?: throw ToolError("$action 要给 package")
        fun guard(p: String) { if (p in DeviceApps.PROTECTED) throw ToolError("$p 是系统或 Zeta 自己离不开的包，不$action") }
        fun state(p: String) = DeviceApps.userState(sh.run(DeviceApps.stateCommand(p), 15_000).stdout)
        return when (action) {
            "info" -> {
                val p = required()
                DeviceApps.parseInfo(p, sh.out(DeviceApps.infoCommand(p), 20_000), label(context, p)) ?: throw ToolError("没有安装 $p")
            }
            "force_stop" -> {
                val p = required().also(::guard)
                if (state(p).first == null) throw ToolError("没有安装 $p")
                sh.out("am force-stop --user 0 ${LinuxEnv.quote(p)}")
                "force-stopped ${named(context, p)}" + if (state(p).second == true) "" else "（读回来还不是 stopped 状态）"
            }
            "freeze" -> {
                val p = required().also(::guard)
                if (state(p).first == null) throw ToolError("没有安装 $p")
                sh.out("pm disable-user --user 0 ${LinuxEnv.quote(p)}")
                val after = state(p).first
                "${named(context, p)}: " + if (after == "3" || after == "2") "frozen" else "state enabled=$after（没冻上）"
            }
            "unfreeze" -> {
                val p = required()
                if (state(p).first == null) throw ToolError("没有安装 $p")
                sh.out("pm enable --user 0 ${LinuxEnv.quote(p)}")
                val after = state(p).first
                "${named(context, p)}: " + if (after == "0" || after == "1") "enabled" else "state enabled=$after（没解冻）"
            }
            "usage", "recent" -> {
                val hours = (a.int("hours") ?: 24).coerceIn(1, 24)
                val limit = (a.int("limit") ?: 20).coerceIn(1, 100)
                val now = LocalDateTime.now()
                val since = now.minusHours(hours.toLong())
                val events = DeviceApps.parseEvents(sh.out(DeviceApps.EVENTS_COMMAND, 30_000))
                fun shown(p: String) = if (packageName != null) p == packageName else p !in HOME_PACKAGES
                if (action == "usage") {
                    val rows = DeviceApps.usage(events, since, now).filter { shown(it.packageName) }.take(limit)
                    if (rows.isEmpty()) "no app usage in the last $hours h"
                    else "foreground time in the last $hours h:\n" + rows.joinToString("\n") { u ->
                        "${named(context, u.packageName)}: ${spoken(u.foregroundMs)}, opened ${u.opens}×" +
                            (u.last?.let { ", last ${it.format(CLOCK)}" } ?: "")
                    }
                } else {
                    val rows = DeviceApps.recent(events, since, HOME_PACKAGES).filter { packageName == null || it.second == packageName }.take(limit)
                    if (rows.isEmpty()) "no apps opened in the last $hours h"
                    else "apps opened in the last $hours h, newest first:\n" + rows.joinToString("\n") { (time, p) -> "${time.format(CLOCK)} ${named(context, p)}" }
                }
            }
            else -> throw ToolError("不认识的 action：$action")
        }
    }

    // ---- device_info ----

    private fun deviceInfo(a: JsonObject): String {
        val limit = (a.int("limit") ?: 15).coerceIn(1, 50)
        return when (val what = a.str("what")) {
            "overview" -> DeviceInfo.overview(sections(sh.out(DeviceInfo.OVERVIEW_SCRIPT, 30_000)))
            "location" -> DeviceInfo.location(sh.out(DeviceInfo.LOCATION_SCRIPT, 15_000))
            "top_memory" -> DeviceInfo.topMemory(sh.out(DeviceInfo.topMemoryCommand(limit)))
            "top_storage" -> DeviceInfo.topStorage(sh.out(DeviceInfo.STORAGE_COMMAND, 30_000), limit)
            "wifi_passwords" -> {
                val filter = a.str("ssid")?.trim().orEmpty()
                val networks = DeviceInfo.parseWifiStore(sh.out("cat ${DeviceInfo.WIFI_STORE}"))
                    .filter { filter.isEmpty() || it.ssid.contains(filter, ignoreCase = true) }
                if (networks.isEmpty()) "no saved network" + if (filter.isNotEmpty()) " matching \"$filter\"" else ""
                else networks.joinToString("\n") { w -> "${w.ssid}: " + (w.password?.let { "password $it" } ?: "no password") + " (${w.security})" }
            }
            else -> throw ToolError("what 只能是 " + DeviceInfo.WHAT.joinToString("、") + "，收到 $what")
        }
    }

    // ---- logcat ----

    private fun logcat(a: JsonObject): String {
        val packageName = a.str("package")?.trim()?.takeIf { it.isNotEmpty() }
        val uid = packageName?.let { p ->
            if (!PACKAGE_NAME.matches(p)) throw ToolError("包名格式不对：$p")
            DeviceLogcat.parseUid(sh.out(DeviceLogcat.uidCommand(p)), p) ?: throw ToolError("没有安装 $p")
        }
        val level = a.str("level")?.uppercase(Locale.ROOT)?.takeIf { it in DeviceLogcat.LEVELS } ?: "V"
        val buffer = a.str("buffer")?.takeIf { it in DeviceLogcat.BUFFERS }
        val minutes = a.int("minutes")?.coerceIn(1, 7 * 24 * 60)
        val query = DeviceLogcat.Query(
            lines = (a.int("lines") ?: DeviceLogcat.DEFAULT_LINES).coerceIn(1, DeviceLogcat.MAX_LINES),
            uid = uid,
            tags = DeviceLogcat.parseTags(a.str("tag")),
            level = level,
            grep = a.str("grep")?.takeIf { it.isNotBlank() },
            since = minutes?.let { LocalDateTime.now().minusMinutes(it.toLong()) },
            buffer = buffer,
        )
        val output = sh.run(DeviceLogcat.command(query), 30_000)
        if (output.exitCode != 0 && output.stdout.isBlank()) throw ToolError("logcat 失败：" + output.stderr.trim().take(300))
        return DeviceLogcat.trim(output.stdout).ifBlank { "no matching log lines" }
    }

    // ---- 标题 ----

    override fun title(name: String, input: JsonObject): String = when (name) {
        "device_state" -> {
            val target = input.str("target")
            val what = DeviceSwitches.NAMES[target] ?: target
            if (input.str("action") == "set" && target != null) setTitle(target, what ?: target, anyString(input, "value"))
            else what?.let { "查看$it" } ?: "查看手机开关状态"
        }
        "media" -> when (val action = input.str("action")) {
            "set_volume" -> (DeviceAudio.STREAM_NAMES[input.str("stream")] ?: "") + "音量调到 " + (input.int("percent")?.let { "$it%" } ?: "")
            null, "status" -> "查看音量与播放"
            else -> DeviceAudio.KEY_NAMES[action] ?: "控制播放"
        }
        "clock" -> {
            val time = input.int("hour")?.let { h -> String.format(Locale.ROOT, "%02d:%02d", h, input.int("minute") ?: 0) }
            when (input.str("action")) {
                "list_alarms" -> "查看闹钟"
                "add_alarm" -> "新建闹钟" + (time?.let { " $it" } ?: "")
                "update_alarm" -> when (input.bool("enabled")) {
                    true -> "打开闹钟"
                    false -> "关闭闹钟"
                    null -> "修改闹钟" + (time?.let { " 为 $it" } ?: "")
                }
                "delete_alarm" -> "删除闹钟"
                "start_timer" -> "开始倒计时" + (input.int("seconds")?.let { " " + spokenZh(it) } ?: "")
                "pause_timer" -> "暂停倒计时"
                "resume_timer" -> "继续倒计时"
                "cancel_timer" -> "取消倒计时"
                "timer_status" -> "查看倒计时"
                else -> "时钟"
            }
        }
        "system_setting" -> {
            val key = input.str("key")?.let { " $it" }.orEmpty()
            when (input.str("action")) {
                "put" -> "修改系统设置$key"
                "delete" -> "删除系统设置$key"
                "list" -> "列出系统设置"
                else -> "读取系统设置$key"
            }
        }
        "app_control" -> {
            val p = input.str("package")?.let { " $it" }.orEmpty()
            when (input.str("action")) {
                "info" -> "查看应用信息$p"
                "force_stop" -> "强行停止$p"
                "freeze" -> "冻结$p"
                "unfreeze" -> "解冻$p"
                "usage" -> "查看应用使用时长"
                "recent" -> "查看最近打开的应用"
                else -> "管理应用$p"
            }
        }
        "device_info" -> when (input.str("what")) {
            "location" -> "查看当前位置"
            "top_memory" -> "查看内存占用"
            "top_storage" -> "查看存储占用"
            "wifi_passwords" -> "读取已保存的 Wi-Fi 密码"
            else -> "查看电池、存储与温度"
        }
        "logcat" -> "读取系统日志" + (input.str("package") ?: input.str("tag"))?.let { " $it" }.orEmpty()
        else -> name
    }

    private fun setTitle(target: String, what: String, value: String?): String {
        if (target in DeviceSwitches.SWITCHES) {
            return when (DeviceSwitches.switchValue(value)) {
                true -> "打开$what"
                false -> "关闭$what"
                null -> "设置$what"
            }
        }
        val v = value?.trim().orEmpty()
        return when (target) {
            "brightness" -> "亮度调到 ${v.removeSuffix("%")}%"
            "screen_timeout" -> "熄屏时间设为 " + (v.toIntOrNull()?.let { spokenZh(it) } ?: if (v == "never") "永不" else v)
            "do_not_disturb" -> when (v) {
                "off" -> "关闭勿扰模式"
                "alarms" -> "勿扰模式设为仅闹钟"
                "silence", "none" -> "勿扰模式设为完全静音"
                else -> "打开勿扰模式"
            }
            "ringer_mode" -> "响铃模式设为" + when (v) { "vibrate" -> "振动"; "silent" -> "静音"; else -> "响铃" }
            else -> "设置$what"
        }
    }

    /** 秒数说成「5 分钟」「1 小时 30 分」。 */
    internal fun spokenZh(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return buildString {
            if (h > 0) append(h).append(" 小时")
            if (m > 0) append(if (isEmpty()) "" else " ").append(m).append(if (h > 0) " 分" else " 分钟")
            if (s > 0 || isEmpty()) append(if (isEmpty()) "" else " ").append(s).append(" 秒")
        }
    }

    /** 字符串、数字、布尔都当字符串取（Claude 可能给 value: true 或 value: 50）。 */
    private fun anyString(a: JsonObject, key: String): String? = (a[key] as? JsonPrimitive)?.contentOrNull

    private val CLOCK = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)

    /** 桌面和系统界面：使用时长、最近打开里不列。 */
    private val HOME_PACKAGES = setOf("com.miui.home", "com.android.systemui", "com.android.launcher3")
}
