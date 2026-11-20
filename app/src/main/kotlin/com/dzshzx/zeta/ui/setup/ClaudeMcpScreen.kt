package com.dzshzx.zeta.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.claude.McpConfig
import com.dzshzx.zeta.core.claude.McpServer
import com.dzshzx.zeta.core.claude.McpState
import com.dzshzx.zeta.core.claude.McpStatus
import com.dzshzx.zeta.core.claude.McpTool
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.DialogTextField
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.IconActionButton
import com.dzshzx.zeta.ui.components.rememberRetained
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.SegmentButton
import io.github.suqi8.coui.kmp.basic.SegmentButtonDefaults
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

private fun stateLabel(state: McpState?): String = when (state?.state) {
    null -> "检查中…"
    "connected" -> "已连接"
    "partial" -> "已连接 · 取工具出错"
    "failed" -> "连不上"
    "auth" -> "需要登录"
    "pending" -> "等待批准"
    "rejected" -> "已拒绝"
    "disabled" -> "已停用"
    "unconfigured" -> "未配置"
    "shadowed" -> "未生效"
    else -> "未知"
}

/** 服务器状态在设置行右边的颜色：连上、连不上、要登录各有颜色，其余是普通值的颜色。 */
@Composable
private fun stateColor(state: McpState?): Color = when (state?.state) {
    "connected" -> COUITheme.colorScheme.success
    "failed" -> COUITheme.colorScheme.error
    "partial", "auth", "pending" -> StatusWarning
    else -> Color.Unspecified
}

private fun typeLabel(type: String): String = when (type) {
    "http" -> "HTTP"
    "sse" -> "SSE"
    "stdio" -> "命令"
    "ws" -> "WebSocket"
    else -> type
}

/** 配置的范围：user 是用户配置，local 是默认工作目录的本地配置（claude mcp add --scope 的叫法）。 */
private fun scopeLabel(scope: String): String = when (scope) {
    "user" -> "所有项目"
    "local" -> "只在默认工作目录"
    "project" -> "项目的 .mcp.json"
    else -> scope
}

private fun McpServer.target(): String = if (type == "stdio") (listOf(command) + args).joinToString(" ") else url

/** 列表和详情里显示的状态：被同名服务器盖住的不会被用到，不看健康检查的结果。 */
private fun McpStatus?.of(server: McpServer, failed: Boolean): McpState? = when {
    server.shadowedBy != null -> McpState("shadowed", "被“${scopeLabel(server.shadowedBy)}”里的同名服务器盖住")
    else -> this?.statuses?.get(McpConfig.statusKey(server.scope, server.name)) ?: if (failed || this != null) McpState("unknown", "") else null
}

/** 把一行参数拆开：空白分隔，单双引号里的空白不拆。 */
internal fun splitArgs(line: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var started = false
    for (c in line) {
        when {
            quote != null && c == quote -> quote = null
            quote != null -> current.append(c)
            c == '"' || c == '\'' -> {
                quote = c
                started = true
            }
            c.isWhitespace() -> {
                if (started || current.isNotEmpty()) result.add(current.toString())
                current.clear()
                started = false
            }
            else -> current.append(c)
        }
    }
    if (started || current.isNotEmpty()) result.add(current.toString())
    return result
}

private val ServerName = Regex("[A-Za-z0-9_-]{1,64}")
private val EnvLine = Regex("([A-Za-z_][A-Za-z0-9_]*)=(.*)")

/**
 * “MCP 服务器”：Claude Code 自己的 MCP 配置，经 `claude mcp` 增删，和终端里 claude mcp 看到的一样。
 * 列出用户配置，加上默认工作目录的本地配置和 .mcp.json；新加的放在用户配置里。
 * 状态来自 claude mcp list，它会把命令行启动的服务器都起一遍来检查，所以慢，和读配置分两步。
 * Zeta 运行时另外塞给 Claude 的手机工具（SDK 类型的 phone 服务器）不在配置里，这里不显示。
 */
