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
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.claude.Subagent
import com.dzshzx.zeta.core.claude.SubagentDraft
import com.dzshzx.zeta.ui.components.coui.CouiButton
import com.dzshzx.zeta.ui.components.coui.CouiButtonRole
import com.dzshzx.zeta.ui.components.coui.CouiCheckboxPreference
import com.dzshzx.zeta.ui.components.coui.CouiConfirmDialog
import com.dzshzx.zeta.ui.components.coui.CouiNoticeDialog
import com.dzshzx.zeta.ui.components.coui.CouiPopupPreference
import com.dzshzx.zeta.ui.components.coui.CouiPreference
import com.dzshzx.zeta.ui.components.coui.CouiScaffoldPage
import com.dzshzx.zeta.ui.components.coui.CouiSwitchPreference
import com.dzshzx.zeta.ui.components.coui.CouiTextField
import com.dzshzx.zeta.ui.components.coui.CouiTopAppBarIconButton
import com.dzshzx.zeta.ui.components.coui.CouiTopAppBarTextButton
import com.dzshzx.zeta.ui.components.rememberRetained
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.Group
import com.itos.coui.menu.CouiMenuItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

    CouiScaffoldPage(
        title = "Subagent",
        onBack = onBack,
        actions = {
            CouiTopAppBarIconButton(icon = CouiIcons.Outlined.Add, contentDescription = "新建 Subagent", onClick = { editor = EditorTarget(null) })
        },
    ) {
        val list = agents
        Group(
            title = "自定义 Subagent · ${list?.size ?: 0}",
            footer = "保存在 ~/.claude/agents，所有项目都能用。Claude 根据每个 Subagent 的“什么时候用”自己决定交给谁，" +
                "你也可以在对话里点名让它用某一个。Claude Code 还自带 general-purpose、Explore、Plan 等几个，这里不列出。",
        ) {
            when {
                list == null -> ConfigLoadingRow()
                list.isEmpty() -> ConfigEmptyRow("暂无自定义 Subagent", "可以在对话里让 Claude 帮你写一个（保存在 ~/.claude/agents/<名字>.md），或点右上角 + 新建")
                else -> list.forEach { agent ->
                    CouiPreference(
                        title = agent.name.ifBlank { agent.file },
                        summary = brief(agent.description).ifBlank { null },
                        value = modelLabel(agent.model).ifBlank { null },
                        onClick = { inspected = agent },
                    )
                }
            }
        }
    }

    ConfigNoticeDialog(notice) { notice = null }
    val shownAgent = rememberRetained(inspected)
    CouiNoticeDialog(
        visible = inspected != null,
        title = shownAgent?.name,
        message = null,
        confirmText = "关闭",
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
    val shownDeleting = rememberRetained(deleting)
    CouiConfirmDialog(
        visible = deleting != null,
        title = "删除 Subagent",
        message = shownDeleting?.let { "删除“${it.name}”（~/.claude/agents/${it.file}）？此操作无法撤销。" },
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

    val shownEditor = rememberRetained(editor)
    ConfigSubPage(visible = editor != null, onBack = { editor = null }) {
        shownEditor?.let { target ->
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
}

private class EditorTarget(val agent: Subagent?)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentDetail(agent: Subagent, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(text = brief(agent.description, 400).ifBlank { "没有写什么时候用" }, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
        if (agent.prompt.isNotBlank()) {
            Text(text = brief(agent.prompt, 240), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "模型：" + modelLabel(agent.model) + " · 工具",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (agent.tools?.ifEmpty { listOf("（没有工具）") } ?: listOf("全部工具")).forEach { tool ->
                    Box(
                        modifier = Modifier
                            .squircleSurface(color = MiuixTheme.colorScheme.surfaceContainer, cornerRadius = 8.dp)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = tool,
                            style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        if (agent.extraKeys.isNotEmpty()) {
            Text(
                text = "另外还设了 " + agent.extraKeys.joinToString("、") + "，编辑时原样保留。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CouiButton(text = "删除", onClick = onDelete, role = CouiButtonRole.Destructive, modifier = Modifier.weight(1f))
            CouiButton(text = "编辑", onClick = onEdit, role = CouiButtonRole.Primary, modifier = Modifier.weight(1f))
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
    var modelMenu by remember { mutableStateOf(false) }
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

    CouiScaffoldPage(
        title = if (original == null) "新建 Subagent" else "编辑 Subagent",
        onBack = onBack,
        actions = {
            CouiTopAppBarTextButton(text = if (saving) "保存中" else "保存", enabled = valid && !saving, onClick = ::save)
        },
    ) {
        Group(title = "名称", footer = "小写字母、数字和连字符，比如 code-reviewer。保存为 ~/.claude/agents/<名称>.md。") {
            FieldBox { CouiTextField(value = name, onValueChange = { name = it }, placeholder = "code-reviewer", enabled = !saving) }
        }
        Group(title = "什么时候用", footer = "写清楚它擅长什么、什么情况下该交给它。Claude 靠这段话决定要不要用它。") {
            FieldBox {
                CouiTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = "比如：改完代码后用它审查改动，找出 bug 和不清楚的地方",
                    singleLine = false,
                    minLines = 2,
                    maxLines = 6,
                    enabled = !saving,
                )
            }
        }
        Group(
            title = "模型",
            footer = "“跟随主对话”用当前对话正在用的模型。“默认”是不写 model：Claude Code 同样让它跟随主对话，" +
                "只有设了环境变量 CLAUDE_CODE_SUBAGENT_MODEL 时改用那个模型。",
        ) {
            val options = if (ModelOptions.any { it.first.equals(model, ignoreCase = true) }) ModelOptions else ModelOptions + (model to model)
            CouiPopupPreference(
                title = "模型",
                value = modelLabel(model),
                expanded = modelMenu,
                onExpandedChange = { modelMenu = it && !saving },
                items = options.map { CouiMenuItem(value = it.first, text = it.second, selected = it.first.equals(model, ignoreCase = true)) },
                onItemClick = {
                    modelMenu = false
                    model = it.value
                },
            )
        }
        Group(
            title = "工具",
            footer = "关掉“全部工具”后，它只能用勾选的这些。别的工具（比如 MCP 的 mcp__服务器名__工具名，Zeta 的手机工具是 mcp__phone__ 开头的）写进“其他工具”，用逗号分开。",
        ) {
            CouiSwitchPreference(
                title = "全部工具",
                summary = "和主对话一样，包括 MCP 工具",
                checked = allTools,
                enabled = !saving,
                onCheckedChange = { allTools = it },
            )
            if (!allTools) {
                CommonTools.forEach { (tool, label) ->
                    CouiCheckboxPreference(
                        title = tool,
                        summary = label,
                        checked = tool in checked,
                        enabled = !saving,
                        onCheckedChange = { on -> checked = if (on) checked + tool else checked - tool },
                    )
                }
                FieldBox { CouiTextField(value = others, onValueChange = { others = it }, label = "其他工具", placeholder = "mcp__github", enabled = !saving) }
            }
        }
        Group(title = "系统提示词", footer = "它的角色和做事方式。Subagent 看不到主对话的内容，只看到这段话和交给它的任务。") {
            FieldBox {
                CouiTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    placeholder = "你是一名代码审查员……",
                    singleLine = false,
                    minLines = 8,
                    enabled = !saving,
                )
            }
        }
        error?.let {
            Text(
                text = it,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
            )
        }
        // 保存按钮灰着的时候说一下缺什么
        if (!valid && (name.isNotEmpty() || description.isNotEmpty())) {
            Text(
                text = if (!AgentName.matches(name.trim())) "名称只能用小写字母、数字和连字符，并以字母或数字开头" else "写上什么时候用它才能保存",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun FieldBox(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
}
