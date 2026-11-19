package com.dzshzx.zeta.ui.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SetupState
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
import com.dzshzx.zeta.ui.chat.PermissionModes
import com.dzshzx.zeta.ui.chat.permissionModeLabel
import com.dzshzx.zeta.ui.components.StatusError
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.coui.CouiConfirmDialog
import com.dzshzx.zeta.ui.components.coui.CouiInputDialog
import com.dzshzx.zeta.ui.components.coui.CouiPopupPreference
import com.dzshzx.zeta.ui.components.coui.CouiPreference
import com.dzshzx.zeta.ui.components.coui.CouiScaffoldPage
import com.dzshzx.zeta.ui.components.coui.CouiSwitchPreference
import com.dzshzx.zeta.ui.components.coui.CouiTextField
import com.dzshzx.zeta.ui.components.coui.CouiTopAppBarIconButton
import com.dzshzx.zeta.ui.components.coui.CouiTopAppBarTextButton
import com.dzshzx.zeta.ui.setup.ConfigSubPage
import com.dzshzx.zeta.ui.setup.rememberRetained
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Group
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.switch.CouiSwitchDefaults
import com.itos.coui.switch.Switch
import com.itos.coui.theme.CouiTheme
import java.text.Collator
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

/** 设置页“能力”里的入口：几条规则开着。 */
@Composable
internal fun NotifyTriggerEntry(onOpen: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { NotifyRuleStore.load(context) } }
    val rules = NotifyRuleStore.observed
    val enabled = rules.count { it.enabled }
    CouiPreference(
        title = "通知触发",
        summary = when {
            rules.isEmpty() -> "某个应用来通知时，让 Claude 在后台做一件事"
            enabled == 0 -> "${rules.size} 条规则，都关着"
            else -> "$enabled 条规则开着"
        },
        onClick = onOpen,
    )
}

/** 正在编辑哪条规则；[id] 为 null 是新建。 */
private class RuleTarget(val id: String?)

