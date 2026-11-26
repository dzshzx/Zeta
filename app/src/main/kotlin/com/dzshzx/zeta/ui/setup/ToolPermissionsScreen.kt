package com.dzshzx.zeta.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.claude.BulkChange
import com.dzshzx.zeta.core.claude.BulkPair
import com.dzshzx.zeta.core.claude.ClaudeConfigCore
import com.dzshzx.zeta.core.claude.McpNames
import com.dzshzx.zeta.core.claude.McpTool
import com.dzshzx.zeta.core.claude.PermissionBehavior
import com.dzshzx.zeta.core.claude.PermissionGroup
import com.dzshzx.zeta.core.claude.PermissionRule
import com.dzshzx.zeta.core.claude.PermissionsQuery
import com.dzshzx.zeta.core.claude.PermissionsView
import com.dzshzx.zeta.core.claude.ServerView
import com.dzshzx.zeta.core.claude.ToolState
import com.dzshzx.zeta.core.claude.ToolView
import com.dzshzx.zeta.core.clipLine
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.phone.PhoneToolCatalog
import com.dzshzx.zeta.phone.PhoneTools
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.miuix.CompactButton
import com.dzshzx.zeta.ui.components.miuix.ConfirmDialog
import com.dzshzx.zeta.ui.components.miuix.DialogTextField
import com.dzshzx.zeta.ui.components.miuix.FooterHint
import com.dzshzx.zeta.ui.components.miuix.IconActionButton
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroupScope
import com.dzshzx.zeta.ui.components.miuix.PreferenceValue
import com.dzshzx.zeta.ui.components.rememberRetained
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 用临时 HOME 和假的 API 实测出来的语义（ClaudeCodeContractTest 钉住），页面上照这句话告诉用户：拒绝压过询问、询问压过允许，和规则写得细不细无关（比如允许
 * Bash(git push:*) 又对 Bash 设了询问，git push
 * 照样要问）；完全放行（bypassPermissions）下允许规则没有用处，询问和拒绝照样生效：设了询问的会弹出确认，整个工具被拒绝时 Claude 根本拿不到这个工具。
 */
internal val PermissionSemantics: String
    get() = Strings.setupToolPermSemantics()

/** 下拉里的四个选项：没写规则（按权限模式走）、允许、询问、拒绝。 */
private val Choices: List<PermissionBehavior?> =
    listOf(null, PermissionBehavior.Allow, PermissionBehavior.Ask, PermissionBehavior.Deny)
private val ChoiceLabels: List<String>
    get() = listOf(Strings.setupToolPermUnset()) + PermissionBehavior.entries.map(::behaviorLabel)

/** 允许、询问、拒绝在界面上的叫法（核心的 [PermissionBehavior.label] 是固定的中文）。 */
private fun behaviorLabel(b: PermissionBehavior): String =
    when (b) {
        PermissionBehavior.Allow -> Strings.setupToolPermAllow()
        PermissionBehavior.Ask -> Strings.setupToolPermAsk()
        PermissionBehavior.Deny -> Strings.setupToolPermDeny()
    }

/** 页面上改规则时的回调：交给后台线程跑（拿到这一页的查询），跑完拿到核心算好的新页面。 */
private typealias ApplyRules = (ClaudeConfigCore.(PermissionsQuery) -> PermissionsView) -> Unit

/** 一行的摘要，只放一行：有从服务器继承来的设置、带参数的规则时写这些状态，没有时写工具的中文说明。 带参数的规则本身列在页面下面的“规则”里。 */
private fun toolSummary(
    label: String?,
    state: ToolState,
    serverLabel: String = Strings.setupToolPermWholeServer(),
): String? =
    listOfNotNull(
            state.behavior
                ?.takeIf { state.inherited }
                ?.let { Strings.setupToolPermInherited(serverLabel, behaviorLabel(it)) },
            state.specific
                .takeIf { it.isNotEmpty() }
                ?.let { Strings.setupToolPermSpecific(it.size) },
        )
        .joinToString(" · ")
        .ifBlank { label?.ifBlank { null } }

