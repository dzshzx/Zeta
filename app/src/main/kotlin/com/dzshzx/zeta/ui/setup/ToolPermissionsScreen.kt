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
import com.dzshzx.zeta.phone.PhoneToolCatalog
import com.dzshzx.zeta.phone.PhoneTools
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.coui2.CompactButton
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.DialogTextField
import com.dzshzx.zeta.ui.components.coui2.IconActionButton
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.rememberRetained
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.CardListGroupScope
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.SegmentButton
import io.github.suqi8.coui.kmp.basic.SegmentButtonDefaults
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference
import io.github.suqi8.coui.kmp.preference.PagerFooterPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 用临时 HOME 和假的 API 实测出来的语义（ClaudeCodeContractTest 钉住），页面上照这句话告诉用户：拒绝压过询问、询问压过允许，和规则写得细不细无关（比如允许
 * Bash(git push:*) 又对 Bash 设了询问，git push
 * 照样要问）；完全放行（bypassPermissions）下允许规则没有用处，询问和拒绝照样生效：设了询问的会弹出确认，整个工具被拒绝时 Claude 根本拿不到这个工具。
 */
internal const val PermissionSemantics = "规则按“拒绝 > 询问 > 允许”取最严的，写得再细也一样。完全放行模式下，询问和拒绝照样生效。"

/** 下拉里的四个选项：没写规则（按权限模式走）、允许、询问、拒绝。 */
private val Choices: List<PermissionBehavior?> =
    listOf(null, PermissionBehavior.Allow, PermissionBehavior.Ask, PermissionBehavior.Deny)
private val ChoiceLabels = listOf("未设置", "允许", "询问", "拒绝")

/** 页面上改规则时的回调：交给后台线程跑（拿到这一页的查询），跑完拿到核心算好的新页面。 */
private typealias ApplyRules = (ClaudeConfigCore.(PermissionsQuery) -> PermissionsView) -> Unit

