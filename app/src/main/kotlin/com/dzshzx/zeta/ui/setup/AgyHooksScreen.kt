package com.dzshzx.zeta.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.dzshzx.zeta.core.claude.AgyConfigCore
import com.dzshzx.zeta.core.claude.AgyHookDraft
import com.dzshzx.zeta.core.claude.AgyHookEntry
import com.dzshzx.zeta.core.claude.AgyHooksView
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.ConfirmDialog
import com.dzshzx.zeta.ui.components.miuix.DialogTextField
import com.dzshzx.zeta.ui.components.miuix.IconActionButton
import com.dzshzx.zeta.ui.components.miuix.MenuEntry
import com.dzshzx.zeta.ui.components.miuix.MenuItem
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.PreferenceValue
import com.dzshzx.zeta.ui.components.miuix.RowMoreMenu
import com.dzshzx.zeta.ui.components.miuix.SegmentedTabs
import com.dzshzx.zeta.ui.components.miuix.SwitchLoadingPreference
import com.dzshzx.zeta.ui.components.rememberRetained
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add

private val HookEvents =
    listOf("SessionStart", "PreInvocation", "PreToolUse", "PostToolUse", "PostInvocation", "Stop")

@Composable
internal fun AgyHooksScreen(hub: ConversationHub, backend: String, onBack: () -> Unit) {
    val client = remember(hub, backend) { AgyConfigCore.of(hub, backend) }
    val cwd = hub.defaultCwd(backend)
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<AgyHooksView?>(null) }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ConfigNotice?>(null) }
    var editing by remember { mutableStateOf<AgyHookDraft?>(null) }
    var deleting by remember { mutableStateOf<AgyHookEntry?>(null) }

    fun runOp(op: () -> AgyHooksView) {
        working = true
        scope.runConfig(op) { res ->
            working = false
            res.onSuccess { view = it }
                .onFailure {
                    notice = ConfigNotice(Strings.setupClaudeChangeFailed(), it.problem())
                }
        }
    }

    LaunchedEffect(backend, cwd) {
        scope.runConfig({ client.hooks(cwd) }) { res ->
            res.onSuccess { view = it }
                .onFailure {
                    notice = ConfigNotice(Strings.setupAgyHooksLoadFailed(), it.problem())
                }
        }
    }

    val shownDeleting = rememberRetained(deleting)
    MiuixPage(
        title = Strings.setupAgyHooksTitle(),
        onBack = onBack,
        actions = {
            IconActionButton(MiuixIcons.Add, Strings.setupAgyHookAdd()) {
                editing = AgyHookDraft("", "", "user", true, "PreToolUse", "", "", 0)
            }
        },
        dialogs = {
            ConfigNoticeDialog(notice) { notice = null }
            HookEditorDialog(
                draft = editing,
                working = working,
                onDismiss = { editing = null },
                onSave = { draft ->
                    editing = null
                    runOp { client.saveHook(cwd, draft) }
                },
            )
            ConfirmDialog(
                show = deleting != null,
                title = Strings.setupClaudeDelete(),
                summary = shownDeleting?.name,
                confirmText = Strings.setupClaudeDelete(),
                destructive = true,
                onConfirm = {
                    val h = deleting
                    deleting = null
                    if (h != null) runOp { client.deleteHook(cwd, h.scope, h.name) }
                },
                onDismissRequest = { deleting = null },
            )
        },
    ) {
        val current = view?.hooks
        item(key = "hooks") {
            PreferenceGroup(
                title = Strings.setupAgyHooksTitle(),
                footer = Strings.setupAgyHooksFooter(),
            ) {
                when {
                    current == null -> item { ConfigLoadingRow() }
                    current.isEmpty() -> item { ConfigEmptyRow(Strings.setupAgyHooksEmpty(), "") }
                    else ->
                        current.forEach { hook ->
                            item(key = "${hook.scope}:${hook.plugin}:${hook.name}") {
                                HookRow(
                                    hook = hook,
                                    working = working,
                                    onToggle = { on ->
                                        runOp {
                                            client.setHookEnabled(cwd, hook.scope, hook.name, on)
                                        }
                                    },
                                    onEdit = {
                                        editing =
                                            AgyHookDraft(
                                                originalName = hook.name,
                                                name = hook.name,
                                                scope = hook.scope,
                                                enabled = hook.enabled,
                                                event = hook.event.ifBlank { "PreToolUse" },
                                                matcher = hook.matcher,
                                                command = hook.command,
                                                timeout = hook.timeout,
                                            )
                                    },
                                    onDelete = { deleting = hook },
                                )
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun HookRow(
    hook: AgyHookEntry,
    working: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val scopePart = if (hook.plugin.isNotBlank()) "${hook.scope} (${hook.plugin})" else hook.scope
    val summary =
        listOf(scopePart, hook.events.joinToString(", "), hook.command)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    if (hook.readOnly) {
        BasicComponent(
            title = hook.name,
            summary = summary,
            endActions = { PreferenceValue(Strings.setupAgyHookReadOnly(), clickable = false) },
        )
        return
    }
    SwitchLoadingPreference(
        checked = hook.enabled,
        onCheckedChange = onToggle,
        title = hook.name,
        summary = summary,
        isLoading = working,
        enabled = !working,
        endActions = {
            RowMoreMenu(
                contentDescription = Strings.setupClaudeMoreFor(hook.name),
                entries =
                    listOf(
                        MenuEntry(
                            items =
                                listOf(
                                    MenuItem(text = Strings.setupClaudeEdit(), onClick = onEdit),
                                    MenuItem(
                                        text = Strings.setupClaudeDelete(),
                                        onClick = onDelete,
                                        alert = true,
                                    ),
                                ),
                        ),
                    ),
            )
        },
    )
}

@Composable
private fun HookEditorDialog(
    draft: AgyHookDraft?,
    working: Boolean,
    onDismiss: () -> Unit,
    onSave: (AgyHookDraft) -> Unit,
) {
    val shown = rememberRetained(draft)
    var name by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf("user") }
    var event by remember { mutableStateOf("PreToolUse") }
    var matcher by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var timeout by remember { mutableStateOf("") }
    LaunchedEffect(draft) {
        if (draft != null) {
            name = draft.name
            scope = draft.scope.ifBlank { "user" }
            event = draft.event.ifBlank { "PreToolUse" }
            matcher = draft.matcher
            command = draft.command
            timeout = if (draft.timeout > 0) draft.timeout.toString() else ""
        }
    }
    ConfirmDialog(
        show = draft != null,
        title = Strings.setupAgyHooksTitle(),
        summary = null,
        confirmText = Strings.setupClaudeSave(),
        confirmEnabled = name.isNotBlank() && command.isNotBlank() && !working,
        onConfirm = {
            val cur = shown ?: return@ConfirmDialog
            onSave(
                cur.copy(
                    name = name.trim(),
                    scope = scope,
                    event = event,
                    matcher = matcher.trim(),
                    command = command.trim(),
                    timeout = timeout.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0,
                ),
            )
        },
        onDismissRequest = onDismiss,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val scopes = listOf("user", "project")
            SegmentedTabs(
                tabs = scopes,
                selectedIndex = scopes.indexOf(scope).coerceAtLeast(0),
                onSelect = { i -> scopes.getOrNull(i)?.let { scope = it } },
                modifier = Modifier.fillMaxWidth(),
            )
            DialogTextField(
                value = name,
                onValueChange = { name = it },
                title = "Name",
                hint = "hook-name",
            )
            DialogTextField(
                value = event,
                onValueChange = { event = it },
                title = "Event",
                hint = HookEvents.joinToString(" / "),
            )
            DialogTextField(
                value = matcher,
                onValueChange = { matcher = it },
                title = "Matcher",
                hint = "optional",
            )
            DialogTextField(
                value = command,
                onValueChange = { command = it },
                title = "Command",
                hint = "echo ok",
            )
            DialogTextField(
                value = timeout,
                onValueChange = { timeout = it },
                title = "Timeout (s)",
                hint = "optional",
            )
        }
    }
}
