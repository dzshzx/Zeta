package com.dzshzx.zeta.ui.settings

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.notify.NotifyRecords
import com.dzshzx.zeta.core.notify.NotifyRule
import com.dzshzx.zeta.core.notify.NotifyRuleStore
import com.dzshzx.zeta.core.notify.NotifyRules
import com.dzshzx.zeta.core.notify.PostedNotification
import com.dzshzx.zeta.core.notify.RuleDraft
import com.dzshzx.zeta.core.notify.TimeWindow
import com.dzshzx.zeta.core.notify.ZetaNotificationListener
import com.dzshzx.zeta.core.schedule.RunOutcome
import com.dzshzx.zeta.core.schedule.ScheduleRunner
import com.dzshzx.zeta.core.schedule.Schedules
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.phone.InstalledApps
import com.dzshzx.zeta.ui.chat.permissionModeLabel
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.coui2.CardListSegment
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.CouiPageScaffold
import com.dzshzx.zeta.ui.components.coui2.IconActionButton
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.SettingsHue
import com.dzshzx.zeta.ui.components.coui2.SettingsIcon
import com.dzshzx.zeta.ui.components.coui2.TextInputDialog
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.components.coui2.couiPageScroll
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.InputView
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.ToolbarTextButton
import io.github.suqi8.coui.kmp.basic.TopAppBarSearchBar
import io.github.suqi8.coui.kmp.basic.rememberTopAppBarSearchState
import io.github.suqi8.coui.kmp.basic.topAppBarSearchContent
import io.github.suqi8.coui.kmp.basic.topAppBarSearchResults
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference
import io.github.suqi8.coui.kmp.preference.PagerFooterPreference
import io.github.suqi8.coui.kmp.preference.PreferenceCategory
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.preference.SwitchWithDividerPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.text.Collator
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 列表页的说明。没写进去的：跑完的通知是规则名加回答的开头，点它打开那次的对话； 冷却、每天上限、不触发的通知在编辑页“防止失控”下面；上一次还没跑完时跳过这一次（编辑页的“跳过了 N
 * 次”会列出来）。
 */
private fun listFooter() = Strings.notifyListFooter()

/**
 * 指令下面的说明。占位符填进去的内容会标成「通知内容（外部数据）」并用分隔符包住，提醒 Claude 只把它当数据
 * （[NotifyRules.render]，分隔符里的随机串每次运行都不一样，通知里的文字仿造不出来）。
 */
private fun promptFooter() = Strings.notifyPromptFooter("{title}", "{text}", "{app}")

/** 权限健康页上的“通知使用权”：有开着的规则时缺了算问题，没有规则时只是提示。 */
internal fun notificationListenerItem(
    snapshot: PermissionSnapshot?,
    root: Boolean?,
): PermissionItem {
    val id = PermissionId.NotificationListener
    val title = Strings.notifyListenerTitle()
    return when {
        snapshot == null ->
            PermissionItem(
                id,
                title,
                null,
                Strings.commonChecking(),
                PermissionStatus.Checking,
                false,
            )
        snapshot.notificationListener ->
            PermissionItem(
                id,
                title,
                Strings.notifyListenerReady(),
                Strings.commonAllowed(),
                PermissionStatus.Ready,
                false,
            )
        NotifyRuleStore.observed.any { it.enabled } ->
            PermissionItem(
                id,
                title,
                Strings.notifyListenerNeeded(),
                Strings.commonNotAllowed(),
                PermissionStatus.Missing,
                root == true,
            )
        else ->
            PermissionItem(
                id,
                title,
                Strings.notifyListenerIdle(),
                Strings.commonNotAllowed(),
                PermissionStatus.Idle,
                false,
            )
    }
}

