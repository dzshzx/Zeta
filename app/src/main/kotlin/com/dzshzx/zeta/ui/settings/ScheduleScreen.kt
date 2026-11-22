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
import com.dzshzx.zeta.core.schedule.Edited
import com.dzshzx.zeta.core.schedule.RuleKind
import com.dzshzx.zeta.core.schedule.RunOutcome
import com.dzshzx.zeta.core.schedule.ScheduleRule
import com.dzshzx.zeta.core.schedule.ScheduleRunner
import com.dzshzx.zeta.core.schedule.ScheduleStore
import com.dzshzx.zeta.core.schedule.ScheduledTask
import com.dzshzx.zeta.core.schedule.Schedules
import com.dzshzx.zeta.core.schedule.TaskDraft
import com.dzshzx.zeta.core.schedule.TaskRun
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.IconActionButton
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.SettingsHue
import com.dzshzx.zeta.ui.components.coui2.SettingsIcon
import com.dzshzx.zeta.ui.components.coui2.TextInputDialog
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
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

/** 列表页的说明。没写进去的：后台对话不打断正在看的对话；跑完的通知是任务名加回答的开头，点它打开那次的对话； 同一个任务上一次还没跑完时跳过这一次（编辑页的“跳过了 N 次”会列出来）。 */
private const val ListFooter =
    "到点时在这台设备上开一个对话在后台运行，跑完发通知；Zeta 没开着也照常运行。" + "也可以在对话里直接说，比如“每天早上 8 点汇总通知和日程”。"

/** 设备页上的入口：主机 [backend] 上的定时任务开着几个、下一次什么时候。 */
@Composable
internal fun ScheduleEntry(backend: String, onOpen: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ScheduleStore.load(context) } }
    val tasks = ScheduleStore.observed.filter { it.backend == backend }
    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    val enabled = tasks.filter { it.enabled }
    val next = enabled.mapNotNull { Schedules.nextTrigger(it.rule, now, zone) }.minOrNull()
    ArrowPreference(
        title = "定时任务",
        summary = next?.let { "下次 " + Schedules.formatWhen(it, now, zone) },
        startAction = { SettingsIcon(ZetaIcons.Clock, SettingsHue.Automation) },
        endActions = {
            if (tasks.isNotEmpty())
                PreferenceValue(if (enabled.isEmpty()) "都关着" else "${enabled.size} 个开着")
        },
        onClick = onOpen,
    )
}

/** 正在编辑哪个任务；[id] 为 null 是新建。 */
private class EditTarget(val id: String?)

/** 主机 [backend] 上的“定时任务”：列出、开关；点一个进去编辑、立即运行、删除。闹钟都由手机排，到点在那台上起对话。 */
@Composable
internal fun ScheduleScreen(
    hub: ConversationHub,
    backend: String,
    onBack: () -> Unit,
    onOpenConversation: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ScheduleStore.load(app) } }
    // 上限按全部设备的任务一起算
    val total = ScheduleStore.observed.size
    val tasks = ScheduleStore.observed.filter { it.backend == backend }
    val snapshot = rememberPermissionSnapshot()
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    val zone = ZoneId.systemDefault()
    val now = Instant.now()

    CouiPage(
        title = "定时任务",
        onBack = onBack,
        actions = {
            IconActionButton(COUIIcons.Add, "新建定时任务", enabled = total < Schedules.MAX_TASKS) {
                editing = EditTarget(null)
            }
        },
    ) {
        if (snapshot != null && !snapshot.exactAlarms) {
            item(key = "alarm") {
                PreferenceGroup {
                    item {
                        PermissionFixRow(
                            hub,
                            PermissionId.ExactAlarm,
                            "精确闹钟未允许",
                            "到点可能被系统推迟。",
                            StatusWarning,
                        )
                    }
                }
            }
        }
        item(key = "tasks") {
            PreferenceGroup(title = "定时任务 · ${tasks.size}", footer = ListFooter) {
                if (tasks.isEmpty()) {
                    item {
                        BasicComponent(title = "还没有定时任务", summary = "点右上角 + 新建", enabled = false)
                    }
                }
                tasks.forEach { task ->
                    val last = task.lastRun
                    // 摘要一行：怎么重复，再加状态。关没关着看开关；下次什么时候在首页入口和编辑页的“时间”下面
                    val state =
                        when {
                            last?.outcome == RunOutcome.Running -> "正在运行"
                            task.enabled && Schedules.nextTrigger(task.rule, now, zone) == null ->
                                "时间已过"
                            else -> last?.let { "上次" + outcomeWord(it.outcome) }
                        }
                    item(key = task.id) {
                        SwitchWithDividerPreference(
                            title = task.name,
                            summary =
                                listOfNotNull(Schedules.describe(task.rule), state)
                                    .joinToString(" · "),
                            checked = task.enabled,
                            onCheckedChange = { on ->
                                ScheduleStore.updateTask(app, task.id) { it.copy(enabled = on) }
                            },
                            onClick = { editing = EditTarget(task.id) },
                        )
                    }
                }
            }
        }
    }

    SlideInPage(editing, onBack = { editing = null }) { target ->
        // 每打开一次是新的编辑状态，不沿用上一个任务填到一半的内容
        key(target) {
            TaskEditor(
                hub = hub,
                backend = backend,
                original = target.id?.let { id -> tasks.firstOrNull { it.id == id } },
                onBack = { editing = null },
                onOpenConversation = onOpenConversation,
            )
        }
    }
}

