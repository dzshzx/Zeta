package com.dzshzx.zeta.ui.settings

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.schedule.RunOutcome
import com.dzshzx.zeta.core.schedule.ScheduleRule
import com.dzshzx.zeta.core.schedule.ScheduleRunner
import com.dzshzx.zeta.core.schedule.ScheduleStore
import com.dzshzx.zeta.core.schedule.ScheduledTask
import com.dzshzx.zeta.core.schedule.Schedules
import com.dzshzx.zeta.core.schedule.TaskDraft
import com.dzshzx.zeta.core.schedule.TaskRun
import com.dzshzx.zeta.ui.chat.PermissionModes
import com.dzshzx.zeta.ui.chat.permissionModeLabel
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.TextInputDialog
import com.dzshzx.zeta.ui.components.coui2.IconActionButton
import com.dzshzx.zeta.ui.components.rememberRetained
import com.dzshzx.zeta.ui.setup.ConfigSubPage
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.CalendarPicker
import io.github.suqi8.coui.kmp.basic.DateValue
import io.github.suqi8.coui.kmp.basic.InputView
import io.github.suqi8.coui.kmp.basic.NumberPicker
import io.github.suqi8.coui.kmp.basic.SegmentButton
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TimePicker
import io.github.suqi8.coui.kmp.basic.TimeValue
import io.github.suqi8.coui.kmp.basic.ToolbarTextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference
import io.github.suqi8.coui.kmp.preference.PagerFooterPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.preference.SwitchWithDividerPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ListFooter = "到点时 Zeta 在后台开一个对话发出指令，不打断你正在看的对话；跑完发一条通知（任务名和回答的开头），点通知打开那次的对话。" +
    "Zeta 没在运行时会被拉起来。同一个任务上一次还没跑完时，跳过这一次。也可以在对话里直接说，比如“每天早上 8 点汇总通知和日程”。"

/** 设置页“能力”里的入口：开着几个、下一次什么时候。 */
@Composable
internal fun ScheduleEntry(onOpen: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ScheduleStore.load(context) } }
    val tasks = ScheduleStore.observed
    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    val enabled = tasks.filter { it.enabled }
    val next = enabled.mapNotNull { Schedules.nextTrigger(it.rule, now, zone) }.minOrNull()
    ArrowPreference(
        title = "定时任务",
        summary = when {
            tasks.isEmpty() -> "到点或按周期让 Claude 在后台做一件事"
            enabled.isEmpty() -> "${tasks.size} 个，都关着"
            else -> "${enabled.size} 个开着" + (next?.let { " · 下次 " + Schedules.formatWhen(it, now, zone) } ?: "")
        },
        onClick = onOpen,
    )
}

/** 正在编辑哪个任务；[id] 为 null 是新建。 */
private class EditTarget(val id: String?)

