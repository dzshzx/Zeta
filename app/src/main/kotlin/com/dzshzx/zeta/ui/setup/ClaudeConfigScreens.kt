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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.claude.ClaudeConfigCore
import com.dzshzx.zeta.core.claude.TextDocument
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.CompactButton
import com.dzshzx.zeta.ui.components.miuix.ConfirmDialog
import com.dzshzx.zeta.ui.components.miuix.IconActionButton
import com.dzshzx.zeta.ui.components.miuix.MiuixPageScaffold
import com.dzshzx.zeta.ui.components.miuix.MiuixTopBarStyle
import com.dzshzx.zeta.ui.components.miuix.NoticeDialog
import com.dzshzx.zeta.ui.components.rememberRetained
import com.dzshzx.zeta.ui.markdown.LazyStaticMarkdown
import com.dzshzx.zeta.ui.markdown.MarkdownTone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** 主机 [backend] 上 Claude Code 自己的配置（~/.claude）的读写口子（经那台的核心），设置里各主机页面下的几页都用它。 */
@Composable
internal fun rememberConfigClient(hub: ConversationHub, backend: String): ClaudeConfigCore =
    remember(hub, backend) { ClaudeConfigCore.of(hub, backend) }

/** 在后台线程跑一个阻塞的配置操作，回到主线程交结果。 */
internal fun <T> CoroutineScope.runConfig(block: () -> T, done: (Result<T>) -> Unit) {
    launch {
        val result = withContext(Dispatchers.IO) { runCatching(block) }
        done(result)
    }
}

internal fun Throwable.problem(): String =
    message?.takeIf { it.isNotBlank() } ?: Strings.setupClaudeFailed()

internal data class ConfigNotice(val title: String, val message: String)

/** 读写配置出错时的告知框；要放在页面的 dialogs 里（MiuixPage）或页面骨架的内容里（MiuixPageScaffold）。 */
@Composable
internal fun ConfigNoticeDialog(notice: ConfigNotice?, onDismiss: () -> Unit) {
    val shown = rememberRetained(notice)
    NoticeDialog(
        show = notice != null,
        title = shown?.title,
        summary = shown?.message,
        closeText = Strings.setupClaudeGotIt(),
        onDismissRequest = onDismiss,
    )
}

internal fun sizeText(bytes: Long): String =
    when {
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
internal fun ConfigLoadingRow(text: String = Strings.setupClaudeLoading()) {
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

private enum class DocumentMode {
    Preview,
    Edit,
}

private const val MAX_DOCUMENT_BYTES = 1024 * 1024

/**
 * 整页的文档编辑器，照 Eta 的记忆页：上面是文件卡片（预览 / 编辑），有改动时底下出现保存条。 [onDelete] 非空时顶栏有删除按钮。保存时检查文件在编辑期间是否被 Claude
 * 改过，改过就先问一声。
 */
@Composable
internal fun ConfigDocumentEditor(
    client: ClaudeConfigCore,
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
            result
                .onSuccess {
                    saved = it.content
                    draft = it.content
                    savedSha = it.baseSha()
                    mode = initialMode(it.content)
                }
                .onFailure { notice = ConfigNotice(Strings.setupClaudeLoadFailed(), it.problem()) }
            loading = false
        }
    }

    fun save(force: Boolean) {
        saving = true
        val content = draft
        // 强制保存时不比对版本，直接覆盖
        scope.runConfig({ client.write(path, content, savedSha.takeUnless { force }) }) { result ->
            saving = false
            result
                .onSuccess { sha ->
                    if (sha == null) {
                        conflict = true
                    } else {
                        saved = content
                        savedSha = sha
                        onSaved()
                    }
                }
                .onFailure { notice = ConfigNotice(Strings.setupClaudeSaveFailed(), it.problem()) }
        }
    }

    // 有没保存的改动时先拦下返回键（比外层页面的处理器晚注册，先收到）
    BackHandler(enabled = dirty) { confirmLeave = true }
    val leave = { if (dirty) confirmLeave = true else onBack() }

    MiuixPageScaffold(
        title = title,
        onBack = leave,
        topBarStyle = MiuixTopBarStyle.Small,
        actions = {
            if (onDelete != null)
                IconActionButton(
                    MiuixIcons.Delete,
                    deleteTitle,
                    enabled = !loading && !saving,
                    onClick = { confirmDelete = true },
                )
        },
    ) { scope ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier =
                Modifier.fillMaxSize()
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
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
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
        EditorConfirmDialogs(
            conflict = conflict,
            onForceSave = {
                conflict = false
                save(force = true)
            },
            onDismissConflict = { conflict = false },
            confirmLeave = confirmLeave,
            onLeave = {
                confirmLeave = false
                onBack()
            },
            onDismissLeave = { confirmLeave = false },
            confirmDelete = confirmDelete,
            deleteTitle = deleteTitle,
            deleteMessage = deleteMessage,
            onDelete =
                onDelete?.let { delete ->
                    {
                        confirmDelete = false
                        delete()
                    }
                },
            onDismissDelete = { confirmDelete = false },
        )
    }
}

/** 写入时比对的版本；文件还不存在时是空串。 */
private fun TextDocument.baseSha(): String = if (exists) sha else ""

/** 空文件直接进编辑，有内容先预览。 */
private fun initialMode(content: String): DocumentMode =
    if (content.isBlank()) DocumentMode.Edit else DocumentMode.Preview

