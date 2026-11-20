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
import com.dzshzx.zeta.core.claude.BuiltinTools
import com.dzshzx.zeta.core.claude.BulkChange
import com.dzshzx.zeta.core.claude.ClaudePermissions
import com.dzshzx.zeta.core.claude.McpNames
import com.dzshzx.zeta.core.claude.McpServer
import com.dzshzx.zeta.core.claude.McpTool
import com.dzshzx.zeta.core.claude.PermissionBehavior
import com.dzshzx.zeta.core.claude.PermissionGroup
import com.dzshzx.zeta.core.claude.PermissionRules
import com.dzshzx.zeta.core.claude.PhoneToolLabels
import com.dzshzx.zeta.core.claude.ToolPermission
import com.dzshzx.zeta.core.claude.addPermissionRule
import com.dzshzx.zeta.core.claude.permissions
import com.dzshzx.zeta.core.claude.removePermissionRule
import com.dzshzx.zeta.core.claude.replacePermissionRule
import com.dzshzx.zeta.core.claude.setGroupPermission
import com.dzshzx.zeta.core.claude.setToolPermission
import com.dzshzx.zeta.phone.PhoneTools
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.coui2.CompactButton
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.DialogTextField
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.IconActionButton
import com.dzshzx.zeta.ui.components.rememberRetained
import io.github.suqi8.coui.kmp.basic.BasicComponent
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

/** 下拉里的四个选项：没写规则（按权限模式走）、允许、询问、拒绝。 */
private val Choices: List<PermissionBehavior?> = listOf(null, PermissionBehavior.Allow, PermissionBehavior.Ask, PermissionBehavior.Deny)
private val ChoiceLabels = listOf("未设置", "允许", "询问", "拒绝")

/** 页面上改规则时的回调：交给后台线程跑，跑完拿到新规则。 */
private typealias ApplyRules = (() -> PermissionRules) -> Unit

/**
 * 一行的摘要，只放一行：有从服务器继承来的设置、带参数的规则时写这些状态，没有时写工具的中文说明。
 * 带参数的规则本身列在页面下面的“规则”里。
 */
private fun toolSummary(label: String?, state: ToolPermission, serverLabel: String = "整个服务器"): String? = listOfNotNull(
    state.behavior?.takeIf { state.inherited }?.let { "跟随$serverLabel：${it.label}" },
    state.specific.takeIf { it.isNotEmpty() }?.let { "另有 ${it.size} 条带参数的规则" },
).joinToString(" · ").ifBlank { label?.ifBlank { null } }

/** 一个工具（或整个服务器）一行：右边下拉选 未设置 / 允许 / 询问 / 拒绝。显示的是它自己的规则；继承来的写在说明里。 */
@Composable
private fun ToolRow(title: String, summary: String?, own: PermissionBehavior?, enabled: Boolean, onChoose: (PermissionBehavior?) -> Unit) {
    OverlayDropdownPreference(
        items = ChoiceLabels,
        selectedIndex = Choices.indexOf(own),
        title = title,
        summary = summary,
        enabled = enabled,
        onSelectedIndexChange = { index -> if (index in Choices.indices && Choices[index] != own) onChoose(Choices[index]) },
    )
}

/**
 * “工具权限”：Claude Code 自己的 permissions 规则（~/.claude/settings.json 的 allow / ask / deny）。
 * 列出内置工具、Zeta 的手机工具、配置里的 MCP 服务器，每个都能整体设成允许、询问、拒绝；带参数的规则和认不出的写法列在“规则”里，
 * 可以改、删、加。每改一处就写回文件，只动那一条规则，别的设置原样保留。
 * 项目里的 .claude/settings.json 也能写规则，这里不显示。手机工具是 Zeta 自己接给 Claude 的 MCP 服务器 phone，
 * 规则里写 mcp__phone（整个服务器）或 mcp__phone__工具名；别的 MCP 服务器同样写 mcp__服务器名。
 */