internal fun outcomeWord(outcome: RunOutcome) = outcome.label

private val Workdays =
    setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
    )

/** 编辑器里开着的那个对话框。 */
private enum class EditorDialog {
    Time,
    Date,
    Hours,
    Cwd,
    Delete,
}

/** 新建或编辑一个任务；编辑时下面还有上次运行、立即运行一次和删除。 */
@Composable
private fun TaskEditor(
    hub: ConversationHub,
    backend: String,
    original: ScheduledTask?,
    onBack: () -> Unit,
    onOpenConversation: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val zone = ZoneId.systemDefault()
    val rule = original?.rule
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(original?.prompt.orEmpty()) }
    var kind by remember { mutableStateOf(rule?.kind ?: RuleKind.Daily) }
    var time by remember { mutableStateOf(rule?.time ?: LocalTime.of(8, 0)) }
    var date by remember { mutableStateOf(initialDate(rule)) }
    var days by remember { mutableStateOf((rule as? ScheduleRule.Weekly)?.days ?: Workdays) }
    var hours by remember { mutableStateOf((rule as? ScheduleRule.Interval)?.hours ?: 3) }
    var cwd by remember { mutableStateOf(original?.cwd.orEmpty()) }
    var model by remember { mutableStateOf(original?.model) }
    var mode by remember { mutableStateOf(original?.permissionMode ?: Schedules.DEFAULT_MODE) }
    var newSession by remember { mutableStateOf(original?.newSession ?: true) }
    var onlyOnChange by remember { mutableStateOf(original?.onlyOnChange ?: false) }
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    var runMessage by remember { mutableStateOf<String?>(null) }

    val draftRule = ruleOf(kind, date, time, days, hours)
    val now = Instant.now()
    // 开关和校验照 Schedules.edit：改了时间就重新打开；关着的只改文字时保持关着，也不看时间
    val edited =
        Schedules.edit(
            original,
            TaskDraft(
                name,
                prompt,
                draftRule,
                cwd,
                model,
                mode,
                newSession,
                onlyOnChange = onlyOnChange,
            ),
            enabled = null,
            now,
            zone,
        )
    val problem = edited.problem
    val next = Schedules.nextTrigger(draftRule, now, zone)
    val defaultCwd = hub.defaultCwd(backend)

    fun save() {
        if (problem != null) return
        saveTask(app, backend, original, edited, now)
        onBack()
    }

    CouiPage(
        title = if (original == null) "新建定时任务" else "编辑定时任务",
        onBack = onBack,
        actions = { ToolbarTextButton(text = "保存", enabled = problem == null, onClick = ::save) },
        dialogs = {
            TaskDialogs(
                dialog = dialog,
                time = time,
                date = date,
                hours = hours,
                cwd = cwd,
                defaultCwd = defaultCwd,
                original = original,
                onTime = { time = it },
                onDate = { date = it },
                onHours = { hours = it },
                onCwd = { cwd = it },
                onClose = { dialog = null },
                onDelete = {
                    dialog = null
                    original?.let { deleteTask(app, it) }
                    onBack()
                },
            )
        },
    ) {
        // 名称是跑完那条通知的标题，也是每次运行那个对话的名字；指令到点时原样发给 Claude，和在输入框里发的一样
        item(key = "name") {
            FieldCard {
                InputView(
                    value = name,
                    onValueChange = { name = it.take(Schedules.MAX_NAME) },
                    title = "名称",
                    label = "每日简报",
                )
            }
        }
        item(key = "prompt") {
            FieldCard {
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
            TaskTimeGroup(
                footer =
                    timeFooter(
                        problem?.takeIf {
                            name.isNotBlank() || prompt.isNotBlank() || original != null
                        },
                        next,
                        now,
                        zone,
                    ),
                kind = kind,
                date = date,
                days = days,
                hours = hours,
                time = time,
                onKind = { kind = it },
                onDays = { days = it },
                onDialog = { dialog = it },
            )
        }
        item(key = "run") {
            TaskRunGroup(
                hub = hub,
                backend = backend,
                cwd = cwd,
                defaultCwd = defaultCwd,
                model = model,
                mode = mode,
                newSession = newSession,
                onModel = { model = it },
                onMode = { mode = it },
                onNewSession = { newSession = it },
                onPickCwd = { dialog = EditorDialog.Cwd },
            )
        }
        item(key = "alert") { OnlyOnChangeGroup(hub, onlyOnChange) { onlyOnChange = it } }
        if (original != null) {
            item(key = "record") {
                TaskRecordGroup(hub, original, now, zone, runMessage, onOpenConversation) {
                    runMessage = ScheduleRunner.start(app, original, manual = true)
                }
            }
            item(key = "delete") {
                PreferenceGroup { item { DangerRow("删除任务") { dialog = EditorDialog.Delete } } }
            }
        }
    }
}