/** 设备页上的入口：主机 [backend] 上的规则几条开着。 */
@Composable
internal fun NotifyTriggerEntry(backend: String, onOpen: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { NotifyRuleStore.load(context) } }
    val rules = NotifyRuleStore.observed.filter { it.backend == backend }
    val enabled = rules.count { it.enabled }
    ArrowPreference(
        title = Strings.settingsTitleNotifyTriggers(),
        startAction = { SettingsIcon(ZetaIcons.Bell, SettingsHue.Automation) },
        endActions = {
            if (rules.isNotEmpty())
                PreferenceValue(
                    if (enabled == 0) Strings.settingsScheduleAllOff()
                    else Strings.notifyOnCount(enabled),
                )
        },
        onClick = onOpen,
    )
}

/** 正在编辑哪条规则；[id] 为 null 是新建。 */
private class RuleTarget(val id: String?)

/** 主机 [backend] 上的“通知触发”：列出规则、开关；点一条进去编辑、删除。通知都在手机上收，命中后在那台上起对话。 */
@Composable
internal fun NotifyTriggerScreen(
    hub: ConversationHub,
    backend: String,
    onBack: () -> Unit,
    onOpenConversation: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { NotifyRuleStore.load(app) } }
    // 上限按全部设备的规则一起算
    val total = NotifyRuleStore.observed.size
    val rules = NotifyRuleStore.observed.filter { it.backend == backend }
    val snapshot = rememberPermissionSnapshot()
    var editing by remember { mutableStateOf<RuleTarget?>(null) }
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()

    CouiPage(
        title = Strings.settingsTitleNotifyTriggers(),
        onBack = onBack,
        actions = {
            IconActionButton(
                COUIIcons.Add,
                Strings.notifyNewRule(),
                enabled = total < NotifyRules.MAX_RULES,
            ) {
                editing = RuleTarget(null)
            }
        },
    ) {
        if (snapshot != null && !snapshot.notificationListener) {
            item(key = "listener") { ListenerMissing(hub, rules) }
        }
        item(key = "rules") {
            RulesGroup(rules, now, zone, app) { rule -> editing = RuleTarget(rule.id) }
        }
    }

    SlideInPage(editing, onBack = { editing = null }) { target ->
        key(target) {
            RuleEditor(
                hub = hub,
                backend = backend,
                original = target.id?.let { id -> rules.firstOrNull { it.id == id } },
                onBack = { editing = null },
                onOpenConversation = onOpenConversation,
            )
        }
    }
}

/** 还没有通知使用权：规则不会触发（有开着的规则时标红）。 */
@Composable
private fun ListenerMissing(hub: ConversationHub, rules: List<NotifyRule>) {
    PreferenceGroup {
        item {
            PermissionFixRow(
                hub,
                PermissionId.NotificationListener,
                Strings.notifyListenerMissing(),
                Strings.notifyListenerMissingSummary(),
                if (rules.any { it.enabled }) COUITheme.colorScheme.error else StatusWarning,
            )
        }
    }
}

/** 规则一览：每条一行开关，点进去编辑。 */
@Composable
private fun RulesGroup(
    rules: List<NotifyRule>,
    now: Long,
    zone: ZoneId,
    app: Application,
    onEdit: (NotifyRule) -> Unit,
) {
    PreferenceGroup(title = Strings.notifyRulesHeader(rules.size), footer = listFooter()) {
        if (rules.isEmpty()) {
            item {
                BasicComponent(
                    title = Strings.notifyNoRules(),
                    summary = Strings.settingsScheduleEmptyHint(),
                    enabled = false,
                )
            }
        }
        rules.forEach { rule ->
            val summary = ruleSummary(rule, now, zone)
            item(key = rule.id) {
                SwitchWithDividerPreference(
                    title = rule.name,
                    summary = summary,
                    checked = rule.enabled,
                    onCheckedChange = { on ->
                        NotifyRuleStore.updateRule(app, rule.id) { it.copy(enabled = on) }
                    },
                    onClick = { onEdit(rule) },
                )
            }
        }
    }
}