/** “定时任务”：列出、开关；点一个进去编辑、立即运行、删除。 */
@Composable
internal fun ScheduleScreen(hub: ConversationHub, onBack: () -> Unit, onOpenConversation: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ScheduleStore.load(app) } }
    val tasks = ScheduleStore.observed
    val snapshot = rememberPermissionSnapshot()
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    val zone = ZoneId.systemDefault()
    val now = Instant.now()

    CouiPage(
        title = "定时任务",
        onBack = onBack,
        actions = { IconActionButton(COUIIcons.Add, "新建定时任务", enabled = tasks.size < Schedules.MAX_TASKS) { editing = EditTarget(null) } },
    ) {
        if (snapshot != null && !snapshot.exactAlarms) {
            item(key = "alarm") {
                PreferenceGroup { item { PermissionFixRow(hub, PermissionId.ExactAlarm, "精确闹钟未允许", "到点可能被系统推迟。", StatusWarning) } }
            }
        }
        item(key = "tasks") {
            PreferenceGroup(title = "定时任务 · ${tasks.size}", footer = ListFooter) {
                if (tasks.isEmpty()) {
                    item { BasicComponent(title = "还没有定时任务", summary = "点右上角 + 新建", enabled = false) }
                }
                tasks.forEach { task ->
                    val next = if (task.enabled) Schedules.nextTrigger(task.rule, now, zone) else null
                    val last = task.lastRun
                    val state = when {
                        last?.outcome == RunOutcome.Running -> "正在运行"
                        !task.enabled -> "已关闭"
                        next == null -> "时间已过，不会再运行"
                        else -> "下次 " + Schedules.formatWhen(next, now, zone)
                    } + (last?.takeIf { it.outcome != RunOutcome.Running }?.let { " · 上次" + outcomeWord(it.outcome) } ?: "")
                    item(key = task.id) {
                        SwitchWithDividerPreference(
                            title = task.name,
                            summary = Schedules.describe(task.rule) + "\n" + state,
                            checked = task.enabled,
                            onCheckedChange = { on -> ScheduleStore.updateTask(app, task.id) { it.copy(enabled = on) } },
                            onClick = { editing = EditTarget(task.id) },
                        )
                    }
                }
            }
        }
    }

    val shown = rememberRetained(editing)
    ConfigSubPage(visible = editing != null, onBack = { editing = null }) {
        shown?.let { target ->
            // 每打开一次是新的编辑状态，不沿用上一个任务填到一半的内容
            key(target) {
                TaskEditor(
                    hub = hub,
                    original = target.id?.let { id -> tasks.firstOrNull { it.id == id } },
                    onBack = { editing = null },
                    onOpenConversation = onOpenConversation,
                )
            }
        }
    }
}

internal fun outcomeWord(outcome: RunOutcome) = outcome.label

private enum class RuleKind(val label: String) { Once("单次"), Daily("每天"), Weekly("每周"), Interval("间隔") }

private val Workdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

/** 没从 Claude Code 拿到模型清单时给的几个别名。 */
internal val FallbackModels = listOf("default" to "默认", "fable" to "Fable", "opus" to "Opus", "sonnet" to "Sonnet", "haiku" to "Haiku")

/** 编辑器里开着的那个对话框。 */
private enum class EditorDialog { Time, Date, Hours, Cwd, Delete }

