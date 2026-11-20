package com.dzshzx.zeta.phone.tools

import android.app.Application
import android.content.Context
import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.clipChars
import com.dzshzx.zeta.core.claude.McpNames
import com.dzshzx.zeta.core.clipLine
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.onMain
import com.dzshzx.zeta.core.schedule.RuleKind
import com.dzshzx.zeta.core.schedule.RunReport
import com.dzshzx.zeta.core.schedule.ScheduleAlarms
import com.dzshzx.zeta.core.schedule.ScheduleRule
import com.dzshzx.zeta.core.schedule.ScheduleRunner
import com.dzshzx.zeta.core.schedule.ScheduleStore
import com.dzshzx.zeta.core.schedule.ScheduledTask
import com.dzshzx.zeta.core.schedule.Schedules
import com.dzshzx.zeta.core.schedule.TaskDraft
import com.dzshzx.zeta.core.schedule.UnattendedRun
import com.dzshzx.zeta.core.str
import com.dzshzx.zeta.phone.PhoneTools
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 定时任务：让用户在对话里说“每天早上 8 点帮我……”“改成 9 点”“现在先跑一次”。新建、列出、修改、删除、立即运行一次；
 * 任务清单和闹钟由 [ScheduleStore]、[ScheduleAlarms] 管（清单一改闹钟就重排），这里只是把参数翻译过去。
 *
 * 无人值守的后台运行里（定时任务、通知触发跑起来的那一轮，见 [UnattendedRun]）管理定时任务的工具都用不了：一次运行常读网页、
 * 通知这类外来的文字，要是能新建、改、删、跑别的任务，里面冒充指令的文字就能让自己反复跑下去，或者把别的任务改成不经确认、
 * 照它的意思跑。所以拦两道：Claude Code 自己的权限规则（[unattendedRules]）让模型看不到它们；调用还是到了 Zeta 时（规则没生效，
 * 或者用户插话撤了规则而这一轮还在跑），[call] 先问 [refusal]，照样不执行。上报结论的 [REPORT] 一直列着，记在调用带来的
 * 这个对话正在跑的运行上（[CallContext.unattended]）。
 */
internal object ScheduleTools : PhoneToolGroup {
    private const val CREATE = "create_scheduled_task"
    private const val LIST = "list_scheduled_tasks"
    private const val UPDATE = "update_scheduled_task"
    private const val DELETE = "delete_scheduled_task"
    private const val RUN = "run_scheduled_task"
    const val REPORT = "report_scheduled_run"

    /** 管理定时任务的工具：后台运行里不给。 */
    private val MANAGE = setOf(CREATE, LIST, UPDATE, DELETE, RUN)

    /**
     * 后台运行期间加给 Claude Code 的权限规则（apply_flag_settings 的 permissions 一层，见 Conversation.sendUnattended）：
     * 管理定时任务的工具拒绝，模型看不到，硬调 Claude Code 直接答没有这个工具，到不了 Zeta；上报结论的不用问。
     * 只拦 Zeta 自己的工具，Claude Code 自带的 CronCreate、ScheduleWakeup 不管。UnattendedRulesContractTest 钉住这些行为。
     */
    val unattendedRules: JsonObject = buildJsonObject {
        putJsonArray("deny") { MANAGE.forEach { add(McpNames.tool(PhoneTools.SERVER, it)) } }
        putJsonArray("allow") { add(McpNames.tool(PhoneTools.SERVER, REPORT)) }
    }

    /** [run] 是这个对话正在跑的后台运行：这时调管理定时任务的工具，不执行，回这句。别的情况是 null，照常执行。 */
    fun refusal(name: String, run: UnattendedRun?): JsonObject? =
        if (run != null && name in MANAGE) {
            toolFailure("这是定时任务或通知触发在后台自动跑的一轮，这时不能新建、修改、删除、运行或列出定时任务。需要的话在回答里请用户自己来。")
        } else {
            null
        }

    private val MODES = listOf("default", "acceptEdits", "plan", "auto", "bypassPermissions")
    private val REPEATS = RuleKind.entries.map { it.key }

    /** 改时间时看的参数：传了其中任一个才重算规则。 */
    private val SCHEDULE_KEYS = listOf("repeat", "time", "at", "days", "every_hours")

