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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.claude.Subagent
import com.dzshzx.zeta.core.claude.SubagentDraft
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.NoticeDialog
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.IconActionButton
import com.dzshzx.zeta.ui.components.rememberRetained
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.InputViewDefaults
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Add
import io.github.suqi8.coui.kmp.preference.CheckboxPreference
import io.github.suqi8.coui.kmp.preference.InputPreference
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference
import io.github.suqi8.coui.kmp.preference.PagerFooterPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.squircle.squircleBackground
import io.github.suqi8.coui.kmp.theme.COUITheme

/** frontmatter 里 model 可写的别名；不写时 Claude Code 让 Subagent 跟随主对话的模型（除非设了 CLAUDE_CODE_SUBAGENT_MODEL）。 */
private val ModelOptions = listOf(
    "" to "默认",
    "inherit" to "跟随主对话",
    "sonnet" to "Sonnet",
    "opus" to "Opus",
    "haiku" to "Haiku",
    "fable" to "Fable",
)

private fun modelLabel(model: String): String = ModelOptions.firstOrNull { it.first.equals(model, ignoreCase = true) }?.second ?: model

/** 常用的内置工具；别的（比如 MCP 工具 mcp__服务器__工具）写进“其他工具”。 */
private val CommonTools = listOf(
    "Read" to "读文件",
    "Edit" to "修改文件",
    "Write" to "新建、覆盖文件",
    "Glob" to "按文件名查找",
    "Grep" to "在文件里搜索",
    "Bash" to "执行命令",
    "WebFetch" to "抓取网页",
    "WebSearch" to "网络搜索",
    "NotebookEdit" to "编辑 Jupyter 笔记本",
    "Skill" to "使用 Skill",
)

/**
 * “Subagent”：~/.claude/agents 下的每个 .md 是一个自定义 Subagent（前言写 name、description、model、tools，正文是系统提示词）。
 * 照 Eta：列表点开先看详情，再编辑或删除。Eta 的“Subagent 模型”是全局的一项，Claude Code 里每个 Subagent 自己写 model。
 * Claude Code 自带的 general-purpose、Explore、Plan 等不在这个目录里，这里不列出。
 */
