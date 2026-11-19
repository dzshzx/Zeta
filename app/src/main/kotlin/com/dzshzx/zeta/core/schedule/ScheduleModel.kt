package com.dzshzx.zeta.core.schedule

import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.NoticeItem
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.clipChars
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.long
import com.dzshzx.zeta.core.obj
import com.dzshzx.zeta.core.str
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 定时任务怎么重复：[key] 是存储和手机工具参数里的写法，[label] 是编辑页上的叫法。 */
enum class RuleKind(val key: String, val label: String) {
    Once("once", "单次"),
    Daily("daily", "每天"),
    Weekly("weekly", "每周"),
    Interval("interval", "间隔"),
    ;

    companion object {
        fun of(key: String?): RuleKind? = entries.firstOrNull { it.key == key }
    }
}

/** 定时任务什么时候跑。时刻都是手机的本地时间，精确到分钟。 */
sealed interface ScheduleRule {
    val kind: RuleKind

    /** 几点跑：单次是到点的那一刻，间隔是第一次的时刻。 */
    val time: LocalTime

    /** 到点跑一次，跑过就自动关掉。 */
    data class Once(val at: LocalDateTime) : ScheduleRule {
        override val kind get() = RuleKind.Once
        override val time: LocalTime get() = at.toLocalTime()
    }

    data class Daily(override val time: LocalTime) : ScheduleRule {
        override val kind get() = RuleKind.Daily
    }

    /** 每周的这几天。 */
    data class Weekly(val days: Set<DayOfWeek>, override val time: LocalTime) : ScheduleRule {
        override val kind get() = RuleKind.Weekly
    }

    /** 从 [start] 起每隔 [hours] 小时（按绝对时间算，跨日照常往后排）。 */
    data class Interval(val hours: Int, val start: LocalDateTime) : ScheduleRule {
        override val kind get() = RuleKind.Interval
        override val time: LocalTime get() = start.toLocalTime()
    }

    companion object {
        const val MAX_INTERVAL_HOURS = 168
    }
}

enum class RunOutcome(val key: String, val label: String) {
    Running("running", "正在运行"),
    Done("done", "完成"),

    /** 只在有变化时提醒的任务：Claude 上报这次没有值得说的，没发通知。 */
    NoChange("no_change", "没有变化"),
    Failed("failed", "没有完成"),
    Stopped("stopped", "被停止"),

    /** Zeta 在这一轮结束前被关掉了，没等到结果（Claude Code 可能已经在后台跑完）。 */
    Interrupted("interrupted", "中途被关掉"),
    ;

    companion object {
        fun of(key: String?): RunOutcome? = entries.firstOrNull { it.key == key }
    }
}

/** 一次运行：开始时间、结果、那次的会话和回答开头（或出错原因）。 */
data class TaskRun(
    val at: Long,
    val outcome: RunOutcome,
    val sessionId: String? = null,
    val summary: String = "",
    /** 是用户点的“立即运行一次”，不是到点触发的。 */
    val manual: Boolean = false,
)

data class ScheduledTask(
    val id: String,
    val name: String,
    /** 到点时原样发给 Claude 的指令。 */
    val prompt: String,
    val rule: ScheduleRule,
    val enabled: Boolean = true,
    /** 工作目录；null 用运行那一刻的默认工作目录。 */
    val cwd: String? = null,
    /** null 跟随当前设置。 */
    val model: String? = null,
    val permissionMode: String? = null,
    /** 每次开新对话；false 时接着 [sessionId] 那个对话。 */
    val newSession: Boolean = true,
    /** 接着同一个对话时用的会话（第一次运行后才有）。 */
    val sessionId: String? = null,
    /** 只在有变化时提醒：Claude 上报“没有变化”时不发通知，只记进运行记录。 */
    val onlyOnChange: Boolean = false,
    val created: Long = 0L,
    val lastRun: TaskRun? = null,
    /** 到点时上一次还没跑完、跳过了几次，以及最近一次跳过的时间。 */
    val skips: Int = 0,
    val lastSkip: Long? = null,
)

/** 新建、编辑时用户填的那些；[toTask] 时再补上 id 和运行记录。 */
data class TaskDraft(
    val name: String,
    val prompt: String,
    val rule: ScheduleRule,
    val cwd: String? = null,
    val model: String? = null,
    val permissionMode: String? = null,
    val newSession: Boolean = true,
    val enabled: Boolean = true,
    val onlyOnChange: Boolean = false,
)