/** 新建或编辑一个任务；编辑时下面还有上次运行、立即运行一次和删除。 */
@Composable
private fun TaskEditor(hub: ConversationHub, original: ScheduledTask?, onBack: () -> Unit, onOpenConversation: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val zone = ZoneId.systemDefault()
    val rule = original?.rule
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(original?.prompt.orEmpty()) }
    var kind by remember {
        mutableStateOf(
            when (rule) {
                is ScheduleRule.Once -> RuleKind.Once
                is ScheduleRule.Weekly -> RuleKind.Weekly
                is ScheduleRule.Interval -> RuleKind.Interval
                else -> RuleKind.Daily
            },
        )
    }
    var time by remember {
        mutableStateOf(
            when (rule) {
                is ScheduleRule.Once -> rule.at.toLocalTime()
                is ScheduleRule.Daily -> rule.time
                is ScheduleRule.Weekly -> rule.time
                is ScheduleRule.Interval -> rule.start.toLocalTime()
                null -> LocalTime.of(8, 0)
            },
        )
    }
    var date by remember {
        mutableStateOf(
            when (rule) {
                is ScheduleRule.Once -> rule.at.toLocalDate()
                is ScheduleRule.Interval -> rule.start.toLocalDate()
                else -> LocalDate.now()
            },
        )
    }
    var days by remember { mutableStateOf((rule as? ScheduleRule.Weekly)?.days ?: Workdays) }
    var hours by remember { mutableStateOf((rule as? ScheduleRule.Interval)?.hours ?: 3) }
    var cwd by remember { mutableStateOf(original?.cwd.orEmpty()) }
    var model by remember { mutableStateOf(original?.model) }
    var mode by remember { mutableStateOf(original?.permissionMode) }
    var newSession by remember { mutableStateOf(original?.newSession ?: true) }
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    var runMessage by remember { mutableStateOf<String?>(null) }

    val draftRule = when (kind) {
        RuleKind.Once -> ScheduleRule.Once(date.atTime(time))
        RuleKind.Daily -> ScheduleRule.Daily(time)
        RuleKind.Weekly -> ScheduleRule.Weekly(days, time)
        RuleKind.Interval -> ScheduleRule.Interval(hours, date.atTime(time))
    }
    // 关着的任务只改文字时保持关着；改了时间就当用户要它重新开始
    val enabled = original == null || original.enabled || draftRule != original.rule
    val draft = TaskDraft(name, prompt, draftRule, cwd, model, mode, newSession, enabled)
    val now = Instant.now()
    val problem = Schedules.problem(draft, now, zone)
    val next = Schedules.nextTrigger(draftRule, now, zone)
    val defaultCwd = hub.env?.defaultCwd ?: "/workspace"

    fun save() {
        if (problem != null) return
        if (original == null) {
            ScheduleStore.update(app) { it + Schedules.toTask(draft, now.toEpochMilli()) }
        } else {
            ScheduleStore.updateTask(app, original.id) { Schedules.applyDraft(it, draft) }
        }
        onBack()
    }

    CouiPage(
        title = if (original == null) "新建定时任务" else "编辑定时任务",
        onBack = onBack,
        actions = { ToolbarTextButton(text = "保存", enabled = problem == null, onClick = ::save) },
        overlays = {
            TimeDialog(visible = dialog == EditorDialog.Time, initial = time, onConfirm = { time = it; dialog = null }, onDismiss = { dialog = null })
            DateDialog(visible = dialog == EditorDialog.Date, initial = date, onConfirm = { date = it; dialog = null }, onDismiss = { dialog = null })
            HoursDialog(visible = dialog == EditorDialog.Hours, initial = hours, onConfirm = { hours = it; dialog = null }, onDismiss = { dialog = null })
            CwdDialog(visible = dialog == EditorDialog.Cwd, cwd = cwd, defaultCwd = defaultCwd, onConfirm = { cwd = it; dialog = null }, onDismiss = { dialog = null })
            ConfirmDialog(
                show = dialog == EditorDialog.Delete,
                title = "删除定时任务",
                summary = original?.let { "删除“${it.name}”？以后不再运行。之前运行留下的对话还在。" },
                confirmText = "删除",
                destructive = true,
                onConfirm = {
                    dialog = null
                    original?.let { task ->
                        ScheduleStore.update(app) { list -> list.filterNot { it.id == task.id } }
                        ScheduleRunner.clearNotification(app, task.id)
                    }
                    onBack()
                },
                onDismissRequest = { dialog = null },
            )
        },
    ) {
        item(key = "name") {
            FieldCard(footer = "通知的标题，也是每次运行那个对话的名字。") {
                InputView(value = name, onValueChange = { name = it.take(Schedules.MAX_NAME) }, title = "名称", label = "每日简报")
            }
        }
        item(key = "prompt") {
            FieldCard(footer = "到点时原样发给 Claude，就像你在输入框里发的一样。写清楚要它做什么、结果怎么写。") {
                InputView(
                    value = prompt,
                    onValueChange = { prompt = it },
                    title = "指令",
                    label = "比如：汇总今天的日程和昨晚以来的通知，三五行说完",
                    singleLine = false,
                    maxLines = 10,
                )
            }
        }
        item(key = "time") {
            PreferenceGroup(
                title = "时间",
                footer = when {
                    problem != null && (name.isNotBlank() || prompt.isNotBlank() || original != null) -> problem
                    next != null -> "下次运行：" + Schedules.formatWhen(next, now, zone)
                    else -> null
                },
            ) {
                item(key = "kind") {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        SegmentButton(
                            items = RuleKind.entries.map { it.label },
                            selectedIndex = kind.ordinal,
                            onSelectedIndexChange = { index -> RuleKind.entries.getOrNull(index)?.let { kind = it } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                when (kind) {
                    RuleKind.Once -> item(key = "date") { ValueRow("日期", dateText(date)) { dialog = EditorDialog.Date } }
                    RuleKind.Weekly -> item(key = "days") { WeekdayPicker(days) { days = it } }
                    RuleKind.Interval -> {
                        item(key = "hours") { ValueRow("每隔", "$hours 小时") { dialog = EditorDialog.Hours } }
                        item(key = "start") { ValueRow("从哪天开始", dateText(date)) { dialog = EditorDialog.Date } }
                    }
                    RuleKind.Daily -> Unit
                }
                item(key = "clock") {
                    ValueRow(if (kind == RuleKind.Interval) "从几点开始" else "时间", "%02d:%02d".format(time.hour, time.minute)) { dialog = EditorDialog.Time }
                }
            }
        }
        item(key = "run") {
            PreferenceGroup(
                title = "运行方式",
                footer = "后台运行时没人点确认。要用到需要确认的工具（执行命令、改文件等），把权限模式设成“自动接受编辑”或“完全放行”；" +
                    "否则这一轮会停下来等你，到时会发通知。",
            ) {
                item { BasicComponent(title = "工作目录", summary = cwd.ifBlank { "$defaultCwd（默认）" }, onClick = { dialog = EditorDialog.Cwd }) }
                item { ModelPreference(hub, model) { model = it } }
                item {
                    val modeOptions = listOf<Pair<String?, String>>(null to "跟随当前设置") + PermissionModes +
                        listOfNotNull(mode?.takeIf { value -> PermissionModes.none { it.first == value } }?.let { it to permissionModeLabel(it) })
                    OverlayDropdownPreference(
                        title = "权限模式",
                        items = modeOptions.map { it.second },
                        selectedIndex = modeOptions.indexOfFirst { it.first == mode },
                        onSelectedIndexChange = { index -> modeOptions.getOrNull(index)?.let { mode = it.first } },
                    )
                }
                item {
                    SwitchPreference(
                        title = "每次开新对话",
                        summary = if (newSession) "每次都是新的对话，互不影响" else "接着同一个对话，Claude 记得前几次的内容",
                        checked = newSession,
                        onCheckedChange = { newSession = it },
                    )
                }
            }
        }
        if (original != null) {
            item(key = "record") {
                PreferenceGroup(title = "运行记录") {
                    original.lastRun?.let { run -> item(key = "last") { LastRunRow(hub, run, now, zone, onOpenConversation) } }
                    if (original.skips > 0) {
                        item(key = "skips") { SkipsRow(original.skips, original.lastSkip, "到点时上一次还没跑完", now, zone) }
                    }
                    item(key = "now") {
                        val running = original.lastRun?.outcome == RunOutcome.Running || ScheduleRunner.isRunning(original.id)
                        BasicComponent(
                            title = "立即运行一次",
                            summary = if (running) "正在运行，跑完会发通知" else runMessage ?: "照已保存的设置马上跑一次，不影响以后的时间",
                            titleColor = BasicComponentDefaults.titleColor(color = COUITheme.colorScheme.primary),
                            enabled = !running,
                            onClick = {
                                val reason = ScheduleRunner.start(app, original, manual = true)
                                runMessage = reason
                            },
                        )
                    }
                }
            }
            item(key = "delete") {
                PreferenceGroup { item { DangerRow("删除任务") { dialog = EditorDialog.Delete } } }
            }
        }
    }
}

/**
 * 一项权限没给时列表顶上的那一行：点了先试 root，没 root 或没改成就打开系统设置。
 * [reason] 是缺了它的后果，后面接“点这里…”。
 */
@Composable
internal fun PermissionFixRow(hub: ConversationHub, id: PermissionId, title: String, reason: String, titleColor: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granting by remember { mutableStateOf(false) }
    val root = hub.setup != SetupState.NoRoot
    BasicComponent(
        title = title,
        summary = if (granting) "正在授予…" else reason + "点这里" + if (root) "经 root 允许" else "到系统设置里允许",
        titleColor = BasicComponentDefaults.titleColor(color = titleColor),
        enabled = !granting,
        onClick = {
            granting = true
            scope.launch {
                fixPermission(context, id, tryRoot = root)
                granting = false
            }
        },
    )
}

/** 编辑器里的“模型”：跟随当前设置，或 Claude Code 给的模型清单里的一个（拿不到清单时用几个别名）。 */
@Composable
internal fun ModelPreference(hub: ConversationHub, model: String?, onChange: (String?) -> Unit) {
    val models = hub.models.map { it.value to (if (it.value == "default") "默认" else it.displayName.ifBlank { it.value }) }.ifEmpty { FallbackModels }
    val options = listOf<Pair<String?, String>>(null to "跟随当前设置") +
        if (model == null || models.any { it.first == model }) models else models + (model to model)
    OverlayDropdownPreference(
        title = "模型",
        items = options.map { it.second },
        selectedIndex = options.indexOfFirst { it.first == model },
        onSelectedIndexChange = { index -> options.getOrNull(index)?.let { onChange(it.first) } },
    )
}

/** 编辑器里改工作目录的对话框；确定时交出去的是去掉首尾空白的输入，空的表示用默认。放在页面的 overlays 里。 */
@Composable
internal fun CwdDialog(visible: Boolean, cwd: String, defaultCwd: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var input by remember(visible) { mutableStateOf(cwd) }
    TextInputDialog(
        show = visible,
        title = "工作目录",
        summary = "Linux 环境里的绝对路径。留空用默认工作目录（现在是 $defaultCwd）。",
        value = input,
        onValueChange = { input = it },
        label = defaultCwd,
        confirmText = "确定",
        onConfirm = { onConfirm(input.trim()) },
        onDismissRequest = onDismiss,
    )
}

/** 运行记录里的“跳过了 N 次”（没跳过时不显示）。[reason] 是为什么跳过。 */
@Composable
internal fun SkipsRow(skips: Int, lastSkip: Long?, reason: String, now: Instant, zone: ZoneId) {
    if (skips <= 0) return
    BasicComponent(
        title = "跳过了 $skips 次",
        summary = reason + (lastSkip?.let { "，最近一次在" + Schedules.formatWhen(Instant.ofEpochMilli(it), now, zone) } ?: ""),
    )
}

/** 运行记录里的“上次运行”：有那次的对话时点了打开它。 */
@Composable
internal fun LastRunRow(hub: ConversationHub, run: TaskRun, now: Instant, zone: ZoneId, onOpenConversation: () -> Unit) {
    val session = run.sessionId
    val title = "上次运行 · " + outcomeWord(run.outcome)
    val summary = Schedules.formatWhen(Instant.ofEpochMilli(run.at), now, zone) + (if (run.manual) "（手动）" else "") +
        run.summary.takeIf { it.isNotBlank() }?.let { "\n" + it }.orEmpty()
    val summaryColor = if (run.outcome == RunOutcome.Failed) {
        BasicComponentDefaults.summaryColor(color = COUITheme.colorScheme.error)
    } else {
        BasicComponentDefaults.summaryColor()
    }
    if (session != null) {
        ArrowPreference(
            title = title,
            summary = summary,
            summaryColor = summaryColor,
            onClick = {
                hub.openConversation(session)
                onOpenConversation()
            },
        )
    } else {
        BasicComponent(title = title, summary = summary, summaryColor = summaryColor)
    }
}

/** 点了开对话框的一行：标题和右边的当前值。 */
@Composable
internal fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    BasicComponent(title = title, endActions = { PreferenceValue(value) }, onClick = onClick)
}

/** 编辑页最下面的“删除…”：错误色的标题，点了弹确认框。 */
@Composable
internal fun DangerRow(title: String, onClick: () -> Unit) {
    BasicComponent(title = title, titleColor = BasicComponentDefaults.titleColor(color = COUITheme.colorScheme.error), onClick = onClick)
}

/**
 * 编辑页里的一个输入框（COUI 的卡片输入 InputView，标题在卡片里）和它下面的说明，
 * 左右和设置分组的卡片对齐，和下一张卡片隔 16dp。
 */
@Composable
internal fun FieldCard(footer: String?, field: @Composable () -> Unit) {
    Column {
        Box(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = if (footer == null) 16.dp else 0.dp)) { field() }
        if (footer != null) PagerFooterPreference(summary = footer)
    }
}

