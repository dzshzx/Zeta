package com.dzshzx.zeta.core.diag

import com.dzshzx.zeta.i18n.Strings
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** 运行日志一条的轻重。 */
enum class DiagLevel(val code: String) {
    Info("i"),
    Warn("w"),
    Error("e");

    /** 界面上的名字，按界面语言取。 */
    val label: String
        get() =
            when (this) {
                Info -> Strings.diagLevelInfo()
                Warn -> Strings.diagLevelWarn()
                Error -> Strings.diagLevelError()
            }
}

/** 运行日志一条记的是哪方面的事，诊断页按它筛。 */
enum class DiagTopic(val code: String) {
    App("app"),
    Env("env"),
    Claude("claude"),
    Helper("helper"),
    Phone("phone"),
    Accessibility("a11y"),
    Schedule("schedule");

    /** 界面上的名字，按界面语言取。 */
    val label: String
        get() =
            when (this) {
                App -> Strings.diagTopicApp()
                Env -> Strings.settingsTitleLinux()
                Claude -> Strings.diagTopicClaude()
                Helper -> "helper"
                Phone -> Strings.diagTopicPhone()
                Accessibility -> Strings.diagTopicA11y()
                Schedule -> Strings.settingsTitleSchedules()
            }
}

data class DiagEntry(val at: Long, val level: DiagLevel, val topic: DiagTopic, val message: String)

/** 运行日志的存取格式与筛选。纯函数，单元测试覆盖。 */
object DiagEntries {
    /** 一条消息最长多少字；多出来的截掉（标准错误输出之类可能很长）。 */
    const val MAX_MESSAGE = 2000

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 文件里的一行：`{"t":毫秒,"l":"w","c":"a11y","m":"…"}`。 */
    fun encode(entry: DiagEntry): String = buildJsonObject {
        put("t", entry.at)
        put("l", entry.level.code)
        put("c", entry.topic.code)
        put("m", entry.message)
    }
        .toString()

    /** 读不懂的行（写到一半被杀、来自更新的版本）返回 null，跳过。 */
    fun decode(line: String): DiagEntry? {
        val o =
            runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
        val at = (o["t"] as? JsonPrimitive)?.longOrNull ?: return null
        val level =
            (o["l"] as? JsonPrimitive)?.contentOrNull?.let { c ->
                DiagLevel.entries.firstOrNull { it.code == c }
            } ?: return null
        val topic =
            (o["c"] as? JsonPrimitive)?.contentOrNull?.let { c ->
                DiagTopic.entries.firstOrNull { it.code == c }
            } ?: return null
        val message = (o["m"] as? JsonPrimitive)?.contentOrNull ?: return null
        return DiagEntry(at, level, topic, message)
    }

    /** 记之前把消息规整一下：去掉首尾空白，太长的截掉。 */
    fun clean(message: String): String {
        val trimmed = message.trim()
        return if (trimmed.length <= MAX_MESSAGE) trimmed else trimmed.take(MAX_MESSAGE) + "…（截断）"
    }

    /** [topic] 为 null 是全部；[minLevel] 以下的不要；[query] 不空时消息里要含它（不分大小写）。 */
    fun matches(
        entry: DiagEntry,
        topic: DiagTopic?,
        minLevel: DiagLevel,
        query: String = "",
    ): Boolean =
        (topic == null || entry.topic == topic) &&
            entry.level.ordinal >= minLevel.ordinal &&
            (query.isBlank() || entry.message.contains(query.trim(), ignoreCase = true))

    /** 导出用的一行文字：`2026-11-19 10:00:00.123 警告 [无障碍] 消息`。多行消息的后续行缩进。 */
    fun format(entry: DiagEntry, zone: ZoneId): String {
        val time = FULL.format(Instant.ofEpochMilli(entry.at).atZone(zone))
        return "$time ${entry.level.label} [${entry.topic.label}] " +
            entry.message.replace("\n", "\n    ")
    }

    /** 诊断页上显示的时间：今天的只显示时分秒，别的天带上日期。 */
    fun shortTime(at: Long, zone: ZoneId, today: LocalDate = LocalDate.now(zone)): String {
        val time = Instant.ofEpochMilli(at).atZone(zone)
        return if (time.toLocalDate() == today) CLOCK.format(time) else DAY.format(time)
    }