    override val tools: List<PhoneTool> = listOf(
        tool(
            CREATE,
            "新建定时任务，到点让 Claude 做事",
            "Schedule a prompt for Claude to run later in the background on this phone, once or repeatedly. At each run Zeta starts " +
                "a conversation with the prompt and notifies the user with the start of the answer. Times are the phone's local time " +
                "(the Linux environment may use another time zone). " +
                "Nobody is there to approve tool calls: with the default permission_mode auto, Claude Code decides on each call itself; " +
                "with default, a run stops at calls that need approval and notifies the user.",
        ) {
            schema(required = listOf("name", "prompt", "repeat")) {
                taskProps(update = false)
            }
        },
        tool(LIST, "列出定时任务", "List the scheduled tasks with their ids, schedules, next and last runs.", readOnly = true) {
            schema {}
        },
        tool(
            UPDATE,
            "修改定时任务的时间、指令、开关等",
            "Change a scheduled task; only the fields you pass change. To change when it runs, pass just what changes: " +
                "time alone keeps a daily or weekly repeat, days alone keeps the time, every_hours alone keeps the start; " +
                "switching repeat needs that kind's fields. Changing the schedule turns a disabled task back on unless enabled is given. " +
                "An empty string for cwd, model or permission_mode goes back to the default. Get the id from list_scheduled_tasks.",
        ) {
            schema(required = listOf("id")) {
                prop("id", "string", "Task id.")
                taskProps(update = true)
                prop("enabled", "boolean", "Turn the task on or off.")
            }
        },
        tool(DELETE, "删除定时任务", "Delete a scheduled task and its alarm. Get the id from list_scheduled_tasks.") {
            schema(required = listOf("id")) { prop("id", "string", "Task id.") }
        },
        tool(
            RUN,
            "立即在后台运行一次定时任务",
            "Run a scheduled task once now in the background with its saved settings, like Run once now in Zeta's settings; " +
                "its schedule is unchanged. Returns as soon as the run starts; the result arrives as the task's own notification, " +
                "not in this conversation. Skipped while the task's previous run is still going. Get the id from list_scheduled_tasks.",
        ) {
            schema(required = listOf("id")) { prop("id", "string", "Task id.") }
        },
        tool(
            REPORT,
            "后台运行上报这次有没有变化",
            "Report the conclusion of this automatic run of a scheduled task or notification rule that notifies the user only on changes. " +
                "Call it once before your final reply. no_change: nothing worth telling the user; no notification is sent and the run is only logged. " +
                "deliver: there is something to tell; summary becomes the notification text.",
            readOnly = true,
        ) {
            schema(required = listOf("outcome", "summary")) {
                enumProp("outcome", listOf("deliver", "no_change"), "deliver to notify the user, no_change to stay quiet.")
                prop("summary", "string", "One or two sentences, at most ${Schedules.MAX_SUMMARY} characters: the notification text, or what was checked when nothing changed.")
            }
        },
    )

