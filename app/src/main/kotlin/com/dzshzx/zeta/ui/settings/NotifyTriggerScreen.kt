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
import com.dzshzx.zeta.phone.InstalledApps
import com.dzshzx.zeta.ui.chat.PermissionModes
import com.dzshzx.zeta.ui.chat.permissionModeLabel
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.coui2.CardListSegment
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.CouiPageScaffold
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.SettingsHue
import com.dzshzx.zeta.ui.components.coui2.SettingsIcon
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.components.coui2.TextInputDialog
import com.dzshzx.zeta.ui.components.coui2.couiPageScroll
import com.dzshzx.zeta.ui.components.coui2.IconActionButton
import com.dzshzx.zeta.ui.components.rememberRetained
import com.dzshzx.zeta.ui.setup.ConfigSubPage
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

private const val ListFooter = "某个应用的通知到了（可以再限定关键词和时段），Zeta 就在后台开一个对话，让 Claude 跑你写的指令；" +
    "跑完发一条通知（规则名和回答的开头），点通知打开那次的对话。Zeta 自己的通知不会触发。" +
    "每条规则触发后有一段冷却时间，每天也有次数上限；上一次还没跑完时跳过这一次。"

private const val PromptFooter = "通知到了时发给 Claude。可以用 {title}（通知标题）、{text}（正文）、{app}（应用名）引用这条通知：" +
    "填进去的内容会标成「通知内容（外部数据）」并用分隔符包住，提醒 Claude 只把它当数据。不用这几个占位符时，通知内容不会交给 Claude。"

/** 权限健康页上的“通知使用权”：有开着的规则时缺了算问题，没有规则时只是提示。 */
internal fun notificationListenerItem(snapshot: PermissionSnapshot?, root: Boolean?): PermissionItem {
    val id = PermissionId.NotificationListener
    val title = "通知使用权"
    return when {
        snapshot == null -> PermissionItem(id, title, null, "检查中", PermissionStatus.Checking, false)
        snapshot.notificationListener -> PermissionItem(id, title, "通知触发的任务靠它实时收到别的应用的通知", "已允许", PermissionStatus.Ready, false)
        NotifyRuleStore.observed.any { it.enabled } -> PermissionItem(
            id, title, "有开着的通知触发规则：没有它收不到通知，规则不会触发", "未允许", PermissionStatus.Missing, root == true,
        )
        else -> PermissionItem(id, title, "通知触发的任务要用；现在没有开着的规则，用不到", "未允许", PermissionStatus.Idle, false)
    }
}

/** 设置首页的入口：几条规则开着。 */
@Composable
internal fun NotifyTriggerEntry(onOpen: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { NotifyRuleStore.load(context) } }
    val rules = NotifyRuleStore.observed
    val enabled = rules.count { it.enabled }
    ArrowPreference(
        title = "通知触发",
        startAction = { SettingsIcon(ZetaIcons.Bell, SettingsHue.Automation) },
        endActions = {
            when {
                rules.isEmpty() -> {}
                enabled == 0 -> PreferenceValue("都关着")
                else -> PreferenceValue("$enabled 条开着")
            }
        },
        onClick = onOpen,
    )
}

/** 正在编辑哪条规则；[id] 为 null 是新建。 */
private class RuleTarget(val id: String?)