    private val FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val DAY = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
}

/**
 * Zeta 自己的运行日志：内存里一个有上限的环形缓冲，同时追加到应用数据里的文件， Zeta 被杀、被强行停止后重新打开还看得到之前的事。只记关键事件（进环境、起停 Claude
 * 进程、helper 出错、 手机工具失败、无障碍被关或加回、定时任务触发），不记对话内容。
 *
 * 文件最多攒到容量的两倍行，再按内存里的内容重写一遍。各线程都能调用。
 */
object DiagLog {
    const val CAPACITY = 2000

    private val lock = Any()
    private val ring = ArrayDeque<DiagEntry>()
    private val io = Executors.newSingleThreadExecutor {
        Thread(it, "zeta-diag-log").apply { isDaemon = true }
    }
    private var file: File? = null

    /** 每条也交给平台的日志（手机上是 logcat），[init] 时给。 */
    @Volatile private var echo: (DiagLevel, String) -> Unit = { _, _ -> }

    /** 文件里现在有多少行（含已经不在内存里的旧行）。只在 [io] 线程上碰。 */
    private var lines = 0

    private val changes = MutableStateFlow(0L)

    /** 每记一条加一；界面据此重读 [entries]。 */
    val revision: StateFlow<Long>
        get() = changes

    /** 应用启动时调用一次（ConversationHub 创建时）：读回上次留下的记录 [target]，之后的都写进它；每条另交给 [echo]（手机上是 logcat）。 */
    fun init(target: File, echo: (DiagLevel, String) -> Unit = { _, _ -> }) {
        this.echo = echo
        // init 之前记下的几条只在内存里，读完旧文件后补写进去；之后记的各自排队写
        val early =
            synchronized(lock) {
                if (file != null) return
                file = target
                ring.toList()
            }
        io.execute {
            val old = runCatching {
                target.takeIf { it.exists() }?.readLines()
            }
                .getOrNull()
                .orEmpty()
            lines = old.size
            val loaded = old.mapNotNull(DiagEntries::decode)
            synchronized(lock) {
                // 旧记录排在这次启动以来记下的前面
                val fresh = ring.toList()
                ring.clear()
                ring.addAll(loaded)
                ring.addAll(fresh)
                while (ring.size > CAPACITY) ring.removeFirst()
            }
            early.forEach(::append)
            changes.update { it + 1 }
        }
    }

    fun info(topic: DiagTopic, message: String) = log(DiagLevel.Info, topic, message)

    fun warn(topic: DiagTopic, message: String) = log(DiagLevel.Warn, topic, message)

    fun error(topic: DiagTopic, message: String) = log(DiagLevel.Error, topic, message)

    fun log(level: DiagLevel, topic: DiagTopic, message: String) {
        val entry = DiagEntry(System.currentTimeMillis(), level, topic, DiagEntries.clean(message))
        val ready =
            synchronized(lock) {
                ring.addLast(entry)
                while (ring.size > CAPACITY) ring.removeFirst()
                file != null
            }
        changes.update { it + 1 }
        runCatching { echo(level, "[${topic.code}] ${entry.message}") }
        if (ready) io.execute { append(entry) }
    }

    /** 内存里的全部记录，旧的在前。 */
    fun entries(): List<DiagEntry> = synchronized(lock) { ring.toList() }

    /** 清空（诊断页上的“清空日志”）。文件一起清掉。 */
    fun clear() {
        synchronized(lock) { ring.clear() }
        changes.update { it + 1 }
        io.execute {
            val target = file ?: return@execute
            runCatching { target.delete() }
            lines = 0
        }
    }

    private fun append(entry: DiagEntry) {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            target.appendText(DiagEntries.encode(entry) + "\n")
            lines++
            if (lines > CAPACITY * 2) {
                val keep = entries()
                val temp = File(target.parentFile, target.name + ".tmp")
                temp.writeText(keep.joinToString("") { DiagEntries.encode(it) + "\n" })
                if (temp.renameTo(target)) lines = keep.size
            }
        }
    }
}