/** 编辑器的三个确认框：保存时文件已被改过、放弃未保存的更改、删除（[onDelete] 为 null 时没有）。 */
@Composable
private fun EditorConfirmDialogs(
    conflict: Boolean,
    onForceSave: () -> Unit,
    onDismissConflict: () -> Unit,
    confirmLeave: Boolean,
    onLeave: () -> Unit,
    onDismissLeave: () -> Unit,
    confirmDelete: Boolean,
    deleteTitle: String,
    deleteMessage: String,
    onDelete: (() -> Unit)?,
    onDismissDelete: () -> Unit,
) {
    ConfirmDialog(
        show = conflict,
        title = Strings.setupClaudeConflictTitle(),
        summary = Strings.setupClaudeConflictSummary(),
        confirmText = Strings.setupClaudeSaveAnyway(),
        destructive = true,
        onConfirm = onForceSave,
        onDismissRequest = onDismissConflict,
    )
    ConfirmDialog(
        show = confirmLeave,
        title = Strings.setupClaudeDiscardTitle(),
        summary = null,
        confirmText = Strings.setupClaudeDiscard(),
        destructive = true,
        onConfirm = onLeave,
        onDismissRequest = onDismissLeave,
    )
    if (onDelete != null) {
        ConfirmDialog(
            show = confirmDelete,
            title = deleteTitle,
            summary = deleteMessage,
            confirmText = Strings.setupClaudeDelete(),
            destructive = true,
            onConfirm = onDelete,
            onDismissRequest = onDismissDelete,
        )
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
    Card(modifier = modifier.fillMaxWidth()) {
        DocumentCardHeader(fileLabel, sizeLine, overLimit, loading, mode, onModeChange)
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        InfiniteProgressIndicator(size = 24.dp)
                    }
                else ->
                    Crossfade(targetState = mode, label = "document_mode") { current ->
                        DocumentBody(
                            mode = current,
                            content = content,
                            placeholder = placeholder,
                            editable = editable,
                            onStartEditing = { onModeChange(DocumentMode.Edit) },
                            onContentChange = onContentChange,
                        )
                    }
            }
        }
    }
}

/** 文件卡片的头：文件名、大小（超过上限标红）、预览 / 编辑切换。 */
@Composable
private fun DocumentCardHeader(
    fileLabel: String,
    sizeLine: String,
    overLimit: Boolean,
    loading: Boolean,
    mode: DocumentMode,
    onModeChange: (DocumentMode) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiuixIcons.Notes,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = colors.primary,
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileLabel,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = colors.onBackground,
                maxLines = 1,
            )
            Text(
                text = if (overLimit) Strings.setupClaudeOverLimit(sizeLine) else sizeLine,
                style = MiuixTheme.textStyles.footnote2,
                color = if (overLimit) colors.error else colors.onBackgroundVariant,
            )
        }
        // 分段标签自己横向撑满，套一层定宽，文件名那一列才分得到宽度
        Box(Modifier.width(160.dp)) {
            TabRowWithContour(
                tabs = listOf(Strings.setupClaudePreview(), Strings.setupClaudeEdit()),
                selectedTabIndex = DocumentMode.entries.indexOf(mode),
                // 分段标签没有不可用的样子：加载中忽略点击
                onTabSelected = { index ->
                    if (!loading) DocumentMode.entries.getOrNull(index)?.let(onModeChange)
                },
            )
        }
    }
}

@Composable
private fun DocumentBody(
    mode: DocumentMode,
    content: String,
    placeholder: String,
    editable: Boolean,
    onStartEditing: () -> Unit,
    onContentChange: (String) -> Unit,
) {
    when (mode) {
        DocumentMode.Preview ->
            DocumentPreview(
                content = content,
                placeholder = placeholder,
                onStartEditing = onStartEditing,
            )
        DocumentMode.Edit ->
            DocumentEditor(
                value = content,
                placeholder = placeholder,
                enabled = editable,
                onValueChange = onContentChange,
            )
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
            Text(
                text = Strings.setupClaudeEmptyDoc(),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = placeholder,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
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
private fun DocumentEditor(
    value: String,
    placeholder: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    val textStyle = MiuixTheme.textStyles.body2.copy(lineHeight = 22.sp)
    Box(
        modifier =
            Modifier.fillMaxSize()
                .clickable(interactionSource = null, indication = null) {
                    focusRequester.requestFocus()
                }
                .overScrollVertical()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty())
            Text(text = placeholder, style = textStyle, color = colors.onBackgroundVariant)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = textStyle.copy(color = colors.onBackground),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
    }
}

@Composable
private fun SaveBar(
    visible: Boolean,
    overLimit: Boolean,
    saving: Boolean,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
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
            UnsavedHint(overLimit, Modifier.weight(1f))
            CompactButton(
                text = Strings.setupClaudeDiscard(),
                enabled = !saving,
                onClick = onDiscard,
            )
            CompactButton(
                text = if (saving) Strings.setupClaudeSaving() else Strings.setupClaudeSave(),
                enabled = !saving && !overLimit,
                onClick = onSave,
                primary = true,
            )
        }
    }
}

/** 保存条左边的提示：未保存的更改，超过上限时标红并提示删减。 */
@Composable
private fun UnsavedHint(overLimit: Boolean, modifier: Modifier) {
    val colors = MiuixTheme.colorScheme
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier.size(6.dp)
                    .background(if (overLimit) colors.error else colors.primary, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text =
                if (overLimit) Strings.setupClaudeOverLimitHint() else Strings.setupClaudeUnsaved(),
            style = MiuixTheme.textStyles.footnote1,
            color = if (overLimit) colors.error else colors.onBackgroundVariant,
            maxLines = 2,
        )
    }
}