/** “通知触发”：列出规则、开关；点一条进去编辑、删除。 */
@Composable
internal fun NotifyTriggerScreen(hub: ConversationHub, onBack: () -> Unit, onOpenConversation: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { NotifyRuleStore.load(app) } }
    val rules = NotifyRuleStore.observed
    val snapshot = rememberPermissionSnapshot()
    var editing by remember { mutableStateOf<RuleTarget?>(null) }
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()

    CouiPage(
        title = "通知触发",
        onBack = onBack,
        actions = { IconActionButton(COUIIcons.Add, "新建规则", enabled = rules.size < NotifyRules.MAX_RULES) { editing = RuleTarget(null) } },
    ) {
        if (snapshot != null && !snapshot.notificationListener) {
            item(key = "listener") {
                PreferenceGroup {
                    item {
                        PermissionFixRow(
                            hub, PermissionId.NotificationListener, "还没有通知使用权", "没有它收不到通知，规则不会触发。",
                            if (rules.any { it.enabled }) COUITheme.colorScheme.error else StatusWarning,
                        )
                    }
                }
            }
        }
        item(key = "rules") {
            PreferenceGroup(title = "规则 · ${rules.size}", footer = ListFooter) {
                if (rules.isEmpty()) {
                    item { BasicComponent(title = "还没有规则", summary = "点右上角 + 新建", enabled = false) }
                }
                rules.forEach { rule ->
                    val last = rule.lastRun
                    val fired = NotifyRules.firedToday(rule, now, zone)
                    val state = when {
                        last?.outcome == RunOutcome.Running -> "正在运行"
                        !rule.enabled -> "已关闭"
                        fired > 0 -> "今天触发 $fired 次"
                        else -> "今天还没触发"
                    } + (last?.takeIf { it.outcome != RunOutcome.Running }?.let { " · 上次" + outcomeWord(it.outcome) } ?: "")
                    item(key = rule.id) {
                        SwitchWithDividerPreference(
                            title = rule.name,
                            summary = NotifyRules.describe(rule) + "\n" + state,
                            checked = rule.enabled,
                            onCheckedChange = { on -> NotifyRuleStore.updateRule(app, rule.id) { it.copy(enabled = on) } },
                            onClick = { editing = RuleTarget(rule.id) },
                        )
                    }
                }
            }
        }
    }

    val shown = rememberRetained(editing)
    ConfigSubPage(visible = editing != null, onBack = { editing = null }) {
        shown?.let { target ->
            key(target) {
                RuleEditor(
                    hub = hub,
                    original = target.id?.let { id -> rules.firstOrNull { it.id == id } },
                    onBack = { editing = null },
                    onOpenConversation = onOpenConversation,
                )
            }
        }
    }
}

/** 编辑器里开着的那个对话框。 */
private enum class RuleDialog { Keywords, Start, End, Cwd, Delete }

