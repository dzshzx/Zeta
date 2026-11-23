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
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.SettingsHue
import com.dzshzx.zeta.ui.components.coui2.SettingsIcon
import com.dzshzx.zeta.ui.setup.problem
import com.dzshzx.zeta.ui.setup.runConfig
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Update

/**
 * 远程主机页「Claude Code」：右边是那台主机上的版本，点按在那台上跑 claude update（docs/core.md「主机」的 system.claude、
 * system.claude_update）。已经在跑的对话不受影响，之后起的用新版本。手机本机的在设置 › 手机 › Linux 环境里。
 */
@Composable
internal fun ClaudeCodeUpdateRow(hub: ConversationHub, backend: String, ready: Boolean) {
    var version by remember(backend) { mutableStateOf<String?>(null) }
    var loaded by remember(backend) { mutableStateOf(false) }
    var updating by remember(backend) { mutableStateOf(false) }
    var note by remember(backend) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(backend, ready) {
        if (ready) {
            scope.runConfig({ hub.convLinks.claudeVersion(backend) }) { result ->
                result.onSuccess { version = it }.onFailure { note = "读不到版本：" + it.problem() }
                loaded = result.isSuccess
            }
        }
    }
    BasicComponent(
        title = "Claude Code",
        summary = if (updating) "正在更新…" else note ?: "点按更新到最新版",
        startAction = {
            SettingsIcon(
                COUIIcons.Update,
                SettingsHue.Claude,
                enabled = ready && loaded && !updating,
            )
        },
        endActions = {
            PreferenceValue(if (!loaded) "…" else version ?: "未安装")
        },
        enabled = ready && loaded && !updating,
        onClick = {
            updating = true
            scope.runConfig({ hub.convLinks.updateClaude(backend) }) { result ->
                updating = false
                result
                    .onSuccess {
                        version = it.version
                        note = updateNote(it)
                    }
                    .onFailure { note = "没更新成：" + it.problem() }
            }
        },
    )
}

/** 更新完的一句话：成了说新旧版本，没成说 claude update 输出的最后一行。 */
internal fun updateNote(u: ClaudeUpdate): String =
    when {
        !u.ok -> "没更新成：" + (u.output.lines().lastOrNull { it.isNotBlank() } ?: "claude update 出错了")
        u.version != null && u.version != u.before -> "已从 ${u.before ?: "未安装"} 更新到 ${u.version}"
        else -> "已是最新"
    }