/** 规则的摘要一行：哪个应用，再加今天触发几次、上次的结果。关键词和时段在编辑页里看，关没关着看开关。 */
private fun ruleSummary(rule: NotifyRule, now: Long, zone: ZoneId): String {
    val last = rule.lastRun
    val fired = NotifyRules.firedToday(rule, now, zone)
    val state =
        if (last?.outcome == RunOutcome.Running) {
            listOf(Strings.settingsScheduleRunning())
        } else {
            listOfNotNull(
                if (fired > 0) Strings.notifyFiredToday(fired) else null,
                last?.let { Strings.settingsScheduleLastOutcome(outcomeWord(it.outcome)) },
            )
        }
    return (listOf(rule.appLabel.ifBlank { rule.packageName }) + state).joinToString(" · ")
}

/** 编辑器里开着的那个对话框。 */
private enum class RuleDialog {
    Keywords,
    Start,
    End,
    Cwd,
    Delete,
}

/** 新建或编辑一条规则；编辑时下面还有运行记录和删除。 */
@Composable
private fun RuleEditor(
    hub: ConversationHub,
    backend: String,
    original: NotifyRule?,
    onBack: () -> Unit,
    onOpenConversation: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val zone = ZoneId.systemDefault()
    var draft by remember {
        mutableStateOf(original?.let(NotifyRules::draftOf) ?: RuleDraft("", "", "", "", null, ""))
    }
    val update: (RuleDraft.() -> RuleDraft) -> Unit = { change -> draft = draft.change() }
    // 关掉“只在某个时段触发”时 draft 里没有时段：记着上次的，再打开时还是它
    var lastWindow by remember {
        mutableStateOf(original?.window ?: TimeWindow(LocalTime.of(8, 0), LocalTime.of(22, 0)))
    }
    fun setWindow(value: TimeWindow) {
        lastWindow = value
        draft = draft.copy(window = value)
    }
    var dialog by remember { mutableStateOf<RuleDialog?>(null) }
    var picking by remember { mutableStateOf(false) }
    val nonce = remember { NotifyRules.nonce() }

    val problem = NotifyRules.problem(draft)
    val defaultCwd = hub.defaultCwd(backend)
    val now = System.currentTimeMillis()

    fun save() {
        if (problem != null) return
        saveRule(app, backend, original, draft, now)
        onBack()
    }

    CouiPage(
        title = if (original == null) Strings.notifyNewTitle() else Strings.notifyEditTitle(),
        onBack = onBack,
        actions = {
            ToolbarTextButton(
                text = Strings.commonSave(),
                enabled = problem == null,
                onClick = ::save,
            )
        },
        dialogs = {
            RuleDialogs(
                dialog = dialog,
                draft = draft,
                times = draft.window ?: lastWindow,
                defaultCwd = defaultCwd,
                original = original,
                update = update,
                onWindow = ::setWindow,
                onClose = { dialog = null },
                onDelete = {
                    dialog = null
                    original?.let { deleteRule(app, it) }
                    onBack()
                },
            )
        },
    ) {
        // 名称是跑完那条通知的标题，也是每次运行那个对话的名字
        item(key = "name") {
            FieldCard {
                InputView(
                    value = draft.name,
                    onValueChange = { text ->
                        update { copy(name = text.take(NotifyRules.MAX_NAME)) }
                    },
                    title = Strings.settingsScheduleName(),
                    label = Strings.notifyNameExample(),
                )
            }
        }
        item(key = "trigger") {
            TriggerGroup(
                draft = draft,
                // 关键词怎么匹配写在关键词对话框里；时段结束早于开始时跨午夜，时段的文字里会注明
                footer = problem?.takeIf { draftStarted(draft) || original != null },
                lastWindow = lastWindow,
                update = update,
                onPickApp = { picking = true },
                onDialog = { dialog = it },
            )
        }
        item(key = "prompt") {
            RulePromptField(draft.prompt) { text -> update { copy(prompt = text) } }
        }
        if (draft.prompt.isNotBlank()) {
            item(key = "preview") { RulePromptPreview(draft, now, nonce) }
        }
        item(key = "limits") { RuleLimitsGroup(draft, update) }
        item(key = "run") {
            RuleRunGroup(hub, backend, draft, defaultCwd, update) { dialog = RuleDialog.Cwd }
        }
        item(key = "alert") {
            OnlyOnChangeGroup(hub, draft.onlyOnChange) { on -> update { copy(onlyOnChange = on) } }
        }
        if (original != null) {
            item(key = "record") { RuleRecordGroup(hub, original, now, zone, onOpenConversation) }
            item(key = "delete") {
                PreferenceGroup {
                    item { DangerRow(Strings.notifyDeleteRule()) { dialog = RuleDialog.Delete } }
                }
            }
        }
    }

    SlideInPage(visible = picking, onBack = { picking = false }) {
        AppPicker(
            current = draft.packageName,
            onPick = { pkg, label ->
                update { copy(packageName = pkg, appLabel = label) }
                picking = false
            },
            onBack = { picking = false },
        )
    }
}