private fun dateText(date: LocalDate): String {
    val today = LocalDate.now()
    val prefix = when (date) {
        today -> "今天 · "
        today.plusDays(1) -> "明天 · "
        else -> ""
    }
    return prefix + (if (date.year == today.year) "" else "${date.year}年") + "${date.monthValue}月${date.dayOfMonth}日 " + Schedules.weekdayName(date.dayOfWeek)
}

/** 一周七天的小圆钮，可以多选。 */
@Composable
private fun WeekdayPicker(days: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    val colors = COUITheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        DayOfWeek.entries.forEach { day ->
            val selected = day in days
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.primary else colors.secondaryButton, CircleShape)
                    .clickable { onChange(if (selected) days - day else days + day) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = Schedules.weekdayName(day).removePrefix("周"),
                    style = COUITheme.textStyles.bodyM,
                    color = if (selected) colors.onPrimary else colors.labelPrimary,
                )
            }
        }
    }
}

/** 选时间（时、分）的对话框。放在页面的 overlays 里。 */
@Composable
internal fun TimeDialog(visible: Boolean, initial: LocalTime, onConfirm: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    var value by remember(visible) { mutableStateOf(TimeValue(initial.hour, initial.minute)) }
    ConfirmDialog(
        show = visible,
        title = "选择时间",
        summary = null,
        confirmText = "确定",
        onConfirm = { onConfirm(LocalTime.of(value.hour, value.minute)) },
        onDismissRequest = onDismiss,
    ) {
        TimePicker(value = value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth(), hourUnit = "时", minuteUnit = "分")
    }
}

