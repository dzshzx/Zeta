package com.dzshzx.zeta.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
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
import com.dzshzx.zeta.ui.chat.permissionModeLabel
import com.dzshzx.zeta.ui.components.coui.CouiButton
import com.dzshzx.zeta.ui.components.coui.CouiConfirmDialog
import com.dzshzx.zeta.ui.components.coui.CouiDropdownPreference
import com.dzshzx.zeta.ui.components.coui.CouiPopupPreference
import com.dzshzx.zeta.ui.components.coui.CouiPreference
import com.dzshzx.zeta.ui.components.coui.CouiScaffoldPage
import com.dzshzx.zeta.ui.components.coui.CouiSegmentedTabs
import com.dzshzx.zeta.ui.components.coui.CouiTextField
import com.dzshzx.zeta.ui.components.coui.CouiTopAppBarIconButton
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.Group
import com.itos.coui.menu.CouiMenuItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 下拉里的四个选项：没写规则（按权限模式走）、允许、询问、拒绝。 */
private val Choices: List<PermissionBehavior?> = listOf(null, PermissionBehavior.Allow, PermissionBehavior.Ask, PermissionBehavior.Deny)
private val ChoiceLabels = listOf("未设置", "允许", "询问", "拒绝")

/** 页面上改规则时的回调：交给后台线程跑，跑完拿到新规则。 */
private typealias ApplyRules = (() -> PermissionRules) -> Unit

/** 一行的说明：工具的中文说明，加上它带参数的规则、从服务器继承来的设置。 */
private fun toolSummary(label: String?, state: ToolPermission, serverLabel: String = "整个服务器"): String? = listOfNotNull(
    label?.ifBlank { null },
    state.behavior?.takeIf { state.inherited }?.let { "$serverLabel 设了${it.label}" },
    state.specific.takeIf { it.isNotEmpty() }?.let { list ->
        if (list.size == 1) "另有规则：${list[0].second}（${list[0].first.label}）" else "另有 ${list.size} 条带参数的规则，见下面“规则”"
    },
).joinToString(" · ").ifBlank { null }

/** 一个工具（或整个服务器）一行：右边下拉选 未设置 / 允许 / 询问 / 拒绝。显示的是它自己的规则；继承来的写在说明里。 */
@Composable
private fun ToolRow(title: String, summary: String?, own: PermissionBehavior?, enabled: Boolean, onChoose: (PermissionBehavior?) -> Unit) {
    CouiDropdownPreference(
        title = title,
        summary = summary,
        items = ChoiceLabels,
        selectedIndex = Choices.indexOf(own),
        onSelectedIndexChange = { index -> if (index in Choices.indices && Choices[index] != own) onChoose(Choices[index]) },
        enabled = enabled,
    )
}

/**
 * “工具权限”：Claude Code 自己的 permissions 规则（~/.claude/settings.json 的 allow / ask / deny）。
 * 列出内置工具、Zeta 的手机工具、配置里的 MCP 服务器，每个都能整体设成允许、询问、拒绝；带参数的规则和认不出的写法列在“规则”里，
 * 可以改、删、加。每改一处就写回文件，只动那一条规则，别的设置原样保留。
 */