@Composable
internal fun ClaudeMcpScreen(hub: ConversationHub, onBack: () -> Unit) {
    val client = rememberConfigClient(hub)
    val scope = rememberCoroutineScope()
    val cwd = hub.env?.defaultCwd ?: "/"
    var servers by remember { mutableStateOf<List<McpServer>?>(null) }
    var status by remember { mutableStateOf<McpStatus?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<ConfigNotice?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<McpServer?>(null) }
    var reloads by remember { mutableStateOf(0) }

    LaunchedEffect(reloads) {
        // 配置马上就能读出来；健康检查要逐个连接，慢，分两步
        scope.runConfig({ client.mcpServers(cwd) }) { result ->
            result.onSuccess { servers = it }.onFailure { notice = ConfigNotice("读取 MCP 配置失败", it.problem()) }
        }
        status = null
        statusError = null
        scope.runConfig({ client.mcpStatus(cwd) }) { result ->
            result.onSuccess { status = it }.onFailure { statusError = it.problem() }
        }
    }
    val reload: () -> Unit = { reloads++ }

    CouiPage(
        title = "MCP 服务器",
        onBack = onBack,
        actions = {
            IconActionButton(COUIIcons.Refresh, "重新检查", enabled = status != null || statusError != null, onClick = reload)
            IconActionButton(COUIIcons.Add, "添加服务器", onClick = { showAdd = true })
        },
        dialogs = {
            ConfigNoticeDialog(notice) { notice = null }
            AddServerDialog(
                visible = showAdd,
                onDismiss = { showAdd = false },
                onAdd = { draft, done ->
                    scope.runConfig({
                        when (draft.type) {
                            "stdio" -> client.addStdioServer(draft.name, draft.command, draft.args, draft.env)
                            else -> client.addHttpServer(draft.name, draft.type, draft.url, draft.token)
                        }
                    }) { result ->
                        done(result.exceptionOrNull()?.problem())
                        if (result.isSuccess) {
                            showAdd = false
                            reload()
                        }
                    }
                },
            )
        },
    ) {
        val list = servers
        item(key = "servers") {
            PreferenceGroup(
                title = "服务器 · ${list?.size ?: 0}",
                footer = "新加的服务器所有项目都能用，改动对之后新开始的对话生效。" +
                    (statusError?.let { "\n\n检查状态没有成功：$it" } ?: ""),
            ) {
                when {
                    list == null -> item { ConfigLoadingRow() }
                    list.isEmpty() -> item { ConfigEmptyRow("还没有 MCP 服务器", "点右上角的 + 添加") }
                    else -> list.forEach { server ->
                        item(key = server.scope + "/" + server.name) {
                            val state = status.of(server, statusError != null)
                            // 摘要只放地址或命令；类型和被谁盖住在详情里看
                            ArrowPreference(
                                title = server.name,
                                summary = (if (server.scope != "user") scopeLabel(server.scope) + " · " else "") + brief(server.target(), 36),
                                endActions = { PreferenceValue(stateLabel(state), color = stateColor(state)) },
                                onClick = { detail = server },
                            )
                        }
                    }
                }
            }
        }
        val connectors = status?.connectors.orEmpty()
        if (connectors.isNotEmpty()) {
            item(key = "connectors") {
                PreferenceGroup(title = "Claude 账号的连接器", footer = "在 claude.ai 上管理，这里只能看。") {
                    connectors.forEach {
                        item(key = it.name) { BasicComponent(title = it.name.removePrefix("claude.ai "), endActions = { PreferenceValue(stateLabel(it.state), color = stateColor(it.state)) }) }
                    }
                }
            }
        }
    }

    val shown = rememberRetained(detail)
    SlideInPage(visible = detail != null, onBack = { detail = null }) {
        shown?.let { server ->
            McpServerDetail(
                hub = hub,
                server = server,
                state = status.of(server, statusError != null),
                onBack = { detail = null },
                onChanged = reload,
                onRemoved = {
                    detail = null
                    reload()
                },
            )
        }
    }
}

private class ServerDraft(
    val type: String,
    val name: String,
    val url: String,
    val token: String,
    val command: String,
    val args: List<String>,
    val env: Map<String, String>,
)

