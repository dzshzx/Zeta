package com.dzshzx.zeta.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.claude.ClaudeConfigClient
import com.dzshzx.zeta.ui.components.coui2.CompactButton
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPageScaffold
import com.dzshzx.zeta.ui.components.coui2.CouiTopBarStyle
import com.dzshzx.zeta.ui.components.coui2.NoticeDialog
import com.dzshzx.zeta.ui.components.coui2.IconActionButton
import com.dzshzx.zeta.ui.components.coui2.SettingsHue
import com.dzshzx.zeta.ui.components.coui2.SettingsIcon
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.components.rememberRetained
import com.dzshzx.zeta.ui.markdown.LazyStaticMarkdown
import com.dzshzx.zeta.ui.markdown.MarkdownTone
import com.dzshzx.zeta.ui.settings.SettingsSubPage
import com.dzshzx.zeta.ui.settings.SettingsSubPageHost
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardListGroupScope
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.SegmentButton
import io.github.suqi8.coui.kmp.basic.SegmentButtonDefaults
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.Lock
import io.github.suqi8.coui.kmp.icon.extended.Notes
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.overScrollVertical
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置首页“上下文与扩展”里管 Claude Code 自身配置的几行。这几页改的是 Linux 环境里 Claude Code 自己的配置（~/.claude），
 * 不是 Zeta 的，终端里的 claude 同样生效；Linux 环境没就绪（[ready] 为 false）时不能点。
 */
internal fun CardListGroupScope.claudeConfigItems(ready: Boolean, onOpen: (SettingsSubPage) -> Unit) {
    fun entry(title: String, icon: ImageVector, hue: SettingsHue, page: SettingsSubPage) = item {
        ArrowPreference(
            title = title,
            startAction = { SettingsIcon(icon, hue, enabled = ready) },
            enabled = ready,
            onClick = { onOpen(page) },
        )
    }
    entry("记忆", COUIIcons.Notes, SettingsHue.Files, SettingsSubPage.ClaudeMemory)
    entry("Skills", ZetaIcons.Book, SettingsHue.Files, SettingsSubPage.ClaudeSkills)
    entry("MCP 服务器", ZetaIcons.Puzzle, SettingsHue.Claude, SettingsSubPage.ClaudeMcp)
    entry("Subagent", ZetaIcons.Branch, SettingsHue.Claude, SettingsSubPage.ClaudeAgents)
    entry("工具权限", COUIIcons.Lock, SettingsHue.Automation, SettingsSubPage.ClaudePermissions)
}

/** 这几个页面挂在设置页的子页面宿主上；返回键由设置页统一处理（关掉当前子页面）。 */
@Composable
internal fun ClaudeConfigPages(hub: ConversationHub, page: SettingsSubPage?, onClose: () -> Unit) {
    SettingsSubPageHost(page == SettingsSubPage.ClaudeMemory) { ClaudeMemoryScreen(hub = hub, onBack = onClose) }
    SettingsSubPageHost(page == SettingsSubPage.ClaudeSkills) { ClaudeSkillsScreen(hub = hub, onBack = onClose) }
    SettingsSubPageHost(page == SettingsSubPage.ClaudeMcp) { ClaudeMcpScreen(hub = hub, onBack = onClose) }
    SettingsSubPageHost(page == SettingsSubPage.ClaudeAgents) { ClaudeAgentsScreen(hub = hub, onBack = onClose) }
    SettingsSubPageHost(page == SettingsSubPage.ClaudePermissions) { ClaudePermissionsScreen(hub = hub, onBack = onClose) }
}

/**
 * 页面里再往下的一层（记忆的编辑器、MCP 服务器详情、Subagent 编辑器），用设置页同一个子页面宿主。
 * 返回键：这里的处理器比设置页的晚注册，打开时先收到返回键，一层一层往回退。
 */
@Composable
internal fun ConfigSubPage(visible: Boolean, onBack: () -> Unit, content: @Composable () -> Unit) {
    SettingsSubPageHost(visible, content)
    BackHandler(enabled = visible, onBack = onBack)
}

@Composable
internal fun rememberConfigClient(hub: ConversationHub): ClaudeConfigClient = remember(hub) { ClaudeConfigClient.of(hub) }

/** 在后台线程跑一个阻塞的配置操作，回到主线程交结果。 */
internal fun <T> CoroutineScope.runConfig(block: () -> T, done: (Result<T>) -> Unit) {
    launch {
        val result = withContext(Dispatchers.IO) { runCatching(block) }
        done(result)
    }
}

internal fun Throwable.problem(): String = message?.takeIf { it.isNotBlank() } ?: "没有成功"

internal data class ConfigNotice(val title: String, val message: String)