@Composable
internal fun ToolPermissionsScreen(hub: ConversationHub, onBack: () -> Unit) {
    val client = rememberConfigClient(hub)
    val scope = rememberCoroutineScope()
    val cwd = hub.env?.defaultCwd ?: "/"
    var rules by remember { mutableStateOf<PermissionRules?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var servers by remember { mutableStateOf<List<McpServer>?>(null) }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ConfigNotice?>(null) }
    var reloads by remember { mutableStateOf(0) }
    var showMore by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<RuleDraft?>(null) }
    var deleting by remember { mutableStateOf<Pair<PermissionBehavior, String>?>(null) }
    var detail by remember { mutableStateOf<McpServer?>(null) }
    var bulk by remember { mutableStateOf<BulkRequest?>(null) }
    val phoneTools = remember { PhoneTools.catalog }

    LaunchedEffect(reloads) {
        scope.runConfig({ client.permissions() }) { result ->
            result.onSuccess {
                rules = it
                loadError = null
            }.onFailure { loadError = it.problem() }
        }
        scope.runConfig({ client.mcpServers(cwd) }) { result -> servers = result.getOrDefault(emptyList()) }
    }
    val apply: ApplyRules = { block ->
        working = true
        scope.runConfig(block) { result ->
            working = false
            result.onSuccess { rules = it }.onFailure {
                notice = ConfigNotice("没有改成", it.problem())
                reloads++
            }
        }
    }
    val current = rules
    val editable = current != null && loadError == null && !working

    val shownDelete = rememberRetained(deleting)
    CouiPage(
        title = "工具权限",
        onBack = onBack,
        actions = {
            IconActionButton(COUIIcons.Refresh, "重新读取", enabled = !working, onClick = { reloads++ })
            IconActionButton(COUIIcons.Add, "添加规则", enabled = editable, onClick = { editor = RuleDraft(null, PermissionBehavior.Ask, "") })
        },
        dialogs = {
            ConfigNoticeDialog(notice) { notice = null }
            BulkConfirmDialog(bulk, onDismiss = { bulk = null }) { request -> apply { client.setGroupPermission(request.group, request.enable) } }
            RuleEditorDialog(
                draft = editor,
                onDismiss = { editor = null },
                onSave = { draft, done ->
                    val original = draft.original
                    working = true
                    scope.runConfig({
                        if (original == null) client.addPermissionRule(draft.behavior, draft.rule)
                        else client.replacePermissionRule(original.first, original.second, draft.behavior, draft.rule)
                    }) { result ->
                        working = false
                        result.onSuccess {
                            rules = it
                            editor = null
                            done(null)
                        }.onFailure {
                            done(it.problem())
                            reloads++
                        }
                    }
                },
            )
            ConfirmDialog(
                show = deleting != null,
                title = "删除规则",
                summary = shownDelete?.let { "删除${it.first.label}规则 ${it.second}？" },
                confirmText = "删除",
                destructive = true,
                onConfirm = {
                    val target = deleting
                    deleting = null
                    if (target != null) apply { client.removePermissionRule(target.first, target.second) }
                },
                onDismissRequest = { deleting = null },
            )
        },
    ) {
        // 这页只管规则；默认权限模式在设置首页，和默认模型、思考强度在一起
        item(key = "semantics") {
            PagerFooterPreference(
                summary = ClaudePermissions.SEMANTICS + "改动对所有项目、之后新开始的对话生效。" +
                    (loadError?.let { "\n\n读取失败：$it" } ?: ""),
            )
        }
        item(key = "builtin") {
            PreferenceGroup(title = "内置工具") {
                item { BulkButtons(current, BuiltinTools.group, editable) { enable, change -> bulk = BulkRequest("内置工具", BuiltinTools.group, enable, change) } }
                BuiltinTools.common.forEach { tool ->
                    item(key = tool.name) {
                        val state = current?.let { ClaudePermissions.tool(it, tool.name) } ?: ToolPermission(null)
                        ToolRow(tool.name, toolSummary(tool.label, state), state.behavior, editable) { b -> apply { client.setToolPermission(tool.name, b) } }
                    }
                }
                if (!showMore) {
                    item(key = "more") {
                        BasicComponent(title = "更多内置工具", endActions = { PreferenceValue("${BuiltinTools.other.size} 个") }, onClick = { showMore = true })
                    }
                }
            }
        }
        if (showMore) {
            item(key = "builtin-more") {
                PreferenceGroup(title = "更多内置工具") {
                    BuiltinTools.other.forEach { tool ->
                        item(key = tool.name) {
                            val state = current?.let { ClaudePermissions.tool(it, tool.name) } ?: ToolPermission(null)
                            ToolRow(tool.name, toolSummary(tool.label, state), state.behavior, editable) { b -> apply { client.setToolPermission(tool.name, b) } }
                        }
                    }
                }
            }
        }
        item(key = "phone") {
            PreferenceGroup(title = "手机工具", footer = if (hub.phoneTools) null else "手机操作现在关着，Claude 拿不到这些工具。") {
                val server = PhoneTools.SERVER
                val group = PermissionGroup.Server(server)
                item { BulkButtons(current, group, editable) { enable, change -> bulk = BulkRequest("手机工具", group, enable, change) } }
                item(key = "whole") {
                    val whole = current?.let { ClaudePermissions.server(it, server) } ?: ToolPermission(null)
                    ToolRow("全部手机工具", McpNames.server(server), whole.behavior, editable) { b -> apply { client.setToolPermission(McpNames.server(server), b) } }
                }
                phoneTools.forEach { (name, description) ->
                    item(key = name) {
                        val state = current?.let { ClaudePermissions.mcpTool(it, server, name) } ?: ToolPermission(null)
                        val own = current?.let { ClaudePermissions.tool(it, McpNames.tool(server, name)).behavior }
                        ToolRow(name, toolSummary(PhoneToolLabels.label(name, description), state, "全部手机工具"), own, editable) { b ->
                            apply { client.setToolPermission(McpNames.tool(server, name), b) }
                        }
                    }
                }
            }
        }
        item(key = "mcp") {
            PreferenceGroup(title = "MCP 服务器") {
                val list = servers
                when {
                    list == null -> item { ConfigLoadingRow() }
                    list.isEmpty() -> item { ConfigEmptyRow("没有配置 MCP 服务器") }
                    else -> list.distinctBy { it.name }.forEach { server ->
                        item(key = server.name) {
                            val state = current?.let { ClaudePermissions.server(it, server.name) } ?: ToolPermission(null)
                            ArrowPreference(
                                title = server.name,
                                endActions = { PreferenceValue(state.behavior?.label ?: "未设置") },
                                onClick = { detail = server },
                            )
                        }
                    }
                }
            }
        }
        item(key = "rules") {
            PreferenceGroup(
                // 全部规则，按拒绝、询问、允许排；认不出的写法也原样列出、原样保留
                title = "规则 · ${current?.all?.size ?: 0}",
                footer = "带参数的规则只在这里改，上面的下拉不动它们。",
            ) {
                when {
                    current == null && loadError == null -> item { ConfigLoadingRow() }
                    current == null -> item { ConfigEmptyRow("读不出规则") }
                    current.isEmpty -> item { ConfigEmptyRow("还没有规则", "点右上角的 + 添加") }
                    else -> current.all.forEach { (behavior, rule) ->
                        item(key = behavior.name + ":" + rule) {
                            RuleRow(
                                behavior,
                                rule,
                                editable,
                                onMove = { b -> apply { client.replacePermissionRule(behavior, rule, b, rule) } },
                                onEdit = { editor = RuleDraft(behavior to rule, behavior, rule) },
                                onDelete = { deleting = behavior to rule },
                            )
                        }
                    }
                }
            }
        }
    }

    val shownServer = rememberRetained(detail)
    SlideInPage(visible = detail != null, onBack = { detail = null }) {
        shownServer?.let { server ->
            McpPermissionDetail(hub = hub, server = server, rules = current, editable = editable, apply = apply, onBack = { detail = null })
        }
    }
}