/** Claude 在只在有变化时提醒的运行里上报的结论（手机工具 report_scheduled_run）。 */
data class RunReport(
    /** true：有结果要告诉用户；false：没有变化。 */
    val changed: Boolean,
    /** 一两句话：有结果时是通知的正文，没有变化时只记进运行记录。 */
    val summary: String,
)

/** 一次运行跑完以后记成什么、摘要写什么、发不发完成通知（见 [Schedules.settle]）。 */
data class Settled(val outcome: RunOutcome, val summary: String, val notify: Boolean)

/** 编辑的结果（见 [Schedules.edit]）：[draft] 是要存的样子，开关已经定好；[problem] 是不能保存的原因。 */
data class Edited(val draft: TaskDraft, val problem: String?)

object Schedules {
    const val MAX_TASKS = 50
    const val MAX_NAME = 40
    const val MAX_PROMPT = 8000

    /** 通知里那段摘要（回答的开头、Claude 上报的结论）最多多少字。 */
    const val MAX_SUMMARY = 200

    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val WEEKDAY = listOf("一", "二", "三", "四", "五", "六", "日")

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    fun draftOf(task: ScheduledTask) =
        TaskDraft(task.name, task.prompt, task.rule, task.cwd, task.model, task.permissionMode, task.newSession, task.enabled, task.onlyOnChange)

    /** 编辑保存：规则以外的运行记录照旧；换了“开新对话”的方式就不再接着原来那个会话。 */
    fun applyDraft(task: ScheduledTask, draft: TaskDraft) = task.copy(
        name = draft.name.trim(),
        prompt = draft.prompt.trim(),
        rule = draft.rule,
        cwd = draft.cwd?.trim()?.takeIf { it.isNotEmpty() },
        model = draft.model,
        permissionMode = draft.permissionMode,
        newSession = draft.newSession,
        enabled = draft.enabled,
        onlyOnChange = draft.onlyOnChange,
        sessionId = if (draft.newSession != task.newSession) null else task.sessionId,
    )

    fun toTask(draft: TaskDraft, now: Long, id: String = newId()) = applyDraft(
        ScheduledTask(id = id, name = "", prompt = "", rule = draft.rule, newSession = draft.newSession, created = now),
        draft,
    )

    /**
     * 新建或编辑一个任务（设置页的编辑器、手机工具 update_scheduled_task 共用）：[draft] 是改完的样子（它的 enabled 不看），
     * [enabled] 是这次明确给的开关，没给为 null。改了时间就当用户要它重新开始：关着的打开，除非明确给了开关；
     * 没改时间、改完还关着的不看时间，跑过、被自动关掉的单次任务也能改名、改指令。新建时 [original] 为 null。
     */
    fun edit(original: ScheduledTask?, draft: TaskDraft, enabled: Boolean?, now: Instant, zone: ZoneId): Edited {
        val ruleChanged = original == null || draft.rule != original.rule
        val on = enabled ?: (original == null || original.enabled || ruleChanged)
        val result = draft.copy(enabled = on)
        return Edited(result, problem(result, now, zone, checkRule = ruleChanged || on))
    }

    /**
     * 不能保存的原因；能保存时为 null。[now] 用来判断单次任务的时间是不是已经过了。
     * [checkRule] 为 false 时不看时间（见 [edit]）。
     */
    fun problem(draft: TaskDraft, now: Instant, zone: ZoneId, checkRule: Boolean = true): String? {
        val name = draft.name.trim()
        val cwd = draft.cwd?.trim().orEmpty()
        return when {
            name.isEmpty() -> "写一个名字"
            name.length > MAX_NAME -> "名字最多 $MAX_NAME 个字"
            draft.prompt.isBlank() -> "写上要让 Claude 做什么"
            draft.prompt.length > MAX_PROMPT -> "指令最多 $MAX_PROMPT 个字"
            cwd.isNotEmpty() && (!cwd.startsWith("/") || '\n' in cwd) -> "工作目录要写 Linux 环境里的绝对路径"
            checkRule -> ruleProblem(draft.rule, now, zone)
            else -> null
        }
    }

