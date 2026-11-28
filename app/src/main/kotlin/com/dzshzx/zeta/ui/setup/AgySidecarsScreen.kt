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
import com.dzshzx.zeta.core.claude.AgySidecar
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.SwitchLoadingPreference

@Composable
internal fun AgySidecarsScreen(hub: ConversationHub, backend: String, onBack: () -> Unit) {
    val client = remember(hub, backend) { AgyConfigCore.of(hub, backend) }
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<AgySidecar>?>(null) }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ConfigNotice?>(null) }

    LaunchedEffect(backend) {
        scope.runConfig({ client.sidecars() }) { res ->
            res.onSuccess { list = it }
                .onFailure {
                    notice = ConfigNotice(Strings.setupAgySidecarsLoadFailed(), it.problem())
                }
        }
    }

    fun toggle(id: String, enabled: Boolean) {
        working = true
        scope.runConfig({ client.setSidecarEnabled(id, enabled) }) { res ->
            working = false
            res.onSuccess { list = it }
                .onFailure {
                    notice = ConfigNotice(Strings.setupClaudeChangeFailed(), it.problem())
                }
        }
    }

    MiuixPage(
        title = "Sidecars",
        onBack = onBack,
        dialogs = { ConfigNoticeDialog(notice) { notice = null } },
    ) {
        val current = list
        item(key = "sidecars") {
            PreferenceGroup(title = "Sidecars", footer = Strings.setupAgySidecarsFooter()) {
                when {
                    current == null -> item { ConfigLoadingRow() }
                    current.isEmpty() ->
                        item { ConfigEmptyRow(Strings.setupAgySidecarsEmpty(), "") }
                    else ->
                        current.forEach { sc ->
                            item(key = sc.id) {
                                SwitchLoadingPreference(
                                    checked = sc.enabled,
                                    onCheckedChange = { on -> toggle(sc.id, on) },
                                    title = sc.displayName,
                                    summary = sc.description.ifBlank { sc.id },
                                    isLoading = working,
                                    enabled = !working,
                                )
                            }
                        }
                }
            }
        }
    }
}