/** 一个工具（或整个服务器）一行：右边下拉选 未设置 / 允许 / 询问 / 拒绝。显示的是它自己的规则；继承来的写在说明里。 */
@Composable
private fun ToolRow(
    title: String,
    summary: String?,
    own: PermissionBehavior?,
    enabled: Boolean,
    onChoose: (PermissionBehavior?) -> Unit,
) {
    OverlayDropdownPreference(
        items = ChoiceLabels,
        selectedIndex = Choices.indexOf(own),
        title = title,
        summary = summary,
        enabled = enabled,
        onSelectedIndexChange = { index ->
            if (index in Choices.indices && Choices[index] != own) onChoose(Choices[index])
        },
    )
}

/**
 * “工具权限”：Claude Code 自己的 permissions 规则（~/.claude/settings.json 的 allow / ask / deny）。 列出内置工具、Zeta
 * 的手机工具、配置里的 MCP 服务器，每个都能整体设成允许、询问、拒绝；带参数的规则和认不出的写法列在“规则”里， 可以改、删、加。每改一处就写回文件，只动那一条规则，别的设置原样保留。
 * 项目里的 .claude/settings.json 也能写规则，这里不显示。手机工具是 Zeta 自己接给 Claude 的 MCP 服务器 phone， 规则里写
 * mcp__phone（整个服务器）或 mcp__phone__工具名；别的 MCP 服务器同样写
 * mcp__服务器名。读、改和每一行的状态都由那台的核心算好（config.permissions.*）。
 */
@Composable
internal fun ToolPermissionsScreen(
    hub: ConversationHub,
    backend: String,
    onBack: () -> Unit,
    cwd: String = hub.defaultCwd(backend),
) {
    val client = rememberConfigClient(hub, backend)
    val scope = rememberCoroutineScope()
    val phoneTools = remember { PhoneToolCatalog.labels }
    // 这一页另要核心算手机工具 phone 整体和它每个工具的状态
    val query =
        remember(cwd, phoneTools) {
            PermissionsQuery(cwd, PhoneTools.SERVER, phoneTools.map { it.first })
        }
    var view by remember { mutableStateOf<PermissionsView?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ConfigNotice?>(null) }
    var reloads by remember { mutableStateOf(0) }
    var showMore by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<RuleDraft?>(null) }
    var deleting by remember { mutableStateOf<PermissionRule?>(null) }
    var detail by remember { mutableStateOf<ServerView?>(null) }
    var bulk by remember { mutableStateOf<BulkRequest?>(null) }

    LaunchedEffect(reloads) {
        scope.runConfig({ client.permissions(query) }) { result ->
            result
                .onSuccess {
                    view = it
                    loadError = null
                }
                .onFailure { loadError = it.problem() }
        }
    }
    val apply: ApplyRules = { block ->
        working = true
        scope.runConfig({ client.block(query) }) { result ->
            working = false
            result
                .onSuccess { view = it }
                .onFailure {
                    notice = ConfigNotice(Strings.setupClaudeChangeFailed(), it.problem())
                    reloads++
                }
        }
    }
    val current = view
    val editable = current != null && loadError == null && !working

    val shownDelete = rememberRetained(deleting)
    MiuixPage(
        title = Strings.setupToolPermTitle(),
        onBack = onBack,
        actions = {
            IconActionButton(
                MiuixIcons.Refresh,
                Strings.setupToolPermReload(),
                enabled = !working,
                onClick = { reloads++ },
            )
            IconActionButton(
                MiuixIcons.Add,
                Strings.setupToolPermAddRule(),
                enabled = editable,
                onClick = { editor = RuleDraft(null, PermissionBehavior.Ask, "") },
            )
        },
        dialogs = {
            ConfigNoticeDialog(notice) { notice = null }
            BulkConfirmDialog(bulk, onDismiss = { bulk = null }) { request ->
                apply { setGroupPermission(it, request.group, request.enable) }
            }
            RuleEditorDialog(
                draft = editor,
                onDismiss = { editor = null },
                onSave = { draft, done ->
                    working = true
                    scope.runConfig({ client.saveRule(query, draft) }) { result ->
                        working = false
                        result
                            .onSuccess {
                                view = it
                                editor = null
                                done(null)
                            }
                            .onFailure {
                                done(it.problem())
                                reloads++
                            }
                    }
                },
            )
            ConfirmDialog(
                show = deleting != null,
                title = Strings.setupToolPermDeleteRule(),
                summary =
                    shownDelete?.let {
                        Strings.setupToolPermDeleteRuleSummary(behaviorLabel(it.behavior), it.rule)
                    },
                confirmText = Strings.setupClaudeDelete(),
                destructive = true,
                onConfirm = {
                    val target = deleting
                    deleting = null
                    target?.let { rule -> apply { removePermissionRule(it, rule) } }
                },
                onDismissRequest = { deleting = null },
            )
        },
    ) {
        // 这页只管规则；默认权限模式在这台主机的页面上，和默认模型、思考强度一起在「新对话默认」里
        item(key = "semantics") {
            FooterHint(
                summary =
                    Strings.setupToolPermIntro(PermissionSemantics) +
                        (loadError?.let { "\n\n" + Strings.setupToolPermLoadFailedDetail(it) }
                            ?: ""),
            )
        }
        item(key = "builtin") {
            BuiltinToolsGroup(
                view = current,
                loadError = loadError,
                editable = editable,
                showMore = showMore,
                onShowMore = { showMore = true },
                apply = apply,
                onBulk = { bulk = it },
            )
        }
        if (showMore && current != null) {
            item(key = "builtin-more") {
                PreferenceGroup(title = Strings.setupToolPermMoreBuiltin()) {
                    toolRows(current.builtin.filter { it.more }, editable, apply)
                }
            }
        }
        item(key = "phone") {
            PhoneToolsGroup(
                phoneToolsOn = hub.settings.phoneTools,
                tools = phoneTools,
                view = current,
                editable = editable,
                apply = apply,
                onBulk = { bulk = it },
            )
        }
        item(key = "mcp") {
            McpServersGroup(current?.servers, loadError, onOpen = { detail = it })
        }
        item(key = "rules") {
            RulesGroup(
                view = current,
                loadError = loadError,
                editable = editable,
                onMove = { rule, b ->
                    apply { replacePermissionRule(it, rule, PermissionRule(b, rule.rule)) }
                },
                onEdit = { rule -> editor = RuleDraft(rule, rule.behavior, rule.rule) },
                onDelete = { rule -> deleting = rule },
            )
        }
    }

    // 在服务器页上改过的，回来重读一遍这一页
    val closeDetail: () -> Unit = {
        detail = null
        reloads++
    }
    SlideInPage(detail, onBack = closeDetail) { server ->
        McpPermissionDetail(
            client = client,
            cwd = cwd,
            server = server,
            onBack = closeDetail,
        )
    }
}