/** 新建或编辑一条规则；编辑时下面还有运行记录和删除。 */
@Composable
private fun RuleEditor(hub: ConversationHub, original: NotifyRule?, onBack: () -> Unit, onOpenConversation: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val zone = ZoneId.systemDefault()
    var draft by remember { mutableStateOf(original?.let(NotifyRules::draftOf) ?: RuleDraft("", "", "", "", null, "")) }
    // 关掉“只在某个时段触发”时 draft 里没有时段：记着上次的，再打开时还是它
    var lastWindow by remember { mutableStateOf(original?.window ?: TimeWindow(LocalTime.of(8, 0), LocalTime.of(22, 0))) }
    val window = draft.window
    fun setWindow(value: TimeWindow) {
        lastWindow = value
        draft = draft.copy(window = value)
    }
    var dialog by remember { mutableStateOf<RuleDialog?>(null) }
    var picking by remember { mutableStateOf(false) }
    val nonce = remember { NotifyRules.nonce() }

    val problem = NotifyRules.problem(draft)
    val defaultCwd = hub.env?.defaultCwd ?: "/workspace"
    val now = System.currentTimeMillis()

    fun save() {
        if (problem != null) return
        if (original == null) {
            NotifyRuleStore.update(app) { it + NotifyRules.toRule(draft, now) }
        } else {
            NotifyRuleStore.updateRule(app, original.id) { NotifyRules.applyDraft(it, draft) }
        }
        onBack()
    }

    val times = window ?: lastWindow
    var keywordsInput by remember(dialog) { mutableStateOf(draft.keywords) }
    CouiPage(
        title = if (original == null) "新建通知规则" else "编辑通知规则",
        onBack = onBack,
        actions = { ToolbarTextButton(text = "保存", enabled = problem == null, onClick = ::save) },
        overlays = {
            TimeDialog(visible = dialog == RuleDialog.Start, initial = times.start, onConfirm = { setWindow(times.copy(start = it)); dialog = null }, onDismiss = { dialog = null })
            TimeDialog(visible = dialog == RuleDialog.End, initial = times.end, onConfirm = { setWindow(times.copy(end = it)); dialog = null }, onDismiss = { dialog = null })
            TextInputDialog(
                show = dialog == RuleDialog.Keywords,
                title = "关键词",
                summary = "标题或正文里含其中任一个才触发，不分大小写。几个关键词用逗号隔开，留空表示不限。",
                value = keywordsInput,
                onValueChange = { keywordsInput = it },
                label = "取件码，快递",
                confirmText = "确定",
                onConfirm = {
                    draft = draft.copy(keywords = keywordsInput.trim())
                    dialog = null
                },
                onDismissRequest = { dialog = null },
            )
            CwdDialog(
                visible = dialog == RuleDialog.Cwd,
                cwd = draft.cwd.orEmpty(),
                defaultCwd = defaultCwd,
                onConfirm = { draft = draft.copy(cwd = it); dialog = null },
                onDismiss = { dialog = null },
            )
            ConfirmDialog(
                show = dialog == RuleDialog.Delete,
                title = "删除规则",
                summary = original?.let { "删除“${it.name}”？以后这个应用的通知不再触发它。之前运行留下的对话还在。" },
                confirmText = "删除",
                destructive = true,
                onConfirm = {
                    dialog = null
                    original?.let { rule ->
                        NotifyRuleStore.update(app) { list -> list.filterNot { it.id == rule.id } }
                        ScheduleRunner.clearNotification(app, NotifyRecords, rule.id)
                    }
                    onBack()
                },
                onDismissRequest = { dialog = null },
            )
        },
    ) {
        item(key = "name") {
            FieldCard(footer = "跑完那条通知的标题，也是每次运行那个对话的名字。") {
                InputView(value = draft.name, onValueChange = { draft = draft.copy(name = it.take(NotifyRules.MAX_NAME)) }, title = "名称", label = "快递取件提醒")
            }
        }
        item(key = "trigger") {
            PreferenceGroup(
                title = "触发条件",
                footer = problem?.takeIf { draft.name.isNotBlank() || draft.prompt.isNotBlank() || original != null }
                    ?: "关键词不分大小写，标题或正文里含其中任一个就触发；几个关键词用逗号隔开。时段的结束早于开始时跨午夜。",
            ) {
                item(key = "app") {
                    ArrowPreference(
                        title = "应用",
                        summary = draft.packageName.takeIf { it.isNotBlank() && it != draft.appLabel },
                        endActions = { PreferenceValue(draft.appLabel.ifBlank { draft.packageName }.ifBlank { "选择" }) },
                        onClick = { picking = true },
                    )
                }
                item(key = "keywords") {
                    BasicComponent(
                        title = "关键词",
                        summary = NotifyRules.parseKeywords(draft.keywords).takeIf { it.isNotEmpty() }?.joinToString("，") ?: "不限：这个应用的每条通知都触发",
                        onClick = { dialog = RuleDialog.Keywords },
                    )
                }
                item(key = "window") {
                    SwitchPreference(
                        title = "只在某个时段触发",
                        summary = window?.let(NotifyRules::windowText) ?: "全天",
                        checked = window != null,
                        onCheckedChange = { draft = draft.copy(window = if (it) lastWindow else null) },
                    )
                }
                if (window != null) {
                    item(key = "start") { ValueRow("从", "%02d:%02d".format(window.start.hour, window.start.minute)) { dialog = RuleDialog.Start } }
                    item(key = "end") { ValueRow("到", "%02d:%02d".format(window.end.hour, window.end.minute)) { dialog = RuleDialog.End } }
                }
            }
        }
        item(key = "prompt") {
            val risky = NotifyRules.usesPlaceholders(draft.prompt)
            Column {
                FieldCard(footer = if (risky) null else PromptFooter) {
                    InputView(
                        value = draft.prompt,
                        onValueChange = { draft = draft.copy(prompt = it) },
                        title = "指令",
                        label = "比如：看看这条快递通知 {text}，把取件码和地址记进今天的待办",
                        singleLine = false,
                        maxLines = 10,
                    )
                }
                if (risky) {
                    PreferenceGroup(footer = PromptFooter) {
                        item {
                            BasicComponent(
                                title = "验证码等内容也会交给 Claude",
                                summary = "用了 {title}、{text} 时，验证码、银行和支付通知的内容也会原样发给 Claude，并留在对话记录里。不想交出去的应用，不要给它设规则。",
                                titleColor = BasicComponentDefaults.titleColor(color = StatusWarning),
                            )
                        }
                    }
                }
            }
        }
        if (draft.prompt.isNotBlank()) {
            item(key = "preview") {
                val sample = PostedNotification(key = "", postTime = now, packageName = draft.packageName, title = "示例标题", text = "示例正文")
                PreferenceGroup(title = "发给 Claude 的样子", footer = "用一条示例通知预览；每次运行时分隔符里的随机串都不一样，通知里的文字仿造不出来。") {
                    item {
                        FieldBox {
                            Text(
                                text = NotifyRules.render(draft.prompt, sample, draft.appLabel.ifBlank { "示例应用" }, nonce),
                                style = COUITheme.textStyles.bodyM,
                                color = COUITheme.colorScheme.labelSecondary,
                            )
                        }
                    }
                }
            }
        }
        item(key = "limits") {
            PreferenceGroup(
                title = "防止失控",
                footer = "触发一次后，冷却时间内这条规则不再触发；一天里触发到上限也不再触发，第二天重新计数。" +
                    "同一条通知只触发一次；常驻通知（音乐、导航、下载进度）和 Zeta 自己的通知不触发。",
            ) {
                val cooldown = draft.cooldownMinutes
                val cooldowns = (NotifyRules.COOLDOWN_CHOICES + cooldown).distinct().sorted()
                item(key = "cooldown") {
                    OverlayDropdownPreference(
                        title = "冷却时间",
                        items = cooldowns.map(NotifyRules::cooldownText),
                        selectedIndex = cooldowns.indexOf(cooldown),
                        onSelectedIndexChange = { index -> cooldowns.getOrNull(index)?.let { draft = draft.copy(cooldownMinutes = it) } },
                    )
                }
                val dailyLimit = draft.dailyLimit
                val limits = (NotifyRules.DAILY_LIMIT_CHOICES + dailyLimit).distinct().sorted()
                item(key = "limit") {
                    OverlayDropdownPreference(
                        title = "每天最多",
                        items = limits.map { "$it 次" },
                        selectedIndex = limits.indexOf(dailyLimit),
                        onSelectedIndexChange = { index -> limits.getOrNull(index)?.let { draft = draft.copy(dailyLimit = it) } },
                    )
                }
            }
        }
        item(key = "run") {
            PreferenceGroup(
                title = "运行方式",
                footer = "通知是别的应用发来的，可能有人借它的内容诱导 Claude。新规则默认“逐项确认”：要执行命令、改文件时这一轮停下来等你，并发通知让你处理。" +
                    "每次触发都开一个新对话。",
            ) {
                item(key = "cwd") { BasicComponent(title = "工作目录", summary = draft.cwd.orEmpty().ifBlank { "$defaultCwd（默认）" }, onClick = { dialog = RuleDialog.Cwd }) }
                item(key = "model") { ModelPreference(hub, draft.model) { draft = draft.copy(model = it) } }
                // 不提供“跟随当前设置”：当前设置可能是完全放行，通知触发的对话要明确选一个
                val mode = draft.permissionMode
                val modes = PermissionModes + listOfNotNull(mode.takeIf { value -> PermissionModes.none { it.first == value } }?.let { it to permissionModeLabel(it) })
                item(key = "mode") {
                    OverlayDropdownPreference(
                        title = "权限模式",
                        items = modes.map { it.second },
                        selectedIndex = modes.indexOfFirst { it.first == mode },
                        onSelectedIndexChange = { index -> modes.getOrNull(index)?.let { draft = draft.copy(permissionMode = it.first) } },
                    )
                }
                if (mode !in NotifyRules.SAFE_MODES) {
                    item(key = "risk") {
                        BasicComponent(
                            title = "“${permissionModeLabel(mode)}”有风险",
                            summary = when (mode) {
                                "acceptEdits" -> "这个模式自动放行改文件。通知里如果有诱导文字，Claude 可能不经你确认就改文件。"
                                "bypassPermissions" -> "这个模式放行所有操作。通知里如果有诱导文字，Claude 可能不经你确认就改文件、执行命令。"
                                else -> "这个模式由 Claude Code 判断哪些操作自动放行。通知里如果有诱导文字，Claude 可能不经你确认就改文件、执行命令。"
                            } + "只给你信得过的应用这样设。",
                            titleColor = BasicComponentDefaults.titleColor(color = StatusWarning),
                        )
                    }
                }
            }
        }
        if (original != null) {
            item(key = "record") {
                PreferenceGroup(title = "运行记录") {
                    original.lastRun?.let { run -> item(key = "last") { LastRunRow(hub, run, Instant.ofEpochMilli(now), zone, onOpenConversation) } }
                    item(key = "today") {
                        val fired = NotifyRules.firedToday(original, now, zone)
                        val until = NotifyRules.cooldownUntil(original, now)
                        BasicComponent(
                            title = "今天触发 $fired 次",
                            summary = "上限 ${original.dailyLimit} 次" + (until?.let { " · 冷却到 " + Schedules.formatWhen(Instant.ofEpochMilli(it), Instant.ofEpochMilli(now), zone) } ?: ""),
                        )
                    }
                    if (original.skips > 0) {
                        item(key = "skips") { SkipsRow(original.skips, original.lastSkip, "触发时上一次还没跑完", Instant.ofEpochMilli(now), zone) }
                    }
                }
            }
            item(key = "delete") {
                PreferenceGroup { item { DangerRow("删除规则") { dialog = RuleDialog.Delete } } }
            }
        }
    }

    val pickerShown = rememberRetained(picking.takeIf { it })
    ConfigSubPage(visible = picking, onBack = { picking = false }) {
        if (pickerShown != null) {
            AppPicker(
                current = draft.packageName,
                onPick = { pkg, label ->
                    draft = draft.copy(packageName = pkg, appLabel = label)
                    picking = false
                },
                onBack = { picking = false },
            )
        }
    }
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

/**
 * 选应用：通知栏里现在有通知的排前面，下面是全部有桌面图标的应用；别的可以手动输入包名。
 * 顶栏下的搜索框和顶栏联动（COUI 设置首页那样）：点进去后列表换成搜索结果，取消或返回回到列表。
 */
@Composable
private fun AppPicker(current: String, onPick: (packageName: String, label: String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var active by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        val (all, recent) = withContext(Dispatchers.IO) {
            val all = launcherApps(context)
            val labels = all.associate { it.packageName to it.label }
            all to ZetaNotificationListener.activePackages().map { AppEntry(it, labels[it] ?: InstalledApps.label(context, it)) }
        }
        active = recent
        apps = all
    }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    val searchState = rememberTopAppBarSearchState(active = searching)
    // 列表的滚动位置提出来：搜索时列表淡出，回来时还在原处
    val listState = rememberLazyListState()
    fun matches(entry: AppEntry) = entry.label.contains(query.trim(), ignoreCase = true) || entry.packageName.contains(query.trim(), ignoreCase = true)

    @Composable
    fun AppRow(entry: AppEntry) {
        BasicComponent(
            title = entry.label,
            summary = entry.packageName.takeIf { it != entry.label },
            endActions = if (entry.packageName == current) ({ PreferenceValue("当前") }) else null,
            onClick = { onPick(entry.packageName, entry.label) },
        )
    }

    /** 一张卡片里的一串应用，逐行放进列表（全部应用可能有几百个）。[head] 是卡片里排在应用前面的行。 */
    fun LazyListScope.appCard(
        key: String,
        title: String?,
        entries: List<AppEntry>,
        footer: String? = null,
        head: (@Composable () -> Unit)? = null,
    ) {
        val offset = if (head != null) 1 else 0
        val count = entries.size + offset
        if (title != null) item(key = "$key:title") { PreferenceCategory(title = title) }
        if (head != null) item(key = "$key:head") { CardListSegment(0, count, footerBelow = footer != null) { head() } }
        itemsIndexed(entries, key = { _, entry -> "$key:${entry.packageName}" }) { index, entry ->
            CardListSegment(index + offset, count, footerBelow = footer != null) { AppRow(entry) }
        }
        if (footer != null) item(key = "$key:footer") { PagerFooterPreference(summary = footer) }
    }

    val manualRow: @Composable () -> Unit = { BasicComponent(title = "手动输入包名", onClick = { manual = true }) }
    CouiPageScaffold(
        title = "选择应用",
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
                cancelText = "取消",
                label = "搜索应用名或包名",
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
                    val all = apps
                    if (all == null) {
                        item(key = "loading") { PreferenceGroup { item { BasicComponent(title = "正在读取应用列表…", enabled = false) } } }
                    } else {
                        if (active.isNotEmpty()) appCard("recent", "通知栏里现在有通知的", active)
                        appCard(
                            key = "all",
                            title = "全部应用",
                            entries = all.take(400),
                            footer = "只列有桌面图标的应用。没有图标的（比如系统组件）用“手动输入包名”。",
                            head = manualRow,
                        )
                    }
                }
            }
            if (searchState.isResultsVisible) {
                LazyColumn(
                    modifier = Modifier.couiPageScroll(scope).topAppBarSearchResults(searchState),
                    contentPadding = scope.contentPadding,
                ) {
                    val all = apps.orEmpty()
                    if (query.isNotBlank()) {
                        val recent = active.filter(::matches)
                        val rest = all.filter { entry -> matches(entry) && recent.none { it.packageName == entry.packageName } }.take(400)
                        if (recent.isNotEmpty()) appCard("found-recent", "通知栏里现在有通知的", recent)
                        appCard("found-all", "全部应用", rest, head = manualRow)
                    }
                }
            }
            var input by remember(manual) { mutableStateOf("") }
            TextInputDialog(
                show = manual,
                title = "应用的包名",
                summary = "比如 com.tencent.mm。",
                value = input,
                onValueChange = { input = it.trim() },
                label = "com.example.app",
                confirmText = "确定",
                confirmEnabled = input.isNotBlank(),
                onConfirm = {
                    manual = false
                    val pkg = input.trim()
                    onPick(pkg, apps?.firstOrNull { it.packageName == pkg }?.label ?: InstalledApps.label(context, pkg))
                },
                onDismissRequest = { manual = false },
            )
        }
    }
}