    fun ruleProblem(rule: ScheduleRule, now: Instant, zone: ZoneId): String? = when (rule) {
        is ScheduleRule.Once -> if (nextTrigger(rule, now, zone) == null) "这个时间已经过了" else null
        is ScheduleRule.Weekly -> if (rule.days.isEmpty()) "至少选一天" else null
        is ScheduleRule.Interval -> if (rule.hours !in 1..ScheduleRule.MAX_INTERVAL_HOURS) "间隔要在 1 到 ${ScheduleRule.MAX_INTERVAL_HOURS} 小时之间" else null
        is ScheduleRule.Daily -> null
    }

    /**
     * [after] 之后（不含）下一次该跑的时刻；单次任务时间已过、每周一天都没选时为 null。
     * 本地时刻在夏令时跳过的那一小时里时按 java.time 的规则往后挪，不另做处理。
     */
    fun nextTrigger(rule: ScheduleRule, after: Instant, zone: ZoneId): Instant? {
        val today = after.atZone(zone).toLocalDate()
        fun at(date: LocalDate, time: LocalTime): Instant = date.atTime(time).atZone(zone).toInstant()
        return when (rule) {
            is ScheduleRule.Once -> rule.at.atZone(zone).toInstant().takeIf { it.isAfter(after) }
            is ScheduleRule.Daily -> at(today, rule.time).takeIf { it.isAfter(after) } ?: at(today.plusDays(1), rule.time)
            is ScheduleRule.Weekly -> (0L..7L).asSequence()
                .map { today.plusDays(it) }
                .filter { it.dayOfWeek in rule.days }
                .map { at(it, rule.time) }
                .firstOrNull { it.isAfter(after) }
            is ScheduleRule.Interval -> {
                if (rule.hours <= 0) return null
                val start = rule.start.atZone(zone).toInstant()
                if (after.isBefore(start)) return start
                val period = rule.hours * 3_600_000L
                val passed = (after.toEpochMilli() - start.toEpochMilli()) / period + 1
                start.plusMillis(passed * period)
            }
        }
    }

    /** “每天 08:00”“每周一、三、五 08:00”“每 3 小时”这样的一句。 */
    fun describe(rule: ScheduleRule): String = when (rule) {
        is ScheduleRule.Once -> "仅一次 · " + rule.at.monthValue + "月" + rule.at.dayOfMonth + "日 " + rule.at.toLocalTime().format(HM)
        is ScheduleRule.Daily -> "每天 " + rule.time.format(HM)
        is ScheduleRule.Weekly -> weekdays(rule.days) + " " + rule.time.format(HM)
        is ScheduleRule.Interval -> "每 ${rule.hours} 小时 · 从 " + rule.start.toLocalTime().format(HM) + " 起"
    }

    fun weekdays(days: Set<DayOfWeek>): String {
        val workdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        return when (days) {
            DayOfWeek.entries.toSet() -> "每天"
            workdays -> "工作日"
            setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "周末"
            else -> "每周" + days.sorted().joinToString("、") { WEEKDAY[it.value - 1] }
        }
    }

    fun weekdayName(day: DayOfWeek): String = "周" + WEEKDAY[day.value - 1]

