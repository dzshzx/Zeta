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
import com.dzshzx.zeta.core.claude.ClaudeConfigCore
import com.dzshzx.zeta.core.claude.McpConnector
import com.dzshzx.zeta.core.claude.McpServer
import com.dzshzx.zeta.core.claude.McpState
import com.dzshzx.zeta.core.claude.McpStatus
import com.dzshzx.zeta.core.claude.McpTool
import com.dzshzx.zeta.core.clipLine
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.DialogTextField
import com.dzshzx.zeta.ui.components.coui2.IconActionButton
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.CardListGroupScope
import io.github.suqi8.coui.kmp.basic.SegmentButton
import io.github.suqi8.coui.kmp.basic.SegmentButtonDefaults
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

private fun stateLabel(state: McpState?): String =
    when (state?.state) {
        null -> Strings.setupMcpStateChecking()
        "connected" -> Strings.setupMcpStateConnected()
        "partial" -> Strings.setupMcpStatePartial()
        "failed" -> Strings.setupMcpStateFailed()
        "auth" -> Strings.setupMcpStateAuth()
        "pending" -> Strings.setupMcpStatePending()
        "rejected" -> Strings.setupMcpStateRejected()
        "disabled" -> Strings.setupClaudeDisabled()
        "unconfigured" -> Strings.setupMcpStateUnconfigured()
        "shadowed" -> Strings.setupMcpStateShadowed()
        else -> Strings.setupMcpStateUnknown()
    }

/** 服务器状态在设置行右边的颜色：连上、连不上、要登录各有颜色，其余是普通值的颜色。 */
@Composable
private fun stateColor(state: McpState?): Color =
    when (state?.state) {
        "connected" -> COUITheme.colorScheme.success
        "failed" -> COUITheme.colorScheme.error
        "partial",
        "auth",
        "pending" -> StatusWarning
        else -> Color.Unspecified
    }

private fun typeLabel(type: String): String =
    when (type) {
        "http" -> "HTTP"
        "sse" -> "SSE"
        "stdio" -> Strings.setupMcpTypeCommand()
        "ws" -> "WebSocket"
        else -> type
    }

/** 配置的范围：user 是用户配置，local 是默认工作目录的本地配置（claude mcp add --scope 的叫法）。 */
private fun scopeLabel(scope: String): String =
    when (scope) {
        "user" -> Strings.setupMcpScopeUser()
        "local" -> Strings.setupMcpScopeLocal()
        "project" -> Strings.setupMcpScopeProject()
        else -> scope
    }

private fun McpServer.target(): String =
    if (type == "stdio") (listOf(command) + args).joinToString(" ") else url

