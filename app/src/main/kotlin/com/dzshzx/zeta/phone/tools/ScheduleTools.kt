package com.dzshzx.zeta.phone.tools

import android.content.Context
import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.schedule.RunOutcome
import com.dzshzx.zeta.core.schedule.ScheduleAlarms
import com.dzshzx.zeta.core.schedule.ScheduleRule
import com.dzshzx.zeta.core.schedule.ScheduleRunner
import com.dzshzx.zeta.core.schedule.ScheduleStore
import com.dzshzx.zeta.core.schedule.ScheduledTask
import com.dzshzx.zeta.core.schedule.Schedules
import com.dzshzx.zeta.core.schedule.TaskDraft
import com.dzshzx.zeta.core.str
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 定时任务：让用户在对话里说“每天早上 8 点帮我……”。只新建、列出、删除；开关、编辑在设置的“定时任务”页。
 * 任务清单和闹钟由 [ScheduleStore]、[ScheduleAlarms] 管，这里只是把参数翻译过去。
 */
internal object ScheduleTools : PhoneToolGroup {
    private const val CREATE = "create_scheduled_task"
    private const val LIST = "list_scheduled_tasks"
    private const val DELETE = "delete_scheduled_task"

    private val MODES = listOf("default", "acceptEdits", "plan", "auto", "bypassPermissions")

    override val tools: List<JsonElement> = listOf(
        tool(
            CREATE,
            "Schedule a prompt for Claude to run later in the background on this phone, once or repeatedly. At each run Zeta starts " +
                "a conversation with the prompt and notifies the user with the start of the answer. Times are the phone's local time " +
                "(the Linux environment may use another time zone). " +
                "Nobody is there to approve tool calls, so pick permission_mode for prompts that need such tools.",
        ) {
            schema(required = listOf("name", "prompt", "repeat")) {
                prop("name", "string", "Short name, shown as the notification title.")
                prop("prompt", "string", "What to send to Claude at each run; write it so it stands on its own.")
                enumProp("repeat", listOf("once", "daily", "weekly", "interval"), "How often.")
                prop("time", "string", "HH:MM for daily and weekly.")
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
                prop("model", "string", "Model alias such as sonnet or opus; default follows the user's current setting.")
                enumProp("permission_mode", MODES, "Default follows the user's current setting.")
                prop("new_session", "boolean", "Start a fresh conversation each run (default true); false continues one conversation across runs.")
            }
        },
        tool(LIST, "List the scheduled tasks with their ids, schedules, next and last runs.", readOnly = true) {
            schema {}
        },
        tool(DELETE, "Delete a scheduled task and its alarm. Get the id from list_scheduled_tasks.") {
            schema(required = listOf("id")) { prop("id", "string", "Task id.") }
        },
    )

    override fun call(context: Context, name: String, input: JsonObject): JsonObject = try {
        when (name) {
            CREATE -> create(context, input)
            LIST -> toolText(list(context))
            DELETE -> delete(context, input)
            else -> toolFailure("没有这个工具：$name")
        }
    } catch (e: BadInput) {
        toolFailure(e.message ?: "参数不对")
    } catch (e: Exception) {
        toolFailure("执行失败：" + (e.message ?: e.javaClass.simpleName))
    }

