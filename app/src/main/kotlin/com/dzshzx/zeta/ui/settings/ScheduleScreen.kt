package com.dzshzx.zeta.ui.settings

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import com.dzshzx.zeta.core.environment
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
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.i18n.UiLanguage
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.miuix.ConfirmDialog
import com.dzshzx.zeta.ui.components.miuix.FieldGroup
import com.dzshzx.zeta.ui.components.miuix.IconActionButton
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.PreferenceValue
import com.dzshzx.zeta.ui.components.miuix.SegmentedTabs
import com.dzshzx.zeta.ui.components.miuix.SettingsIcon
import com.dzshzx.zeta.ui.components.miuix.SwitchWithDividerPreference
import com.dzshzx.zeta.ui.components.miuix.TextField
import com.dzshzx.zeta.ui.components.miuix.TextInputDialog
import com.dzshzx.zeta.ui.components.miuix.TopBarTextButton
import com.dzshzx.zeta.ui.components.miuix.ZetaIcons
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
        title = Strings.settingsTitleSchedules(),
        summary = next?.let { Strings.settingsScheduleNext(Schedules.formatWhen(it, now, zone)) },
        startAction = { SettingsIcon(ZetaIcons.Clock) },
        endActions = {
            if (tasks.isNotEmpty())
                PreferenceValue(
                    if (enabled.isEmpty()) Strings.settingsScheduleAllOff()
                    else Strings.settingsScheduleOnCount(enabled.size),
                )
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

    MiuixPage(
        title = Strings.settingsTitleSchedules(),
        onBack = onBack,
        actions = {
            IconActionButton(
                MiuixIcons.Add,
                Strings.settingsScheduleNew(),
                enabled = total < Schedules.MAX_TASKS,
            ) {
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
                            Strings.settingsScheduleExactAlarmMissing(),
                            Strings.settingsScheduleExactAlarmReason(),
                            StatusWarning,
                        )
                    }
                }
            }
        }
        item(key = "tasks") {
            PreferenceGroup(
                title = Strings.settingsScheduleListTitle(tasks.size),
                // 没写进说明的：后台对话不打断正在看的对话；跑完的通知是任务名加回答的开头，点它打开那次的对话；
                // 同一个任务上一次还没跑完时跳过这一次（编辑页的“跳过了 N 次”会列出来）
                footer = Strings.settingsScheduleListFooter(),
            ) {
                if (tasks.isEmpty()) {
                    item {
                        BasicComponent(
                            title = Strings.settingsScheduleEmpty(),
                            summary = Strings.settingsScheduleEmptyHint(),
                            enabled = false,
                        )
                    }
                }
                tasks.forEach { task ->
                    val last = task.lastRun
                    // 摘要一行：怎么重复，再加状态。关没关着看开关；下次什么时候在首页入口和编辑页的“时间”下面
                    val state =
                        when {
                            last?.outcome == RunOutcome.Running -> Strings.settingsScheduleRunning()
                            task.enabled && Schedules.nextTrigger(task.rule, now, zone) == null ->
                                Strings.settingsScheduleTimePassed()
                            else ->
                                last?.let {
                                    Strings.settingsScheduleLastOutcome(outcomeWord(it.outcome))
                                }
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

    MiuixPage(
        title =
            if (original == null) Strings.settingsScheduleNew() else Strings.settingsScheduleEdit(),
        onBack = onBack,
        actions = {
            TopBarTextButton(
                text = Strings.commonSave(),
                enabled = problem == null,
                onClick = ::save,
            )
        },
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
            FieldGroup(title = Strings.settingsScheduleName()) {
                TextField(
                    value = name,
                    onValueChange = { name = it.take(Schedules.MAX_NAME) },
                    label = Strings.settingsScheduleNameHint(),
                    singleLine = true,
                )
            }
        }
        item(key = "prompt") {
            FieldGroup(title = Strings.settingsSchedulePrompt()) {
                TextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = Strings.settingsSchedulePromptHint(),
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
                PreferenceGroup {
                    item {
                        DangerRow(Strings.settingsScheduleDeleteTask()) {
                            dialog = EditorDialog.Delete
                        }
                    }
                }
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
        next != null -> Strings.settingsScheduleNextRun(Schedules.formatWhen(next, now, zone))
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
        title = Strings.settingsScheduleDeleteTitle(),
        summary = original?.let { Strings.settingsScheduleDeleteSummary(it.name) },
        confirmText = Strings.commonDelete(),
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
    PreferenceGroup(title = Strings.settingsScheduleTime(), footer = footer) {
        item(key = "kind") {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                SegmentedTabs(
                    tabs = RuleKind.entries.map { it.label },
                    selectedIndex = kind.ordinal,
                    onSelect = { index ->
                        RuleKind.entries.getOrNull(index)?.let(onKind)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        when (kind) {
            RuleKind.Once ->
                item(key = "date") {
                    ValueRow(Strings.settingsScheduleDate(), dateText(date)) {
                        onDialog(EditorDialog.Date)
                    }
                }
            RuleKind.Weekly -> item(key = "days") { WeekdayPicker(days, onDays) }
            RuleKind.Interval -> {
                item(key = "hours") {
                    ValueRow(
                        Strings.settingsScheduleEvery(),
                        Strings.settingsScheduleHours(hours),
                    ) {
                        onDialog(EditorDialog.Hours)
                    }
                }
                item(key = "start") {
                    ValueRow(Strings.settingsScheduleStartDate(), dateText(date)) {
                        onDialog(EditorDialog.Date)
                    }
                }
            }
            RuleKind.Daily -> Unit
        }
        item(key = "clock") {
            ValueRow(
                if (kind == RuleKind.Interval) Strings.settingsScheduleStartTime()
                else Strings.settingsScheduleTime(),
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
        title = Strings.settingsScheduleRunGroup(),
        footer = Strings.settingsScheduleRunFooter(),
    ) {
        item {
            BasicComponent(
                title = Strings.commonWorkingDir(),
                summary = cwd.ifBlank { Strings.settingsScheduleDefaultCwd(defaultCwd) },
                onClick = onPickCwd,
            )
        }
        item { ModelPreference(hub, backend, model, onModel) }
        item { PermissionModePreference(mode, onMode) }
        item {
            SwitchPreference(
                title = Strings.settingsScheduleNewSession(),
                summary =
                    if (newSession) Strings.settingsScheduleNewSessionOn()
                    else Strings.settingsScheduleNewSessionOff(),
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
    PreferenceGroup(title = Strings.commonRunHistory()) {
        original.lastRun?.let { run ->
            item(key = "last") { LastRunRow(hub, run, now, zone, onOpenConversation) }
        }
        if (original.skips > 0) {
            item(key = "skips") {
                SkipsRow(
                    original.skips,
                    original.lastSkip,
                    Strings.settingsScheduleSkippedBusy(),
                    now,
                    zone,
                )
            }
        }
        item(key = "now") {
            val running =
                original.lastRun?.outcome == RunOutcome.Running ||
                    ScheduleRunner.isRunning(original.id)
            BasicComponent(
                title = Strings.settingsScheduleRunNow(),
                summary = runNowSummary(running, original.onlyOnChange, runMessage),
                titleColor =
                    BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.primary),
                enabled = !running,
                onClick = onRunNow,
            )
        }
    }
}

private fun runNowSummary(running: Boolean, onlyOnChange: Boolean, runMessage: String?): String =
    when {
        running && onlyOnChange -> Strings.settingsScheduleRunningOnChange()
        running -> Strings.settingsScheduleRunningNotify()
        else -> runMessage ?: Strings.settingsScheduleRunNowHint()
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
        summary =
            when {
                granting -> Strings.commonGranting()
                root -> Strings.settingsScheduleFixViaRoot(reason)
                else -> Strings.settingsScheduleFixInSettings(reason)
            },
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
        title = Strings.commonWorkingDir(),
        summary = Strings.settingsScheduleCwdSummary(defaultCwd),
        value = input,
        onValueChange = { input = it },
        label = defaultCwd,
        confirmText = Strings.commonOk(),
        onConfirm = { onConfirm(input.trim()) },
        onDismissRequest = onDismiss,
    )
}

/** 运行记录里的“跳过了 N 次”（没跳过时不显示）。[reason] 是为什么跳过，后面接最近一次跳过的时间。 */
@Composable
internal fun SkipsRow(skips: Int, lastSkip: Long?, reason: String, now: Instant, zone: ZoneId) {
    if (skips <= 0) return
    BasicComponent(
        title = Strings.settingsScheduleSkips(skips),
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
    PreferenceGroup(
        title = Strings.settingsScheduleAlertGroup(),
        footer = Strings.settingsScheduleOnlyOnChangeFooter(),
    ) {
        item {
            SwitchPreference(
                title = Strings.settingsScheduleOnlyOnChange(),
                summary =
                    when {
                        checked && !hub.settings.phoneTools ->
                            Strings.settingsScheduleOnlyOnChangeNoTools()
                        checked -> Strings.settingsScheduleOnlyOnChangeOn()
                        else -> Strings.settingsScheduleOnlyOnChangeOff()
                    },
                checked = checked,
                onCheckedChange = onChange,
            )
        }
    }
}

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
    val title = Strings.settingsScheduleLastRun(outcomeWord(run.outcome))
    val summary =
        Schedules.formatWhen(Instant.ofEpochMilli(run.at), now, zone) +
            (if (run.manual) Strings.settingsScheduleManual() else "") +
            run.summary
                .takeIf {
                    (session == null || run.outcome == RunOutcome.NoChange) && it.isNotBlank()
                }
                ?.let { " · " + it }
                .orEmpty()
    val summaryColor =
        if (run.outcome == RunOutcome.Failed) {
            BasicComponentDefaults.summaryColor(color = MiuixTheme.colorScheme.error)
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

private fun dateText(date: LocalDate): String {
    val today = LocalDate.now()
    val pattern =
        if (date.year == today.year) Strings.settingsScheduleDatePattern()
        else Strings.settingsScheduleDatePatternYear()
    val day =
        Strings.settingsScheduleDateWeekday(
            date.format(
                DateTimeFormatter.ofPattern(pattern, Locale.forLanguageTag(UiLanguage.current)),
            ),
            Schedules.weekdayName(date.dayOfWeek),
        )
    return when (date) {
        today -> Strings.settingsScheduleToday(day)
        today.plusDays(1) -> Strings.settingsScheduleTomorrow(day)
        else -> day
    }
}

/** 一周七天的小圆钮，可以多选。 */
@Composable
private fun WeekdayPicker(days: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    val colors = MiuixTheme.colorScheme
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
                            if (selected) colors.primary else colors.secondaryVariant,
                            CircleShape,
                        )
                        .clickable { onChange(if (selected) days - day else days + day) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = Schedules.dayShort(day),
                    style = MiuixTheme.textStyles.body2,
                    color = if (selected) colors.onPrimary else colors.onBackground,
                )
            }
        }
    }
}

/** 选时间（时、分）的对话框：两列滚轮，各自循环滚动。放在页面的 dialogs 里。 */
@Composable
internal fun TimeDialog(
    visible: Boolean,
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    var hour by remember(visible) { mutableIntStateOf(initial.hour) }
    var minute by remember(visible) { mutableIntStateOf(initial.minute) }
    ConfirmDialog(
        show = visible,
        title = Strings.settingsSchedulePickTime(),
        summary = null,
        confirmText = Strings.commonOk(),
        onConfirm = { onConfirm(LocalTime.of(hour, minute)) },
        onDismissRequest = onDismiss,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WheelColumn(
                value = hour,
                onValueChange = { hour = it },
                range = 0..23,
                unit = Strings.settingsScheduleHourUnit(),
                wrapAround = true,
                label = ::twoDigits,
            )
            WheelColumn(
                value = minute,
                onValueChange = { minute = it },
                range = 0..59,
                unit = Strings.settingsScheduleMinuteUnit(),
                wrapAround = true,
                label = ::twoDigits,
            )
        }
    }
}

private fun twoDigits(value: Int) = value.toString().padStart(2, '0')

/** 选日期的对话框：年、月、日三列滚轮，只能选今天到五年后；说明一行是选中的日期和星期。 */
@Composable
private fun DateDialog(
    visible: Boolean,
    initial: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = LocalDate.now()
    val last = today.plusYears(5)
    var value by remember(visible) { mutableStateOf(initial.coerceIn(today, last)) }
    // 换了年或月，日子超出新的月份（或超出可选的范围）时落到最近的一天
    fun pick(year: Int, month: Int, day: Int) {
        val length = YearMonth.of(year, month).lengthOfMonth()
        value = LocalDate.of(year, month, day.coerceAtMost(length)).coerceIn(today, last)
    }
    val locale = Locale.forLanguageTag(UiLanguage.current)
    val firstMonth = if (value.year == today.year) today.monthValue else 1
    val lastMonth = if (value.year == last.year) last.monthValue else 12
    val firstDay =
        if (value.year == today.year && value.monthValue == today.monthValue) today.dayOfMonth
        else 1
    val lastDay =
        if (value.year == last.year && value.monthValue == last.monthValue) last.dayOfMonth
        else value.lengthOfMonth()
    ConfirmDialog(
        show = visible,
        title = Strings.settingsSchedulePickDate(),
        summary = dateText(value),
        confirmText = Strings.commonOk(),
        onConfirm = { onConfirm(value) },
        onDismissRequest = onDismiss,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WheelColumn(
                value = value.year,
                onValueChange = { pick(it, value.monthValue, value.dayOfMonth) },
                range = today.year..last.year,
            )
            WheelColumn(
                value = value.monthValue,
                onValueChange = { pick(value.year, it, value.dayOfMonth) },
                range = firstMonth..lastMonth,
                label = { Month.of(it).getDisplayName(TextStyle.SHORT, locale) },
            )
            WheelColumn(
                value = value.dayOfMonth,
                onValueChange = { pick(value.year, value.monthValue, it) },
                range = firstDay..lastDay,
            )
        }
    }
}

@Composable
private fun HoursDialog(
    visible: Boolean,
    initial: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by
        remember(visible) {
            mutableIntStateOf(initial.coerceIn(1, ScheduleRule.MAX_INTERVAL_HOURS))
        }
    ConfirmDialog(
        show = visible,
        title = Strings.settingsSchedulePickHours(),
        summary = null,
        confirmText = Strings.commonOk(),
        onConfirm = { onConfirm(value) },
        onDismissRequest = onDismiss,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WheelColumn(
                value = value,
                onValueChange = { value = it },
                range = 1..ScheduleRule.MAX_INTERVAL_HOURS,
                unit = Strings.settingsScheduleHoursUnit(),
            )
        }
    }
}

/** 选择对话框里的一列滚轮（Miuix 的 NumberPicker），[unit] 是选中行右边的单位，几列在一行里平分宽度。 */
@Composable
private fun RowScope.WheelColumn(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    unit: String? = null,
    wrapAround: Boolean = false,
    label: (Int) -> String = { it.toString() },
) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        NumberPicker(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            range = range,
            label = label,
            wrapAround = wrapAround,
        )
        if (unit != null) {
            Text(
                text = unit,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
internal fun FieldBox(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        content()
    }
}