/** 一行的摘要，只放一行：有从服务器继承来的设置、带参数的规则时写这些状态，没有时写工具的中文说明。 带参数的规则本身列在页面下面的“规则”里。 */
private fun toolSummary(label: String?, state: ToolState, serverLabel: String = "整个服务器"): String? =
    listOfNotNull(
            state.behavior?.takeIf { state.inherited }?.let { "跟随$serverLabel：${it.label}" },
            state.specific.takeIf { it.isNotEmpty() }?.let { "另有 ${it.size} 条带参数的规则" },
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
internal fun ToolPermissionsScreen(hub: ConversationHub, backend: String, onBack: () -> Unit) {
    val client = rememberConfigClient(hub, backend)
    val scope = rememberCoroutineScope()
    val cwd = hub.defaultCwd(backend)
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
                    notice = ConfigNotice("没有改成", it.problem())
                    reloads++
                }
        }
    }
    val current = view
    val editable = current != null && loadError == null && !working

    val shownDelete = rememberRetained(deleting)
    CouiPage(
        title = "工具权限",
        onBack = onBack,
        actions = {
            IconActionButton(COUIIcons.Refresh, "重新读取", enabled = !working, onClick = { reloads++ })
            IconActionButton(
                COUIIcons.Add,
                "添加规则",
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
                title = "删除规则",
                summary = shownDelete?.let { "删除${it.behavior.label}规则 ${it.rule}？" },
                confirmText = "删除",
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
            PagerFooterPreference(
                summary =
                    PermissionSemantics +
                        "改动对所有项目、之后新开始的对话生效。" +
                        (loadError?.let { "\n\n读取失败：$it" } ?: ""),
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
                PreferenceGroup(title = "更多内置工具") {
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
    PreferenceGroup(title = "内置工具") {
        if (view == null) {
            item { if (loadError == null) ConfigLoadingRow() else ConfigEmptyRow("读不出规则") }
            return@PreferenceGroup
        }
        item {
            BulkButtons(view.builtinBulk, editable) { enable, change ->
                onBulk(
                    BulkRequest("内置工具", view.builtinGroup, enable, change, warnNoTools = !enable),
                )
            }
        }
        toolRows(view.builtin.filter { !it.more }, editable, apply)
        if (!showMore) {
            item(key = "more") {
                BasicComponent(
                    title = "更多内置工具",
                    endActions = { PreferenceValue("${view.builtin.count { it.more }} 个") },
                    onClick = onShowMore,
                )
            }
        }
    }
}

/** 工具每个一行：它自己的设置，改了就写回规则。 */
private fun CardListGroupScope.toolRows(
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
        title = "手机工具",
        footer = if (phoneToolsOn) null else "手机操作现在关着，Claude 拿不到这些工具。",
    ) {
        val focus = view?.focus
        item {
            BulkButtons(focus?.bulk, editable) { enable, change ->
                onBulk(
                    BulkRequest(
                        "手机工具",
                        PermissionGroup(server = PhoneTools.SERVER),
                        enable,
                        change,
                    ),
                )
            }
        }
        item(key = "whole") {
            val rule = focus?.rule ?: McpNames.server(PhoneTools.SERVER)
            ToolRow("全部手机工具", rule, focus?.state?.behavior, editable) { b ->
                apply { setToolPermission(it, rule, b) }
            }
        }
        val states = view?.serverTools.orEmpty().associateBy { it.name }
        tools.forEach { (name, label) ->
            item(key = name) {
                McpToolRow(name, label, states[name], "全部手机工具", editable, apply)
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
    PreferenceGroup(title = "MCP 服务器") {
        when {
            servers == null && loadError == null -> item { ConfigLoadingRow() }
            servers == null -> item { ConfigEmptyRow("读不出规则") }
            servers.isEmpty() -> item { ConfigEmptyRow("没有配置 MCP 服务器") }
            else ->
                servers.forEach { server ->
                    item(key = server.name) {
                        ArrowPreference(
                            title = server.name,
                            endActions = {
                                PreferenceValue(server.state.behavior?.label ?: "未设置")
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
        title = "规则 · ${view?.rules?.size ?: 0}",
        footer = "带参数的规则只在这里改，上面的下拉不动它们。",
    ) {
        when {
            view == null && loadError == null -> item { ConfigLoadingRow() }
            view == null -> item { ConfigEmptyRow("读不出规则") }
            view.rules.isEmpty() -> item { ConfigEmptyRow("还没有规则", "点右上角的 + 添加") }
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
                                text = it.label,
                                selected = it == rule.behavior,
                                onClick = { if (it != rule.behavior) onMove(it) },
                            )
                        },
                ),
                DropdownEntry(
                    items =
                        listOf(
                            DropdownItem(text = "编辑…", onClick = onEdit),
                            DropdownItem(text = "删除", onClick = onDelete),
                        ),
                ),
            ),
        title = rule.rule,
        summary = if (malformed) "括号不配对，Claude Code 认不出这条" else null,
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
        title = if (shown?.original == null) "添加规则" else "编辑规则",
        summary = null,
        confirmText = if (saving) "正在保存…" else "保存",
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
            SegmentButton(
                items = behaviors.map { it.label },
                selectedIndex = behaviors.indexOf(behavior),
                onSelectedIndexChange = { index ->
                    behaviors.getOrNull(index)?.let { behavior = it }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving,
                height = SegmentButtonDefaults.HeightSmall,
                padding = SegmentButtonDefaults.PaddingSmall,
            )
            DialogTextField(
                value = text,
                onValueChange = { text = it },
                title = "规则",
                hint = "Bash(git push:*)",
                enabled = !saving,
            )
            Text(
                text =
                    "写成 工具名 或 工具名(参数)。比如 Bash(npm run test:*)、Read(./.env)、Edit(src/**)、WebFetch(domain:github.com)、mcp__github（整个服务器）。",
                style = COUITheme.textStyles.bodyS,
                color = COUITheme.colorScheme.labelSecondary,
            )
            error?.let {
                Text(
                    text = it,
                    style = COUITheme.textStyles.bodyM,
                    color = COUITheme.colorScheme.error,
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
            result.onSuccess { view = it }.onFailure { notice = ConfigNotice("读取失败", it.problem()) }
        }
    }
    val apply: ApplyRules = { block ->
        working = true
        scope.runConfig({ client.block(query) }) { result ->
            working = false
            result
                .onSuccess { view = it }
                .onFailure {
                    notice = ConfigNotice("没有改成", it.problem())
                    reloads++
                }
        }
    }
    val editable = view != null && !working
    var bulk by remember { mutableStateOf<BulkRequest?>(null) }

    CouiPage(
        title = server.name,
        onBack = onBack,
        actions = {
            IconActionButton(COUIIcons.Refresh, "刷新工具", enabled = !loading, onClick = ::load)
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
            PreferenceGroup(footer = "整个服务器的设置管它的每个工具；工具自己也设了的，取更严的那个。") {
                item {
                    BulkButtons(view?.focus?.bulk, editable) { enable, change ->
                        bulk =
                            BulkRequest(
                                "${server.name} 的工具",
                                PermissionGroup(server = server.name),
                                enable,
                                change,
                            )
                    }
                }
                item {
                    ToolRow("整个服务器", focus.rule, focus.state.behavior, editable) { b ->
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
                    "整个服务器",
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
        title = tools?.let { "工具 · ${it.size}" } ?: "工具",
        footer = if (server.oauth) "要按工具设置，在上一页的“规则”里手写 ${server.rule}__工具名。" else null,
    ) {
        when {
            loading -> item { ConfigLoadingRow("正在连接…") }
            error != null ->
                item { BasicComponent(title = "取不到工具清单", summary = error, onClick = onLoad) }
            tools == null ->
                item {
                    BasicComponent(
                        title = "列出工具",
                        summary = if (server.oauth) "用 OAuth 登录的服务器取不到" else "连上服务器取工具清单",
                        enabled = !server.oauth,
                        onClick = onLoad,
                    )
                }
            tools.isEmpty() -> item { ConfigEmptyRow("没有工具") }
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
            text = "全部开启",
            onClick = { pair?.let { onRequest(true, it.enable) } },
            enabled = editable && pair?.enable?.isEmpty == false,
            modifier = Modifier.weight(1f),
        )
        CompactButton(
            text = "全部关闭",
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
        title = shown?.let { (if (it.enable) "全部开启" else "全部关闭") + it.label + "？" },
        summary = shown?.let { bulkMessage(it.change, warnNoTools = it.warnNoTools) },
        confirmText = if (shown?.enable == false) "全部关闭" else "全部开启",
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
            append("加到${b.label}（${rules.size} 条）：").append(rules.joinToString("、")).append("\n")
    }
    PermissionBehavior.entries.forEach { b ->
        val rules = change.remove.filter { it.behavior == b }.map { it.rule }
        if (rules.isNotEmpty())
            append("从${b.label}里删（${rules.size} 条）：").append(rules.joinToString("、")).append("\n")
    }
    if (warnNoTools) append("\n关掉以后 Claude 连读写文件、跑命令都做不了，只能聊天和用手机工具、MCP 工具。")
}
    .trimEnd()