@Composable
internal fun ClaudeAgentsScreen(hub: ConversationHub, onBack: () -> Unit) {
    val client = rememberConfigClient(hub)
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
            result.onSuccess { agents = it }.onFailure { notice = ConfigNotice("读取 Subagent 失败", it.problem()) }
        }
    }
    val reload: () -> Unit = { reloads++ }

    val shownAgent = rememberRetained(inspected)
    val shownDeleting = rememberRetained(deleting)
    CouiPage(
        title = "Subagent",
        onBack = onBack,
        actions = { IconActionButton(COUIIcons.Add, "新建 Subagent", onClick = { editor = EditorTarget(null) }) },
        dialogs = {
            ConfigNoticeDialog(notice) { notice = null }
            NoticeDialog(
                show = inspected != null,
                title = shownAgent?.name,
                summary = null,
                closeText = "关闭",
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
                title = "删除 Subagent",
                summary = shownDeleting?.let { "删除“${it.name}”？删了就找不回来。" },
                confirmText = "删除",
                destructive = true,
                onConfirm = {
                    deleting?.let { agent ->
                        scope.runConfig({ client.deleteAgent(agent.file) }) { result ->
                            result.onFailure { notice = ConfigNotice("删除失败", it.problem()) }
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
                title = "自定义 Subagent · ${list?.size ?: 0}",
                footer = "存在 ~/.claude/agents，所有项目都能用。Claude 按“什么时候用”自己决定交给哪个，你也可以在对话里点名。",
            ) {
                when {
                    list == null -> item { ConfigLoadingRow() }
                    list.isEmpty() -> item { ConfigEmptyRow("还没有自定义 Subagent", "让 Claude 写一个，或点右上角 + 新建") }
                    else -> list.forEach { agent ->
                        item(key = agent.file) {
                            // 点开是详情对话框，不是另一页：不带箭头
                            BasicComponent(
                                title = agent.name.ifBlank { agent.file },
                                summary = brief(agent.description).ifBlank { null },
                                endActions = { modelLabel(agent.model).takeIf { it.isNotBlank() }?.let { PreferenceValue(it) } },
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
    val colors = COUITheme.colorScheme
    val styles = COUITheme.textStyles
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(text = brief(agent.description, 400).ifBlank { "没有写什么时候用" }, style = styles.bodyXL, fontWeight = FontWeight.Medium, color = colors.labelPrimary)
        if (agent.prompt.isNotBlank()) {
            Text(text = brief(agent.prompt, 240), style = styles.bodyM, color = colors.labelSecondary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "模型：" + modelLabel(agent.model) + " · 工具", style = styles.bodyS, color = colors.labelSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (agent.tools?.ifEmpty { listOf("（没有工具）") } ?: listOf("全部工具")).forEach { tool ->
                    Box(
                        modifier = Modifier
                            .squircleBackground(color = colors.container8, cornerRadius = 8.dp)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(text = tool, style = styles.bodyXS.copy(fontFamily = FontFamily.Monospace), color = colors.labelSecondary)
                    }
                }
            }
        }
        if (agent.extraKeys.isNotEmpty()) {
            Text(text = "另外还设了 " + agent.extraKeys.joinToString("、") + "，编辑时原样保留。", style = styles.bodyS, color = colors.labelSecondary)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                text = "删除",
                onClick = onDelete,
                modifier = Modifier.weight(1f),
                minWidth = 0.dp,
                colors = ButtonDefaults.textButtonColors(textColor = colors.error),
            )
            TextButton(text = "编辑", onClick = onEdit, modifier = Modifier.weight(1f), minWidth = 0.dp, colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

private val AgentName = Regex("[a-z0-9][a-z0-9-]{0,63}")

/** 新建或编辑一个 Subagent：名称、什么时候用、模型、工具、系统提示词。 */
@Composable
private fun AgentEditor(hub: ConversationHub, original: Subagent?, onBack: () -> Unit, onSaved: () -> Unit) {
    val client = rememberConfigClient(hub)
    val scope = rememberCoroutineScope()
    val commonNames = CommonTools.map { it.first }.toSet()
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var description by remember { mutableStateOf(original?.description.orEmpty()) }
    var model by remember { mutableStateOf(original?.model.orEmpty()) }
    var allTools by remember { mutableStateOf(original?.tools == null) }
    var checked by remember { mutableStateOf(original?.tools.orEmpty().filter { it in commonNames }.toSet()) }
    var others by remember { mutableStateOf(original?.tools.orEmpty().filter { it !in commonNames }.joinToString(", ")) }
    var prompt by remember { mutableStateOf(original?.prompt.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = AgentName.matches(name.trim()) && description.isNotBlank()

    fun save() {
        val tools = if (allTools) null else CommonTools.map { it.first }.filter { it in checked } +
            others.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        val draft = SubagentDraft(name.trim(), description.trim(), model, tools, prompt)
        saving = true
        error = null
        scope.runConfig({ client.saveAgent(original, draft) }) { result ->
            saving = false
            result.onSuccess { onSaved() }.onFailure { error = it.problem() }
        }
    }

    CouiPage(
        title = if (original == null) "新建 Subagent" else "编辑 Subagent",
        onBack = onBack,
        actions = {
            TextButton(
                text = if (saving) "保存中" else "保存",
                onClick = ::save,
                enabled = valid && !saving,
                minWidth = 0.dp,
                colors = ButtonDefaults.textButtonColorsBorderless(),
            )
        },
    ) {
        item(key = "name") {
            // 保存为 ~/.claude/agents/<名称>.md
            PreferenceGroup(title = "名称", footer = "小写字母、数字和连字符，比如 code-reviewer。") {
                item { InputPreference(value = name, onValueChange = { name = it }, hint = "code-reviewer", enabled = !saving) }
            }
        }
        item(key = "description") {
            PreferenceGroup(title = "什么时候用", footer = "Claude 靠这段话决定要不要交给它，写清楚它擅长什么。") {
                item {
                    MultiLineField(
                        value = description,
                        onValueChange = { description = it },
                        hint = "比如：改完代码后用它审查改动，找出 bug 和不清楚的地方",
                        minLines = 2,
                        maxLines = 6,
                        enabled = !saving,
                    )
                }
            }
        }
        item(key = "model") {
            // “默认”是不写 model：Claude Code 同样让它跟随主对话，只有设了环境变量 CLAUDE_CODE_SUBAGENT_MODEL 时改用那个模型
            PreferenceGroup(footer = "“默认”一般和“跟随主对话”一样，用当前对话的模型。") {
                item {
                    val options = if (ModelOptions.any { it.first.equals(model, ignoreCase = true) }) ModelOptions else ModelOptions + (model to model)
                    OverlayDropdownPreference(
                        items = options.map { it.second },
                        selectedIndex = options.indexOfFirst { it.first.equals(model, ignoreCase = true) },
                        title = "模型",
                        enabled = !saving,
                        onSelectedIndexChange = { index -> options.getOrNull(index)?.let { model = it.first } },
                    )
                }
            }
        }
        item(key = "tools") {
            // “其他工具”写 MCP 工具：mcp__服务器名（整个服务器）或 mcp__服务器名__工具名；Zeta 的手机工具是 mcp__phone__ 开头的
            PreferenceGroup(title = "工具", footer = "关掉“全部工具”后，它只能用勾选的和“其他工具”里写的，多个用逗号分开。") {
                item {
                    SwitchPreference(
                        checked = allTools,
                        onCheckedChange = { allTools = it },
                        title = "全部工具",
                        summary = "和主对话一样，包括 MCP 工具",
                        enabled = !saving,
                    )
                }
                if (!allTools) {
                    CommonTools.forEach { (tool, label) ->
                        item(key = tool) {
                            CheckboxPreference(
                                title = tool,
                                checked = tool in checked,
                                onCheckedChange = { on -> checked = if (on) checked + tool else checked - tool },
                                summary = label,
                                enabled = !saving,
                            )
                        }
                    }
                    item(key = "others") {
                        InputPreference(value = others, onValueChange = { others = it }, title = "其他工具", hint = "mcp__github", enabled = !saving)
                    }
                }
            }
        }
        item(key = "prompt") {
            PreferenceGroup(title = "系统提示词", footer = "写它的角色和做事方式。它看不到主对话，只看到这段话和交给它的任务。") {
                item {
                    MultiLineField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        hint = "你是一名代码审查员……",
                        minLines = 8,
                        enabled = !saving,
                    )
                }
            }
        }
        error?.let { item(key = "error") { PagerFooterPreference(summary = it, summaryColor = COUITheme.colorScheme.error) } }
        // 保存按钮灰着的时候说一下缺什么
        if (!valid && (name.isNotEmpty() || description.isNotEmpty())) {
            item(key = "missing") {
                PagerFooterPreference(
                    summary = if (!AgentName.matches(name.trim())) "名称只能用小写字母、数字和连字符，并以字母或数字开头" else "写上什么时候用它才能保存",
                )
            }
        }
    }
}

/**
 * 卡片里的多行输入，随内容变高。COUI 2 的 InputPreference 只有单行，InputView 的多行固定 152dp 高，
 * 系统提示词这种长文本要能整段看到，所以用无边框的 TextField，字号、提示色和留白照 InputPreference。
 */
@Composable
private fun MultiLineField(value: String, onValueChange: (String) -> Unit, hint: String, minLines: Int, enabled: Boolean, maxLines: Int = Int.MAX_VALUE) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        backgroundMode = TextFieldMode.None,
        insideMargin = DpSize(0.dp, 13.dp),
        minHeight = 0.dp,
        colors = InputViewDefaults.textFieldColors(),
        label = hint,
        enabled = enabled,
        textStyle = InputViewDefaults.textStyle(),
        minLines = minLines,
        maxLines = maxLines,
    )
}