@Composable
private fun DateDialog(visible: Boolean, initial: LocalDate, onConfirm: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    var value by remember(visible) { mutableStateOf(maxOf(initial, today)) }
    ConfirmDialog(
        show = visible,
        title = "选择日期",
        summary = null,
        confirmText = "确定",
        onConfirm = { onConfirm(value) },
        onDismissRequest = onDismiss,
    ) {
        CalendarPicker(
            value = value.toDateValue(),
            onValueChange = { value = LocalDate.of(it.year, it.month, it.day) },
            minDate = today.toDateValue(),
            maxDate = today.plusYears(5).toDateValue(),
            today = today.toDateValue(),
        )
    }
}

private fun LocalDate.toDateValue() = DateValue(year, monthValue, dayOfMonth)

@Composable
private fun HoursDialog(visible: Boolean, initial: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by remember(visible) { mutableStateOf(initial.coerceIn(1, ScheduleRule.MAX_INTERVAL_HOURS)) }
    ConfirmDialog(
        show = visible,
        title = "每隔几小时",
        summary = null,
        confirmText = "确定",
        onConfirm = { onConfirm(value) },
        onDismissRequest = onDismiss,
    ) {
        NumberPicker(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
            range = 1..ScheduleRule.MAX_INTERVAL_HOURS,
            unit = "小时",
            wrapAround = false,
        )
    }
}

@Composable
internal fun FieldBox(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
}
