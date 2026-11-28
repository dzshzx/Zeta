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
import com.dzshzx.zeta.core.claude.AgyPlugin
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.ConfirmDialog
import com.dzshzx.zeta.ui.components.miuix.DialogTextField
import com.dzshzx.zeta.ui.components.miuix.IconActionButton
import com.dzshzx.zeta.ui.components.miuix.MenuEntry
import com.dzshzx.zeta.ui.components.miuix.MenuItem
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.RowMoreMenu
import com.dzshzx.zeta.ui.components.miuix.SwitchLoadingPreference
import com.dzshzx.zeta.ui.components.rememberRetained
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add

@Composable
internal fun AgyPluginsScreen(hub: ConversationHub, backend: String, onBack: () -> Unit) {
    val client = remember(hub, backend) { AgyConfigCore.of(hub, backend) }
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<AgyPlugin>?>(null) }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ConfigNotice?>(null) }
    var showInstall by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<AgyPlugin?>(null) }

    fun runOp(op: () -> List<AgyPlugin>) {
        working = true
        scope.runConfig(op) { res ->
            working = false
            res.onSuccess { list = it }
                .onFailure {
                    notice = ConfigNotice(Strings.setupClaudeChangeFailed(), it.problem())
                }
        }
    }

    LaunchedEffect(backend) {
        scope.runConfig({ client.plugins() }) { res ->
            res.onSuccess { list = it }
                .onFailure {
                    notice = ConfigNotice(Strings.setupAgyPluginsLoadFailed(), it.problem())
                }
        }
    }

    val shownRemoving = rememberRetained(removing)
    MiuixPage(
        title = Strings.setupAgyPluginsTitle(),
        onBack = onBack,
        actions = {
            IconActionButton(MiuixIcons.Add, Strings.setupAgyPluginInstall()) {
                target = ""
                showInstall = true
            }
        },
        dialogs = {
            ConfigNoticeDialog(notice) { notice = null }
            ConfirmDialog(
                show = showInstall,
                title = Strings.setupAgyPluginInstall(),
                summary = null,
                confirmText = Strings.setupAgyPluginInstall(),
                confirmEnabled = target.isNotBlank() && !working,
                onConfirm = {
                    val t = target.trim()
                    showInstall = false
                    runOp { client.installPlugin(t) }
                },
                onDismissRequest = { showInstall = false },
            ) {
                DialogTextField(
                    value = target,
                    onValueChange = { target = it },
                    title = Strings.setupAgyPluginInstall(),
                    hint = "plugin-name / path / URL",
                    enabled = !working,
                )
            }
            ConfirmDialog(
                show = removing != null,
                title = Strings.setupAgyPluginUninstall(),
                summary = shownRemoving?.name,
                confirmText = Strings.setupClaudeDelete(),
                destructive = true,
                onConfirm = {
                    val name = removing?.name
                    removing = null
                    if (name != null) runOp { client.uninstallPlugin(name) }
                },
                onDismissRequest = { removing = null },
            )
        },
    ) {
        val current = list
        item(key = "plugins") {
            PreferenceGroup(
                title = Strings.setupAgyPluginsTitle(),
                footer = Strings.setupAgyPluginsFooter(),
            ) {
                when {
                    current == null -> item { ConfigLoadingRow() }
                    current.isEmpty() -> item { ConfigEmptyRow(Strings.setupAgyPluginsEmpty(), "") }
                    else ->
                        current.forEach { plugin ->
                            item(key = plugin.name) {
                                PluginRow(
                                    plugin = plugin,
                                    working = working,
                                    onToggle = { on ->
                                        runOp { client.setPluginEnabled(plugin.name, on) }
                                    },
                                    onUninstall = { removing = plugin },
                                )
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun PluginRow(
    plugin: AgyPlugin,
    working: Boolean,
    onToggle: (Boolean) -> Unit,
    onUninstall: () -> Unit,
) {
    val summary =
        listOf(plugin.version, plugin.description)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { null }
    SwitchLoadingPreference(
        checked = plugin.enabled,
        onCheckedChange = onToggle,
        title = plugin.name,
        summary = summary,
        isLoading = working,
        enabled = !working,
        endActions = {
            RowMoreMenu(
                contentDescription = Strings.setupClaudeMoreFor(plugin.name),
                entries =
                    listOf(
                        MenuEntry(
                            items =
                                listOf(
                                    MenuItem(
                                        text = Strings.setupClaudeDelete(),
                                        enabled = !working,
                                        onClick = onUninstall,
                                        alert = true,
                                    ),
                                ),
                        ),
                    ),
            )
        },
    )
}
