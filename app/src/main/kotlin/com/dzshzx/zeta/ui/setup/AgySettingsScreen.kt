package com.dzshzx.zeta.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.claude.AgyConfigCore
import com.dzshzx.zeta.core.claude.AgySettings
import com.dzshzx.zeta.core.claude.jsonStringArray
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.DropdownPreference
import com.dzshzx.zeta.ui.components.miuix.FieldGroup
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.TextField
import com.dzshzx.zeta.ui.components.miuix.TopBarTextButton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.preference.SwitchPreference

private val AgyToolPerms =
    listOf("", "request-review", "proceed-in-sandbox", "always-proceed", "strict")
private val AgyReviewPols = listOf("", "asks-for-review", "agent-decides", "always-proceed")
private val AgyQueuePols = listOf("", "queue", "send-immediately")

@Composable
internal fun AgySettingsScreen(hub: ConversationHub, backend: String, onBack: () -> Unit) {
    val client = remember(hub, backend) { AgyConfigCore.of(hub, backend) }
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<AgySettings?>(null) }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ConfigNotice?>(null) }
    var planner by remember { mutableStateOf("") }
    var executor by remember { mutableStateOf("") }
    var customModels by remember { mutableStateOf("") }
    var gcpProject by remember { mutableStateOf("") }
    var gcpLocation by remember { mutableStateOf("") }
    var proxyHttp by remember { mutableStateOf("") }
    var proxyHttps by remember { mutableStateOf("") }
    var proxyNo by remember { mutableStateOf("") }
    var proxyUser by remember { mutableStateOf("") }
    var proxyPass by remember { mutableStateOf("") }
    var workspaces by remember { mutableStateOf("") }

    fun applyData(d: AgySettings) {
        data = d
        planner = d.orchestrator.planner
        executor = d.orchestrator.executor
        customModels = d.customModelsConfig
        gcpProject = d.gcp.project
        gcpLocation = d.gcp.location
        proxyHttp = d.sandboxProxy.http
        proxyHttps = d.sandboxProxy.https
        proxyNo = d.sandboxProxy.noProxy
        proxyUser = d.sandboxProxy.username
        proxyPass = ""
        workspaces = d.trustedWorkspaces.joinToString("\n")
    }

    fun patch(body: JsonObject) {
        working = true
        scope.runConfig({ client.updateAgySettings(body) }) { res ->
            working = false
            res.onSuccess(::applyData).onFailure {
                notice = ConfigNotice(Strings.setupClaudeChangeFailed(), it.problem())
            }
        }
    }

    LaunchedEffect(backend) {
        scope.runConfig({ client.agySettings() }) { res ->
            res.onSuccess(::applyData).onFailure {
                notice = ConfigNotice(Strings.setupAgySettingsLoadFailed(), it.problem())
            }
        }
    }

    fun saveFields() {
        val ws = workspaces.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val proxy = buildJsonObject {
            put("http", proxyHttp)
            put("https", proxyHttps)
            put("noProxy", proxyNo)
            put("username", proxyUser)
            if (proxyPass.isNotEmpty()) put("password", proxyPass)
        }
        patch(
            buildJsonObject {
                put(
                    "orchestrator",
                    buildJsonObject {
                        put("planner", planner)
                        put("executor", executor)
                    },
                )
                put("sandboxProxy", proxy)
                put("trustedWorkspaces", jsonStringArray(ws))
                put("customModelsConfig", customModels)
                put(
                    "gcp",
                    buildJsonObject {
                        put("project", gcpProject)
                        put("location", gcpLocation)
                    },
                )
            },
        )
    }

    MiuixPage(
        title = Strings.setupAgySettingsTitle(),
        onBack = onBack,
        actions = {
            TopBarTextButton(
                text = if (working) Strings.setupClaudeSaving() else Strings.setupClaudeSave(),
                enabled = data != null && !working,
                onClick = ::saveFields,
            )
        },
        dialogs = { ConfigNoticeDialog(notice) { notice = null } },
    ) {
        val current = data
        if (current == null) {
            item(key = "loading") { PreferenceGroup { item { ConfigLoadingRow() } } }
            return@MiuixPage
        }
        item(key = "policy") { AgyPolicyGroup(current, working, ::patch) }
        item(key = "models") {
            AgyModelsGroup(
                planner = planner,
                onPlanner = { planner = it },
                executor = executor,
                onExecutor = { executor = it },
                customModels = customModels,
                onCustomModels = { customModels = it },
                gcpProject = gcpProject,
                onGcpProject = { gcpProject = it },
                gcpLocation = gcpLocation,
                onGcpLocation = { gcpLocation = it },
                working = working,
            )
        }
        item(key = "proxy") {
            AgyProxyGroup(
                hasPassword = current.sandboxProxy.hasPassword,
                http = proxyHttp,
                onHttp = { proxyHttp = it },
                https = proxyHttps,
                onHttps = { proxyHttps = it },
                noProxy = proxyNo,
                onNoProxy = { proxyNo = it },
                username = proxyUser,
                onUsername = { proxyUser = it },
                password = proxyPass,
                onPassword = { proxyPass = it },
                working = working,
                onClearPassword = {
                    patch(
                        buildJsonObject {
                            put("sandboxProxy", buildJsonObject { put("clearPassword", true) })
                        },
                    )
                },
            )
        }
        item(key = "workspaces") {
            FieldGroup(
                title = Strings.setupAgyTrustedWorkspaces(),
                footer = Strings.setupAgyTrustedWorkspacesFooter(),
            ) {
                TextField(
                    value = workspaces,
                    onValueChange = { workspaces = it },
                    label = "/path/to/workspace",
                    enabled = !working,
                    minLines = 3,
                    maxLines = 8,
                )
            }
        }
    }
}