/** 读写配置出错时的告知框；要放在页面的 overlays 里（CouiPage）或页面骨架的内容里（CouiPageScaffold）。 */
@Composable
internal fun ConfigNoticeDialog(notice: ConfigNotice?, onDismiss: () -> Unit) {
    val shown = rememberRetained(notice)
    NoticeDialog(
        show = notice != null,
        title = shown?.title,
        summary = shown?.message,
        closeText = "知道了",
        onDismissRequest = onDismiss,
    )
}

/** 一段说明文字只取开头，列表里两行放得下。 */
internal fun brief(text: String, limit: Int = 90): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= limit) flat else flat.take(limit).trimEnd() + "…"
}

internal fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "%.2f MiB".format(bytes / (1024.0 * 1024.0))
}

/** 列表里的一行空状态：没有可点的东西时也让分组有内容。放在 PreferenceGroup 的 item 里。 */
@Composable
internal fun ConfigEmptyRow(title: String, summary: String? = null) {
    BasicComponent(title = title, summary = summary, enabled = false)
}

/** 列表里的一行“正在读取”，右边转圈。放在 PreferenceGroup 的 item 里。 */
@Composable
internal fun ConfigLoadingRow(text: String = "正在读取…") {
    BasicComponent(title = text, endActions = { InfiniteProgressIndicator(size = 20.dp) })
}

/** 正文前面的 --- 前言块：预览时不显示（Markdown 会把它渲染成标题线）。 */
internal fun stripFrontmatter(text: String): String {
    if (!text.startsWith("---")) return text
    val lines = text.lines()
    if (lines.first().trim() != "---") return text
    val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
    return if (end < 0) text else lines.drop(end + 2).joinToString("\n").trimStart('\n')
}

private enum class DocumentMode { Preview, Edit }

private const val MAX_DOCUMENT_BYTES = 1024 * 1024

/**
 * 整页的文档编辑器，照 Eta 的记忆页：上面是文件卡片（预览 / 编辑），有改动时底下出现保存条。
 * [onDelete] 非空时顶栏有删除按钮。保存时检查文件在编辑期间是否被 Claude 改过，改过就先问一声。
 */
@Composable
internal fun ConfigDocumentEditor(
    client: ClaudeConfigClient,
    path: String,
    title: String,
    fileLabel: String,
    placeholder: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    deleteTitle: String = "",
    deleteMessage: String = "",
    onDelete: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var saved by remember { mutableStateOf("") }
    var savedSha by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(DocumentMode.Preview) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ConfigNotice?>(null) }
    var conflict by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val dirty = !loading && draft != saved
    val bytes = remember(draft) { draft.toByteArray().size }
    val overLimit = bytes > MAX_DOCUMENT_BYTES

    LaunchedEffect(path) {
        loading = true
        scope.runConfig({ client.read(path) }) { result ->
            result.onSuccess {
                saved = it.content
                draft = it.content
                savedSha = if (it.exists) it.sha else ""
                mode = if (it.content.isBlank()) DocumentMode.Edit else DocumentMode.Preview
            }.onFailure { notice = ConfigNotice("读取失败", it.problem()) }
            loading = false
        }
    }

    fun save(force: Boolean) {
        saving = true
        val content = draft
        scope.runConfig({ client.write(path, content, if (force) null else savedSha) }) { result ->
            saving = false
            result.onSuccess { sha ->
                if (sha == null) {
                    conflict = true
                } else {
                    saved = content
                    savedSha = sha
                    onSaved()
                }
            }.onFailure { notice = ConfigNotice("保存失败", it.problem()) }
        }
    }

    // 有没保存的改动时先拦下返回键（比外层页面的处理器晚注册，先收到）
    BackHandler(enabled = dirty) { confirmLeave = true }
    val leave = { if (dirty) confirmLeave = true else onBack() }

    CouiPageScaffold(
        title = title,
        onBack = leave,
        topBarStyle = CouiTopBarStyle.Small,
        actions = {
            if (onDelete != null) IconActionButton(COUIIcons.Delete, deleteTitle, enabled = !loading && !saving, onClick = { confirmDelete = true })
        },
    ) { scope ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = scope.contentPadding.calculateStartPadding(layoutDirection),
                    top = scope.contentPadding.calculateTopPadding(),
                    end = scope.contentPadding.calculateEndPadding(layoutDirection),
                )
                // 骨架已经给键盘让了位（会消费掉键盘那部分），这里只补键盘收起时的导航栏
                .navigationBarsPadding(),
        ) {
            DocumentCard(
                fileLabel = fileLabel,
                sizeLine = "${sizeText(bytes.toLong())} / 1 MiB",
                overLimit = overLimit,
                loading = loading,
                mode = mode,
                onModeChange = { mode = it },
                content = draft,
                placeholder = placeholder,
                editable = !saving,
                onContentChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            )
            SaveBar(
                visible = dirty || saving,
                overLimit = overLimit,
                saving = saving,
                onDiscard = { draft = saved },
                onSave = { save(force = false) },
            )
            Spacer(Modifier.height(12.dp))
        }

        ConfigNoticeDialog(notice) { notice = null }
        ConfirmDialog(
            show = conflict,
            title = "文件已经变了",
            summary = "你编辑的时候，这个文件被改过了（多半是 Claude 在对话里更新了它）。保存会用你的版本覆盖它。",
            confirmText = "仍然保存",
            destructive = true,
            onConfirm = {
                conflict = false
                save(force = true)
            },
            onDismissRequest = { conflict = false },
        )
        ConfirmDialog(
            show = confirmLeave,
            title = "放弃未保存的更改？",
            summary = null,
            confirmText = "放弃",
            destructive = true,
            onConfirm = {
                confirmLeave = false
                onBack()
            },
            onDismissRequest = { confirmLeave = false },
        )
        if (onDelete != null) {
            ConfirmDialog(
                show = confirmDelete,
                title = deleteTitle,
                summary = deleteMessage,
                confirmText = "删除",
                destructive = true,
                onConfirm = {
                    confirmDelete = false
                    onDelete()
                },
                onDismissRequest = { confirmDelete = false },
            )
        }
    }
}