/** 编辑器里日期一开始是哪天：单次任务是它运行的那天，间隔任务是开始的那天，别的是今天。 */
private fun initialDate(rule: ScheduleRule?): LocalDate =
    when (rule) {
        is ScheduleRule.Once -> rule.at.toLocalDate()
        is ScheduleRule.Interval -> rule.start.toLocalDate()
        is ScheduleRule.Daily,
        is ScheduleRule.Weekly,
        null -> LocalDate.now()
    }

/** 编辑器里选的这些拼成的规则。 */
private fun ruleOf(
    kind: RuleKind,
    date: LocalDate,
    time: LocalTime,
    days: Set<DayOfWeek>,
    hours: Int,
): ScheduleRule =
    when (kind) {
        RuleKind.Once -> ScheduleRule.Once(date.atTime(time))
        RuleKind.Daily -> ScheduleRule.Daily(time)
        RuleKind.Weekly -> ScheduleRule.Weekly(days, time)
        RuleKind.Interval -> ScheduleRule.Interval(hours, date.atTime(time))
    }

/** 存下任务：新建的加进去，编辑的照草稿改。 */
private fun saveTask(
    app: Application,
    backend: String,
    original: ScheduledTask?,
    edited: Edited,
    now: Instant,
) {
    if (original == null) {
        ScheduleStore.update(app) {
            it + Schedules.toTask(edited.draft, now.toEpochMilli(), backend = backend)
        }
    } else {
        ScheduleStore.updateTask(app, original.id) { Schedules.applyDraft(it, edited.draft) }
    }
}

/** 删掉任务和它留在通知栏里的通知；之前运行留下的对话还在。 */
private fun deleteTask(app: Application, task: ScheduledTask) {
    ScheduleStore.update(app) { list -> list.filterNot { it.id == task.id } }
    ScheduleRunner.clearNotification(app, task.id)
}

