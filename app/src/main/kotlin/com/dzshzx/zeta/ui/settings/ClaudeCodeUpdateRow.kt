package com.dzshzx.zeta.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.conv.ClaudeUpdate
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.PreferenceValue
import com.dzshzx.zeta.ui.components.miuix.SettingsIcon
import com.dzshzx.zeta.ui.setup.problem
import com.dzshzx.zeta.ui.setup.runConfig
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Update

/**
 * 远程主机页「Claude Code」：右边是那台主机上的版本，点按在那台上跑 claude update（docs/core.md「主机」的 system.claude、
 * system.claude_update）。已经在跑的对话不受影响，之后起的用新版本。手机本机的在设置 › 手机 › Linux 环境里。
 */
@Composable
internal fun ClaudeCodeUpdateRow(hub: ConversationHub, backend: String, ready: Boolean) {
    CliUpdateRow(
        title = "Claude Code",
        key = "$backend:claude",
        ready = ready,
        loadVersion = { hub.convLinks.claudeVersion(backend) },
        runUpdate = { hub.convLinks.updateClaude(backend) },
    )
}

/** 主机页「Antigravity CLI」：右边是那台主机上的版本，点按一键更新（system.agy、system.agy_update）。 */
@Composable
internal fun AgyUpdateRow(hub: ConversationHub, backend: String, ready: Boolean) {
    CliUpdateRow(
        title = "Antigravity CLI",
        key = "$backend:agy",
        ready = ready,
        loadVersion = { hub.convLinks.agyVersion(backend) },
        runUpdate = { hub.convLinks.updateAgy(backend) },
    )
}

@Composable
private fun CliUpdateRow(
    title: String,
    key: String,
    ready: Boolean,
    loadVersion: () -> String?,
    runUpdate: () -> ClaudeUpdate,
) {
    var version by remember(key) { mutableStateOf<String?>(null) }
    var loaded by remember(key) { mutableStateOf(false) }
    var updating by remember(key) { mutableStateOf(false) }
    var note by remember(key) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(key, ready) {
        if (ready) {
            scope.runConfig(loadVersion) { result ->
                result
                    .onSuccess { version = it }
                    .onFailure { note = Strings.settingsClaudeUpdateVersionFailed(it.problem()) }
                loaded = result.isSuccess
            }
        }
    }
    val usable = ready && loaded && !updating
    BasicComponent(
        title = title,
        summary =
            if (updating) Strings.settingsClaudeUpdateUpdating()
            else note ?: Strings.settingsClaudeUpdateHint(),
        startAction = { SettingsIcon(MiuixIcons.Update, enabled = usable) },
        endActions = {
            PreferenceValue(
                if (!loaded) "…" else version ?: Strings.commonNotInstalled(),
                clickable = usable,
            )
        },
        enabled = usable,
        onClick = {
            updating = true
            scope.runConfig(runUpdate) { result ->
                updating = false
                result
                    .onSuccess {
                        version = it.version
                        note = updateNote(it)
                    }
                    .onFailure { note = Strings.settingsClaudeUpdateFailed(it.problem()) }
            }
        },
    )
}

/** 更新完的一句话：成了说新旧版本，没成说输出里的最后一行。 */
internal fun updateNote(u: ClaudeUpdate): String {
    val after = u.version
    return when {
        !u.ok ->
            Strings.settingsClaudeUpdateFailed(
                u.output.lines().lastOrNull { it.isNotBlank() }
                    ?: Strings.settingsClaudeUpdateError(),
            )
        after != null && after != u.before ->
            Strings.settingsClaudeUpdateUpdated(u.before ?: Strings.commonNotInstalled(), after)
        else -> Strings.updateUpToDate()
    }
}
