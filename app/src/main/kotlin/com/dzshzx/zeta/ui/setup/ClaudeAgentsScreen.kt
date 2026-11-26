package com.dzshzx.zeta.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.claude.Subagent
import com.dzshzx.zeta.core.claude.SubagentDraft
import com.dzshzx.zeta.core.clipLine
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.fill8
import com.dzshzx.zeta.ui.components.miuix.ConfirmDialog
import com.dzshzx.zeta.ui.components.miuix.DropdownPreference
import com.dzshzx.zeta.ui.components.miuix.FieldGroup
import com.dzshzx.zeta.ui.components.miuix.FooterHint
import com.dzshzx.zeta.ui.components.miuix.IconActionButton
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.NoticeDialog
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.PreferenceValue
import com.dzshzx.zeta.ui.components.miuix.TextField
import com.dzshzx.zeta.ui.components.rememberRetained
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * frontmatter 里 model 可写的别名；不写时 Claude Code 让 Subagent 跟随主对话的模型（除非设了 CLAUDE_CODE_SUBAGENT_MODEL）。
 */
private val ModelOptions: List<Pair<String, String>>
    get() =
        listOf(
            "" to Strings.setupAgentsModelDefault(),
            "inherit" to Strings.setupAgentsModelInherit(),
            "sonnet" to "Sonnet",
            "opus" to "Opus",
            "haiku" to "Haiku",
            "fable" to "Fable",
        )

private fun modelLabel(model: String): String =
    ModelOptions.firstOrNull { it.first.equals(model, ignoreCase = true) }?.second ?: model

/** 常用的内置工具；别的（比如 MCP 工具 mcp__服务器__工具）写进“其他工具”。 */
private val CommonTools: List<Pair<String, String>>
    get() =
        listOf(
            "Read" to Strings.setupAgentsToolRead(),
            "Edit" to Strings.setupAgentsToolEdit(),
            "Write" to Strings.setupAgentsToolWrite(),
            "Glob" to Strings.setupAgentsToolGlob(),
            "Grep" to Strings.setupAgentsToolGrep(),
            "Bash" to Strings.setupAgentsToolBash(),
            "WebFetch" to Strings.setupAgentsToolWebFetch(),
            "WebSearch" to Strings.setupAgentsToolWebSearch(),
            "NotebookEdit" to Strings.setupAgentsToolNotebookEdit(),
            "Skill" to Strings.setupAgentsToolSkill(),
        )

/**
 * “Subagent”：~/.claude/agents 下的每个 .md 是一个自定义 Subagent（前言写 name、description、model、tools，正文是系统提示词）。
 * 照 Eta：列表点开先看详情，再编辑或删除。Eta 的“Subagent 模型”是全局的一项，Claude Code 里每个 Subagent 自己写 model。 Claude Code
 * 自带的 general-purpose、Explore、Plan 等不在这个目录里，这里不列出。
 */
