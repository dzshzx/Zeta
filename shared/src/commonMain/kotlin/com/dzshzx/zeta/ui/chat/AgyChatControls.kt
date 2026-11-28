package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.Harnesses
import com.dzshzx.zeta.core.conv.ArtifactContent
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.MonoFont
import com.dzshzx.zeta.ui.components.miuix.ConfirmDialog
import com.dzshzx.zeta.ui.components.miuix.DialogTextField
import com.dzshzx.zeta.ui.components.miuix.NoticeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgyLaunchChip(conversation: Conversation) {
    if (conversation.harness != Harnesses.AGY || !conversation.canSwitchHarness) return
    var open by remember { mutableStateOf(false) }
    val active =
        conversation.agySandbox ||
            conversation.agyAgent.isNotBlank() ||
            conversation.agyExtraDirs.isNotEmpty()
    val label = if (active) "${Strings.composerAgyLaunch()} ●" else Strings.composerAgyLaunch()
    TextButton(
        text = label,
        onClick = { open = true },
        minWidth = 0.dp,
    )
    AgyLaunchDialog(
        open = open,
        initialAgent = conversation.agyAgent,
        initialDirs = conversation.agyExtraDirs,
        initialSandbox = conversation.agySandbox,
        onDismiss = { open = false },
        onSave = { agent, dirs, sandbox ->
            open = false
            conversation.setAgyLaunch(agent, dirs, sandbox)
        },
    )
}

@Composable
private fun AgyLaunchDialog(
    open: Boolean,
    initialAgent: String,
    initialDirs: List<String>,
    initialSandbox: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, List<String>, Boolean) -> Unit,
) {
    var agent by remember { mutableStateOf("") }
    var dirsRaw by remember { mutableStateOf("") }
    var sandbox by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) {
            agent = initialAgent
            dirsRaw = initialDirs.joinToString("\n")
            sandbox = initialSandbox
        }
    }
    ConfirmDialog(
        show = open,
        title = Strings.composerAgyLaunchTitle(),
        summary = null,
        confirmText = Strings.chatOk(),
        onConfirm = {
            val dirs = dirsRaw.lines().map { it.trim() }.filter { it.isNotEmpty() }
            onSave(agent.trim(), dirs, sandbox)
        },
        onDismissRequest = onDismiss,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SwitchPreference(
                title = Strings.setupAgySandbox(),
                checked = sandbox,
                onCheckedChange = { sandbox = it },
            )
            DialogTextField(
                value = agent,
                onValueChange = { agent = it },
                title = "--agent",
                hint = Strings.composerAgyAgentPlaceholder(),
            )
            DialogTextField(
                value = dirsRaw,
                onValueChange = { dirsRaw = it },
                title = "--add-dir",
                hint = "/path/one\n/path/two",
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtifactsChip(conversation: Conversation) {
    val artifacts = conversation.artifacts
    if (artifacts.isEmpty()) return
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf<ArtifactContent?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun load(name: String) {
        selected = name
        loading = true
        error = ""
        scope.launch {
            val res =
                withContext(Dispatchers.IO) { runCatching { conversation.readArtifact(name) } }
            loading = false
            res.onSuccess { content = it }.onFailure { error = it.message.orEmpty() }
        }
    }

    TextButton(
        text = Strings.chatArtifactsCount(artifacts.size),
        onClick = {
            open = true
            if (selected == null) load(artifacts.first().name)
        },
        minWidth = 0.dp,
    )

    NoticeDialog(
        show = open,
        title = Strings.chatArtifactsTitle(),
        summary = null,
        closeText = Strings.chatCollapse(),
        onDismissRequest = { open = false },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                artifacts.forEach { item ->
                    val active = selected == item.name
                    TextButton(
                        text = item.name,
                        onClick = { load(item.name) },
                        minWidth = 0.dp,
                        colors =
                            if (active) ButtonDefaults.textButtonColorsPrimary()
                            else ButtonDefaults.textButtonColors(),
                    )
                }
            }
            ArtifactContentBody(loading, error, content)
        }
    }
}

@Composable
private fun ArtifactContentBody(
    loading: Boolean,
    error: String,
    content: ArtifactContent?,
) {
    val styles = MiuixTheme.textStyles
    val colors = MiuixTheme.colorScheme
    when {
        loading -> Text(text = "…", style = styles.body2, color = colors.onBackgroundVariant)
        error.isNotBlank() -> Text(text = error, style = styles.body2, color = colors.error)
        content != null -> {
            if (content.truncated) {
                Text(
                    text = Strings.chatArtifactTruncated(content.size.toString()),
                    style = styles.footnote1,
                    color = colors.onBackgroundVariant,
                )
            }
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = content.content,
                    style = styles.footnote1.copy(fontFamily = MonoFont),
                    color = colors.onBackground,
                )
            }
        }
    }
}