/** 名称或指令填过了：这时才在触发条件下面说哪里还不对。 */
private fun draftStarted(draft: RuleDraft): Boolean =
    draft.name.isNotBlank() || draft.prompt.isNotBlank()

/** 存下规则：新建的加进去，编辑的照草稿改。 */
private fun saveRule(
    app: Application,
    backend: String,
    original: NotifyRule?,
    draft: RuleDraft,
    now: Long,
) {
    if (original == null) {
        NotifyRuleStore.update(app) { it + NotifyRules.toRule(draft, now, backend = backend) }
    } else {
        NotifyRuleStore.updateRule(app, original.id) { NotifyRules.applyDraft(it, draft) }
    }
}

/** 删掉规则和它留在通知栏里的通知；之前运行留下的对话还在。 */
private fun deleteRule(app: Application, rule: NotifyRule) {
    NotifyRuleStore.update(app) { list -> list.filterNot { it.id == rule.id } }
    ScheduleRunner.clearNotification(app, NotifyRecords, rule.id)
}

/** 编辑器的对话框：时段的起止、关键词、工作目录和删除确认。 */
@Composable
private fun RuleDialogs(
    dialog: RuleDialog?,
    draft: RuleDraft,
    times: TimeWindow,
    defaultCwd: String,
    original: NotifyRule?,
    update: (RuleDraft.() -> RuleDraft) -> Unit,
    onWindow: (TimeWindow) -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    var keywordsInput by remember(dialog) { mutableStateOf(draft.keywords) }
    TimeDialog(
        visible = dialog == RuleDialog.Start,
        initial = times.start,
        onConfirm = {
            onWindow(times.copy(start = it))
            onClose()
        },
        onDismiss = onClose,
    )
    TimeDialog(
        visible = dialog == RuleDialog.End,
        initial = times.end,
        onConfirm = {
            onWindow(times.copy(end = it))
            onClose()
        },
        onDismiss = onClose,
    )
    TextInputDialog(
        show = dialog == RuleDialog.Keywords,
        title = Strings.notifyKeywords(),
        summary = Strings.notifyKeywordsHelp(),
        value = keywordsInput,
        onValueChange = { keywordsInput = it },
        label = Strings.notifyKeywordsExample(),
        confirmText = Strings.commonOk(),
        onConfirm = {
            update { copy(keywords = keywordsInput.trim()) }
            onClose()
        },
        onDismissRequest = onClose,
    )
    CwdDialog(
        visible = dialog == RuleDialog.Cwd,
        cwd = draft.cwd.orEmpty(),
        defaultCwd = defaultCwd,
        onConfirm = { cwd ->
            update { copy(cwd = cwd) }
            onClose()
        },
        onDismiss = onClose,
    )
    ConfirmDialog(
        show = dialog == RuleDialog.Delete,
        title = Strings.notifyDeleteRule(),
        summary = original?.let { Strings.notifyDeleteConfirm(it.name) },
        confirmText = Strings.commonDelete(),
        destructive = true,
        onConfirm = onDelete,
        onDismissRequest = onClose,
    )
}