@Composable
internal fun ClaudePermissionsScreen(hub: ConversationHub, onBack: () -> Unit) {
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
    val mode = hub.settings.permissionMode ?: hub.defaultPermissionMode

    CouiScaffoldPage(
        title = "工具权限",
        onBack = onBack,
        actions = {
            CouiTopAppBarIconButton(icon = Icons.Rounded.Refresh, contentDescription = "重新读取", enabled = !working, onClick = { reloads++ })
            CouiTopAppBarIconButton(icon = CouiIcons.Outlined.Add, contentDescription = "添加规则", enabled = editable, onClick = { editor = RuleDraft(null, PermissionBehavior.Ask, "") })
        },
    ) {
        Group(
            footer = ClaudePermissions.SEMANTICS + "\n\n这里改的是 ~/.claude/settings.json（所有项目都生效），对之后新开始的对话生效。" +
                "项目里的 .claude/settings.json 也能写规则，这里不显示。" +
                (loadError?.let { "\n\n读取失败：$it" } ?: ""),
        ) {
            CouiPreference(title = "Zeta 当前的权限模式", value = permissionModeLabel(mode), summary = "在聊天输入栏里切换")
        }
        Group(title = "内置工具") {
            BulkButtons(current, BuiltinTools.group, editable) { enable, change -> bulk = BulkRequest("内置工具", BuiltinTools.group, enable, change) }
            BuiltinTools.common.forEach { tool ->
                val state = current?.let { ClaudePermissions.tool(it, tool.name) } ?: ToolPermission(null)
                ToolRow(tool.name, toolSummary(tool.label, state), state.behavior, editable) { b -> apply { client.setToolPermission(tool.name, b) } }
            }
            if (!showMore) CouiPreference(title = "更多内置工具", summary = BuiltinTools.other.joinToString("、") { it.name }, onClick = { showMore = true })
        }
        if (showMore) {
            Group(title = "更多内置工具") {
                BuiltinTools.other.forEach { tool ->
                    val state = current?.let { ClaudePermissions.tool(it, tool.name) } ?: ToolPermission(null)
                    ToolRow(tool.name, toolSummary(tool.label, state), state.behavior, editable) { b -> apply { client.setToolPermission(tool.name, b) } }
                }
            }
        }
        Group(
            title = "手机工具",
            footer = "Zeta 自己接给 Claude 的 MCP 服务器 ${PhoneTools.SERVER}，规则里的名字是 ${McpNames.server(PhoneTools.SERVER)}__工具名。" +
                (if (hub.phoneTools) "" else "手机操作现在是关着的，Claude 拿不到这些工具。"),
        ) {
            val server = PhoneTools.SERVER
            val whole = current?.let { ClaudePermissions.server(it, server) } ?: ToolPermission(null)
            val group = PermissionGroup.Server(server)
            BulkButtons(current, group, editable) { enable, change -> bulk = BulkRequest("手机工具", group, enable, change) }
            ToolRow("全部手机工具", McpNames.server(server), whole.behavior, editable) { b -> apply { client.setToolPermission(McpNames.server(server), b) } }
            phoneTools.forEach { (name, description) ->
                val state = current?.let { ClaudePermissions.mcpTool(it, server, name) } ?: ToolPermission(null)
                val own = current?.let { ClaudePermissions.tool(it, McpNames.tool(server, name)).behavior }
                ToolRow(name, toolSummary(PhoneToolLabels.label(name, description), state, "全部手机工具"), own, editable) { b ->
                    apply { client.setToolPermission(McpNames.tool(server, name), b) }
                }
            }
        }
        Group(title = "MCP 服务器", footer = "点进去可以按工具分别设置。整个服务器的规则写成 mcp__服务器名。") {
            val list = servers
            when {
                list == null -> ConfigLoadingRow()
                list.isEmpty() -> ConfigEmptyRow("没有配置 MCP 服务器")
                else -> list.distinctBy { it.name }.forEach { server ->
                    val state = current?.let { ClaudePermissions.server(it, server.name) } ?: ToolPermission(null)
                    CouiPreference(
                        title = server.name,
                        summary = McpNames.server(server.name),
                        value = state.behavior?.label ?: "未设置",
                        onClick = { detail = server },
                    )
                }
            }
        }
        Group(
            title = "规则 · ${current?.all?.size ?: 0}",
            footer = "settings.json 里的全部规则，按拒绝、询问、允许排。带参数的规则（比如 Bash(git push:*)、Read(./.env)、WebFetch(domain:github.com)）" +
                "和这里认不出的写法都原样保留，点一下可以改成别的、编辑或删除。",
        ) {
            when {
                current == null && loadError == null -> ConfigLoadingRow()
                current == null -> ConfigEmptyRow("读不出规则")
                current.isEmpty -> ConfigEmptyRow("还没有规则", "点右上角的 + 添加")
                else -> current.all.forEach { (behavior, rule) ->
                    RuleRow(behavior, rule, editable,
                        onMove = { b -> apply { client.replacePermissionRule(behavior, rule, b, rule) } },
                        onEdit = { editor = RuleDraft(behavior to rule, behavior, rule) },
                        onDelete = { deleting = behavior to rule },
                    )
                }
            }
        }
    }

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
    val shownDelete = rememberRetained(deleting)
    CouiConfirmDialog(
        visible = deleting != null,
        title = "删除规则",
        message = shownDelete?.let { "删除${it.first.label}规则 ${it.second}？" },
        confirmText = "删除",
        destructive = true,
        onConfirm = {
            val target = deleting
            deleting = null
            if (target != null) apply { client.removePermissionRule(target.first, target.second) }
        },
        onDismissRequest = { deleting = null },
    )

    val shownServer = rememberRetained(detail)
    ConfigSubPage(visible = detail != null, onBack = { detail = null }) {
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
    var expanded by remember { mutableStateOf(false) }
    val parsed = remember(rule) { ClaudePermissions.parse(rule) }
    CouiPopupPreference(
        title = rule,
        summary = if (parsed.malformed) "括号不配对，Claude Code 认不出这条" else null,
        value = behavior.label,
        expanded = expanded,
        onExpandedChange = { expanded = it && enabled },
        enabled = enabled,
        items = PermissionBehavior.entries.map { CouiMenuItem<Any>(value = it, text = it.label, selected = it == behavior) } +
            CouiMenuItem<Any>(value = "edit", text = "编辑…", dividerBefore = true) +
            CouiMenuItem<Any>(value = "delete", text = "删除"),
        onItemClick = { item ->
            when (val v = item.value) {
                is PermissionBehavior -> if (v != behavior) onMove(v)
                "edit" -> onEdit()
                "delete" -> onDelete()
            }
        },
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
    CouiConfirmDialog(
        visible = draft != null,
        title = if (shown?.original == null) "添加规则" else "编辑规则",
        message = null,
        confirmText = if (saving) "正在保存…" else "保存",
        confirmEnabled = !saving,
        cancelEnabled = !saving,
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
            CouiSegmentedTabs(
                items = PermissionBehavior.entries.map { it to it.label },
                selectedKey = behavior,
                onSelectedKeyChange = { behavior = it },
                enabled = !saving,
                small = true,
            )
            CouiTextField(value = text, onValueChange = { text = it }, label = "规则", placeholder = "Bash(git push:*)", enabled = !saving)
            Text(
                text = "写成 工具名 或 工具名(参数)。比如 Bash(npm run test:*)、Read(./.env)、Edit(src/**)、WebFetch(domain:github.com)、mcp__github（整个服务器）。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            error?.let { Text(text = it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error) }
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

    CouiScaffoldPage(
        title = server.name,
        onBack = onBack,
        actions = { CouiTopAppBarIconButton(icon = Icons.Rounded.Refresh, contentDescription = "刷新工具", enabled = !loading, onClick = ::load) },
    ) {
        Group(footer = "整个服务器的设置对它的每个工具都生效；工具自己也设了的，按“拒绝 > 询问 > 允许”取最严的。") {
            val whole = rules?.let { ClaudePermissions.server(it, server.name) } ?: ToolPermission(null)
            val group = PermissionGroup.Server(server.name)
            BulkButtons(rules, group, editable) { enable, change -> bulk = BulkRequest("${server.name} 的工具", group, enable, change) }
            ToolRow("整个服务器", McpNames.server(server.name), whole.behavior, editable) { b -> apply { client.setToolPermission(McpNames.server(server.name), b) } }
        }
        Group(title = tools?.let { "工具 · ${it.size}" } ?: "工具") {
            val list = tools
            when {
                loading -> ConfigLoadingRow("正在连接…")
                toolsError != null -> CouiPreference(title = "取不到工具清单", summary = toolsError, showChevron = false, onClick = ::load)
                list == null -> CouiPreference(
                    title = "列出工具",
                    summary = if (server.oauth) "这个服务器用 OAuth 登录，Zeta 取不到它的工具；可以在“规则”里手写 ${McpNames.server(server.name)}__工具名" else "连接这个服务器，取它的工具清单",
                    enabled = !server.oauth,
                    onClick = ::load,
                )
                list.isEmpty() -> ConfigEmptyRow("没有工具")
                else -> list.forEach { tool ->
                    val state = rules?.let { ClaudePermissions.mcpTool(it, server.name, tool.name) } ?: ToolPermission(null)
                    val own = rules?.let { ClaudePermissions.tool(it, McpNames.tool(server.name, tool.name)).behavior }
                    ToolRow(tool.name, toolSummary(brief(tool.description, 60), state), own, editable) { b ->
                        apply { client.setToolPermission(McpNames.tool(server.name, tool.name), b) }
                    }
                }
            }
        }
    }
    BulkConfirmDialog(bulk, onDismiss = { bulk = null }) { request -> apply { client.setGroupPermission(request.group, request.enable) } }
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
        CouiButton(
            text = "全部开启",
            onClick = { on?.let { onRequest(true, it) } },
            enabled = editable && on?.isEmpty == false,
            minHeight = 36.dp,
            modifier = Modifier.weight(1f),
        )
        CouiButton(
            text = "全部关闭",
            onClick = { off?.let { onRequest(false, it) } },
            enabled = editable && off?.isEmpty == false,
            minHeight = 36.dp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BulkConfirmDialog(request: BulkRequest?, onDismiss: () -> Unit, onConfirm: (BulkRequest) -> Unit) {
    val shown = rememberRetained(request)
    CouiConfirmDialog(
        visible = request != null,
        title = shown?.let { (if (it.enable) "全部开启" else "全部关闭") + it.label + "？" },
        message = shown?.let { bulkMessage(it.change, warnNoTools = !it.enable && it.group == BuiltinTools.group) },
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

/** 确认框里的说明：按表列出会加、会删的规则。关闭内置工具时另外说清后果。 */
internal fun bulkMessage(change: BulkChange, warnNoTools: Boolean): String = buildString {
    PermissionBehavior.entries.forEach { b ->
        val rules = change.add.filter { it.first == b }.map { it.second }
        if (rules.isNotEmpty()) append("加到${b.label}（${rules.size} 条）：").append(rules.joinToString("、")).append("\n")
    }
    PermissionBehavior.entries.forEach { b ->
        val rules = change.remove.filter { it.first == b }.map { it.second }
        if (rules.isNotEmpty()) append("从${b.label}里删（${rules.size} 条）：").append(rules.joinToString("、")).append("\n")
    }
    if (warnNoTools) append("\n关掉以后 Claude 连读写文件、跑命令都做不了，只能聊天和用手机工具、MCP 工具。\n")
    append("\n写进 ~/.claude/settings.json，对之后新开始的对话生效。")
}