/** 一条规则一行：右边是它在哪张表，点开菜单可以换表、编辑、删除。 */
@Composable
private fun RuleRow(
    behavior: PermissionBehavior,
    rule: String,
    enabled: Boolean,
    onMove: (PermissionBehavior) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val parsed = remember(rule) { ClaudePermissions.parse(rule) }
    // 右边显示它在哪张表；菜单里换表、编辑、删除
    OverlayDropdownPreference(
        entries = listOf(
            DropdownEntry(items = PermissionBehavior.entries.map { DropdownItem(text = it.label, selected = it == behavior, onClick = { if (it != behavior) onMove(it) }) }),
            DropdownEntry(items = listOf(DropdownItem(text = "编辑…", onClick = onEdit), DropdownItem(text = "删除", onClick = onDelete))),
        ),
        title = rule,
        summary = if (parsed.malformed) "括号不配对，Claude Code 认不出这条" else null,
        enabled = enabled,
        collapseOnSelection = true,
    )
}

/** 添加或编辑一条规则；[original] 为 null 是新加。 */
private data class RuleDraft(val original: Pair<PermissionBehavior, String>?, val behavior: PermissionBehavior, val rule: String)

@Composable
private fun RuleEditorDialog(draft: RuleDraft?, onDismiss: () -> Unit, onSave: (RuleDraft, (String?) -> Unit) -> Unit) {
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
            val rule = text.trim()
            error = ClaudePermissions.ruleProblem(rule)
            val current = shown
            if (error == null && current != null) {
                saving = true
                onSave(current.copy(behavior = behavior, rule = rule)) { problem ->
                    saving = false
                    error = problem
                }
            }
        },
        onDismissRequest = { if (!saving) onDismiss() },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            val behaviors = PermissionBehavior.entries
            SegmentButton(
                items = behaviors.map { it.label },
                selectedIndex = behaviors.indexOf(behavior),
                onSelectedIndexChange = { index -> behaviors.getOrNull(index)?.let { behavior = it } },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving,
                height = SegmentButtonDefaults.HeightSmall,
                padding = SegmentButtonDefaults.PaddingSmall,
            )
            DialogTextField(value = text, onValueChange = { text = it }, title = "规则", hint = "Bash(git push:*)", enabled = !saving)
            Text(
                text = "写成 工具名 或 工具名(参数)。比如 Bash(npm run test:*)、Read(./.env)、Edit(src/**)、WebFetch(domain:github.com)、mcp__github（整个服务器）。",
                style = COUITheme.textStyles.bodyS,
                color = COUITheme.colorScheme.labelSecondary,
            )
            error?.let { Text(text = it, style = COUITheme.textStyles.bodyM, color = COUITheme.colorScheme.error) }
        }
    }
}