/** 触发条件：哪个应用、关键词，和只在某个时段触发（打开时下面是起止时间）。 */
@Composable
private fun TriggerGroup(
    draft: RuleDraft,
    footer: String?,
    lastWindow: TimeWindow,
    update: (RuleDraft.() -> RuleDraft) -> Unit,
    onPickApp: () -> Unit,
    onDialog: (RuleDialog) -> Unit,
) {
    val window = draft.window
    PreferenceGroup(title = Strings.notifyTrigger(), footer = footer) {
        item(key = "app") {
            ArrowPreference(
                title = Strings.notifyApp(),
                summary = draft.packageName.takeIf { it.isNotBlank() && it != draft.appLabel },
                endActions = {
                    PreferenceValue(
                        draft.appLabel
                            .ifBlank { draft.packageName }
                            .ifBlank { Strings.notifyChoose() },
                    )
                },
                onClick = onPickApp,
            )
        }
        item(key = "keywords") {
            BasicComponent(
                title = Strings.notifyKeywords(),
                summary = keywordsSummary(draft.keywords),
                onClick = { onDialog(RuleDialog.Keywords) },
            )
        }
        item(key = "window") {
            SwitchPreference(
                title = Strings.notifyOnlyWindow(),
                summary = window?.let(NotifyRules::windowText) ?: Strings.notifyAllDay(),
                checked = window != null,
                onCheckedChange = { on -> update { copy(window = lastWindow.takeIf { on }) } },
            )
        }
        if (window != null) {
            item(key = "start") {
                ValueRow(Strings.notifyFrom(), clockText(window.start)) {
                    onDialog(RuleDialog.Start)
                }
            }
            item(key = "end") {
                ValueRow(Strings.notifyTo(), clockText(window.end)) { onDialog(RuleDialog.End) }
            }
        }
    }
}

private fun keywordsSummary(keywords: String): String =
    NotifyRules.parseKeywords(keywords)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(Strings.notifyKeywordSeparator()) ?: Strings.notifyKeywordsAny()