    /** 界面上说一个时刻：“今天 08:00”“明天 08:00”“11月22日 周六 08:00”。 */
    fun formatWhen(at: Instant, now: Instant, zone: ZoneId): String {
        val time = at.atZone(zone)
        val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), time.toLocalDate())
        val hm = time.toLocalTime().format(HM)
        return when (days) {
            0L -> "今天 $hm"
            1L -> "明天 $hm"
            -1L -> "昨天 $hm"
            else -> {
                val sameYear = time.year == now.atZone(zone).year
                (if (sameYear) "" else "${time.year}年") + "${time.monthValue}月${time.dayOfMonth}日 " + weekdayName(time.dayOfWeek) + " " + hm
            }
        }
    }

    /** 回答的开头，给通知和列表用：去掉 Markdown 的标记、合并空白，太长截断。 */
    fun preview(text: String, limit: Int = MAX_SUMMARY): String {
        val plain = text.lineSequence()
            .map { line ->
                line.trim()
                    .replace(Regex("^#{1,6}\\s+"), "")
                    .replace(Regex("^(?:[-*+]|\\d+[.)])\\s+"), "")
                    .replace(Regex("^>\\s?"), "")
            }
            .filter { it.isNotEmpty() && !it.startsWith("```") && !Regex("^[-*_|: ]{3,}$").matches(it) }
            .joinToString(" ")
            .replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("(\\*\\*|__|`)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return plain.clipChars(limit, "…")
    }

    /** 这一轮的结果：看这次发出的那条消息之后的内容。回答取最后一段（前面多是“我先看看”），出错取提示。 */
    fun outcomeOf(items: List<ChatItem>, prompt: String): Pair<RunOutcome, String> {
        val mine = items.indexOfLast { it is UserItem && it.text == prompt }
        val tail = if (mine < 0) items.takeLastWhile { it !is UserItem } else items.drop(mine + 1)
        val answer = tail.filterIsInstance<AnswerItem>().lastOrNull { it.text.isNotBlank() }?.text.orEmpty()
        val notices = tail.filterIsInstance<NoticeItem>()
        val error = notices.lastOrNull { it.error }?.text
        return when {
            notices.any { !it.error && it.text.startsWith("已停止") } -> RunOutcome.Stopped to (Schedules.preview(answer).ifBlank { "已停止" })
            error != null -> RunOutcome.Failed to Schedules.preview(error)
            mine < 0 -> RunOutcome.Failed to "指令没有发出去"
            else -> RunOutcome.Done to Schedules.preview(answer)
        }
    }

    /**
     * 一次运行跑完：[outcome]、[answer] 是 [outcomeOf] 看对话得出的，[report] 是 Claude 上报的结论（没上报为 null），
     * [asked] 是这次停下来等过用户确认。只在有变化时提醒的任务，Claude 上报了“没有变化”才不发通知，记成 [RunOutcome.NoChange]；
     * 没上报、出了错、等过确认的照常提醒（宁可多提醒一次，不漏）。上报了有结果的，通知用它的摘要。用户叫停的不提醒。
     * 不是只在有变化时提醒的任务不看上报。
     */
    fun settle(outcome: RunOutcome, answer: String, onlyOnChange: Boolean, report: RunReport?, asked: Boolean): Settled {
        if (outcome != RunOutcome.Done || !onlyOnChange || report == null) return Settled(outcome, answer, notify = outcome != RunOutcome.Stopped)
        val summary = preview(report.summary)
        return if (report.changed) {
            Settled(RunOutcome.Done, summary.ifBlank { answer }, notify = true)
        } else {
            Settled(RunOutcome.NoChange, summary.ifBlank { RunOutcome.NoChange.label }, notify = asked)
        }
    }

    // ---- 文字形式的时间（手机工具的参数） ----

    /** “08:00”“8:00”“0800”。 */
    fun parseTime(text: String?): LocalTime? {
        val t = text?.trim().orEmpty()
        val m = Regex("^(\\d{1,2}):?(\\d{2})$").matchEntire(t) ?: return null
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
    }

    /** “2026-11-20T08:00”“2026-11-20 08:00”；秒数舍掉。 */
    fun parseDateTime(text: String?): LocalDateTime? {
        val t = text?.trim()?.replace(' ', 'T').orEmpty()
        if (t.isEmpty()) return null
        return runCatching { LocalDateTime.parse(t).truncatedTo(ChronoUnit.MINUTES) }.getOrNull()
    }

    private val DAY_KEYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    fun dayKey(day: DayOfWeek): String = DAY_KEYS[day.value - 1]

    /** “mon”“monday”“1”（周一）到“sun”“7”。 */
    fun parseDay(text: String): DayOfWeek? {
        val t = text.trim().lowercase()
        t.toIntOrNull()?.let { return if (it in 1..7) DayOfWeek.of(it) else null }
        val index = DAY_KEYS.indexOfFirst { t.startsWith(it) }
        return if (index >= 0) DayOfWeek.of(index + 1) else null
    }

    // ---- 存储：Zeta 设置里的一个 JSON 数组 ----

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun encode(tasks: List<ScheduledTask>): String = buildJsonArray {
        for (task in tasks) add(encodeTask(task))
    }.toString()

    private fun encodeTask(task: ScheduledTask): JsonObject = buildJsonObject {
        put("id", task.id)
        put("name", task.name)
        put("prompt", task.prompt)
        put("rule", encodeRule(task.rule))
        put("enabled", task.enabled)
        task.cwd?.let { put("cwd", it) }
        task.model?.let { put("model", it) }
        task.permissionMode?.let { put("permissionMode", it) }
        put("newSession", task.newSession)
        task.sessionId?.let { put("sessionId", it) }
        if (task.onlyOnChange) put("onlyOnChange", true)
        put("created", task.created)
        task.lastRun?.let { put("lastRun", encodeRun(it)) }
        if (task.skips > 0) put("skips", task.skips)
        task.lastSkip?.let { put("lastSkip", it) }
    }

    /** 一次运行的记录（定时任务和通知触发的规则共用这个格式）。 */
    fun encodeRun(run: TaskRun): JsonObject = buildJsonObject {
        put("at", run.at)
        put("outcome", run.outcome.key)
        run.sessionId?.let { put("session", it) }
        put("summary", run.summary)
        if (run.manual) put("manual", true)
    }

    fun decodeRun(o: JsonObject?): TaskRun? {
        o ?: return null
        val at = o.long("at") ?: return null
        val outcome = RunOutcome.of(o.str("outcome")) ?: return null
        return TaskRun(at, outcome, o.str("session"), o.str("summary").orEmpty(), o.bool("manual") == true)
    }

    /** Zeta 进程刚起来时：上一个进程里没等到结果的那次运行记成中断。 */
    fun interrupted(run: TaskRun?): TaskRun? =
        if (run?.outcome == RunOutcome.Running) run.copy(outcome = RunOutcome.Interrupted, summary = "Zeta 在这次运行结束前被关掉了，结果在对话里") else run

    private fun encodeRule(rule: ScheduleRule): JsonObject = buildJsonObject {
        put("kind", rule.kind.key)
        when (rule) {
            is ScheduleRule.Once -> put("at", rule.at.toString())
            is ScheduleRule.Daily -> put("time", rule.time.format(HM))
            is ScheduleRule.Weekly -> {
                putJsonArray("days") { rule.days.sorted().forEach { add(JsonPrimitive(it.value)) } }
                put("time", rule.time.format(HM))
            }
            is ScheduleRule.Interval -> { put("hours", rule.hours); put("start", rule.start.toString()) }
        }
    }

    /** 容错：整段不是 JSON 数组时当成没有任务，单个任务缺了必要的字段就跳过它，其余字段缺了取默认值。 */
    fun decode(text: String?): List<ScheduledTask> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        val seen = HashSet<String>()
        return array.mapNotNull { element -> runCatching { decodeTask(element) }.getOrNull() }.filter { seen.add(it.id) }
    }

    private fun decodeTask(element: JsonElement): ScheduledTask? {
        val o = element as? JsonObject ?: return null
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val prompt = o.str("prompt")?.takeIf { it.isNotBlank() } ?: return null
        val rule = decodeRule(o.obj("rule")) ?: return null
        return ScheduledTask(
            id = id,
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: prompt.lineSequence().first().clipChars(MAX_NAME),
            prompt = prompt,
            rule = rule,
            enabled = o.bool("enabled") ?: true,
            cwd = o.str("cwd")?.takeIf { it.isNotBlank() },
            model = o.str("model")?.takeIf { it.isNotBlank() },
            permissionMode = o.str("permissionMode")?.takeIf { it.isNotBlank() },
            newSession = o.bool("newSession") ?: true,
            sessionId = o.str("sessionId")?.takeIf { it.isNotBlank() },
            onlyOnChange = o.bool("onlyOnChange") == true,
            created = o.long("created") ?: 0L,
            lastRun = decodeRun(o.obj("lastRun")),
            skips = (o.int("skips") ?: 0).coerceAtLeast(0),
            lastSkip = o.long("lastSkip"),
        )
    }

    private fun decodeRule(o: JsonObject?): ScheduleRule? {
        o ?: return null
        return when (RuleKind.of(o.str("kind"))) {
            RuleKind.Once -> parseDateTime(o.str("at"))?.let { ScheduleRule.Once(it) }
            RuleKind.Daily -> parseTime(o.str("time"))?.let { ScheduleRule.Daily(it) }
            RuleKind.Weekly -> {
                val time = parseTime(o.str("time")) ?: return null
                val days = o.arr("days").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.let(::parseDay) }.toSet()
                if (days.isEmpty()) null else ScheduleRule.Weekly(days, time)
            }
            RuleKind.Interval -> {
                val hours = o.int("hours")?.takeIf { it in 1..ScheduleRule.MAX_INTERVAL_HOURS } ?: return null
                parseDateTime(o.str("start"))?.let { ScheduleRule.Interval(hours, it) }
            }
            null -> null
        }
    }
}