/** “通知触发”：列出规则、开关；点一条进去编辑、删除。 */
@Composable
internal fun NotifyTriggerScreen(hub: ConversationHub, onBack: () -> Unit, onOpenConversation: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { NotifyRuleStore.load(app) } }
    val rules = NotifyRuleStore.observed
    val snapshot = rememberPermissionSnapshot()
    var editing by remember { mutableStateOf<RuleTarget?>(null) }
    var granting by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val accent = CouiPageDefaults.themeColors().accent

    CouiScaffoldPage(
        title = "通知触发",
        onBack = onBack,
        actions = {
            CouiTopAppBarIconButton(
                icon = CouiIcons.Outlined.Add,
                contentDescription = "新建规则",
                enabled = rules.size < NotifyRules.MAX_RULES,
                onClick = { editing = RuleTarget(null) },
            )
        },
    ) {
        if (snapshot != null && !snapshot.notificationListener) {
            Group {
                CouiPreference(
                    title = "还没有通知使用权",
                    summary = if (granting) "正在授予…" else "没有它收不到通知，规则不会触发。点这里" + if (hub.setup == SetupState.NoRoot) "到系统设置里允许" else "经 root 允许",
                    titleColor = if (rules.any { it.enabled }) StatusError else StatusWarning,
                    enabled = !granting,
                    onClick = {
                        granting = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                hub.setup != SetupState.NoRoot &&
                                    runCatching { PermissionHealth.fixWithRoot(app, PermissionId.NotificationListener) }.getOrDefault(false)
                            }
                            granting = false
                            if (!ok) PermissionHealth.settingsIntent(app, PermissionId.NotificationListener)?.let { runCatching { context.startActivity(it) } }
                        }
                    },
                )
            }
        }
        Group(title = "规则 · ${rules.size}", footer = ListFooter) {
            if (rules.isEmpty()) {
                CouiPreference(title = "还没有规则", summary = "点右上角 + 新建", enabled = false, showChevron = false)
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
                CouiPreference(
                    title = rule.name,
                    summary = NotifyRules.describe(rule) + "\n" + state,
                    onClick = { editing = RuleTarget(rule.id) },
                    showChevron = false,
                    trailing = {
                        Switch(
                            modifier = Modifier.padding(start = 16.dp),
                            checked = rule.enabled,
                            onCheckedChange = { on -> NotifyRuleStore.updateRule(app, rule.id) { it.copy(enabled = on) } },
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
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var packageName by remember { mutableStateOf(original?.packageName.orEmpty()) }
    var appLabel by remember { mutableStateOf(original?.appLabel.orEmpty()) }
    var keywords by remember { mutableStateOf(original?.keywords?.let(NotifyRules::keywordsText).orEmpty()) }
    var windowOn by remember { mutableStateOf(original?.window != null) }
    var start by remember { mutableStateOf(original?.window?.start ?: LocalTime.of(8, 0)) }
    var end by remember { mutableStateOf(original?.window?.end ?: LocalTime.of(22, 0)) }
    var prompt by remember { mutableStateOf(original?.prompt.orEmpty()) }
    var cwd by remember { mutableStateOf(original?.cwd.orEmpty()) }
    var model by remember { mutableStateOf(original?.model) }
    var mode by remember { mutableStateOf(original?.permissionMode ?: NotifyRules.DEFAULT_MODE) }
    var cooldown by remember { mutableStateOf(original?.cooldownMinutes ?: NotifyRules.DEFAULT_COOLDOWN) }
    var dailyLimit by remember { mutableStateOf(original?.dailyLimit ?: NotifyRules.DEFAULT_DAILY_LIMIT) }
    var dialog by remember { mutableStateOf<RuleDialog?>(null) }
    var picking by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var cooldownMenu by remember { mutableStateOf(false) }
    var limitMenu by remember { mutableStateOf(false) }
    val nonce = remember { NotifyRules.nonce() }

    val window = if (windowOn) TimeWindow(start, end) else null
    val draft = RuleDraft(name, packageName, appLabel, keywords, window, prompt, cwd, model, mode, cooldown, dailyLimit, original?.enabled ?: true)
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

    CouiScaffoldPage(
        title = if (original == null) "新建通知规则" else "编辑通知规则",
        onBack = onBack,
        actions = { CouiTopAppBarTextButton(text = "保存", enabled = problem == null, onClick = ::save) },
    ) {
        Group(title = "名称", footer = "跑完那条通知的标题，也是每次运行那个对话的名字。") {
            FieldBox { CouiTextField(value = name, onValueChange = { name = it.take(NotifyRules.MAX_NAME) }, placeholder = "快递取件提醒") }
        }
        Group(
            title = "触发条件",
            footer = problem?.takeIf { name.isNotBlank() || prompt.isNotBlank() || original != null }
                ?: "关键词不分大小写，标题或正文里含其中任一个就触发；几个关键词用逗号隔开。时段的结束早于开始时跨午夜。",
        ) {
            CouiPreference(
                title = "应用",
                summary = packageName.takeIf { it.isNotBlank() && it != appLabel },
                value = appLabel.ifBlank { packageName }.ifBlank { "选择" },
                onClick = { picking = true },
            )
            CouiPreference(
                title = "关键词",
                summary = NotifyRules.parseKeywords(keywords).takeIf { it.isNotEmpty() }?.joinToString("，") ?: "不限：这个应用的每条通知都触发",
                onClick = { dialog = RuleDialog.Keywords },
            )
            CouiSwitchPreference(
                title = "只在某个时段触发",
                summary = if (windowOn) NotifyRules.windowText(TimeWindow(start, end)) else "全天",
                checked = windowOn,
                onCheckedChange = { windowOn = it },
            )
            if (windowOn) {
                CouiPreference(title = "从", value = "%02d:%02d".format(start.hour, start.minute), onClick = { dialog = RuleDialog.Start })
                CouiPreference(title = "到", value = "%02d:%02d".format(end.hour, end.minute), onClick = { dialog = RuleDialog.End })
            }
        }
        Group(title = "指令", footer = PromptFooter) {
            FieldBox {
                CouiTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    placeholder = "比如：看看这条快递通知 {text}，把取件码和地址记进今天的待办",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 10,
                )
            }
            if (NotifyRules.usesPlaceholders(prompt)) {
                CouiPreference(
                    title = "验证码等内容也会交给 Claude",
                    summary = "用了 {title}、{text} 时，验证码、银行和支付通知的内容也会原样发给 Claude，并留在对话记录里。不想交出去的应用，不要给它设规则。",
                    titleColor = StatusWarning,
                    showChevron = false,
                )
            }
        }
        if (prompt.isNotBlank()) {
            val sample = PostedNotification(key = "", postTime = now, packageName = packageName, title = "示例标题", text = "示例正文")
            Group(title = "发给 Claude 的样子", footer = "用一条示例通知预览；每次运行时分隔符里的随机串都不一样，通知里的文字仿造不出来。") {
                FieldBox {
                    Text(
                        text = NotifyRules.render(prompt, sample, appLabel.ifBlank { "示例应用" }, nonce),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        Group(
            title = "防止失控",
            footer = "触发一次后，冷却时间内这条规则不再触发；一天里触发到上限也不再触发，第二天重新计数。" +
                "同一条通知只触发一次；常驻通知（音乐、导航、下载进度）和 Zeta 自己的通知不触发。",
        ) {
            val cooldowns = (NotifyRules.COOLDOWN_CHOICES + cooldown).distinct().sorted()
            CouiPopupPreference(
                title = "冷却时间",
                value = NotifyRules.cooldownText(cooldown),
                expanded = cooldownMenu,
                onExpandedChange = { cooldownMenu = it },
                items = cooldowns.map { CouiMenuItem(value = it, text = NotifyRules.cooldownText(it), selected = it == cooldown) },
                onItemClick = {
                    cooldownMenu = false
                    cooldown = it.value
                },
            )
            val limits = (NotifyRules.DAILY_LIMIT_CHOICES + dailyLimit).distinct().sorted()
            CouiPopupPreference(
                title = "每天最多",
                value = "$dailyLimit 次",
                expanded = limitMenu,
                onExpandedChange = { limitMenu = it },
                items = limits.map { CouiMenuItem(value = it, text = "$it 次", selected = it == dailyLimit) },
                onItemClick = {
                    limitMenu = false
                    dailyLimit = it.value
                },
            )
        }
        Group(
            title = "运行方式",
            footer = "通知是别的应用发来的，可能有人借它的内容诱导 Claude。新规则默认“逐项确认”：要执行命令、改文件时这一轮停下来等你，并发通知让你处理。" +
                "每次触发都开一个新对话。",
        ) {
            CouiPreference(title = "工作目录", summary = cwd.ifBlank { "$defaultCwd（默认）" }, onClick = { dialog = RuleDialog.Cwd })
            val models = hub.models.map { it.value to (if (it.value == "default") "默认" else it.displayName.ifBlank { it.value }) }.ifEmpty { FallbackModels }
            val chosen = model
            val modelOptions = listOf<Pair<String?, String>>(null to "跟随当前设置") +
                if (chosen == null || models.any { it.first == chosen }) models else models + (chosen to chosen)
            CouiPopupPreference(
                title = "模型",
                value = modelOptions.firstOrNull { it.first == model }?.second ?: model.orEmpty(),
                expanded = modelMenu,
                onExpandedChange = { modelMenu = it },
                items = modelOptions.map { CouiMenuItem(value = it.first, text = it.second, selected = it.first == model) },
                onItemClick = {
                    modelMenu = false
                    model = it.value
                },
            )
            // 不提供“跟随当前设置”：当前设置可能是完全放行，通知触发的对话要明确选一个
            CouiPopupPreference(
                title = "权限模式",
                value = permissionModeLabel(mode),
                expanded = modeMenu,
                onExpandedChange = { modeMenu = it },
                items = PermissionModes.map { CouiMenuItem(value = it.first, text = it.second, selected = it.first == mode) },
                onItemClick = {
                    modeMenu = false
                    mode = it.value
                },
            )
            if (mode !in NotifyRules.SAFE_MODES) {
                CouiPreference(
                    title = "“${permissionModeLabel(mode)}”有风险",
                    summary = when (mode) {
                        "acceptEdits" -> "这个模式自动放行改文件。通知里如果有诱导文字，Claude 可能不经你确认就改文件。"
                        "bypassPermissions" -> "这个模式放行所有操作。通知里如果有诱导文字，Claude 可能不经你确认就改文件、执行命令。"
                        else -> "这个模式由 Claude Code 判断哪些操作自动放行。通知里如果有诱导文字，Claude 可能不经你确认就改文件、执行命令。"
                    } + "只给你信得过的应用这样设。",
                    titleColor = StatusWarning,
                    showChevron = false,
                )
            }
        }
        if (original != null) {
            Group(title = "运行记录") {
                original.lastRun?.let { run -> LastRunRow(hub, run, Instant.ofEpochMilli(now), zone, onOpenConversation) }
                val fired = NotifyRules.firedToday(original, now, zone)
                val until = NotifyRules.cooldownUntil(original, now)
                CouiPreference(
                    title = "今天触发 $fired 次",
                    summary = "上限 ${original.dailyLimit} 次" + (until?.let { " · 冷却到 " + Schedules.formatWhen(Instant.ofEpochMilli(it), Instant.ofEpochMilli(now), zone) } ?: ""),
                    showChevron = false,
                )
                if (original.skips > 0) {
                    CouiPreference(
                        title = "跳过了 ${original.skips} 次",
                        summary = "触发时上一次还没跑完" + (original.lastSkip?.let { "，最近一次在" + Schedules.formatWhen(Instant.ofEpochMilli(it), Instant.ofEpochMilli(now), zone) } ?: ""),
                        showChevron = false,
                    )
                }
            }
            Group {
                CouiPreference(title = "删除规则", titleColor = StatusError, showChevron = false, onClick = { dialog = RuleDialog.Delete })
            }
        }
    }

    val pickerShown = rememberRetained(picking.takeIf { it })
    ConfigSubPage(visible = picking, onBack = { picking = false }) {
        if (pickerShown != null) {
            AppPicker(
                current = packageName,
                onPick = { pkg, label ->
                    packageName = pkg
                    appLabel = label
                    picking = false
                },
                onBack = { picking = false },
            )
        }
    }

    TimeDialog(visible = dialog == RuleDialog.Start, initial = start, onConfirm = { start = it; dialog = null }, onDismiss = { dialog = null })
    TimeDialog(visible = dialog == RuleDialog.End, initial = end, onConfirm = { end = it; dialog = null }, onDismiss = { dialog = null })
    var keywordsInput by remember(dialog) { mutableStateOf(keywords) }
    CouiInputDialog(
        visible = dialog == RuleDialog.Keywords,
        title = "关键词",
        message = "标题或正文里含其中任一个才触发，不分大小写。几个关键词用逗号隔开，留空表示不限。",
        value = keywordsInput,
        onValueChange = { keywordsInput = it },
        placeholder = "取件码，快递",
        confirmText = "确定",
        onConfirm = {
            keywords = keywordsInput.trim()
            dialog = null
        },
        onDismissRequest = { dialog = null },
    )
    var cwdInput by remember(dialog) { mutableStateOf(cwd) }
    CouiInputDialog(
        visible = dialog == RuleDialog.Cwd,
        title = "工作目录",
        message = "Linux 环境里的绝对路径。留空用默认工作目录（现在是 $defaultCwd）。",
        value = cwdInput,
        onValueChange = { cwdInput = it },
        placeholder = defaultCwd,
        confirmText = "确定",
        onConfirm = {
            cwd = cwdInput.trim()
            dialog = null
        },
        onDismissRequest = { dialog = null },
    )
    CouiConfirmDialog(
        visible = dialog == RuleDialog.Delete,
        title = "删除规则",
        message = original?.let { "删除“${it.name}”？以后这个应用的通知不再触发它。之前运行留下的对话还在。" },
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
}

/** 选应用时列出的一个。 */
private class AppEntry(val packageName: String, val label: String)

/** 有桌面图标的应用（Zeta 自己除外），按名字排序。 */
private fun launcherApps(context: Context): List<AppEntry> {
    val manager = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val collator = Collator.getInstance(Locale.CHINA)
    return manager.queryIntentActivities(intent, 0)
        .map { AppEntry(it.activityInfo.packageName, it.loadLabel(manager).toString()) }
        .filterNot { AppVariant.isZetaPackage(it.packageName) }
        .distinctBy { it.packageName }
        .sortedWith { a, b -> collator.compare(a.label, b.label) }
}

private fun appLabel(context: Context, packageName: String): String = runCatching {
    context.packageManager.run { getApplicationLabel(getApplicationInfo(packageName, 0)).toString() }
}.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName

/** 选应用：通知栏里现在有通知的排前面，下面是全部有桌面图标的应用；别的可以手动输入包名。 */
@Composable
private fun AppPicker(current: String, onPick: (packageName: String, label: String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var active by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        val (all, recent) = withContext(Dispatchers.IO) {
            val all = launcherApps(context)
            val labels = all.associate { it.packageName to it.label }
            all to ZetaNotificationListener.activePackages().map { AppEntry(it, labels[it] ?: appLabel(context, it)) }
        }
        active = recent
        apps = all
    }
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    fun matches(entry: AppEntry) = query.isBlank() || entry.label.contains(query.trim(), ignoreCase = true) || entry.packageName.contains(query.trim(), ignoreCase = true)

    @Composable
    fun AppRow(entry: AppEntry) {
        CouiPreference(
            title = entry.label,
            summary = entry.packageName.takeIf { it != entry.label },
            value = if (entry.packageName == current) "当前" else null,
            onClick = { onPick(entry.packageName, entry.label) },
            showChevron = false,
        )
    }

    CouiScaffoldPage(title = "选择应用", onBack = onBack) {
        FieldBox { CouiTextField(value = query, onValueChange = { query = it }, placeholder = "搜索应用名或包名") }
        val all = apps
        if (all == null) {
            Group { CouiPreference(title = "正在读取应用列表…", enabled = false, showChevron = false) }
        } else {
            val recent = active.filter(::matches)
            if (recent.isNotEmpty()) {
                Group(title = "通知栏里现在有通知的") { recent.forEach { AppRow(it) } }
            }
            Group(title = "全部应用", footer = "只列有桌面图标的应用。没有图标的（比如系统组件）用“手动输入包名”。") {
                CouiPreference(title = "手动输入包名", onClick = { manual = true })
                all.filter(::matches).take(400).forEach { AppRow(it) }
            }
        }
    }

    var input by remember(manual) { mutableStateOf("") }
    CouiInputDialog(
        visible = manual,
        title = "应用的包名",
        message = "比如 com.tencent.mm。",
        value = input,
        onValueChange = { input = it.trim() },
        placeholder = "com.example.app",
        confirmText = "确定",
        confirmEnabled = input.isNotBlank(),
        onConfirm = {
            manual = false
            val pkg = input.trim()
            onPick(pkg, apps?.firstOrNull { it.packageName == pkg }?.label ?: appLabel(context, pkg))
        },
        onDismissRequest = { manual = false },
    )
}