/** 添加服务器：照 Eta 的对话框（名称、地址、可选的 Bearer Token），另加命令行（stdio）这一种。 */
@Composable
private fun AddServerDialog(visible: Boolean, onDismiss: () -> Unit, onAdd: (ServerDraft, (String?) -> Unit) -> Unit) {
    var type by remember { mutableStateOf("http") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var env by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(visible) {
        if (visible) {
            name = ""; url = ""; token = ""; command = ""; args = ""; env = ""; error = null
        }
    }
    val confirm = {
        val envLines = env.lines().map { it.trim() }.filter { it.isNotEmpty() }
        error = when {
            !ServerName.matches(name.trim()) -> "名称只能用字母、数字、下划线和连字符"
            type != "stdio" && !Regex("https?://\\S+").matches(url.trim()) -> "地址要以 http:// 或 https:// 开头"
            type == "stdio" && command.isBlank() -> "请输入要运行的命令"
            type == "stdio" && envLines.any { !EnvLine.matches(it) } -> "环境变量每行一个，写成 KEY=VALUE"
            else -> null
        }
        if (error == null) {
            working = true
            val draft = ServerDraft(
                type = type,
                name = name.trim(),
                url = url.trim(),
                token = token.trim(),
                command = command.trim(),
                args = splitArgs(args),
                env = envLines.associate { line -> EnvLine.matchEntire(line)!!.let { it.groupValues[1] to it.groupValues[2] } },
            )
            onAdd(draft) { problem ->
                working = false
                error = problem
            }
        }
    }
    val types = listOf("http" to "HTTP", "sse" to "SSE", "stdio" to "命令")
    ConfirmDialog(
        show = visible,
        title = "添加服务器",
        summary = null,
        confirmText = if (working) "正在添加…" else "添加",
        confirmEnabled = !working,
        onConfirm = confirm,
        onDismissRequest = { if (!working) onDismiss() },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            SegmentButton(
                items = types.map { it.second },
                selectedIndex = types.indexOfFirst { it.first == type }.coerceAtLeast(0),
                onSelectedIndexChange = { index -> types.getOrNull(index)?.let { type = it.first } },
                modifier = Modifier.fillMaxWidth(),
                enabled = !working,
                height = SegmentButtonDefaults.HeightSmall,
                padding = SegmentButtonDefaults.PaddingSmall,
            )
            DialogTextField(value = name, onValueChange = { name = it }, title = "名称", hint = "比如 github", enabled = !working)
            if (type == "stdio") {
                DialogTextField(value = command, onValueChange = { command = it }, title = "命令", hint = "比如 npx", enabled = !working)
                DialogTextField(value = args, onValueChange = { args = it }, title = "参数（可选，空格分开）", hint = "-y @modelcontextprotocol/server-everything", enabled = !working)
                DialogTextField(
                    value = env,
                    onValueChange = { env = it },
                    title = "环境变量（可选，每行一个 KEY=VALUE）",
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    enabled = !working,
                )
            } else {
                DialogTextField(
                    value = url,
                    onValueChange = { url = it },
                    title = "服务器地址",
                    hint = "https://",
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                DialogTextField(
                    value = token,
                    onValueChange = { token = it },
                    title = "Bearer Token（可选）",
                    enabled = !working,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            error?.let { Text(text = it, style = COUITheme.textStyles.bodyM, color = COUITheme.colorScheme.error) }
        }
    }
}

/** 一个服务器的详情：配置（秘密都只显示“有没有”）、身份认证、工具清单、删除。 */
@Composable
private fun McpServerDetail(
    hub: ConversationHub,
    server: McpServer,
    state: McpState?,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onRemoved: () -> Unit,
) {
    val client = rememberConfigClient(hub)
    val scope = rememberCoroutineScope()
    val cwd = hub.env?.defaultCwd ?: "/"
    var tools by remember(server) { mutableStateOf<List<McpTool>?>(null) }
    var toolsError by remember(server) { mutableStateOf<String?>(null) }
    var loadingTools by remember(server) { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ConfigNotice?>(null) }
    var auth by remember(server) { mutableStateOf(server.bearer to server.authHeader) }
    val tokenEditable = server.type != "stdio" && server.scope in setOf("user", "local")

    fun loadTools() {
        loadingTools = true
        toolsError = null
        scope.runConfig({ client.mcpTools(server, cwd) }) { result ->
            loadingTools = false
            result.onSuccess { tools = it }.onFailure { toolsError = it.problem() }
        }
    }
    // 项目里的 .mcp.json 可能来自别人的仓库，不自动替它启动命令；要看工具时手动点
    LaunchedEffect(server) { if (server.scope != "project" && !server.oauth) loadTools() }

    CouiPage(
        title = server.name,
        onBack = onBack,
        actions = { IconActionButton(COUIIcons.Refresh, "刷新工具", enabled = !loadingTools, onClick = ::loadTools) },
        dialogs = {
            ConfigNoticeDialog(notice) { notice = null }
            ConfirmDialog(
                show = showToken,
                title = "身份认证",
                summary = "输入新 Token，留空则移除身份认证。原来的 Token 不会显示出来。",
                confirmText = "保存",
                onConfirm = {
                    showToken = false
                    working = true
                    val value = token
                    token = ""
                    scope.runConfig({ client.setMcpToken(server, cwd, value) }) { result ->
                        working = false
                        result.onSuccess {
                            auth = value.isNotBlank() to value.isNotBlank()
                            onChanged()
                            loadTools()
                        }.onFailure { notice = ConfigNotice("没有改成", it.problem()) }
                    }
                },
                onDismissRequest = {
                    token = ""
                    showToken = false
                },
            ) {
                DialogTextField(
                    value = token,
                    onValueChange = { token = it },
                    hint = "Bearer Token",
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            ConfirmDialog(
                show = confirmDelete,
                title = "删除服务器",
                summary = "删除 ${server.name}？它的地址、Token 和设置都会从配置里去掉，无法撤销。",
                confirmText = "删除",
                destructive = true,
                onConfirm = {
                    confirmDelete = false
                    working = true
                    scope.runConfig({ client.removeMcp(server, cwd) }) { result ->
                        working = false
                        result.onSuccess { onRemoved() }.onFailure { notice = ConfigNotice("删除失败", it.problem()) }
                    }
                },
                onDismissRequest = { confirmDelete = false },
            )
        },
    ) {
        item(key = "server") {
            PreferenceGroup(title = "服务器") {
                item { BasicComponent(title = "状态", summary = state?.issue?.ifBlank { null }, endActions = { PreferenceValue(stateLabel(state), color = stateColor(state)) }) }
                item { BasicComponent(title = "范围", endActions = { PreferenceValue(scopeLabel(server.scope)) }) }
                item { BasicComponent(title = "类型", endActions = { PreferenceValue(typeLabel(server.type)) }) }
                if (server.type == "stdio") {
                    item { BasicComponent(title = "命令", summary = server.target()) }
                    if (server.envKeys.isNotEmpty()) {
                        item { BasicComponent(title = "环境变量", summary = server.envKeys.joinToString("、"), endActions = { PreferenceValue("值已隐藏") }) }
                    }
                } else {
                    item { BasicComponent(title = "地址", summary = server.url) }
                    if (server.headerKeys.isNotEmpty()) {
                        item { BasicComponent(title = "其他请求头", summary = server.headerKeys.joinToString("、"), endActions = { PreferenceValue("值已隐藏") }) }
                    }
                    // 照 Eta：身份认证是“服务器”组里的一行。项目的 .mcp.json 可能来自别人的仓库，不替它改
                    item {
                        BasicComponent(
                            title = "身份认证",
                            summary = when {
                                auth.first -> "Bearer Token"
                                auth.second -> "Authorization 请求头"
                                server.oauth -> "OAuth：在终端用 claude 的 /mcp 登录"
                                else -> "未配置"
                            } + if (tokenEditable) "" else " · 在项目里改",
                            enabled = tokenEditable && !working,
                            onClick = if (tokenEditable) ({
                                token = ""
                                showToken = true
                            }) else null,
                        )
                    }
                }
            }
        }
        item(key = "tools") {
            // Zeta 自己按 MCP 协议连上去取工具清单，和 Claude Code 连接时看到的一样；要 OAuth 登录的取不到
            PreferenceGroup(title = tools?.let { "工具 · ${it.size}" } ?: "工具") {
                val list = tools
                when {
                    loadingTools -> item { ConfigLoadingRow("正在连接…") }
                    toolsError != null -> item { BasicComponent(title = "取不到工具清单", summary = toolsError, onClick = ::loadTools) }
                    list == null -> item {
                        BasicComponent(
                            title = "列出工具",
                            summary = if (server.oauth) "用 OAuth 登录的服务器取不到" else "连上服务器取工具清单",
                            enabled = !server.oauth,
                            onClick = ::loadTools,
                        )
                    }
                    list.isEmpty() -> item { ConfigEmptyRow("没有工具") }
                    else -> list.forEach { tool -> item(key = tool.name) { BasicComponent(title = tool.name, summary = brief(tool.description).ifBlank { null }) } }
                }
            }
        }
        item(key = "delete") {
            PreferenceGroup {
                item {
                    BasicComponent(
                        title = "删除服务器",
                        // 经 claude mcp remove 从配置里移除
                        titleColor = BasicComponentDefaults.titleColor(color = COUITheme.colorScheme.error),
                        enabled = !working,
                        onClick = { confirmDelete = true },
                    )
                }
            }
        }
    }
}