/** 新加一条规则，或把 [RuleDraft.original] 换成草稿里的那条。 */
private fun ClaudeConfigCore.saveRule(query: PermissionsQuery, draft: RuleDraft): PermissionsView {
    val rule = PermissionRule(draft.behavior, draft.rule)
    val original = draft.original
    return if (original == null) addPermissionRule(query, rule)
    else replacePermissionRule(query, original, rule)
}

/** 「内置工具」一组：一键开关、常用的每个一行，收起来的那些没展开（[showMore]）时是最后一行「更多内置工具」。 */
@Composable
private fun BuiltinToolsGroup(
    view: PermissionsView?,
    loadError: String?,
    editable: Boolean,
    showMore: Boolean,
    onShowMore: () -> Unit,
    apply: ApplyRules,
    onBulk: (BulkRequest) -> Unit,
) {
    PreferenceGroup(title = Strings.setupToolPermBuiltin()) {
        if (view == null) {
            item {
                if (loadError == null) ConfigLoadingRow()
                else ConfigEmptyRow(Strings.setupToolPermCantRead())
            }
            return@PreferenceGroup
        }
        item {
            BulkButtons(view.builtinBulk, editable) { enable, change ->
                onBulk(
                    BulkRequest(
                        Strings.setupToolPermBulkBuiltin(),
                        view.builtinGroup,
                        enable,
                        change,
                        warnNoTools = !enable,
                    ),
                )
            }
        }
        toolRows(view.builtin.filter { !it.more }, editable, apply)
        if (!showMore) {
            item(key = "more") {
                BasicComponent(
                    title = Strings.setupToolPermMoreBuiltin(),
                    endActions = {
                        PreferenceValue(
                            Strings.setupToolPermMoreCount(view.builtin.count { it.more }),
                        )
                    },
                    onClick = onShowMore,
                )
            }
        }
    }
}