/** 时间一组下面的说明：哪里还不对（[problem]，名称或指令填过了才给），否则是下次运行的时间。 */
private fun timeFooter(problem: String?, next: Instant?, now: Instant, zone: ZoneId): String? =
    when {
        problem != null -> problem
        next != null -> "下次运行：" + Schedules.formatWhen(next, now, zone)
        else -> null
    }

/** 编辑器的对话框：时间、日期、间隔、工作目录和删除确认。 */
@Composable
private fun TaskDialogs(
    dialog: EditorDialog?,
    time: LocalTime,
    date: LocalDate,
    hours: Int,
    cwd: String,
    defaultCwd: String,
    original: ScheduledTask?,
    onTime: (LocalTime) -> Unit,
    onDate: (LocalDate) -> Unit,
    onHours: (Int) -> Unit,
    onCwd: (String) -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    TimeDialog(
        visible = dialog == EditorDialog.Time,
        initial = time,
        onConfirm = {
            onTime(it)
            onClose()
        },
        onDismiss = onClose,
    )
    DateDialog(
        visible = dialog == EditorDialog.Date,
        initial = date,
        onConfirm = {
            onDate(it)
            onClose()
        },
        onDismiss = onClose,
    )
    HoursDialog(
        visible = dialog == EditorDialog.Hours,
        initial = hours,
        onConfirm = {
            onHours(it)
            onClose()
        },
        onDismiss = onClose,
    )
    CwdDialog(
        visible = dialog == EditorDialog.Cwd,
        cwd = cwd,
        defaultCwd = defaultCwd,
        onConfirm = {
            onCwd(it)
            onClose()
        },
        onDismiss = onClose,
    )
    ConfirmDialog(
        show = dialog == EditorDialog.Delete,
        title = "删除定时任务",
        summary = original?.let { "删除“${it.name}”？以后不再运行。之前运行留下的对话还在。" },
        confirmText = "删除",
        destructive = true,
        onConfirm = onDelete,
        onDismissRequest = onClose,
    )
}