    private fun JsonObjectBuilder.taskProps(update: Boolean) {
        prop("name", "string", "Short name, shown as the notification title.")
        prop("prompt", "string", "What to send to Claude at each run; write it so it stands on its own.")
        enumProp("repeat", REPEATS, "How often.")
        prop("time", "string", "HH:MM for daily and weekly" + if (update) " (or a once task's new time on the same day)." else ".")
        prop("at", "string", "Local date-time like 2026-11-20T08:00: when to run (once), or the first run (interval, default now + every_hours).")
        putJsonObject("days") {
            put("type", "array")
            putJsonObject("items") {
                put("type", "string")
                putJsonArray("enum") { listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun").forEach { add(JsonPrimitive(it)) } }
            }
            put("description", "Weekdays for weekly.")
        }
        prop("every_hours", "integer", "Hours between runs for interval, 1-${ScheduleRule.MAX_INTERVAL_HOURS}.")
        prop("cwd", "string", "Working directory in the Linux environment; default the user's default directory.")
        prop("model", "string", "Model alias such as sonnet or opus; default is Claude Code's default model.")
        enumProp("permission_mode", if (update) MODES + "" else MODES, "Permission mode for the runs; default ${Schedules.DEFAULT_MODE}.")
        prop("new_session", "boolean", "Start a fresh conversation each run (default true); false continues one conversation across runs.")
        prop(
            "only_on_change", "boolean",
            "Notify the user only when a run finds something worth telling (default false): each run then reports its conclusion " +
                "with $REPORT, and runs that report no_change stay quiet. Good for monitoring and checks.",
        )
    }

    /** 后台运行里调到管理定时任务的工具时不执行（[refusal]，第二道）；上报记在这次调用所在的运行上。 */
    override fun call(call: CallContext, name: String, input: JsonObject): JsonObject = refusal(name, call.unattended) ?: toolCall {
        val context = call.context
        when (name) {
            CREATE -> create(context, input)
            LIST -> toolText(list(context))
            UPDATE -> update(context, input)
            DELETE -> delete(context, input)
            RUN -> runNow(context, input)
            REPORT -> report(input, call.unattended)
            else -> toolFailure("没有这个工具：$name")
        }
    }

    override fun title(name: String, input: JsonObject): String = when (name) {
        CREATE -> input.str("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { "新建定时任务「" + short(it) + "」" } ?: "新建定时任务"
        LIST -> "查看定时任务"
        UPDATE -> "修改定时任务"
        DELETE -> "删除定时任务"
        RUN -> "立即运行定时任务"
        REPORT -> if (input.str("outcome") == "no_change") "上报：没有变化" else "上报：有结果"
        else -> name
    }

    private fun short(text: String) = text.clipChars(12, "…")

    private fun create(context: Context, input: JsonObject): JsonObject {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val draft = TaskDraft(
            name = input.str("name").orEmpty(),
            prompt = input.str("prompt").orEmpty(),
            rule = rule(input, base = null, now, zone)!!,
            cwd = input.str("cwd"),
            model = input.str("model")?.trim()?.takeIf { it.isNotEmpty() },
            permissionMode = mode(input),
            newSession = input.bool("new_session") ?: true,
            onlyOnChange = input.bool("only_on_change") ?: false,
        )
        // 手机上的 Linux 环境常用 UTC，Claude 照它的 date 算出来的时间会差几个小时：报错时带上手机本地的现在
        Schedules.problem(draft, now, zone)?.let { throw BadInput(it + localNote(now, zone)) }
        if (ScheduleStore.load(context).size >= Schedules.MAX_TASKS) throw BadInput("最多 ${Schedules.MAX_TASKS} 个定时任务，先删掉一些")
        val task = Schedules.toTask(draft, now.toEpochMilli())
        ScheduleStore.update(context) { it + task }
        val next = Schedules.nextTrigger(task.rule, now, zone)
        return toolText(
            buildString {
                append("已新建定时任务「${task.name}」（id ${task.id}）：${Schedules.describe(task.rule)}")
                next?.let { append("，下次 " + Schedules.formatWhen(it, now, zone)) }
                append("。用户可以在 Zeta 的 设置 → 定时任务 里开关、编辑或立即运行。")
                exactNote(context)?.let { append("\n" + it) }
            },
        )
    }

    private fun update(context: Context, input: JsonObject): JsonObject {
        val id = input.str("id")?.trim().orEmpty()
        val task = ScheduleStore.find(context, id) ?: return toolFailure("没有 id 为 $id 的定时任务，用 list_scheduled_tasks 看看现有的")
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val changed = updated(task, input, now, zone)
        if (changed == task) return toolText("没有要改的。现在是这样：\n" + describe(task, now, zone))
        // 改动套在存盘时最新的那份上：这期间可能刚跑完一次，运行记录要留着
        ScheduleStore.updateTask(context, id) { Schedules.applyDraft(it, Schedules.draftOf(changed)) }
        val saved = ScheduleStore.find(context, id) ?: changed
        return toolText(
            buildString {
                append("已修改定时任务。现在是这样：\n")
                append(describe(saved, now, zone))
                exactNote(context)?.let { append("\n" + it) }
            },
        )
    }

    /**
     * 按 update_scheduled_task 的参数改 [task]：只改传了的，运行记录照旧（见 [Schedules.applyDraft]）。参数不对时抛 [BadInput]。
     * 开关和校验照 [Schedules.edit]，和设置页的编辑一样。
     */
    fun updated(task: ScheduledTask, input: JsonObject, now: Instant, zone: ZoneId): ScheduledTask {
        val draft = TaskDraft(
            name = input.str("name") ?: task.name,
            prompt = input.str("prompt") ?: task.prompt,
            rule = rule(input, task.rule, now, zone) ?: task.rule,
            cwd = if (given(input, "cwd")) input.str("cwd") else task.cwd,
            model = if (given(input, "model")) input.str("model")?.trim()?.takeIf { it.isNotEmpty() } else task.model,
            permissionMode = if (given(input, "permission_mode")) mode(input) else task.permissionMode,
            newSession = input.bool("new_session") ?: task.newSession,
            onlyOnChange = input.bool("only_on_change") ?: task.onlyOnChange,
        )
        val edited = Schedules.edit(task, draft, input.bool("enabled"), now, zone)
        edited.problem?.let { throw BadInput(it + localNote(now, zone)) }
        return Schedules.applyDraft(task, edited.draft)
    }

    private fun given(input: JsonObject, key: String) = input[key].let { it != null && it !is JsonNull }

    /** permission_mode；没给或给了空字符串时是默认的（[Schedules.DEFAULT_MODE]）。 */
    private fun mode(input: JsonObject): String {
        val mode = input.str("permission_mode")?.trim()?.takeIf { it.isNotEmpty() } ?: return Schedules.DEFAULT_MODE
        if (mode !in MODES) throw BadInput("permission_mode 只能是 " + MODES.joinToString("、"))
        return mode
    }

    /**
     * 参数里的时间规则。新建时 [base] 为 null，缺了必要的参数就抛 [BadInput]；修改时 [base] 是原来的规则，
     * 没传任何时间参数返回 null（不改），传了的覆盖原来的、没传的沿用原来的。
     */
    fun rule(input: JsonObject, base: ScheduleRule?, now: Instant, zone: ZoneId): ScheduleRule? {
        if (base != null && SCHEDULE_KEYS.none { given(input, it) }) return null
        val repeat = input.str("repeat")?.takeIf { it.isNotBlank() }
        val kind = (if (repeat != null) RuleKind.of(repeat) else base?.kind)
            ?: throw BadInput("repeat 只能是 " + REPEATS.joinToString("、") + "，收到的是 $repeat")
        val time = input.str("time")?.let { Schedules.parseTime(it) ?: throw BadInput("time 要写成 08:00 这样") }
        val at = input.str("at")?.let { Schedules.parseDateTime(it) ?: throw BadInput("at 要写成 2026-11-20T08:00 这样") }
        val sameTime = time ?: base?.time
        return when (kind) {
            RuleKind.Once -> ScheduleRule.Once(
                at ?: (base as? ScheduleRule.Once)?.at?.let { old -> time?.let { old.toLocalDate().atTime(it) } ?: old }
                    ?: throw BadInput("once 要给 at，比如 2026-11-20T08:00"),
            )
            RuleKind.Daily -> ScheduleRule.Daily(sameTime ?: throw BadInput("daily 要给 time，比如 08:00"))
            RuleKind.Weekly -> {
                val days = if (given(input, "days")) {
                    input.arr("days").orEmpty().map { element ->
                        (element as? JsonPrimitive)?.content?.let(Schedules::parseDay) ?: throw BadInput("days 里的 $element 不是星期几（mon…sun）")
                    }.toSet()
                } else {
                    (base as? ScheduleRule.Weekly)?.days.orEmpty()
                }
                if (days.isEmpty()) throw BadInput("weekly 要给 days，比如 [\"mon\",\"wed\"]")
                ScheduleRule.Weekly(days, sameTime ?: throw BadInput("weekly 要给 time，比如 08:00"))
            }
            RuleKind.Interval -> {
                val hours = input.int("every_hours") ?: (base as? ScheduleRule.Interval)?.hours ?: throw BadInput("interval 要给 every_hours")
                // 不给第一次的时间：从现在起算，第一次在 every_hours 小时后
                val start = at ?: (base as? ScheduleRule.Interval)?.start ?: LocalDateTime.ofInstant(now, zone).truncatedTo(ChronoUnit.MINUTES)
                ScheduleRule.Interval(hours, start)
            }
        }
    }

    private fun localNote(now: Instant, zone: ZoneId) =
        "（手机本地时间现在是 " + LocalDateTime.ofInstant(now, zone).truncatedTo(ChronoUnit.MINUTES) + "）"

    private fun exactNote(context: Context): String? =
        if (ScheduleAlarms.exact(context)) null else "注意：Zeta 还没有精确闹钟权限，到点可能被系统推迟。请用户到 设置 → 权限健康 里一键授予。"

    private fun list(context: Context): String {
        val tasks = ScheduleStore.load(context)
        if (tasks.isEmpty()) return "还没有定时任务"
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        return tasks.joinToString("\n\n") { task -> describe(task, now, zone) }
    }

    fun describe(task: ScheduledTask, now: Instant, zone: ZoneId): String = buildString {
        append("${task.name}（id ${task.id}）：${Schedules.describe(task.rule)}")
        val next = if (task.enabled) Schedules.nextTrigger(task.rule, now, zone) else null
        append(
            when {
                !task.enabled -> "，已关闭"
                next == null -> "，不会再运行"
                else -> "，下次 " + Schedules.formatWhen(next, now, zone)
            },
        )
        append("\n  指令：" + task.prompt.clipLine(120))
        val options = listOfNotNull(
            task.cwd?.let { "工作目录 $it" },
            task.model?.let { "模型 $it" },
            "权限模式 " + task.permissionMode,
            if (task.newSession) null else "接着同一个对话",
            if (task.onlyOnChange) "只在有变化时提醒" else null,
        )
        if (options.isNotEmpty()) append("\n  " + options.joinToString("，"))
        task.lastRun?.let { run ->
            append("\n  上次 " + Schedules.formatWhen(Instant.ofEpochMilli(run.at), now, zone) + "：" + run.outcome.label)
            if (run.summary.isNotBlank()) append("，" + run.summary.clipChars(80, "…"))
        }
        if (task.skips > 0) append("\n  因上一次没跑完跳过 ${task.skips} 次")
    }

    private fun delete(context: Context, input: JsonObject): JsonObject {
        val id = input.str("id")?.trim().orEmpty()
        val task = ScheduleStore.find(context, id) ?: return toolFailure("没有 id 为 $id 的定时任务，用 list_scheduled_tasks 看看现有的")
        ScheduleStore.update(context) { list -> list.filterNot { it.id == id } }
        ScheduleRunner.clearNotification(context, id)
        return toolText("已删除定时任务「${task.name}」，它的闹钟也取消了。之前运行留下的对话还在。")
    }

    /** 和设置页的“立即运行一次”同一条路（[ScheduleRunner.start]，manual），不动闹钟。 */
    private fun runNow(context: Context, input: JsonObject): JsonObject {
        val id = input.str("id")?.trim().orEmpty()
        val task = ScheduleStore.find(context, id) ?: return toolFailure("没有 id 为 $id 的定时任务，用 list_scheduled_tasks 看看现有的")
        val app = context.applicationContext as Application
        val reason = onMain { ScheduleRunner.start(app, task, manual = true) }
        if (reason != null) return toolFailure("「${task.name}」这次没有运行：$reason")
        return toolText(
            "已在后台开始运行「${task.name}」，定时不受影响。结果不会回到这个对话里：跑完照任务的设置发通知" +
                (if (task.onlyOnChange) "（只在有变化时提醒，没有变化就不发）" else "") + "，也能在 设置 → 定时任务 里看运行记录。",
        )
    }

    /**
     * 上报这次运行的结论，记在 [run] 上（这个对话正在跑的后台运行，调用带来的）。不在后台运行里、
     * 或者这次运行不是只在有变化时提醒的，回一句不用上报。
     */
    fun report(input: JsonObject, run: UnattendedRun?): JsonObject {
        if (run == null || !run.onlyOnChange) return toolText("这不是只在有变化时提醒的后台运行，不用上报，直接回答就好。")
        val changed = when (input.str("outcome")) {
            "deliver" -> true
            "no_change" -> false
            else -> return toolFailure("outcome 只能是 deliver 或 no_change")
        }
        val summary = input.str("summary")?.trim().orEmpty().clipChars(Schedules.MAX_SUMMARY, "…")
        if (changed && summary.isEmpty()) return toolFailure("deliver 要给 summary：通知里显示的一两句话")
        run.report = RunReport(changed, summary)
        return toolText(if (changed) "已记下：这一轮跑完会发通知，正文是这段摘要。" else "已记下：这次没有变化，跑完不发通知，只记进运行记录。")
    }
}