/** 一个 MCP 服务器：整体的设置，加上逐个工具的设置（工具清单要连上服务器去取）。 */
@Composable
private fun McpPermissionDetail(
    hub: ConversationHub,
    server: McpServer,
    rules: PermissionRules?,
    editable: Boolean,
    apply: ApplyRules,
    onBack: () -> Unit,
) {
    val client = rememberConfigClient(hub)
    val scope = rememberCoroutineScope()
    val cwd = hub.env?.defaultCwd ?: "/"
    var tools by remember(server) { mutableStateOf<List<McpTool>?>(null) }
    var toolsError by remember(server) { mutableStateOf<String?>(null) }
    var loading by remember(server) { mutableStateOf(false) }

    fun load() {
        loading = true
        toolsError = null
        scope.runConfig({ client.mcpTools(server, cwd) }) { result ->
            loading = false
            result.onSuccess { tools = it }.onFailure { toolsError = it.problem() }
        }
    }
    // 和 MCP 页一样：项目里的 .mcp.json 可能来自别人的仓库，不自动替它启动命令
    LaunchedEffect(server) { if (server.scope != "project" && !server.oauth) load() }
    var bulk by remember { mutableStateOf<BulkRequest?>(null) }

    CouiPage(
        title = server.name,
        onBack = onBack,
        actions = { IconActionButton(COUIIcons.Refresh, "刷新工具", enabled = !loading, onClick = ::load) },
        dialogs = { BulkConfirmDialog(bulk, onDismiss = { bulk = null }) { request -> apply { client.setGroupPermission(request.group, request.enable) } } },
    ) {
        item(key = "whole") {
            PreferenceGroup(footer = "整个服务器的设置管它的每个工具；工具自己也设了的，取更严的那个。") {
                val group = PermissionGroup.Server(server.name)
                item { BulkButtons(rules, group, editable) { enable, change -> bulk = BulkRequest("${server.name} 的工具", group, enable, change) } }
                item {
                    val whole = rules?.let { ClaudePermissions.server(it, server.name) } ?: ToolPermission(null)
                    ToolRow("整个服务器", McpNames.server(server.name), whole.behavior, editable) { b -> apply { client.setToolPermission(McpNames.server(server.name), b) } }
                }
            }
        }
        item(key = "tools") {
            PreferenceGroup(
                title = tools?.let { "工具 · ${it.size}" } ?: "工具",
                footer = if (server.oauth) "要按工具设置，在上一页的“规则”里手写 ${McpNames.server(server.name)}__工具名。" else null,
            ) {
                val list = tools
                when {
                    loading -> item { ConfigLoadingRow("正在连接…") }
                    toolsError != null -> item { BasicComponent(title = "取不到工具清单", summary = toolsError, onClick = ::load) }
                    list == null -> item {
                        BasicComponent(
                            title = "列出工具",
                            summary = if (server.oauth) "用 OAuth 登录的服务器取不到" else "连上服务器取工具清单",
                            enabled = !server.oauth,
                            onClick = ::load,
                        )
                    }
                    list.isEmpty() -> item { ConfigEmptyRow("没有工具") }
                    else -> list.forEach { tool ->
                        item(key = tool.name) {
                            val state = rules?.let { ClaudePermissions.mcpTool(it, server.name, tool.name) } ?: ToolPermission(null)
                            val own = rules?.let { ClaudePermissions.tool(it, McpNames.tool(server.name, tool.name)).behavior }
                            ToolRow(tool.name, toolSummary(brief(tool.description, 60), state), own, editable) { b ->
                                apply { client.setToolPermission(McpNames.tool(server.name, tool.name), b) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 点了“全部开启 / 全部关闭”、等确认的那一组。[change] 是按页面上的规则算的，确认后按文件里最新的内容重算再写。 */
internal data class BulkRequest(val label: String, val group: PermissionGroup, val enable: Boolean, val change: BulkChange)

/** 一组上面的一对按钮；已经全是那个状态（没什么可加可删）时置灰。 */
@Composable
private fun BulkButtons(rules: PermissionRules?, group: PermissionGroup, editable: Boolean, onRequest: (Boolean, BulkChange) -> Unit) {
    val on = remember(rules, group) { rules?.let { ClaudePermissions.bulk(it, group, enable = true) } }
    val off = remember(rules, group) { rules?.let { ClaudePermissions.bulk(it, group, enable = false) } }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CompactButton(
            text = "全部开启",
            onClick = { on?.let { onRequest(true, it) } },
            enabled = editable && on?.isEmpty == false,
            modifier = Modifier.weight(1f),
        )
        CompactButton(
            text = "全部关闭",
            onClick = { off?.let { onRequest(false, it) } },
            enabled = editable && off?.isEmpty == false,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BulkConfirmDialog(request: BulkRequest?, onDismiss: () -> Unit, onConfirm: (BulkRequest) -> Unit) {
    val shown = rememberRetained(request)
    ConfirmDialog(
        show = request != null,
        title = shown?.let { (if (it.enable) "全部开启" else "全部关闭") + it.label + "？" },
        summary = shown?.let { bulkMessage(it.change, warnNoTools = !it.enable && it.group == BuiltinTools.group) },
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
        val rules = change.add.filter { it.first == b }.map { it.second }
        if (rules.isNotEmpty()) append("加到${b.label}（${rules.size} 条）：").append(rules.joinToString("、")).append("\n")
    }
    PermissionBehavior.entries.forEach { b ->
        val rules = change.remove.filter { it.first == b }.map { it.second }
        if (rules.isNotEmpty()) append("从${b.label}里删（${rules.size} 条）：").append(rules.joinToString("、")).append("\n")
    }
    if (warnNoTools) append("\n关掉以后 Claude 连读写文件、跑命令都做不了，只能聊天和用手机工具、MCP 工具。")
}.trimEnd()