@Composable
private fun DocumentCard(
    fileLabel: String,
    sizeLine: String,
    overLimit: Boolean,
    loading: Boolean,
    mode: DocumentMode,
    onModeChange: (DocumentMode) -> Unit,
    content: String,
    placeholder: String,
    editable: Boolean,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = COUITheme.colorScheme
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = COUIIcons.Notes, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.primary)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = fileLabel, style = COUITheme.textStyles.bodyM, fontWeight = FontWeight.Medium, color = colors.labelPrimary, maxLines = 1)
                Text(
                    text = if (overLimit) "$sizeLine · 超过上限" else sizeLine,
                    style = COUITheme.textStyles.bodyXS,
                    color = if (overLimit) colors.error else colors.labelSecondary,
                )
            }
            SegmentButton(
                items = listOf("预览", "编辑"),
                selectedIndex = DocumentMode.entries.indexOf(mode),
                onSelectedIndexChange = { index -> DocumentMode.entries.getOrNull(index)?.let(onModeChange) },
                enabled = !loading,
                height = SegmentButtonDefaults.HeightSmall,
                padding = SegmentButtonDefaults.PaddingSmall,
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { InfiniteProgressIndicator(size = 24.dp) }
                else -> Crossfade(targetState = mode, label = "document_mode") { current ->
                    when (current) {
                        DocumentMode.Preview -> DocumentPreview(content = content, placeholder = placeholder, onStartEditing = { onModeChange(DocumentMode.Edit) })
                        DocumentMode.Edit -> DocumentEditor(value = content, placeholder = placeholder, enabled = editable, onValueChange = onContentChange)
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentPreview(content: String, placeholder: String, onStartEditing: () -> Unit) {
    val body = remember(content) { stripFrontmatter(content) }
    if (body.isBlank()) {
        Column(
            modifier = Modifier.fillMaxSize().clickable(onClick = onStartEditing).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = "还没有内容", style = COUITheme.textStyles.bodyXL, fontWeight = FontWeight.Medium, color = COUITheme.colorScheme.labelPrimary)
            Spacer(Modifier.height(6.dp))
            Text(text = placeholder, style = COUITheme.textStyles.bodyM, color = COUITheme.colorScheme.labelSecondary)
        }
        return
    }
    LazyStaticMarkdown(
        content = body,
        tone = MarkdownTone.Answer,
        modifier = Modifier.fillMaxSize().overScrollVertical(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun DocumentEditor(value: String, placeholder: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    val colors = COUITheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    val textStyle = COUITheme.textStyles.bodyM.copy(lineHeight = 22.sp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = null, indication = null) { focusRequester.requestFocus() }
            .overScrollVertical()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) Text(text = placeholder, style = textStyle, color = colors.labelSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = textStyle.copy(color = colors.labelPrimary),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
    }
}

@Composable
private fun SaveBar(visible: Boolean, overLimit: Boolean, saving: Boolean, onDiscard: () -> Unit, onSave: () -> Unit) {
    val colors = COUITheme.colorScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(if (overLimit) colors.error else colors.primary, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (overLimit) "已超过 1 MiB 上限，请删减" else "未保存的更改",
                    style = COUITheme.textStyles.bodyS,
                    color = if (overLimit) colors.error else colors.labelSecondary,
                    maxLines = 2,
                )
            }
            CompactButton(text = "放弃", enabled = !saving, onClick = onDiscard)
            CompactButton(text = if (saving) "保存中" else "保存", enabled = !saving && !overLimit, onClick = onSave, primary = true)
        }
    }
}