/** 工具每个一行：它自己的设置，改了就写回规则。 */
private fun PreferenceGroupScope.toolRows(
    tools: List<ToolView>,
    editable: Boolean,
    apply: ApplyRules,
) {
    tools.forEach { tool ->
        item(key = tool.name) {
            ToolRow(tool.name, toolSummary(tool.label, tool.state), tool.own, editable) { b ->
                apply { setToolPermission(it, tool.rule, b) }
            }
        }
    }
}

/** 「手机工具」一组：一键开关、整个 phone 服务器的设置、每个手机工具的设置（状态都是核心按查询里的 phone 算的）。 */
@Composable
private fun PhoneToolsGroup(
    phoneToolsOn: Boolean,
    tools: List<Pair<String, String>>,
    view: PermissionsView?,
    editable: Boolean,
    apply: ApplyRules,
    onBulk: (BulkRequest) -> Unit,
) {
    PreferenceGroup(
        title = Strings.setupToolPermPhone(),
        footer = if (phoneToolsOn) null else Strings.setupToolPermPhoneOff(),
    ) {
        val focus = view?.focus
        item {
            BulkButtons(focus?.bulk, editable) { enable, change ->
                onBulk(
                    BulkRequest(
                        Strings.setupToolPermBulkPhone(),
                        PermissionGroup(server = PhoneTools.SERVER),
                        enable,
                        change,
                    ),
                )
            }
        }
        item(key = "whole") {
            val rule = focus?.rule ?: McpNames.server(PhoneTools.SERVER)
            ToolRow(Strings.setupToolPermAllPhone(), rule, focus?.state?.behavior, editable) { b ->
                apply { setToolPermission(it, rule, b) }
            }
        }
        val states = view?.serverTools.orEmpty().associateBy { it.name }
        tools.forEach { (name, label) ->
            item(key = name) {
                McpToolRow(
                    name,
                    label,
                    states[name],
                    Strings.setupToolPermAllPhone(),
                    editable,
                    apply,
                )
            }
        }
    }
}

/** MCP 服务器里一个工具一行：显示它自己的设置，从整个服务器继承来的写在说明里。[tool] 是核心算的，还没算出来时为 null（灰着）。 */
@Composable
private fun McpToolRow(
    name: String,
    label: String?,
    tool: ToolView?,
    serverLabel: String,
    editable: Boolean,
    apply: ApplyRules,
) {
    ToolRow(
        name,
        toolSummary(label, tool?.state ?: ToolState(null), serverLabel),
        tool?.own,
        editable && tool != null,
    ) { b ->
        tool?.let { t -> apply { setToolPermission(it, t.rule, b) } }
    }
}

/** 「MCP 服务器」一组：配置里的每个服务器（同名的只列一次）和它整体的设置，点进去按工具设。 */
@Composable
private fun McpServersGroup(
    servers: List<ServerView>?,
    loadError: String?,
    onOpen: (ServerView) -> Unit,
) {
    PreferenceGroup(title = Strings.setupMcpTitle()) {
        when {
            servers == null && loadError == null -> item { ConfigLoadingRow() }
            servers == null -> item { ConfigEmptyRow(Strings.setupToolPermCantRead()) }
            servers.isEmpty() -> item { ConfigEmptyRow(Strings.setupToolPermNoServers()) }
            else ->
                servers.forEach { server ->
                    item(key = server.name) {
                        ArrowPreference(
                            title = server.name,
                            endActions = {
                                PreferenceValue(
                                    server.state.behavior?.let(::behaviorLabel)
                                        ?: Strings.setupToolPermUnset(),
                                )
                            },
                            onClick = { onOpen(server) },
                        )
                    }
                }
        }
    }
}