/** 列表和详情里显示的状态：被同名服务器盖住的不会被用到，不看健康检查的结果。 */
private fun McpStatus?.of(server: McpServer, failed: Boolean): McpState? {
    val shadowedBy = server.shadowedBy
    return when {
        shadowedBy != null ->
            McpState("shadowed", Strings.setupMcpShadowedBy(scopeLabel(shadowedBy)))
        else ->
            this?.of(server.scope, server.name)
                ?: if (failed || this != null) McpState("unknown", "") else null
    }
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
 * “MCP 服务器”：Claude Code 自己的 MCP 配置，经 `claude mcp` 增删，和终端里 claude mcp 看到的一样。 列出用户配置，加上默认工作目录的本地配置和
 * .mcp.json；新加的放在用户配置里。 状态来自 claude mcp list，它会把命令行启动的服务器都起一遍来检查，所以慢，和读配置分两步。 Zeta 运行时另外塞给 Claude
 * 的手机工具（SDK 类型的 phone 服务器）不在配置里，这里不显示。
 */
@Composable
internal fun ClaudeMcpScreen(hub: ConversationHub, backend: String, onBack: () -> Unit) {
    val client = rememberConfigClient(hub, backend)
    val scope = rememberCoroutineScope()
    val cwd = hub.defaultCwd(backend)
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
            result
                .onSuccess { servers = it }
                .onFailure { notice = ConfigNotice(Strings.setupMcpLoadFailed(), it.problem()) }
        }
        status = null
        statusError = null
        scope.runConfig({ client.mcpStatus(cwd) }) { result ->
            result.onSuccess { status = it }.onFailure { statusError = it.problem() }
        }
    }
    val reload: () -> Unit = { reloads++ }

    CouiPage(
        title = Strings.setupMcpTitle(),
        onBack = onBack,
        actions = {
            IconActionButton(
                COUIIcons.Refresh,
                Strings.setupMcpRecheck(),
                enabled = status != null || statusError != null,
                onClick = reload,
            )
            IconActionButton(COUIIcons.Add, Strings.setupMcpAdd(), onClick = { showAdd = true })
        },
        dialogs = {
            ConfigNoticeDialog(notice) { notice = null }
            AddServerDialog(
                visible = showAdd,
                onDismiss = { showAdd = false },
                onAdd = { draft, done ->
                    scope.runConfig({ client.addFromDraft(draft) }) { result ->
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
            ServersGroup(list, status, statusError, onOpen = { detail = it })
        }
        val connectors = status?.connectors.orEmpty()
        if (connectors.isNotEmpty()) {
            item(key = "connectors") { ConnectorsGroup(connectors) }
        }
    }

    SlideInPage(detail, onBack = { detail = null }) { server ->
        McpServerDetail(
            hub = hub,
            backend = backend,
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

/** 「服务器」一组：配置里的每个服务器和它的状态；检查状态失败时在说明里写原因。 */
@Composable
private fun ServersGroup(
    list: List<McpServer>?,
    status: McpStatus?,
    statusError: String?,
    onOpen: (McpServer) -> Unit,
) {
    PreferenceGroup(
        title = Strings.setupMcpServers(list?.size ?: 0),
        footer =
            Strings.setupMcpServersFooter() +
                (statusError?.let { "\n\n" + Strings.setupMcpStatusFailed(it) } ?: ""),
    ) {
        when {
            list == null -> item { ConfigLoadingRow() }
            list.isEmpty() ->
                item { ConfigEmptyRow(Strings.setupMcpNone(), Strings.setupClaudeTapPlusToAdd()) }
            else ->
                list.forEach { server ->
                    item(key = server.scope + "/" + server.name) {
                        val state = status.of(server, statusError != null)
                        // 摘要只放地址或命令；类型和被谁盖住在详情里看
                        ArrowPreference(
                            title = server.name,
                            summary = serverSummary(server),
                            endActions = {
                                PreferenceValue(stateLabel(state), color = stateColor(state))
                            },
                            onClick = { onOpen(server) },
                        )
                    }
                }
        }
    }
}

/** 列表里一个服务器的摘要：不是用户配置的先写范围，再是截短的地址或命令。 */
private fun serverSummary(server: McpServer): String =
    (if (server.scope != "user") scopeLabel(server.scope) + " · " else "") +
        server.target().clipLine(36)

/** 「Claude 账号的连接器」一组，只能看。 */
@Composable
private fun ConnectorsGroup(connectors: List<McpConnector>) {
    PreferenceGroup(
        title = Strings.setupMcpConnectors(),
        footer = Strings.setupClaudeManagedOnline(),
    ) {
        connectors.forEach {
            item(key = it.name) {
                BasicComponent(
                    title = it.name.removePrefix("claude.ai "),
                    endActions = {
                        PreferenceValue(
                            stateLabel(it.state),
                            color = stateColor(it.state),
                            clickable = false,
                        )
                    },
                )
            }
        }
    }
}

/** 按草稿的类型加一个命令行（stdio）或 HTTP / SSE 服务器。 */
private fun ClaudeConfigCore.addFromDraft(draft: ServerDraft) {
    when (draft.type) {
        "stdio" -> addStdioServer(draft.name, draft.command, draft.args, draft.env)
        else -> addHttpServer(draft.name, draft.type, draft.url, draft.token)
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
private fun AddServerDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onAdd: (ServerDraft, (String?) -> Unit) -> Unit,
) {
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
            name = ""
            url = ""
            token = ""
            command = ""
            args = ""
            env = ""
            error = null
        }
    }
    val confirm = {
        val envLines = env.lines().map { it.trim() }.filter { it.isNotEmpty() }
        error =
            when {
                !ServerName.matches(name.trim()) -> Strings.setupMcpNameInvalid()
                type != "stdio" && !Regex("https?://\\S+").matches(url.trim()) ->
                    Strings.setupMcpUrlInvalid()
                type == "stdio" && command.isBlank() -> Strings.setupMcpCommandMissing()
                type == "stdio" && envLines.any { !EnvLine.matches(it) } ->
                    Strings.setupMcpEnvInvalid()
                else -> null
            }
        if (error == null) {
            working = true
            val draft =
                ServerDraft(
                    type = type,
                    name = name.trim(),
                    url = url.trim(),
                    token = token.trim(),
                    command = command.trim(),
                    args = splitArgs(args),
                    env =
                        envLines.associate { line ->
                            EnvLine.matchEntire(line)!!.let {
                                it.groupValues[1] to it.groupValues[2]
                            }
                        },
                )
            onAdd(draft) { problem ->
                working = false
                error = problem
            }
        }
    }
    val types = listOf("http" to "HTTP", "sse" to "SSE", "stdio" to Strings.setupMcpTypeCommand())
    ConfirmDialog(
        show = visible,
        title = Strings.setupMcpAdd(),
        summary = null,
        confirmText = if (working) Strings.setupMcpAdding() else Strings.setupAdd(),
        confirmEnabled = !working,
        onConfirm = confirm,
        onDismissRequest = { if (!working) onDismiss() },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SegmentButton(
                items = types.map { it.second },
                selectedIndex = types.indexOfFirst { it.first == type }.coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    types.getOrNull(index)?.let { type = it.first }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !working,
                height = SegmentButtonDefaults.HeightSmall,
                padding = SegmentButtonDefaults.PaddingSmall,
            )
            DialogTextField(
                value = name,
                onValueChange = { name = it },
                title = Strings.setupMcpName(),
                hint = Strings.setupMcpNameHint(),
                enabled = !working,
            )
            if (type == "stdio") {
                DialogTextField(
                    value = command,
                    onValueChange = { command = it },
                    title = Strings.setupMcpTypeCommand(),
                    hint = Strings.setupMcpCommandHint(),
                    enabled = !working,
                )
                DialogTextField(
                    value = args,
                    onValueChange = { args = it },
                    title = Strings.setupMcpArgs(),
                    hint = "-y @modelcontextprotocol/server-everything",
                    enabled = !working,
                )
                DialogTextField(
                    value = env,
                    onValueChange = { env = it },
                    title = Strings.setupMcpEnv(),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    enabled = !working,
                )
            } else {
                DialogTextField(
                    value = url,
                    onValueChange = { url = it },
                    title = Strings.setupMcpUrl(),
                    hint = "https://",
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                DialogTextField(
                    value = token,
                    onValueChange = { token = it },
                    title = Strings.setupMcpTokenOptional(),
                    enabled = !working,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
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

/** 一个服务器的详情：配置（秘密都只显示“有没有”）、身份认证、工具清单、删除。 */
@Composable
private fun McpServerDetail(
    hub: ConversationHub,
    backend: String,
    server: McpServer,
    state: McpState?,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onRemoved: () -> Unit,
) {
    val client = rememberConfigClient(hub, backend)
    val scope = rememberCoroutineScope()
    val cwd = hub.defaultCwd(backend)
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
        scope.runConfig({ client.mcpTools(server.scope, server.name, cwd) }) { result ->
            loadingTools = false
            result.onSuccess { tools = it }.onFailure { toolsError = it.problem() }
        }
    }
    fun saveToken() {
        showToken = false
        working = true
        val value = token
        token = ""
        scope.runConfig({ client.setMcpToken(server, cwd, value) }) { result ->
            working = false
            result
                .onSuccess {
                    auth = value.isNotBlank() to value.isNotBlank()
                    onChanged()
                    loadTools()
                }
                .onFailure {
                    notice = ConfigNotice(Strings.setupClaudeChangeFailed(), it.problem())
                }
        }
    }

    fun remove() {
        confirmDelete = false
        working = true
        scope.runConfig({ client.removeMcp(server, cwd) }) { result ->
            working = false
            result
                .onSuccess { onRemoved() }
                .onFailure {
                    notice = ConfigNotice(Strings.setupClaudeDeleteFailed(), it.problem())
                }
        }
    }
    // 项目里的 .mcp.json 可能来自别人的仓库，不自动替它启动命令；要看工具时手动点
    LaunchedEffect(server) { if (server.scope != "project" && !server.oauth) loadTools() }

    CouiPage(
        title = server.name,
        onBack = onBack,
        actions = {
            IconActionButton(
                COUIIcons.Refresh,
                Strings.setupMcpRefreshTools(),
                enabled = !loadingTools,
                onClick = ::loadTools,
            )
        },
        dialogs = {
            ConfigNoticeDialog(notice) { notice = null }
            ConfirmDialog(
                show = showToken,
                title = Strings.setupMcpAuth(),
                summary = Strings.setupMcpAuthSummary(),
                confirmText = Strings.setupClaudeSave(),
                onConfirm = ::saveToken,
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
                title = Strings.setupMcpDelete(),
                summary = Strings.setupMcpDeleteSummary(server.name),
                confirmText = Strings.setupClaudeDelete(),
                destructive = true,
                onConfirm = ::remove,
                onDismissRequest = { confirmDelete = false },
            )
        },
    ) {
        item(key = "server") {
            ServerInfoGroup(
                server = server,
                state = state,
                auth = auth,
                tokenEditable = tokenEditable,
                working = working,
                onEditToken = {
                    token = ""
                    showToken = true
                },
            )
        }
        item(key = "tools") {
            ToolsGroup(
                tools = tools,
                loading = loadingTools,
                error = toolsError,
                oauth = server.oauth,
                onLoad = ::loadTools,
            )
        }
        item(key = "delete") {
            PreferenceGroup {
                item {
                    BasicComponent(
                        title = Strings.setupMcpDelete(),
                        // 经 claude mcp remove 从配置里移除
                        titleColor =
                            BasicComponentDefaults.titleColor(color = COUITheme.colorScheme.error),
                        enabled = !working,
                        onClick = { confirmDelete = true },
                    )
                }
            }
        }
    }
}

/** 详情里的「服务器」一组：状态、范围、类型，再按类型列命令或地址、请求头和身份认证。 */
@Composable
private fun ServerInfoGroup(
    server: McpServer,
    state: McpState?,
    auth: Pair<Boolean, Boolean>,
    tokenEditable: Boolean,
    working: Boolean,
    onEditToken: () -> Unit,
) {
    PreferenceGroup(title = Strings.setupMcpServer()) {
        item {
            BasicComponent(
                title = Strings.setupStatus(),
                summary = state?.issue?.ifBlank { null },
                endActions = {
                    PreferenceValue(stateLabel(state), color = stateColor(state), clickable = false)
                },
            )
        }
        item {
            BasicComponent(
                title = Strings.setupMcpScope(),
                endActions = { PreferenceValue(scopeLabel(server.scope), clickable = false) },
            )
        }
        item {
            BasicComponent(
                title = Strings.setupMcpType(),
                endActions = { PreferenceValue(typeLabel(server.type), clickable = false) },
            )
        }
        if (server.type == "stdio") {
            stdioRows(server)
        } else {
            remoteRows(server, auth, tokenEditable, working, onEditToken)
        }
    }
}

private fun CardListGroupScope.stdioRows(server: McpServer) {
    item { BasicComponent(title = Strings.setupMcpTypeCommand(), summary = server.target()) }
    if (server.envKeys.isNotEmpty()) {
        item {
            BasicComponent(
                title = Strings.setupMcpEnvKeys(),
                summary = server.envKeys.joinToString(Strings.setupListSeparator()),
                endActions = { PreferenceValue(Strings.setupMcpValuesHidden(), clickable = false) },
            )
        }
    }
}

private fun CardListGroupScope.remoteRows(
    server: McpServer,
    auth: Pair<Boolean, Boolean>,
    tokenEditable: Boolean,
    working: Boolean,
    onEditToken: () -> Unit,
) {
    item { BasicComponent(title = Strings.setupMcpAddress(), summary = server.url) }
    if (server.headerKeys.isNotEmpty()) {
        item {
            BasicComponent(
                title = Strings.setupMcpHeaders(),
                summary = server.headerKeys.joinToString(Strings.setupListSeparator()),
                endActions = { PreferenceValue(Strings.setupMcpValuesHidden(), clickable = false) },
            )
        }
    }
    // 照 Eta：身份认证是“服务器”组里的一行。项目的 .mcp.json 可能来自别人的仓库，不替它改
    item {
        BasicComponent(
            title = Strings.setupMcpAuth(),
            summary =
                authSummary(auth, server.oauth).let {
                    if (tokenEditable) it else Strings.setupMcpAuthInProject(it)
                },
            enabled = tokenEditable && !working,
            onClick = if (tokenEditable) onEditToken else null,
        )
    }
}

/** [auth] 是（有 Bearer Token，有 Authorization 请求头）。 */
private fun authSummary(auth: Pair<Boolean, Boolean>, oauth: Boolean): String =
    when {
        auth.first -> "Bearer Token"
        auth.second -> Strings.setupMcpAuthHeader()
        oauth -> Strings.setupMcpAuthOauth()
        else -> Strings.setupMcpStateUnconfigured()
    }

/** 详情里的「工具」一组：连接中、出错、还没取（可以手动取）、空的，或者工具清单。 */
@Composable
private fun ToolsGroup(
    tools: List<McpTool>?,
    loading: Boolean,
    error: String?,
    oauth: Boolean,
    onLoad: () -> Unit,
) {
    // Zeta 自己按 MCP 协议连上去取工具清单，和 Claude Code 连接时看到的一样；要 OAuth 登录的取不到
    PreferenceGroup(
        title = tools?.let { Strings.setupClaudeToolsCount(it.size) } ?: Strings.setupClaudeTools(),
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
                            if (oauth) Strings.setupClaudeListToolsOauth()
                            else Strings.setupClaudeListToolsHint(),
                        enabled = !oauth,
                        onClick = onLoad,
                    )
                }
            tools.isEmpty() -> item { ConfigEmptyRow(Strings.setupClaudeNoTools()) }
            else ->
                tools.forEach { tool ->
                    item(key = tool.name) {
                        BasicComponent(
                            title = tool.name,
                            summary = tool.description.clipLine(90).ifBlank { null },
                        )
                    }
                }
        }
    }
}