/** 时间：单次、每天、每周、间隔四选一，下面是各自要定的日期、星期、间隔和几点。 */
@Composable
private fun TaskTimeGroup(
    footer: String?,
    kind: RuleKind,
    date: LocalDate,
    days: Set<DayOfWeek>,
    hours: Int,
    time: LocalTime,
    onKind: (RuleKind) -> Unit,
    onDays: (Set<DayOfWeek>) -> Unit,
    onDialog: (EditorDialog) -> Unit,
) {
    PreferenceGroup(title = "时间", footer = footer) {
        item(key = "kind") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                SegmentButton(
                    items = RuleKind.entries.map { it.label },
                    selectedIndex = kind.ordinal,
                    onSelectedIndexChange = { index ->
                        RuleKind.entries.getOrNull(index)?.let(onKind)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        when (kind) {
            RuleKind.Once ->
                item(key = "date") {
                    ValueRow("日期", dateText(date)) { onDialog(EditorDialog.Date) }
                }
            RuleKind.Weekly -> item(key = "days") { WeekdayPicker(days, onDays) }
            RuleKind.Interval -> {
                item(key = "hours") {
                    ValueRow("每隔", "$hours 小时") { onDialog(EditorDialog.Hours) }
                }
                item(key = "start") {
                    ValueRow("从哪天开始", dateText(date)) { onDialog(EditorDialog.Date) }
                }
            }
            RuleKind.Daily -> Unit
        }
        item(key = "clock") {
            ValueRow(
                if (kind == RuleKind.Interval) "从几点开始" else "时间",
                "%02d:%02d".format(time.hour, time.minute),
            ) {
                onDialog(EditorDialog.Time)
            }
        }
    }
}

/** 运行方式：工作目录、模型、权限模式，和每次开不开新对话。 */
@Composable
private fun TaskRunGroup(
    hub: ConversationHub,
    backend: String,
    cwd: String,
    defaultCwd: String,
    model: String?,
    mode: String,
    newSession: Boolean,
    onModel: (String?) -> Unit,
    onMode: (String) -> Unit,
    onNewSession: (Boolean) -> Unit,
    onPickCwd: () -> Unit,
) {
    PreferenceGroup(
        title = "运行方式",
        footer =
            "后台运行时没人确认。默认“自动判断”：由 Claude Code 判断每一步放不放行，一般不用停下来等你。" +
                "选“逐项确认”的话，要改文件、执行命令时这一轮会停下来，发通知等你。",
    ) {
        item {
            BasicComponent(
                title = "工作目录",
                summary = cwd.ifBlank { "$defaultCwd（默认）" },
                onClick = onPickCwd,
            )
        }
        item { ModelPreference(hub, backend, model, onModel) }
        item { PermissionModePreference(mode, onMode) }
        item {
            SwitchPreference(
                title = "每次开新对话",
                summary = if (newSession) "几次运行互不相干" else "接着同一个对话，记得之前的内容",
                checked = newSession,
                onCheckedChange = onNewSession,
            )
        }
    }
}

/** 运行记录：上一次运行、跳过了几次，和立即运行一次（[runMessage] 是上次没跑成的原因）。 */
@Composable
private fun TaskRecordGroup(
    hub: ConversationHub,
    original: ScheduledTask,
    now: Instant,
    zone: ZoneId,
    runMessage: String?,
    onOpenConversation: () -> Unit,
    onRunNow: () -> Unit,
) {
    PreferenceGroup(title = "运行记录") {
        original.lastRun?.let { run ->
            item(key = "last") { LastRunRow(hub, run, now, zone, onOpenConversation) }
        }
        if (original.skips > 0) {
            item(key = "skips") {
                SkipsRow(original.skips, original.lastSkip, "上一次还没跑完", now, zone)
            }
        }
        item(key = "now") {
            val running =
                original.lastRun?.outcome == RunOutcome.Running ||
                    ScheduleRunner.isRunning(original.id)
            BasicComponent(
                title = "立即运行一次",
                summary = runNowSummary(running, original.onlyOnChange, runMessage),
                titleColor =
                    BasicComponentDefaults.titleColor(color = COUITheme.colorScheme.primary),
                enabled = !running,
                onClick = onRunNow,
            )
        }
    }
}

private fun runNowSummary(running: Boolean, onlyOnChange: Boolean, runMessage: String?): String =
    when {
        running && onlyOnChange -> "正在运行，有变化才发通知"
        running -> "正在运行，跑完会发通知"
        else -> runMessage ?: "照已保存的设置跑一次，不影响定时"
    }

/** 一项权限没给时列表顶上的那一行：点了先试 root，没 root 或没改成就打开系统设置。 [reason] 是缺了它的后果，后面接“点这里…”。 */
@Composable
internal fun PermissionFixRow(
    hub: ConversationHub,
    id: PermissionId,
    title: String,
    reason: String,
    titleColor: Color,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granting by remember { mutableStateOf(false) }
    val root = hub.environment.setup != SetupState.NoRoot
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

/** 编辑器里改工作目录的对话框；确定时交出去的是去掉首尾空白的输入，空的表示用默认。放在页面的 dialogs 里。 */
@Composable
internal fun CwdDialog(
    visible: Boolean,
    cwd: String,
    defaultCwd: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember(visible) { mutableStateOf(cwd) }
    TextInputDialog(
        show = visible,
        title = "工作目录",
        summary = "这台设备上的绝对路径。留空用默认工作目录（现在是 $defaultCwd）。",
        value = input,
        onValueChange = { input = it },
        label = defaultCwd,
        confirmText = "确定",
        onConfirm = { onConfirm(input.trim()) },
        onDismissRequest = onDismiss,
    )
}

/** 运行记录里的“跳过了 N 次”（没跳过时不显示）。[reason] 是为什么跳过，后面接最近一次跳过的时间。 */
@Composable
internal fun SkipsRow(skips: Int, lastSkip: Long?, reason: String, now: Instant, zone: ZoneId) {
    if (skips <= 0) return
    BasicComponent(
        title = "跳过了 $skips 次",
        summary =
            reason +
                (lastSkip?.let { " · " + Schedules.formatWhen(Instant.ofEpochMilli(it), now, zone) }
                    ?: ""),
    )
}

/** 编辑页的“提醒”一组：只在有变化时提醒（定时任务和通知触发的规则共用）。 手机工具关着时 Claude 没有上报的工具，开了也每次照常提醒，摘要里说一声。 */
@Composable
internal fun OnlyOnChangeGroup(
    hub: ConversationHub,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    PreferenceGroup(title = "提醒", footer = OnlyOnChangeFooter) {
        item {
            SwitchPreference(
                title = "只在有变化时提醒",
                summary =
                    when {
                        checked && !hub.settings.phoneTools -> "手机工具关着，Claude 没法上报，照常提醒"
                        checked -> "没有变化只记进运行记录，不发通知"
                        else -> "每次跑完都发通知"
                    },
                checked = checked,
                onCheckedChange = onChange,
            )
        }
    }
}

private const val OnlyOnChangeFooter =
    "打开后，Claude 每次跑完判断有没有值得告诉你的，没有就不发通知。" + "出了错、停下来等你确认、Claude 没给结论时照常提醒。"

/**
 * 运行记录里的“上次运行”：有那次的对话时点了打开它。 摘要只写什么时候跑的：回答的开头在跑完的通知里，点进对话看全文；没有对话可开（没跑起来）、或没有变化（没发通知）时，才在后面接上原因或
 * Claude 的说明。
 */
@Composable
internal fun LastRunRow(
    hub: ConversationHub,
    run: TaskRun,
    now: Instant,
    zone: ZoneId,
    onOpenConversation: () -> Unit,
) {
    val session = run.sessionId
    val title = "上次运行 · " + outcomeWord(run.outcome)
    val summary =
        Schedules.formatWhen(Instant.ofEpochMilli(run.at), now, zone) +
            (if (run.manual) "（手动）" else "") +
            run.summary
                .takeIf {
                    (session == null || run.outcome == RunOutcome.NoChange) && it.isNotBlank()
                }
                ?.let { " · " + it }
                .orEmpty()
    val summaryColor =
        if (run.outcome == RunOutcome.Failed) {
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
    BasicComponent(
        title = title,
        titleColor = BasicComponentDefaults.titleColor(color = COUITheme.colorScheme.error),
        onClick = onClick,
    )
}

/** 编辑页里的一个输入框（COUI 的卡片输入 InputView，标题在卡片里）和它下面的说明， 左右和设置分组的卡片对齐，和下一张卡片隔 16dp。 */
@Composable
internal fun FieldCard(footer: String? = null, field: @Composable () -> Unit) {
    Column {
        Box(
            Modifier.padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = if (footer == null) 16.dp else 0.dp),
        ) {
            field()
        }
        if (footer != null) PagerFooterPreference(summary = footer)
    }
}

private fun dateText(date: LocalDate): String {
    val today = LocalDate.now()
    val prefix =
        when (date) {
            today -> "今天 · "
            today.plusDays(1) -> "明天 · "
            else -> ""
        }
    return prefix +
        (if (date.year == today.year) "" else "${date.year}年") +
        "${date.monthValue}月${date.dayOfMonth}日 " +
        Schedules.weekdayName(date.dayOfWeek)
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
                modifier =
                    Modifier.size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) colors.primary else colors.secondaryButton,
                            CircleShape,
                        )
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

/** 选时间（时、分）的对话框。放在页面的 dialogs 里。 */
@Composable
internal fun TimeDialog(
    visible: Boolean,
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(visible) { mutableStateOf(TimeValue(initial.hour, initial.minute)) }
    ConfirmDialog(
        show = visible,
        title = "选择时间",
        summary = null,
        confirmText = "确定",
        onConfirm = { onConfirm(LocalTime.of(value.hour, value.minute)) },
        onDismissRequest = onDismiss,
    ) {
        TimePicker(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
            hourUnit = "时",
            minuteUnit = "分",
        )
    }
}

@Composable
private fun DateDialog(
    visible: Boolean,
    initial: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
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
private fun HoursDialog(
    visible: Boolean,
    initial: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by
        remember(visible) { mutableStateOf(initial.coerceIn(1, ScheduleRule.MAX_INTERVAL_HOURS)) }
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
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        content()
    }
}