/** 「规则」一组：全部规则，按拒绝、询问、允许排；认不出的写法也原样列出、原样保留。 */
@Composable
private fun RulesGroup(
    view: PermissionsView?,
    loadError: String?,
    editable: Boolean,
    onMove: (PermissionRule, PermissionBehavior) -> Unit,
    onEdit: (PermissionRule) -> Unit,
    onDelete: (PermissionRule) -> Unit,
) {
    PreferenceGroup(
        title = Strings.setupToolPermRules(view?.rules?.size ?: 0),
        footer = Strings.setupToolPermRulesFooter(),
    ) {
        when {
            view == null && loadError == null -> item { ConfigLoadingRow() }
            view == null -> item { ConfigEmptyRow(Strings.setupToolPermCantRead()) }
            view.rules.isEmpty() ->
                item {
                    ConfigEmptyRow(
                        Strings.setupToolPermNoRules(),
                        Strings.setupClaudeTapPlusToAdd(),
                    )
                }
            else ->
                view.rules.forEach { rule ->
                    item(key = rule.behavior.name + ":" + rule.rule) {
                        RuleRow(
                            rule,
                            malformed = rule in view.malformed,
                            enabled = editable,
                            onMove = { b -> onMove(rule, b) },
                            onEdit = { onEdit(rule) },
                            onDelete = { onDelete(rule) },
                        )
                    }
                }
        }
    }
}

/** 一条规则一行：右边是它在哪张表，点开菜单可以换表、编辑、删除。 */
@Composable
private fun RuleRow(
    rule: PermissionRule,
    malformed: Boolean,
    enabled: Boolean,
    onMove: (PermissionBehavior) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // 右边显示它在哪张表；菜单里换表、编辑、删除
    OverlayDropdownPreference(
        entries =
            listOf(
                DropdownEntry(
                    items =
                        PermissionBehavior.entries.map {
                            DropdownItem(
                                text = behaviorLabel(it),
                                selected = it == rule.behavior,
                                onClick = { if (it != rule.behavior) onMove(it) },
                            )
                        },
                ),
                DropdownEntry(
                    items =
                        listOf(
                            DropdownItem(text = Strings.setupToolPermEditMenu(), onClick = onEdit),
                            DropdownItem(text = Strings.setupClaudeDelete(), onClick = onDelete),
                        ),
                ),
            ),
        title = rule.rule,
        summary = if (malformed) Strings.setupToolPermMalformed() else null,
        enabled = enabled,
        collapseOnSelection = true,
    )
}

/** 添加或编辑一条规则；[original] 为 null 是新加。 */
private data class RuleDraft(
    val original: PermissionRule?,
    val behavior: PermissionBehavior,
    val rule: String,
)

