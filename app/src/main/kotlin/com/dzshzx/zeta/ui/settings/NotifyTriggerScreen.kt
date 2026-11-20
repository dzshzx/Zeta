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
import com.dzshzx.zeta.ui.chat.permissionModeLabel
import com.dzshzx.zeta.ui.components.SlideInPage
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
 * 列表页的说明。没写进去的：跑完的通知是规则名加回答的开头，点它打开那次的对话；
 * 冷却、每天上限、不触发的通知在编辑页“防止失控”下面；上一次还没跑完时跳过这一次（编辑页的“跳过了 N 次”会列出来）。
 */
private const val ListFooter = "选定的应用来了通知，就在后台开一个对话运行指令，跑完发通知。"

/**
 * 指令下面的说明。占位符填进去的内容会标成「通知内容（外部数据）」并用分隔符包住，提醒 Claude 只把它当数据
 * （[NotifyRules.render]，分隔符里的随机串每次运行都不一样，通知里的文字仿造不出来）。
 */
private const val PromptFooter = "可以用 {title}、{text}、{app} 引用通知的标题、正文和应用名；不用时，通知内容不会交给 Claude。"

/** 权限健康页上的“通知使用权”：有开着的规则时缺了算问题，没有规则时只是提示。 */
internal fun notificationListenerItem(snapshot: PermissionSnapshot?, root: Boolean?): PermissionItem {
    val id = PermissionId.NotificationListener
    val title = "通知使用权"
    return when {
        snapshot == null -> PermissionItem(id, title, null, "检查中", PermissionStatus.Checking, false)
        snapshot.notificationListener -> PermissionItem(id, title, "通知触发的任务靠它收通知", "已允许", PermissionStatus.Ready, false)
        NotifyRuleStore.observed.any { it.enabled } -> PermissionItem(
            id, title, "有开着的规则，没它不会触发", "未允许", PermissionStatus.Missing, root == true,
        )
        else -> PermissionItem(id, title, "通知触发要用，现在用不到", "未允许", PermissionStatus.Idle, false)
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
        endActions = { if (rules.isNotEmpty()) PreferenceValue(if (enabled == 0) "都关着" else "$enabled 条开着") },
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
                            hub, PermissionId.NotificationListener, "还没有通知使用权", "规则不会触发。",
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
                    // 摘要一行：哪个应用，再加今天触发几次、上次的结果。关键词和时段在编辑页里看，关没关着看开关
                    val state = if (last?.outcome == RunOutcome.Running) {
                        listOf("正在运行")
                    } else {
                        listOfNotNull(if (fired > 0) "今天触发 $fired 次" else null, last?.let { "上次" + outcomeWord(it.outcome) })
                    }
                    item(key = rule.id) {
                        SwitchWithDividerPreference(
                            title = rule.name,
                            summary = (listOf(rule.appLabel.ifBlank { rule.packageName }) + state).joinToString(" · "),
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
    SlideInPage(visible = editing != null, onBack = { editing = null }) {
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
        // 名称是跑完那条通知的标题，也是每次运行那个对话的名字
        item(key = "name") {
            FieldCard {
                InputView(value = draft.name, onValueChange = { draft = draft.copy(name = it.take(NotifyRules.MAX_NAME)) }, title = "名称", label = "快递取件提醒")
            }
        }
        item(key = "trigger") {
            PreferenceGroup(
                title = "触发条件",
                // 关键词怎么匹配写在关键词对话框里；时段结束早于开始时跨午夜，时段的文字里会注明
                footer = problem?.takeIf { draft.name.isNotBlank() || draft.prompt.isNotBlank() || original != null },
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
                        summary = NotifyRules.parseKeywords(draft.keywords).takeIf { it.isNotEmpty() }?.joinToString("，") ?: "不限，每条通知都触发",
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
                            // 用了 {title}、{text} 时，验证码、银行和支付通知也原样发出去；不想交出去的应用，不要给它设规则
                            BasicComponent(
                                title = "验证码等内容也会交给 Claude",
                                summary = "原样发出，并留在对话记录里",
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
                // 用一条示例通知预览；分隔符里的随机串每次运行都不一样
                PreferenceGroup(title = "发给 Claude 的样子") {
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
                // 每天的次数第二天重新计数；同一条通知只触发一次
                footer = "冷却时间内、或今天已到上限时不再触发。常驻通知（音乐、导航、下载进度）和 Zeta 自己的通知不触发。",
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
                footer = "通知可能被人用来诱导 Claude，所以默认逐项确认：要改文件、执行命令时停下来，发通知等你。每次触发都开一个新对话。",
            ) {
                item(key = "cwd") { BasicComponent(title = "工作目录", summary = draft.cwd.orEmpty().ifBlank { "$defaultCwd（默认）" }, onClick = { dialog = RuleDialog.Cwd }) }
                item(key = "model") { ModelPreference(hub, draft.model) { draft = draft.copy(model = it) } }
                val mode = draft.permissionMode
                item(key = "mode") { PermissionModePreference(mode) { draft = draft.copy(permissionMode = it) } }
                if (mode !in NotifyRules.SAFE_MODES) {
                    // 自动接受编辑只放行改文件；完全放行放行所有操作；自动判断由 Claude Code 决定放行哪些。只给信得过的应用这样设
                    item(key = "risk") {
                        BasicComponent(
                            title = "“${permissionModeLabel(mode)}”有风险",
                            summary = if (mode == "acceptEdits") "可能不经确认就改文件" else "可能不经确认就改文件、执行命令",
                            titleColor = BasicComponentDefaults.titleColor(color = StatusWarning),
                        )
                    }
                }
            }
        }
        item(key = "alert") { OnlyOnChangeGroup(hub, draft.onlyOnChange) { draft = draft.copy(onlyOnChange = it) } }
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
                        item(key = "skips") { SkipsRow(original.skips, original.lastSkip, "上一次还没跑完", Instant.ofEpochMilli(now), zone) }
                    }
                }
            }
            item(key = "delete") {
                PreferenceGroup { item { DangerRow("删除规则") { dialog = RuleDialog.Delete } } }
            }
        }
    }

    val pickerShown = rememberRetained(picking.takeIf { it })
    SlideInPage(visible = picking, onBack = { picking = false }) {
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
                            footer = "只列有桌面图标的应用，别的用“手动输入包名”。",
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