@Composable
internal fun ClaudeAgentsScreen(hub: ConversationHub, backend: String, onBack: () -> Unit) {
    val client = rememberConfigClient(hub, backend)
    val scope = rememberCoroutineScope()
    var agents by remember { mutableStateOf<List<Subagent>?>(null) }
    var notice by remember { mutableStateOf<ConfigNotice?>(null) }
    var inspected by remember { mutableStateOf<Subagent?>(null) }
    var deleting by remember { mutableStateOf<Subagent?>(null) }
    // 编辑器：null 关着；original 为 null 是新建
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    var reloads by remember { mutableStateOf(0) }

    LaunchedEffect(reloads) {
        scope.runConfig({ client.agents() }) { result ->
            result
                .onSuccess { agents = it }
                .onFailure { notice = ConfigNotice(Strings.setupAgentsLoadFailed(), it.problem()) }
        }
    }
    val reload: () -> Unit = { reloads++ }

    val shownAgent = rememberRetained(inspected)
    val shownDeleting = rememberRetained(deleting)
    MiuixPage(
        title = "Subagent",
        onBack = onBack,
        actions = {
            IconActionButton(
                MiuixIcons.Add,
                Strings.setupAgentsNew(),
                onClick = { editor = EditorTarget(null) },
            )
        },
        dialogs = {
            ConfigNoticeDialog(notice) { notice = null }
            NoticeDialog(
                show = inspected != null,
                title = shownAgent?.name,
                summary = null,
                closeText = Strings.setupClaudeClose(),
                onDismissRequest = { inspected = null },
            ) {
                shownAgent?.let { agent ->
                    AgentDetail(
                        agent = agent,
                        onEdit = {
                            inspected = null
                            editor = EditorTarget(agent)
                        },
                        onDelete = {
                            inspected = null
                            deleting = agent
                        },
                    )
                }
            }
            ConfirmDialog(
                show = deleting != null,
                title = Strings.setupAgentsDeleteTitle(),
                summary = shownDeleting?.let { Strings.setupAgentsDeleteSummary(it.name) },
                confirmText = Strings.setupClaudeDelete(),
                destructive = true,
                onConfirm = {
                    deleting?.let { agent ->
                        scope.runConfig({ client.deleteAgent(agent.file) }) { result ->
                            result.onFailure {
                                notice =
                                    ConfigNotice(Strings.setupClaudeDeleteFailed(), it.problem())
                            }
                            reload()
                        }
                    }
                    deleting = null
                },
                onDismissRequest = { deleting = null },
            )
        },
    ) {
        val list = agents
        item(key = "agents") {
            PreferenceGroup(
                title = Strings.setupAgentsCustom(list?.size ?: 0),
                footer = Strings.setupAgentsCustomFooter(),
            ) {
                when {
                    list == null -> item { ConfigLoadingRow() }
                    list.isEmpty() ->
                        item {
                            ConfigEmptyRow(
                                Strings.setupAgentsNone(),
                                Strings.setupAgentsNoneSummary(),
                            )
                        }
                    else ->
                        list.forEach { agent ->
                            item(key = agent.file) {
                                // 点开是详情对话框，不是另一页：不带箭头
                                BasicComponent(
                                    title = agent.name.ifBlank { agent.file },
                                    summary = agent.description.clipLine(90).ifBlank { null },
                                    endActions = {
                                        modelLabel(agent.model)
                                            .takeIf { it.isNotBlank() }
                                            ?.let { PreferenceValue(it) }
                                    },
                                    onClick = { inspected = agent },
                                )
                            }
                        }
                }
            }
        }
    }

    SlideInPage(editor, onBack = { editor = null }) { target ->
        AgentEditor(
            hub = hub,
            backend = backend,
            original = target.agent,
            onBack = { editor = null },
            onSaved = {
                editor = null
                reload()
            },
        )
    }
}