/** 添加、编辑规则的对话框；规则写得对不对由核心查，没写成时它给的原因显示在框里。 */
@Composable
private fun RuleEditorDialog(
    draft: RuleDraft?,
    onDismiss: () -> Unit,
    onSave: (RuleDraft, (String?) -> Unit) -> Unit,
) {
    val shown = rememberRetained(draft)
    var behavior by remember { mutableStateOf(PermissionBehavior.Ask) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(draft) {
        if (draft != null) {
            behavior = draft.behavior
            text = draft.rule
            error = null
            saving = false
        }
    }
    ConfirmDialog(
        show = draft != null,
        title =
            if (shown?.original == null) Strings.setupToolPermAddRule()
            else Strings.setupToolPermEditRule(),
        summary = null,
        confirmText = if (saving) Strings.setupToolPermSaving() else Strings.setupClaudeSave(),
        confirmEnabled = !saving,
        onConfirm = {
            val current = shown
            if (current != null) {
                saving = true
                error = null
                onSave(current.copy(behavior = behavior, rule = text.trim())) { problem ->
                    saving = false
                    error = problem
                }
            }
        },
        onDismissRequest = { if (!saving) onDismiss() },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val behaviors = PermissionBehavior.entries
            TabRowWithContour(
                tabs = behaviors.map(::behaviorLabel),
                selectedTabIndex = behaviors.indexOf(behavior),
                // 分段标签没有不可用的样子：保存中忽略点击
                onTabSelected = { index ->
                    if (!saving) behaviors.getOrNull(index)?.let { behavior = it }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DialogTextField(
                value = text,
                onValueChange = { text = it },
                title = Strings.setupToolPermRule(),
                hint = "Bash(git push:*)",
                enabled = !saving,
            )
            Text(
                text = Strings.setupToolPermRuleHelp(),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
            error?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 一个 MCP 服务器：整体的设置，加上逐个工具的设置（工具清单要连上服务器去取）。这一页自己向核心要它的状态（查询里带上这个服务器和取到的工具），回到上一页时上一页重读。 */
@Composable
private fun McpPermissionDetail(
    client: ClaudeConfigCore,
    cwd: String,
    server: ServerView,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tools by remember(server) { mutableStateOf<List<McpTool>?>(null) }
    var toolsError by remember(server) { mutableStateOf<String?>(null) }
    var loading by remember(server) { mutableStateOf(false) }
    var view by remember(server) { mutableStateOf<PermissionsView?>(null) }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ConfigNotice?>(null) }
    var reloads by remember { mutableStateOf(0) }
    val query = PermissionsQuery(cwd, server.name, tools.orEmpty().map { it.name })

    fun load() {
        loading = true
        toolsError = null
        scope.runConfig({ client.mcpTools(server.scope, server.name, cwd) }) { result ->
            loading = false
            result.onSuccess { tools = it }.onFailure { toolsError = it.problem() }
        }
    }
    // 和 MCP 页一样：项目里的 .mcp.json 可能来自别人的仓库，不自动替它启动命令
    LaunchedEffect(server) { if (server.scope != "project" && !server.oauth) load() }
    // 取到工具清单以后再算一遍，各工具的状态才在里面
    LaunchedEffect(query, reloads) {
        scope.runConfig({ client.permissions(query) }) { result ->
            result
                .onSuccess { view = it }
                .onFailure { notice = ConfigNotice(Strings.setupClaudeLoadFailed(), it.problem()) }
        }
    }
    val apply: ApplyRules = { block ->
        working = true
        scope.runConfig({ client.block(query) }) { result ->
            working = false
            result
                .onSuccess { view = it }
                .onFailure {
                    notice = ConfigNotice(Strings.setupClaudeChangeFailed(), it.problem())
                    reloads++
                }
        }
    }
    val editable = view != null && !working
    var bulk by remember { mutableStateOf<BulkRequest?>(null) }

    MiuixPage(
        title = server.name,
        onBack = onBack,
        actions = {
            IconActionButton(
                MiuixIcons.Refresh,
                Strings.setupMcpRefreshTools(),
                enabled = !loading,
                onClick = ::load,
            )
        },
        dialogs = {
            ConfigNoticeDialog(notice) { notice = null }
            BulkConfirmDialog(bulk, onDismiss = { bulk = null }) { request ->
                apply { setGroupPermission(it, request.group, request.enable) }
            }
        },
    ) {
        item(key = "whole") {
            val focus = view?.focus ?: server
            PreferenceGroup(footer = Strings.setupToolPermServerFooter()) {
                item {
                    BulkButtons(view?.focus?.bulk, editable) { enable, change ->
                        bulk =
                            BulkRequest(
                                Strings.setupToolPermBulkServer(server.name),
                                PermissionGroup(server = server.name),
                                enable,
                                change,
                            )
                    }
                }
                item {
                    ToolRow(
                        Strings.setupToolPermWholeServer(),
                        focus.rule,
                        focus.state.behavior,
                        editable,
                    ) { b ->
                        apply { setToolPermission(it, focus.rule, b) }
                    }
                }
            }
        }
        item(key = "tools") {
            val states = view?.serverTools.orEmpty().associateBy { it.name }
            McpToolsGroup(
                server = server,
                tools = tools,
                loading = loading,
                error = toolsError,
                onLoad = ::load,
            ) { tool ->
                McpToolRow(
                    tool.name,
                    tool.description.clipLine(60),
                    states[tool.name],
                    Strings.setupToolPermWholeServer(),
                    editable,
                    apply,
                )
            }
        }
    }
}

/** 详情里的「工具」一组：连接中、出错、还没取（可以手动取）、空的，或者每个工具一行（[row]）。 */
@Composable
private fun McpToolsGroup(
    server: ServerView,
    tools: List<McpTool>?,
    loading: Boolean,
    error: String?,
    onLoad: () -> Unit,
    row: @Composable (McpTool) -> Unit,
) {
    PreferenceGroup(
        title = tools?.let { Strings.setupClaudeToolsCount(it.size) } ?: Strings.setupClaudeTools(),
        footer = if (server.oauth) Strings.setupToolPermOauthFooter(server.rule) else null,
    ) {
        when {
            loading -> item { ConfigLoadingRow(Strings.setupClaudeConnecting()) }
            error != null ->
                item {
                    BasicComponent(
                        title = Strings.setupClaudeToolsError(),
                        summary = error,
                        onClick = onLoad,
                    )
                }
            tools == null ->
                item {
                    BasicComponent(
                        title = Strings.setupClaudeListTools(),
                        summary =
                            if (server.oauth) Strings.setupClaudeListToolsOauth()
                            else Strings.setupClaudeListToolsHint(),
                        enabled = !server.oauth,
                        onClick = onLoad,
                    )
                }
            tools.isEmpty() -> item { ConfigEmptyRow(Strings.setupClaudeNoTools()) }
            else -> tools.forEach { tool -> item(key = tool.name) { row(tool) } }
        }
    }
}

/** 点了“全部开启 / 全部关闭”、等确认的那一组。[change] 是按页面上的规则算的，确认后核心按文件里最新的内容重算再写。 */
internal data class BulkRequest(
    val label: String,
    val group: PermissionGroup,
    val enable: Boolean,
    val change: BulkChange,
    /** 关的是全部内置工具：确认框里另外说清后果。 */
    val warnNoTools: Boolean = false,
)

/** 一组上面的一对按钮；已经全是那个状态（没什么可加可删）时置灰。[pair] 是核心算的，还没算出来时为 null。 */
@Composable
private fun BulkButtons(
    pair: BulkPair?,
    editable: Boolean,
    onRequest: (Boolean, BulkChange) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CompactButton(
            text = Strings.setupToolPermEnableAll(),
            onClick = { pair?.let { onRequest(true, it.enable) } },
            enabled = editable && pair?.enable?.isEmpty == false,
            modifier = Modifier.weight(1f),
        )
        CompactButton(
            text = Strings.setupToolPermDisableAll(),
            onClick = { pair?.let { onRequest(false, it.disable) } },
            enabled = editable && pair?.disable?.isEmpty == false,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BulkConfirmDialog(
    request: BulkRequest?,
    onDismiss: () -> Unit,
    onConfirm: (BulkRequest) -> Unit,
) {
    val shown = rememberRetained(request)
    ConfirmDialog(
        show = request != null,
        title =
            shown?.let {
                if (it.enable) Strings.setupToolPermEnableAllTitle(it.label)
                else Strings.setupToolPermDisableAllTitle(it.label)
            },
        summary = shown?.let { bulkMessage(it.change, warnNoTools = it.warnNoTools) },
        confirmText =
            if (shown?.enable == false) Strings.setupToolPermDisableAll()
            else Strings.setupToolPermEnableAll(),
        destructive = shown?.enable == false,
        onConfirm = {
            val target = request
            onDismiss()
            if (target != null) onConfirm(target)
        },
        onDismissRequest = onDismiss,
    )
}

/** 确认框里的说明：按表列出会加、会删的规则。关闭内置工具时另外说清后果。什么时候生效页面说明里说过，这里不重复。 */
internal fun bulkMessage(change: BulkChange, warnNoTools: Boolean): String = buildString {
    PermissionBehavior.entries.forEach { b ->
        val rules = change.add.filter { it.behavior == b }.map { it.rule }
        if (rules.isNotEmpty())
            append(
                    Strings.setupToolPermBulkAdd(
                        behaviorLabel(b),
                        rules.size,
                        rules.joinToString(Strings.setupListSeparator()),
                    ),
                )
                .append("\n")
    }
    PermissionBehavior.entries.forEach { b ->
        val rules = change.remove.filter { it.behavior == b }.map { it.rule }
        if (rules.isNotEmpty())
            append(
                    Strings.setupToolPermBulkRemove(
                        behaviorLabel(b),
                        rules.size,
                        rules.joinToString(Strings.setupListSeparator()),
                    ),
                )
                .append("\n")
    }
    if (warnNoTools) append("\n").append(Strings.setupToolPermNoToolsWarning())
}
    .trimEnd()
