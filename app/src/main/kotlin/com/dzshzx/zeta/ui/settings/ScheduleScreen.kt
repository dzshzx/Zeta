package com.dzshzx.zeta.ui.settings

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.dzshzx.zeta.ui.components.StatusError
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.coui.CouiConfirmDialog
import com.dzshzx.zeta.ui.components.coui.CouiInputDialog
import com.dzshzx.zeta.ui.components.coui.CouiPopupPreference
import com.dzshzx.zeta.ui.components.coui.CouiPreference
import com.dzshzx.zeta.ui.components.coui.CouiScaffoldPage
import com.dzshzx.zeta.ui.components.coui.CouiSegmentedTabs
import com.dzshzx.zeta.ui.components.coui.CouiSwitchPreference
import com.dzshzx.zeta.ui.components.coui.CouiTextField
import com.dzshzx.zeta.ui.components.coui.CouiTopAppBarIconButton
import com.dzshzx.zeta.ui.components.coui.CouiTopAppBarTextButton
import com.dzshzx.zeta.ui.setup.ConfigSubPage
import com.dzshzx.zeta.ui.components.rememberRetained
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Group
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.picker.CalendarPicker
import com.itos.coui.picker.CouiPickerDefaults
import com.itos.coui.picker.WheelPicker
import com.itos.coui.switch.CouiSwitchDefaults
import com.itos.coui.switch.Switch
import com.itos.coui.theme.CouiTheme
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    CouiPreference(
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
    val accent = CouiPageDefaults.themeColors().accent

    CouiScaffoldPage(
        title = "定时任务",
        onBack = onBack,
        actions = {
            CouiTopAppBarIconButton(
                icon = CouiIcons.Outlined.Add,
                contentDescription = "新建定时任务",
                enabled = tasks.size < Schedules.MAX_TASKS,
                onClick = { editing = EditTarget(null) },
            )
        },
    ) {
        if (snapshot != null && !snapshot.exactAlarms) {
            Group { PermissionFixRow(hub, PermissionId.ExactAlarm, "精确闹钟未允许", "到点可能被系统推迟。", StatusWarning) }
        }
        Group(title = "定时任务 · ${tasks.size}", footer = ListFooter) {
            if (tasks.isEmpty()) {
                CouiPreference(title = "还没有定时任务", summary = "点右上角 + 新建", enabled = false, showChevron = false)
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
                CouiPreference(
                    title = task.name,
                    summary = Schedules.describe(task.rule) + "\n" + state,
                    onClick = { editing = EditTarget(task.id) },
                    showChevron = false,
                    trailing = {
                        Switch(
                            modifier = Modifier.padding(start = 16.dp),
                            checked = task.enabled,
                            onCheckedChange = { on -> ScheduleStore.updateTask(app, task.id) { it.copy(enabled = on) } },
                            dark = CouiTheme.isDark,
                            colors = CouiSwitchDefaults.colors(accent, CouiTheme.isDark),
                        )
                    },
                )
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

internal fun outcomeWord(outcome: RunOutcome) = when (outcome) {
    RunOutcome.Running -> "正在运行"
    RunOutcome.Done -> "完成"
    RunOutcome.Failed -> "没有完成"
    RunOutcome.Stopped -> "被停止"
    RunOutcome.Interrupted -> "中途被关掉"
}

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
    var modeMenu by remember { mutableStateOf(false) }
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

    CouiScaffoldPage(
        title = if (original == null) "新建定时任务" else "编辑定时任务",
        onBack = onBack,
        actions = { CouiTopAppBarTextButton(text = "保存", enabled = problem == null, onClick = ::save) },
    ) {
        Group(title = "名称", footer = "通知的标题，也是每次运行那个对话的名字。") {
            FieldBox { CouiTextField(value = name, onValueChange = { name = it.take(Schedules.MAX_NAME) }, placeholder = "每日简报") }
        }
        Group(title = "指令", footer = "到点时原样发给 Claude，就像你在输入框里发的一样。写清楚要它做什么、结果怎么写。") {
            FieldBox {
                CouiTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    placeholder = "比如：汇总今天的日程和昨晚以来的通知，三五行说完",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 10,
                )
            }
        }
        Group(
            title = "时间",
            footer = when {
                problem != null && (name.isNotBlank() || prompt.isNotBlank() || original != null) -> problem
                next != null -> "下次运行：" + Schedules.formatWhen(next, now, zone)
                else -> null
            },
        ) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                CouiSegmentedTabs(
                    items = RuleKind.entries.map { it to it.label },
                    selectedKey = kind,
                    onSelectedKeyChange = { kind = it },
                )
            }
            when (kind) {
                RuleKind.Once -> CouiPreference(title = "日期", value = dateText(date), onClick = { dialog = EditorDialog.Date })
                RuleKind.Weekly -> WeekdayPicker(days) { days = it }
                RuleKind.Interval -> {
                    CouiPreference(title = "每隔", value = "$hours 小时", onClick = { dialog = EditorDialog.Hours })
                    CouiPreference(title = "从哪天开始", value = dateText(date), onClick = { dialog = EditorDialog.Date })
                }
                RuleKind.Daily -> Unit
            }
            CouiPreference(title = if (kind == RuleKind.Interval) "从几点开始" else "时间", value = "%02d:%02d".format(time.hour, time.minute), onClick = { dialog = EditorDialog.Time })
        }
        Group(
            title = "运行方式",
            footer = "后台运行时没人点确认。要用到需要确认的工具（执行命令、改文件等），把权限模式设成“自动接受编辑”或“完全放行”；" +
                "否则这一轮会停下来等你，到时会发通知。",
        ) {
            CouiPreference(title = "工作目录", summary = cwd.ifBlank { "$defaultCwd（默认）" }, onClick = { dialog = EditorDialog.Cwd })
            ModelPreference(hub, model) { model = it }
            val modeOptions = listOf<Pair<String?, String>>(null to "跟随当前设置") + PermissionModes
            CouiPopupPreference(
                title = "权限模式",
                value = if (mode == null) "跟随当前设置" else permissionModeLabel(mode),
                expanded = modeMenu,
                onExpandedChange = { modeMenu = it },
                items = modeOptions.map { CouiMenuItem(value = it.first, text = it.second, selected = it.first == mode) },
                onItemClick = {
                    modeMenu = false
                    mode = it.value
                },
            )
            CouiSwitchPreference(
                title = "每次开新对话",
                summary = if (newSession) "每次都是新的对话，互不影响" else "接着同一个对话，Claude 记得前几次的内容",
                checked = newSession,
                onCheckedChange = { newSession = it },
            )
        }
        if (original != null) {
            Group(title = "运行记录") {
                original.lastRun?.let { run -> LastRunRow(hub, run, now, zone, onOpenConversation) }
                SkipsRow(original.skips, original.lastSkip, "到点时上一次还没跑完", now, zone)
                val running = original.lastRun?.outcome == RunOutcome.Running || ScheduleRunner.isRunning(original.id)
                CouiPreference(
                    title = "立即运行一次",
                    summary = if (running) "正在运行，跑完会发通知" else runMessage ?: "照已保存的设置马上跑一次，不影响以后的时间",
                    titleColor = CouiPageDefaults.themeColors().accent,
                    enabled = !running,
                    showChevron = false,
                    onClick = {
                        val reason = ScheduleRunner.start(app, original, manual = true)
                        runMessage = reason
                    },
                )
            }
            Group {
                CouiPreference(title = "删除任务", titleColor = StatusError, showChevron = false, onClick = { dialog = EditorDialog.Delete })
            }
        }
    }

    TimeDialog(visible = dialog == EditorDialog.Time, initial = time, onConfirm = { time = it; dialog = null }, onDismiss = { dialog = null })
    DateDialog(visible = dialog == EditorDialog.Date, initial = date, onConfirm = { date = it; dialog = null }, onDismiss = { dialog = null })
    HoursDialog(visible = dialog == EditorDialog.Hours, initial = hours, onConfirm = { hours = it; dialog = null }, onDismiss = { dialog = null })
    CwdDialog(visible = dialog == EditorDialog.Cwd, cwd = cwd, defaultCwd = defaultCwd, onConfirm = { cwd = it; dialog = null }, onDismiss = { dialog = null })
    CouiConfirmDialog(
        visible = dialog == EditorDialog.Delete,
        title = "删除定时任务",
        message = original?.let { "删除“${it.name}”？以后不再运行。之前运行留下的对话还在。" },
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
    CouiPreference(
        title = title,
        summary = if (granting) "正在授予…" else reason + "点这里" + if (root) "经 root 允许" else "到系统设置里允许",
        titleColor = titleColor,
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
    var menu by remember { mutableStateOf(false) }
    val models = hub.models.map { it.value to (if (it.value == "default") "默认" else it.displayName.ifBlank { it.value }) }.ifEmpty { FallbackModels }
    val options = listOf<Pair<String?, String>>(null to "跟随当前设置") +
        if (model == null || models.any { it.first == model }) models else models + (model to model)
    CouiPopupPreference(
        title = "模型",
        value = options.firstOrNull { it.first == model }?.second ?: model.orEmpty(),
        expanded = menu,
        onExpandedChange = { menu = it },
        items = options.map { CouiMenuItem(value = it.first, text = it.second, selected = it.first == model) },
        onItemClick = {
            menu = false
            onChange(it.value)
        },
    )
}

/** 编辑器里改工作目录的对话框；确定时交出去的是去掉首尾空白的输入，空的表示用默认。 */
@Composable
internal fun CwdDialog(visible: Boolean, cwd: String, defaultCwd: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var input by remember(visible) { mutableStateOf(cwd) }
    CouiInputDialog(
        visible = visible,
        title = "工作目录",
        message = "Linux 环境里的绝对路径。留空用默认工作目录（现在是 $defaultCwd）。",
        value = input,
        onValueChange = { input = it },
        placeholder = defaultCwd,
        confirmText = "确定",
        onConfirm = { onConfirm(input.trim()) },
        onDismissRequest = onDismiss,
    )
}

/** 运行记录里的“跳过了 N 次”（没跳过时不显示）。[reason] 是为什么跳过。 */
@Composable
internal fun SkipsRow(skips: Int, lastSkip: Long?, reason: String, now: Instant, zone: ZoneId) {
    if (skips <= 0) return
    CouiPreference(
        title = "跳过了 $skips 次",
        summary = reason + (lastSkip?.let { "，最近一次在" + Schedules.formatWhen(Instant.ofEpochMilli(it), now, zone) } ?: ""),
        showChevron = false,
    )
}

@Composable
internal fun LastRunRow(hub: ConversationHub, run: TaskRun, now: Instant, zone: ZoneId, onOpenConversation: () -> Unit) {
    val session = run.sessionId
    CouiPreference(
        title = "上次运行 · " + outcomeWord(run.outcome),
        summary = Schedules.formatWhen(Instant.ofEpochMilli(run.at), now, zone) + (if (run.manual) "（手动）" else "") +
            run.summary.takeIf { it.isNotBlank() }?.let { "\n" + it }.orEmpty(),
        summaryColor = if (run.outcome == RunOutcome.Failed) StatusError else Color.Unspecified,
        onClick = session?.let {
            {
                hub.openConversation(it)
                onOpenConversation()
            }
        },
    )
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
    val colors = CouiPageDefaults.themeColors()
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
                    .background(if (selected) colors.accent else MiuixTheme.colorScheme.surfaceContainer, CircleShape)
                    .clickable { onChange(if (selected) days - day else days + day) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = Schedules.weekdayName(day).removePrefix("周"),
                    style = MiuixTheme.textStyles.body2,
                    color = if (selected) Color.White else MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
internal fun TimeDialog(visible: Boolean, initial: LocalTime, onConfirm: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    var hour by remember(visible) { mutableStateOf(initial.hour) }
    var minute by remember(visible) { mutableStateOf(initial.minute) }
    CouiConfirmDialog(
        visible = visible,
        title = "选择时间",
        message = null,
        confirmText = "确定",
        onConfirm = { onConfirm(LocalTime.of(hour, minute)) },
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WheelPicker(
                modifier = Modifier.width(CouiPickerDefaults.columnWidth).height(CouiPickerDefaults.height),
                value = hour,
                onValueChange = { hour = it },
                valueRange = 0..23,
                label = "时",
                clockTypeface = true,
                formatter = { "%02d".format(it) },
            )
            Text(text = ":", style = MiuixTheme.textStyles.title3, modifier = Modifier.padding(horizontal = 8.dp))
            WheelPicker(
                modifier = Modifier.width(CouiPickerDefaults.columnWidth).height(CouiPickerDefaults.height),
                value = minute,
                onValueChange = { minute = it },
                valueRange = 0..59,
                label = "分",
                clockTypeface = true,
                formatter = { "%02d".format(it) },
            )
        }
    }
}

@Composable
private fun DateDialog(visible: Boolean, initial: LocalDate, onConfirm: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    var value by remember(visible) { mutableStateOf(maxOf(initial, today)) }
    CouiConfirmDialog(
        visible = visible,
        title = "选择日期",
        message = null,
        confirmText = "确定",
        onConfirm = { onConfirm(value) },
        onDismissRequest = onDismiss,
    ) {
        CalendarPicker(
            value = value,
            onValueChange = { value = it },
            minDate = today,
            maxDate = today.plusYears(5),
            today = today,
        )
    }
}

@Composable
private fun HoursDialog(visible: Boolean, initial: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by remember(visible) { mutableStateOf(initial.coerceIn(1, ScheduleRule.MAX_INTERVAL_HOURS)) }
    CouiConfirmDialog(
        visible = visible,
        title = "每隔几小时",
        message = null,
        confirmText = "确定",
        onConfirm = { onConfirm(value) },
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WheelPicker(
                modifier = Modifier.width(CouiPickerDefaults.columnWidth).height(CouiPickerDefaults.height),
                value = value,
                onValueChange = { value = it },
                valueRange = 1..ScheduleRule.MAX_INTERVAL_HOURS,
                label = "小时",
                wrap = false,
            )
            Text(text = "小时", style = MiuixTheme.textStyles.body1, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
internal fun FieldBox(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
}