private class EditorTarget(val agent: Subagent?)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentDetail(agent: Subagent, onEdit: () -> Unit, onDelete: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = agent.description.clipLine(400).ifBlank { Strings.setupAgentsNoWhen() },
            style = styles.body1,
            fontWeight = FontWeight.Medium,
            color = colors.onBackground,
        )
        if (agent.prompt.isNotBlank()) {
            Text(
                text = agent.prompt.clipLine(240),
                style = styles.body2,
                color = colors.onBackgroundVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = Strings.setupAgentsModelTools(modelLabel(agent.model)),
                style = styles.footnote1,
                color = colors.onBackgroundVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (agent.tools?.ifEmpty { listOf(Strings.setupAgentsNoTools()) }
                        ?: listOf(Strings.setupAgentsAllTools()))
                    .forEach { tool ->
                        Box(
                            modifier =
                                Modifier.squircleBackground(
                                        color = colors.fill8,
                                        cornerRadius = 8.dp,
                                    )
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                text = tool,
                                style = styles.footnote2.copy(fontFamily = FontFamily.Monospace),
                                color = colors.onBackgroundVariant,
                            )
                        }
                    }
            }
        }
        if (agent.extraKeys.isNotEmpty()) {
            Text(
                text =
                    Strings.setupAgentsExtraKeys(
                        agent.extraKeys.joinToString(Strings.setupListSeparator()),
                    ),
                style = styles.footnote1,
                color = colors.onBackgroundVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = Strings.setupClaudeDelete(),
                onClick = onDelete,
                modifier = Modifier.weight(1f),
                minWidth = 0.dp,
                colors = ButtonDefaults.textButtonColors(textColor = colors.error),
            )
            TextButton(
                text = Strings.setupClaudeEdit(),
                onClick = onEdit,
                modifier = Modifier.weight(1f),
                minWidth = 0.dp,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

private val AgentName = Regex("[a-z0-9][a-z0-9-]{0,63}")

/** 新建或编辑一个 Subagent：名称、什么时候用、模型、工具、系统提示词。 */
@Composable
private fun AgentEditor(
    hub: ConversationHub,
    backend: String,
    original: Subagent?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val client = rememberConfigClient(hub, backend)
    val scope = rememberCoroutineScope()
    val commonNames = CommonTools.map { it.first }.toSet()
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var description by remember { mutableStateOf(original?.description.orEmpty()) }
    var model by remember { mutableStateOf(original?.model.orEmpty()) }
    var allTools by remember { mutableStateOf(original?.tools == null) }
    var checked by remember {
        mutableStateOf(original?.tools.orEmpty().filter { it in commonNames }.toSet())
    }
    var others by remember {
        mutableStateOf(original?.tools.orEmpty().filter { it !in commonNames }.joinToString(", "))
    }
    var prompt by remember { mutableStateOf(original?.prompt.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = AgentName.matches(name.trim()) && description.isNotBlank()

    fun save() {
        val tools = if (allTools) null else chosenTools(checked, others)
        val draft = SubagentDraft(name.trim(), description.trim(), model, tools, prompt)
        saving = true
        error = null
        scope.runConfig({ client.saveAgent(original, draft) }) { result ->
            saving = false
            result.onSuccess { onSaved() }.onFailure { error = it.problem() }
        }
    }

    MiuixPage(
        title = if (original == null) Strings.setupAgentsNew() else Strings.setupAgentsEdit(),
        onBack = onBack,
        actions = {
            TextButton(
                text = if (saving) Strings.setupClaudeSaving() else Strings.setupClaudeSave(),
                onClick = ::save,
                enabled = valid && !saving,
                minWidth = 0.dp,
                colors =
                    ButtonDefaults.textButtonColors(
                        color = Color.Transparent,
                        disabledColor = Color.Transparent,
                        textColor = MiuixTheme.colorScheme.primary,
                    ),
            )
        },
    ) {
        item(key = "name") {
            // 保存为 ~/.claude/agents/<名称>.md
            FieldGroup(
                title = Strings.setupAgentsName(),
                footer = Strings.setupAgentsNameFooter(),
            ) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "code-reviewer",
                    enabled = !saving,
                    showClearButton = true,
                    singleLine = true,
                )
            }
        }
        item(key = "description") {
            FieldGroup(
                title = Strings.setupAgentsWhen(),
                footer = Strings.setupAgentsWhenFooter(),
            ) {
                TextField(
                    value = description,
                    onValueChange = { description = it },
                    label = Strings.setupAgentsWhenHint(),
                    enabled = !saving,
                    minLines = 2,
                    maxLines = 6,
                )
            }
        }
        item(key = "model") {
            AgentModelGroup(model = model, enabled = !saving, onModelChange = { model = it })
        }
        item(key = "tools") {
            AgentToolsGroup(
                allTools = allTools,
                onAllToolsChange = { allTools = it },
                checked = checked,
                onToolCheckedChange = { tool, on -> checked = checked.toggled(tool, on) },
                others = others,
                onOthersChange = { others = it },
                enabled = !saving,
            )
        }
        item(key = "prompt") {
            FieldGroup(
                title = Strings.setupAgentsPrompt(),
                footer = Strings.setupAgentsPromptFooter(),
            ) {
                TextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = Strings.setupAgentsPromptHint(),
                    enabled = !saving,
                    minLines = 8,
                )
            }
        }
        error?.let {
            item(key = "error") {
                FooterHint(summary = it, summaryColor = MiuixTheme.colorScheme.error)
            }
        }
        // 保存按钮灰着的时候说一下缺什么
        if (!valid && (name.isNotEmpty() || description.isNotEmpty())) {
            item(key = "missing") { FooterHint(summary = missingFieldHint(name)) }
        }
    }
}

/** 勾选的常用工具（按 [CommonTools] 的顺序）加上「其他工具」里用逗号分开的那些。 */
private fun chosenTools(checked: Set<String>, others: String): List<String> =
    CommonTools.map { it.first }.filter { it in checked } +
        others.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }

private fun Set<String>.toggled(tool: String, on: Boolean): Set<String> =
    if (on) this + tool else this - tool

private fun missingFieldHint(name: String): String =
    if (!AgentName.matches(name.trim())) Strings.setupAgentsNameInvalid()
    else Strings.setupAgentsWhenMissing()

/** 选模型；文件里写的不在选项里时把它加到最后，原样保留。 */
@Composable
private fun AgentModelGroup(model: String, enabled: Boolean, onModelChange: (String) -> Unit) {
    // “默认”是不写 model：Claude Code 同样让它跟随主对话，只有设了环境变量 CLAUDE_CODE_SUBAGENT_MODEL 时改用那个模型
    PreferenceGroup(footer = Strings.setupAgentsModelFooter()) {
        item {
            val options =
                if (ModelOptions.any { it.first.equals(model, ignoreCase = true) }) ModelOptions
                else ModelOptions + (model to model)
            DropdownPreference(
                items = options.map { it.second },
                selectedIndex = options.indexOfFirst { it.first.equals(model, ignoreCase = true) },
                title = Strings.setupAgentsModel(),
                enabled = enabled,
                onSelectedIndexChange = { index ->
                    options.getOrNull(index)?.let { onModelChange(it.first) }
                },
            )
        }
    }
}

/** 「工具」一组：全部工具，或者勾选常用工具再补写其他工具。 */
@Composable
private fun AgentToolsGroup(
    allTools: Boolean,
    onAllToolsChange: (Boolean) -> Unit,
    checked: Set<String>,
    onToolCheckedChange: (String, Boolean) -> Unit,
    others: String,
    onOthersChange: (String) -> Unit,
    enabled: Boolean,
) {
    // “其他工具”写 MCP 工具：mcp__服务器名（整个服务器）或 mcp__服务器名__工具名；Zeta 的手机工具是 mcp__phone__ 开头的
    // 全部工具关掉时说明挪到“其他工具”输入框下面，那里才写得到工具名
    Column {
        PreferenceGroup(
            title = Strings.setupAgentsTools(),
            footer = if (allTools) Strings.setupAgentsToolsFooter() else null,
        ) {
            item {
                SwitchPreference(
                    checked = allTools,
                    onCheckedChange = onAllToolsChange,
                    title = Strings.setupAgentsAllTools(),
                    summary = Strings.setupAgentsAllToolsSummary(),
                    enabled = enabled,
                )
            }
            if (!allTools) {
                CommonTools.forEach { (tool, label) ->
                    item(key = tool) {
                        CheckboxPreference(
                            title = tool,
                            checked = tool in checked,
                            onCheckedChange = { on -> onToolCheckedChange(tool, on) },
                            summary = label,
                            enabled = enabled,
                        )
                    }
                }
            }
        }
        if (!allTools) {
            FieldGroup(
                title = Strings.setupAgentsOtherTools(),
                footer = Strings.setupAgentsToolsFooter(),
            ) {
                TextField(
                    value = others,
                    onValueChange = onOthersChange,
                    label = "mcp__github",
                    enabled = enabled,
                    showClearButton = true,
                    singleLine = true,
                )
            }
        }
    }
}