    override fun title(name: String, input: JsonObject): String = when (name) {
        CREATE -> input.str("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { "新建定时任务「" + short(it) + "」" } ?: "新建定时任务"
        LIST -> "查看定时任务"
        DELETE -> "删除定时任务"
        else -> name
    }

    private fun short(text: String) = if (text.length > 12) text.take(12) + "…" else text

    private fun create(context: Context, input: JsonObject): JsonObject {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val rule = rule(input, now, zone)
        val mode = input.str("permission_mode")?.takeIf { it.isNotBlank() }
        if (mode != null && mode !in MODES) throw BadInput("permission_mode 只能是 " + MODES.joinToString("、"))
        val draft = TaskDraft(
            name = input.str("name").orEmpty(),
            prompt = input.str("prompt").orEmpty(),
            rule = rule,
            cwd = input.str("cwd"),
            model = input.str("model")?.trim()?.takeIf { it.isNotEmpty() },
            permissionMode = mode,
            newSession = input.bool("new_session") ?: true,
        )
        // 手机上的 Linux 环境常用 UTC，Claude 照它的 date 算出来的时间会差几个小时：报错时带上手机本地的现在
        Schedules.problem(draft, now, zone)?.let { throw BadInput(it + "（手机本地时间现在是 " + localNow(now, zone) + "）") }
        if (ScheduleStore.load(context).size >= Schedules.MAX_TASKS) throw BadInput("最多 ${Schedules.MAX_TASKS} 个定时任务，先删掉一些")
        val task = Schedules.toTask(draft, now.toEpochMilli())
        ScheduleStore.update(context) { it + task }
        val next = Schedules.nextTrigger(task.rule, now, zone)
        return toolText(
            buildString {
                append("已新建定时任务「${task.name}」（id ${task.id}）：${Schedules.describe(task.rule)}")
                next?.let { append("，下次 " + Schedules.formatWhen(it, now, zone)) }
                append("。用户可以在 Zeta 的 设置 → 定时任务 里开关、编辑或立即运行。")
                if (!ScheduleAlarms.exact(context)) {
                    append("\n注意：Zeta 还没有精确闹钟权限，到点可能被系统推迟。请用户到 设置 → 权限健康 里一键授予。")
                }
            },
        )
    }

    private fun localNow(now: Instant, zone: ZoneId) = LocalDateTime.ofInstant(now, zone).truncatedTo(ChronoUnit.MINUTES).toString()

    private fun rule(input: JsonObject, now: Instant, zone: ZoneId): ScheduleRule = when (val repeat = input.str("repeat")) {
        "once" -> ScheduleRule.Once(Schedules.parseDateTime(input.str("at")) ?: throw BadInput("once 要给 at，比如 2026-11-20T08:00"))
        "daily" -> ScheduleRule.Daily(Schedules.parseTime(input.str("time")) ?: throw BadInput("daily 要给 time，比如 08:00"))
        "weekly" -> {
            val time = Schedules.parseTime(input.str("time")) ?: throw BadInput("weekly 要给 time，比如 08:00")
            val days = input.arr("days").orEmpty().map { element ->
                (element as? JsonPrimitive)?.content?.let(Schedules::parseDay) ?: throw BadInput("days 里的 $element 不是星期几（mon…sun）")
            }.toSet()
            if (days.isEmpty()) throw BadInput("weekly 要给 days，比如 [\"mon\",\"wed\"]")
            ScheduleRule.Weekly(days, time)
        }
        "interval" -> {
            val hours = input.int("every_hours") ?: throw BadInput("interval 要给 every_hours")
            // 不给第一次的时间：从现在起算，第一次在 every_hours 小时后
            val start = input.str("at")?.let { Schedules.parseDateTime(it) ?: throw BadInput("at 要写成 2026-11-20T08:00 这样") }
                ?: LocalDateTime.ofInstant(now, zone).truncatedTo(ChronoUnit.MINUTES)
            ScheduleRule.Interval(hours, start)
        }
        else -> throw BadInput("repeat 只能是 once、daily、weekly、interval，收到的是 $repeat")
    }

    private fun list(context: Context): String {
        val tasks = ScheduleStore.load(context)
        if (tasks.isEmpty()) return "还没有定时任务"
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        return tasks.joinToString("\n\n") { task -> describe(task, now, zone) }
    }

    private fun describe(task: ScheduledTask, now: Instant, zone: ZoneId): String = buildString {
        append("${task.name}（id ${task.id}）：${Schedules.describe(task.rule)}")
        val next = if (task.enabled) Schedules.nextTrigger(task.rule, now, zone) else null
        append(
            when {
                !task.enabled -> "，已关闭"
                next == null -> "，不会再运行"
                else -> "，下次 " + Schedules.formatWhen(next, now, zone)
            },
        )
        append("\n  指令：" + task.prompt.replace(Regex("\\s+"), " ").let { if (it.length > 120) it.take(120) + "…" else it })
        val options = listOfNotNull(
            task.cwd?.let { "工作目录 $it" },
            task.model?.let { "模型 $it" },
            task.permissionMode?.let { "权限模式 $it" },
            if (task.newSession) null else "接着同一个对话",
        )
        if (options.isNotEmpty()) append("\n  " + options.joinToString("，"))
        task.lastRun?.let { run ->
            append("\n  上次 " + Schedules.formatWhen(Instant.ofEpochMilli(run.at), now, zone) + "：" + outcomeText(run.outcome))
            if (run.summary.isNotBlank()) append("，" + run.summary.take(80))
        }
        if (task.skips > 0) append("\n  因上一次没跑完跳过 ${task.skips} 次")
    }

    private fun outcomeText(outcome: RunOutcome) = when (outcome) {
        RunOutcome.Running -> "进行中"
        RunOutcome.Done -> "完成"
        RunOutcome.Failed -> "出错"
        RunOutcome.Stopped -> "被停止"
        RunOutcome.Interrupted -> "Zeta 中途被关掉"
    }

    private fun delete(context: Context, input: JsonObject): JsonObject {
        val id = input.str("id")?.trim().orEmpty()
        val task = ScheduleStore.find(context, id) ?: return toolFailure("没有 id 为 $id 的定时任务，用 list_scheduled_tasks 看看现有的")
        ScheduleStore.update(context) { list -> list.filterNot { it.id == id } }
        ScheduleRunner.clearNotification(context, id)
        return toolText("已删除定时任务「${task.name}」，它的闹钟也取消了。之前运行留下的对话还在。")
    }
}