@Composable
private fun AgyPolicyGroup(
    current: AgySettings,
    working: Boolean,
    onPatch: (JsonObject) -> Unit,
) {
    PreferenceGroup(title = Strings.setupAgyPolicyGroup()) {
        item {
            AgyDropdown(
                title = Strings.setupAgyToolPerm(),
                values = AgyToolPerms,
                selected = current.toolPermission,
                enabled = !working,
            ) {
                onPatch(buildJsonObject { put("toolPermission", it) })
            }
        }
        item {
            SwitchPreference(
                title = Strings.setupAgySandbox(),
                checked = current.enableTerminalSandbox,
                enabled = !working,
                onCheckedChange = {
                    onPatch(buildJsonObject { put("enableTerminalSandbox", it) })
                },
            )
        }
        item {
            SwitchPreference(
                title = Strings.setupAgyNonWorkspace(),
                checked = current.allowNonWorkspaceAccess,
                enabled = !working,
                onCheckedChange = {
                    onPatch(buildJsonObject { put("allowNonWorkspaceAccess", it) })
                },
            )
        }
        item {
            AgyDropdown(
                title = Strings.setupAgyArtifactReview(),
                values = AgyReviewPols,
                selected = current.artifactReviewPolicy,
                enabled = !working,
            ) {
                onPatch(buildJsonObject { put("artifactReviewPolicy", it) })
            }
        }
        item {
            SwitchPreference(
                title = Strings.setupAgyUseG1Credits(),
                checked = current.useG1Credits,
                enabled = !working,
                onCheckedChange = { onPatch(buildJsonObject { put("useG1Credits", it) }) },
            )
        }
        item {
            AgyDropdown(
                title = Strings.setupAgyQueuedMessages(),
                values = AgyQueuePols,
                selected = current.queuedMessages,
                enabled = !working,
            ) {
                onPatch(buildJsonObject { put("queuedMessages", it) })
            }
        }
    }
}

@Composable
private fun AgyDropdown(
    title: String,
    values: List<String>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val labels = values.map { it.ifEmpty { Strings.commonFollowAntigravity() } }
    val idx = values.indexOf(selected).coerceAtLeast(0)
    DropdownPreference(
        items = labels,
        selectedIndex = idx,
        title = title,
        enabled = enabled,
        onSelectedIndexChange = { i -> values.getOrNull(i)?.let(onSelect) },
    )
}

@Composable
private fun AgyModelsGroup(
    planner: String,
    onPlanner: (String) -> Unit,
    executor: String,
    onExecutor: (String) -> Unit,
    customModels: String,
    onCustomModels: (String) -> Unit,
    gcpProject: String,
    onGcpProject: (String) -> Unit,
    gcpLocation: String,
    onGcpLocation: (String) -> Unit,
    working: Boolean,
) {
    FieldGroup(title = Strings.setupAgyModelsGcpGroup()) {
        TextField(
            value = planner,
            onValueChange = onPlanner,
            label = "Planner",
            enabled = !working,
            singleLine = true,
        )
        TextField(
            value = executor,
            onValueChange = onExecutor,
            label = "Executor",
            enabled = !working,
            singleLine = true,
        )
        TextField(
            value = customModels,
            onValueChange = onCustomModels,
            label = Strings.setupAgyCustomModels(),
            enabled = !working,
            singleLine = true,
        )
        TextField(
            value = gcpProject,
            onValueChange = onGcpProject,
            label = "GCP Project",
            enabled = !working,
            singleLine = true,
        )
        TextField(
            value = gcpLocation,
            onValueChange = onGcpLocation,
            label = "GCP Location",
            enabled = !working,
            singleLine = true,
        )
    }
}

@Composable
private fun AgyProxyGroup(
    hasPassword: Boolean,
    http: String,
    onHttp: (String) -> Unit,
    https: String,
    onHttps: (String) -> Unit,
    noProxy: String,
    onNoProxy: (String) -> Unit,
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    working: Boolean,
    onClearPassword: () -> Unit,
) {
    FieldGroup(title = Strings.setupAgyProxyGroup()) {
        TextField(
            value = http,
            onValueChange = onHttp,
            label = "HTTP Proxy",
            enabled = !working,
            singleLine = true,
        )
        TextField(
            value = https,
            onValueChange = onHttps,
            label = "HTTPS Proxy",
            enabled = !working,
            singleLine = true,
        )
        TextField(
            value = noProxy,
            onValueChange = onNoProxy,
            label = "No Proxy",
            enabled = !working,
            singleLine = true,
        )
        TextField(
            value = username,
            onValueChange = onUsername,
            label = "Username",
            enabled = !working,
            singleLine = true,
        )
        TextField(
            value = password,
            onValueChange = onPassword,
            label =
                if (hasPassword) Strings.setupAgyProxyPassSet()
                else Strings.setupAgyProxyPassUnset(),
            enabled = !working,
            singleLine = true,
        )
        if (hasPassword) {
            BasicComponent(
                title = Strings.setupAgyProxyClearPass(),
                enabled = !working,
                onClick = onClearPassword,
            )
        }
    }
}