private fun clockText(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

/** 指令输入框；用了 {title}、{text} 时下面提醒验证码等内容也会原样交给 Claude。 */
@Composable
private fun RulePromptField(prompt: String, onChange: (String) -> Unit) {
    val risky = NotifyRules.usesPlaceholders(prompt)
    Column {
        FieldCard(footer = if (risky) null else promptFooter()) {
            InputView(
                value = prompt,
                onValueChange = onChange,
                title = Strings.settingsSchedulePrompt(),
                label = Strings.notifyPromptExample("{text}"),
                singleLine = false,
                maxLines = 10,
            )
        }
        if (risky) {
            PreferenceGroup(footer = promptFooter()) {
                item {
                    // 用了 {title}、{text} 时，验证码、银行和支付通知也原样发出去；不想交出去的应用，不要给它设规则
                    BasicComponent(
                        title = Strings.notifyRiskyTitle(),
                        summary = Strings.notifyRiskySummary(),
                        titleColor = BasicComponentDefaults.titleColor(color = StatusWarning),
                    )
                }
            }
        }
    }
}

/** 用一条示例通知预览发给 Claude 的样子；分隔符里的随机串每次运行都不一样。 */
@Composable
private fun RulePromptPreview(draft: RuleDraft, now: Long, nonce: String) {
    val sample =
        PostedNotification(
            key = "",
            postTime = now,
            packageName = draft.packageName,
            title = Strings.notifySampleTitle(),
            text = Strings.notifySampleText(),
        )
    PreferenceGroup(title = Strings.notifyPreviewHeader()) {
        item {
            FieldBox {
                Text(
                    text =
                        NotifyRules.render(
                            draft.prompt,
                            sample,
                            draft.appLabel.ifBlank { Strings.notifySampleApp() },
                            nonce,
                        ),
                    style = COUITheme.textStyles.bodyM,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
        }
    }
}

/** 防止失控：冷却时间和每天最多几次（每天的次数第二天重新计数；同一条通知只触发一次）。 */
@Composable
private fun RuleLimitsGroup(draft: RuleDraft, update: (RuleDraft.() -> RuleDraft) -> Unit) {
    PreferenceGroup(
        title = Strings.notifyLimits(),
        footer = Strings.notifyLimitsFooter(),
    ) {
        val cooldown = draft.cooldownMinutes
        val cooldowns = (NotifyRules.COOLDOWN_CHOICES + cooldown).distinct().sorted()
        item(key = "cooldown") {
            OverlayDropdownPreference(
                title = Strings.notifyCooldown(),
                items = cooldowns.map(NotifyRules::cooldownText),
                selectedIndex = cooldowns.indexOf(cooldown),
                onSelectedIndexChange = { index ->
                    cooldowns.getOrNull(index)?.let { minutes ->
                        update { copy(cooldownMinutes = minutes) }
                    }
                },
            )
        }
        val dailyLimit = draft.dailyLimit
        val limits = (NotifyRules.DAILY_LIMIT_CHOICES + dailyLimit).distinct().sorted()
        item(key = "limit") {
            OverlayDropdownPreference(
                title = Strings.notifyDailyMax(),
                items = limits.map(Strings::notifyTimes),
                selectedIndex = limits.indexOf(dailyLimit),
                onSelectedIndexChange = { index ->
                    limits.getOrNull(index)?.let { limit -> update { copy(dailyLimit = limit) } }
                },
            )
        }
    }
}

/** 运行方式：工作目录、模型、权限模式；不在安全模式时说明风险。 */
@Composable
private fun RuleRunGroup(
    hub: ConversationHub,
    backend: String,
    draft: RuleDraft,
    defaultCwd: String,
    update: (RuleDraft.() -> RuleDraft) -> Unit,
    onPickCwd: () -> Unit,
) {
    PreferenceGroup(
        title = Strings.settingsScheduleRunGroup(),
        footer = Strings.notifyRunFooter(),
    ) {
        item(key = "cwd") {
            BasicComponent(
                title = Strings.commonWorkingDir(),
                summary =
                    draft.cwd.orEmpty().ifBlank { Strings.settingsScheduleDefaultCwd(defaultCwd) },
                onClick = onPickCwd,
            )
        }
        item(key = "model") {
            ModelPreference(hub, backend, draft.model) { model -> update { copy(model = model) } }
        }
        val mode = draft.permissionMode
        item(key = "mode") {
            PermissionModePreference(mode) { chosen -> update { copy(permissionMode = chosen) } }
        }
        if (mode !in NotifyRules.SAFE_MODES) {
            // 自动接受编辑只放行改文件；完全放行放行所有操作；自动判断由 Claude Code 决定放行哪些。只给信得过的应用这样设
            item(key = "risk") {
                BasicComponent(
                    title = Strings.notifyModeRisk(permissionModeLabel(mode)),
                    summary =
                        if (mode == "acceptEdits") Strings.notifyRiskEdits()
                        else Strings.notifyRiskAll(),
                    titleColor = BasicComponentDefaults.titleColor(color = StatusWarning),
                )
            }
        }
    }
}

/** 运行记录：上一次运行、今天触发几次（和冷却到什么时候），有跳过的说明跳过了几次。 */
@Composable
private fun RuleRecordGroup(
    hub: ConversationHub,
    original: NotifyRule,
    now: Long,
    zone: ZoneId,
    onOpenConversation: () -> Unit,
) {
    PreferenceGroup(title = Strings.commonRunHistory()) {
        original.lastRun?.let { run ->
            item(key = "last") {
                LastRunRow(hub, run, Instant.ofEpochMilli(now), zone, onOpenConversation)
            }
        }
        item(key = "today") {
            val fired = NotifyRules.firedToday(original, now, zone)
            val until = NotifyRules.cooldownUntil(original, now)
            BasicComponent(
                title = Strings.notifyFiredToday(fired),
                summary =
                    listOfNotNull(
                            Strings.notifyDailyLimitValue(original.dailyLimit),
                            cooldownText(until, now, zone),
                        )
                        .joinToString(" · "),
            )
        }
        if (original.skips > 0) {
            item(key = "skips") {
                SkipsRow(
                    original.skips,
                    original.lastSkip,
                    Strings.settingsScheduleSkippedBusy(),
                    Instant.ofEpochMilli(now),
                    zone,
                )
            }
        }
    }
}

/** “冷却到 …”；不在冷却时为 null。 */
private fun cooldownText(until: Long?, now: Long, zone: ZoneId): String? = until?.let {
    Strings.notifyCooldownUntil(
        Schedules.formatWhen(Instant.ofEpochMilli(it), Instant.ofEpochMilli(now), zone),
    )
}

/** 选应用时列出的一个。 */
private class AppEntry(val packageName: String, val label: String)

/** 有桌面图标的应用（Zeta 自己除外），按名字排序。 */
private fun launcherApps(context: Context): List<AppEntry> {
    val collator = Collator.getInstance(Locale.CHINA)
    return InstalledApps.launchable(context)
        .map { AppEntry(it.packageName, it.label) }
        .filterNot { AppVariant.isZetaPackage(it.packageName) }
        .distinctBy { it.packageName }
        .sortedWith { a, b -> collator.compare(a.label, b.label) }
}

/** 选应用：通知栏里现在有通知的排前面，下面是全部有桌面图标的应用；别的可以手动输入包名。 顶栏下的搜索框和顶栏联动（COUI 设置首页那样）：点进去后列表换成搜索结果，取消或返回回到列表。 */
@Composable
private fun AppPicker(
    current: String,
    onPick: (packageName: String, label: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var active by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        val (all, recent) = withContext(Dispatchers.IO) { loadPickerApps(context) }
        active = recent
        apps = all
    }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    val searchState = rememberTopAppBarSearchState(active = searching)
    // 列表的滚动位置提出来：搜索时列表淡出，回来时还在原处
    val listState = rememberLazyListState()
    val cards = AppCards(current, onPick, manualRow = { manual = true })
    CouiPageScaffold(
        title = Strings.notifyPickApp(),
        onBack = onBack,
        topBarBottomContent = {
            TopAppBarSearchBar(
                query = query,
                onQueryChange = { query = it },
                expanded = searching,
                onExpandedChange = {
                    searching = it
                    if (!it) query = ""
                },
                cancelText = Strings.commonCancel(),
                label = Strings.notifySearchApps(),
            )
        },
        topBarSearchState = searchState,
    ) { scope ->
        Box(Modifier.fillMaxSize()) {
            if (searchState.isContentVisible) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.couiPageScroll(scope).topAppBarSearchContent(searchState),
                    contentPadding = scope.contentPadding,
                ) {
                    appListItems(apps, active, cards)
                }
            }
            if (searchState.isResultsVisible) {
                LazyColumn(
                    modifier = Modifier.couiPageScroll(scope).topAppBarSearchResults(searchState),
                    contentPadding = scope.contentPadding,
                ) {
                    appSearchItems(apps.orEmpty(), active, query, cards)
                }
            }
            ManualPackageDialog(
                show = manual,
                apps = apps,
                onPick = { pkg, label ->
                    manual = false
                    onPick(pkg, label)
                },
                onDismiss = { manual = false },
            )
        }
    }
}

/** 有桌面图标的应用，和通知栏里现在有通知的那些（名字取前者的，没有的另查）。 */
private fun loadPickerApps(context: Context): Pair<List<AppEntry>, List<AppEntry>> {
    val all = launcherApps(context)
    val labels = all.associate { it.packageName to it.label }
    return all to
        ZetaNotificationListener.activePackages().map {
            AppEntry(it, labels[it] ?: InstalledApps.label(context, it))
        }
}

/** 应用名或包名里有 [query]（去掉首尾空白，不分大小写）。 */
private fun appMatches(entry: AppEntry, query: String): Boolean =
    entry.label.contains(query.trim(), ignoreCase = true) ||
        entry.packageName.contains(query.trim(), ignoreCase = true)

/** 画应用卡片要的：现在选的是哪个、点了选哪个，和卡片头上“手动输入包名”那一行。 */
private class AppCards(
    val current: String,
    val onPick: (packageName: String, label: String) -> Unit,
    val manualRow: () -> Unit,
)

@Composable
private fun AppRow(entry: AppEntry, cards: AppCards) {
    BasicComponent(
        title = entry.label,
        summary = entry.packageName.takeIf { it != entry.label },
        endActions =
            if (entry.packageName == cards.current) ({ PreferenceValue(Strings.commonCurrent()) })
            else null,
        onClick = { cards.onPick(entry.packageName, entry.label) },
    )
}

@Composable
private fun ManualRow(cards: AppCards) {
    BasicComponent(title = Strings.notifyManualPackage(), onClick = cards.manualRow)
}

/** 一张卡片里的一串应用，逐行放进列表（全部应用可能有几百个）。[manual] 时卡片里排在应用前面的是“手动输入包名”。 */
private fun LazyListScope.appCard(
    key: String,
    title: String?,
    entries: List<AppEntry>,
    cards: AppCards,
    footer: String? = null,
    manual: Boolean = false,
) {
    val offset = if (manual) 1 else 0
    val count = entries.size + offset
    if (title != null) item(key = "$key:title") { PreferenceCategory(title = title) }
    if (manual)
        item(key = "$key:head") {
            CardListSegment(0, count, footerBelow = footer != null) { ManualRow(cards) }
        }
    itemsIndexed(entries, key = { _, entry -> "$key:${entry.packageName}" }) { index, entry ->
        CardListSegment(index + offset, count, footerBelow = footer != null) {
            AppRow(entry, cards)
        }
    }
    if (footer != null) item(key = "$key:footer") { PagerFooterPreference(summary = footer) }
}

/** 平时的列表：通知栏里现在有通知的，然后是全部应用（最多 400 个）；还没读到时说明一行。 */
private fun LazyListScope.appListItems(
    apps: List<AppEntry>?,
    active: List<AppEntry>,
    cards: AppCards,
) {
    if (apps == null) {
        item(key = "loading") {
            PreferenceGroup {
                item { BasicComponent(title = Strings.notifyLoadingApps(), enabled = false) }
            }
        }
        return
    }
    if (active.isNotEmpty()) appCard("recent", Strings.notifyActiveApps(), active, cards)
    appCard(
        key = "all",
        title = Strings.notifyAllApps(),
        entries = apps.take(400),
        cards = cards,
        footer = Strings.notifyAllAppsFooter(),
        manual = true,
    )
}

/** 搜索结果：通知栏里有通知的匹配项在前，其余的（不重复）在后；没输入时是空的。 */
private fun LazyListScope.appSearchItems(
    all: List<AppEntry>,
    active: List<AppEntry>,
    query: String,
    cards: AppCards,
) {
    if (query.isBlank()) return
    val recent = active.filter { appMatches(it, query) }
    val rest =
        all.filter { entry ->
                appMatches(entry, query) && recent.none { it.packageName == entry.packageName }
            }
            .take(400)
    if (recent.isNotEmpty()) appCard("found-recent", Strings.notifyActiveApps(), recent, cards)
    appCard("found-all", Strings.notifyAllApps(), rest, cards, manual = true)
}

/** 手动输入包名；名字从读到的列表里找，找不到另查。 */
@Composable
private fun ManualPackageDialog(
    show: Boolean,
    apps: List<AppEntry>?,
    onPick: (packageName: String, label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var input by remember(show) { mutableStateOf("") }
    TextInputDialog(
        show = show,
        title = Strings.notifyPackageDialog(),
        summary = Strings.notifyPackageExample(),
        value = input,
        onValueChange = { input = it.trim() },
        label = "com.example.app",
        confirmText = Strings.commonOk(),
        confirmEnabled = input.isNotBlank(),
        onConfirm = {
            val pkg = input.trim()
            onPick(
                pkg,
                apps?.firstOrNull { it.packageName == pkg }?.label
                    ?: InstalledApps.label(context, pkg),
            )
        },
        onDismissRequest = onDismiss,
    )
}
