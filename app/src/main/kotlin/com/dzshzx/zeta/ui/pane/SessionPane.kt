package com.dzshzx.zeta.ui.pane

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.R
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.conversationPaneContainerColor
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.NoticeDialog
import com.dzshzx.zeta.ui.components.coui2.TextInputDialog
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.components.rememberRetained
import com.dzshzx.zeta.ui.tasks.BackgroundEntry
import io.github.suqi8.coui.kmp.basic.Checkbox
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.InputField
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.Pin
import io.github.suqi8.coui.kmp.icon.extended.Rename
import io.github.suqi8.coui.kmp.icon.extended.SelectAll
import io.github.suqi8.coui.kmp.icon.extended.Settings
import io.github.suqi8.coui.kmp.icon.extended.Share
import io.github.suqi8.coui.kmp.icon.extended.Unpin
import io.github.suqi8.coui.kmp.menu.OverlayContextMenu
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val PaneHorizontalPadding = 16.dp
// 行内文字离卡片边 16dp，和 COUI 卡片里的内容同一个缩进；分组标题因此离侧栏边 32dp，和 SmallTitle 默认一致
private val RowHorizontalPadding = 16.dp

/** 内容搜索进行到哪了。 */
private enum class SearchProgress { Idle, Running, Done, Partial, Failed }

/** 一行会话长按菜单里的各项操作；[toggle] 是多选时点一行。 */
private class RowActions(
    val open: (SessionSummary) -> Unit,
    val select: (SessionSummary) -> Unit,
    val toggle: (SessionSummary) -> Unit,
    val togglePin: (SessionSummary) -> Unit,
    val rename: (SessionSummary) -> Unit,
    val export: (SessionSummary) -> Unit,
    val share: (SessionSummary) -> Unit,
    val archive: (SessionSummary) -> Unit,
    val delete: (SessionSummary) -> Unit,
)

/**
 * 侧栏：品牌头 + 新对话；“置顶 / 今天 / 更早”分组的历史会话；底部搜索框、归档入口和设置。
 * 搜索时列表换成结果：标题当场比对，对话内容交给助手在 Debian 里边找边出。
 * 长按菜单里的“选择”进入多选（见 SessionSelection.kt），[revealed] 是侧栏是否正露在外面，收起时退出多选。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionPane(
    width: Dp,
    sessions: List<SessionSummary>,
    currentSessionId: String?,
    revealed: Boolean,
    isRunning: (String) -> Boolean,
    onNewChat: () -> Unit,
    onOpen: (SessionSummary) -> Unit,
    onDelete: (SessionSummary) -> Unit,
    onRename: (SessionSummary, String) -> Unit,
    onArchive: (SessionSummary, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    /** 打开后台任务面板（全部对话的）。 */
    onOpenBackground: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = COUITheme.colorScheme
    val dark = colors.isDark
    // 置顶、内容搜索、导出、“等你确认”都要用到应用级的单例，直接取，不再从外面一样样传进来
    val context = LocalContext.current
    val hub = remember(context) { ConversationHub.get(context.applicationContext as Application) }
    val focusManager = LocalFocusManager.current
    var deleting by remember { mutableStateOf<DeletePrompt?>(null) }
    val lastDeleting = rememberRetained(deleting)
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    // 多选：长按菜单里的“选择”进入；返回键、顶部的叉、侧栏收起或做完一次批量操作时退出
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val exitSelection: () -> Unit = {
        selecting = false
        selected = emptySet()
    }
    var batchDeleting by remember { mutableStateOf<List<SessionSummary>?>(null) }
    var batchSkipped by remember { mutableStateOf(0) }
    val lastBatch = rememberRetained(batchDeleting)
    LaunchedEffect(revealed) { if (!revealed) exitSelection() }
    BackHandler(enabled = selecting && revealed, onBack = exitSelection)

    val exporter = rememberSessionExporter(hub, onSavedMany = exitSelection)

    // 置顶存在设置里，按会话 id；从备份导入设置时也会变，所以用 hub 上那份可观察的
    val pins = hub.pinnedSessions
    val savePins: (Set<String>) -> Unit = hub::choosePinned
    // 置顶的会话不在 list 给的最近 300 个里时单独取来；确实已经不在了（别处删掉的）就把置顶去掉
    var farPinned by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    LaunchedEffect(sessions, pins) {
        val missing = pins - sessions.mapTo(HashSet()) { it.id }
        if (sessions.isEmpty() || missing.isEmpty()) {
            farPinned = emptyList()
            return@LaunchedEffect
        }
        val found = withContext(Dispatchers.IO) { lookupSessions(hub, missing) } ?: return@LaunchedEffect
        farPinned = found
        val gone = missing - found.mapTo(HashSet()) { it.id }
        if (gone.isNotEmpty()) savePins(pins - gone)
    }
    // 存储页要定位的会话不在最近 300 个里时，也单独取来放进列表
    var farRevealed by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    val all = remember(sessions, farPinned, farRevealed) {
        val far = (farPinned + farRevealed).distinctBy { it.id }.filter { extra -> sessions.none { it.id == extra.id } }
        if (far.isEmpty()) sessions else (sessions + far).sortedByDescending { it.updated }
    }

    // 搜索：标题、首句当场比对；对话内容交给助手，换了关键词就把上一次的停掉
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim()
    val searching = needle.isNotEmpty()
    var hits by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var progress by remember { mutableStateOf(SearchProgress.Idle) }
    LaunchedEffect(needle) {
        hits = emptyMap()
        if (needle.isEmpty()) {
            progress = SearchProgress.Idle
            return@LaunchedEffect
        }
        progress = SearchProgress.Running
        delay(300) // 打字时不必每个字都起一次进程
        val search = ContentSearch(
            hub, needle,
            onHit = { id, snippet -> hits = hits + (id to snippet) },
            onDone = { complete ->
                progress = when (complete) {
                    true -> SearchProgress.Done
                    false -> SearchProgress.Partial
                    null -> SearchProgress.Failed
                }
            },
        )
        try {
            awaitCancellation()
        } finally {
            search.cancel()
        }
    }
    val results = remember(all, needle, hits) {
        if (needle.isEmpty()) emptyList()
        else all.filter { it.id in hits || it.title.contains(needle, ignoreCase = true) || it.preview.contains(needle, ignoreCase = true) }
    }

    val actions = RowActions(
        open = { session ->
            focusManager.clearFocus()
            onOpen(session)
            query = ""
        },
        select = { session ->
            focusManager.clearFocus()
            selected = setOf(session.id)
            selecting = true
        },
        toggle = { session -> selected = if (session.id in selected) selected - session.id else selected + session.id },
        togglePin = { session -> savePins(if (session.id in pins) pins - session.id else pins + session.id) },
        rename = { session ->
            renameDraft = session.title.ifBlank { session.preview.take(40) }
            renaming = session
        },
        export = exporter.save,
        share = exporter.share,
        archive = { session -> onArchive(session, !session.archived) },
        delete = { session -> deleting = deletePrompt(session, hub::isActive) },
    )
    val todayStart = remember(sessions) {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    // 归档的会话收在底部的入口里，点进去单独看
    var showArchived by remember { mutableStateOf(false) }
    val archived = remember(all) { all.filter { it.archived } }
    if (archived.isEmpty()) showArchived = false
    val visible = remember(all, showArchived) { all.filter { it.archived == showArchived } }
    // 置顶只在主列表里单独成组；归档里的照常按时间排，只带个图钉
    val pinned = remember(visible, pins, showArchived) { if (showArchived) emptyList() else visible.filter { it.id in pins } }
    val unpinned = remember(visible, pinned) { if (pinned.isEmpty()) visible else visible - pinned.toSet() }
    val today = remember(unpinned, todayStart) { if (showArchived) emptyList() else unpinned.filter { it.updated >= todayStart } }
    val earlier = remember(unpinned, todayStart) { if (showArchived) unpinned else unpinned.filter { it.updated < todayStart } }
    // 多选：全选管的是眼前这份列表（搜索结果、归档或主列表）；选中的按列表里的顺序取，已经不在的不算
    val selectable = if (searching) results else visible
    val chosen = remember(all, selected) { all.filter { it.id in selected } }

    // 存储页请侧栏定位一个会话（见 SessionReveal）：侧栏露出来以后切到它所在的列表，滚过去，高亮一会儿
    val listState = rememberLazyListState()
    var flash by remember { mutableStateOf<String?>(null) }
    val allNow by rememberUpdatedState(all)
    val groupsNow by rememberUpdatedState(listOf(pinned, today, earlier))
    LaunchedEffect(revealed) {
        if (!revealed) return@LaunchedEffect
        snapshotFlow { SessionReveal.request }.collect { id ->
            if (id == null) return@collect
            SessionReveal.request = null
            val target = allNow.firstOrNull { it.id == id }
                ?: withContext(Dispatchers.IO) { lookupSessions(hub, listOf(id)) }?.firstOrNull { it.id == id }?.also { farRevealed = farRevealed + it }
            if (target == null) {
                Toast.makeText(context, "侧栏里没有这个对话（它没有可显示的内容）", Toast.LENGTH_SHORT).show()
                return@collect
            }
            query = ""
            exitSelection()
            showArchived = target.archived
            flash = id
            // 等列表按新的分组排好再滚
            withFrameNanos { }
            withFrameNanos { }
            revealIndex(groupsNow, id)?.let { listState.animateScrollToItem(it) }
            delay(1800)
            flash = null
        }
    }

    @Composable
    fun SessionItem(session: SessionSummary, snippet: String? = null) = SessionRow(
        session = session,
        selected = session.id == currentSessionId || session.id == flash,
        running = isRunning(session.id),
        // 正看着的对话，确认面板就在眼前，不必再标
        waiting = session.id != currentSessionId && hub.isWaiting(session.id),
        pinned = session.id in pins,
        snippet = snippet,
        highlight = needle,
        selecting = selecting,
        checked = session.id in selected,
        actions = actions,
    )

    fun LazyListScope.group(key: String, label: String, first: Boolean, list: List<SessionSummary>) {
        if (list.isEmpty()) return
        item(key = "h-$key") { SectionHeader(label, first = first) }
        items(list, key = { it.id }) { SessionItem(it) }
    }

    Column(
        modifier = modifier
            .width(width)
            .fillMaxSize()
            .background(conversationPaneContainerColor())
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
    ) {
        if (selecting) {
            SelectionHeader(
                count = chosen.size,
                allSelected = allSelected(selected, selectable),
                canSelectAll = selectable.isNotEmpty(),
                onToggleAll = { selected = toggleAll(selected, selectable) },
                onExit = exitSelection,
            )
        } else Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = PaneHorizontalPadding + 4.dp, end = PaneHorizontalPadding - 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val accent = colors.primary
            // 品牌标：与启动图标同一个 ζ 字形，主题色往黑（深色下往白）稍稍渐变，和启动图标一致，所以这里用了黑白两个定值
            val badge = remember(accent, dark) { Brush.linearGradient(listOf(accent, lerp(accent, if (dark) Color.White else Color.Black, 0.12f))) }
            val badgeShape = remember { SquircleShape(8.dp) }
            Box(Modifier.size(28.dp).clip(badgeShape).background(badge, badgeShape), contentAlignment = Alignment.Center) {
                Icon(painter = painterResource(R.drawable.ic_stat_zeta), contentDescription = null, modifier = Modifier.size(22.dp), tint = colors.onPrimary)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = COUITheme.textStyles.headlineM.copy(fontWeight = FontWeight.SemiBold),
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            PaneCircleButton(icon = ZetaIcons.NewChat, description = "新对话", onClick = onNewChat)
        }

        // 有后台对话或后台任务时，列表上方多一个入口
        if (!selecting && !searching) {
            BackgroundEntry(hub, onOpen = onOpenBackground, modifier = Modifier.padding(start = PaneHorizontalPadding, end = PaneHorizontalPadding, bottom = 6.dp))
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
            if (searching) {
                if (results.isEmpty()) {
                    item(key = "search-empty") {
                        PaneMessage(if (progress == SearchProgress.Running) "正在搜索…" else "没有匹配的对话")
                    }
                } else {
                    item(key = "h-search") { SectionHeader("搜索结果", first = true) }
                    items(results, key = { it.id }) { SessionItem(it, snippet = hits[it.id]) }
                    when (progress) {
                        SearchProgress.Running -> item(key = "search-more") { SearchFooter("正在搜索对话内容…") }
                        SearchProgress.Partial -> item(key = "search-more") { SearchFooter("结果较多，只列出了一部分，换个更具体的词试试") }
                        SearchProgress.Failed -> item(key = "search-more") { SearchFooter("对话内容没搜成，只比对了标题") }
                        else -> Unit
                    }
                }
            } else {
                if (visible.isEmpty() && !showArchived) {
                    item(key = "empty") { PaneMessage("还没有对话") }
                }
                group("pinned", "置顶", first = true, list = pinned)
                group("today", "今天", first = pinned.isEmpty(), list = today)
                group("earlier", if (showArchived) "已归档 · ${archived.size}" else "更早", first = pinned.isEmpty() && today.isEmpty(), list = earlier)
            }
        }

        if (selecting) {
            val archive = archiveAction(chosen)
            SelectionActions(
                count = chosen.size,
                archive = archive,
                onArchive = {
                    hub.archiveSessions(chosen.filter { it.archived != archive }, archive)
                    exitSelection()
                },
                onExport = { exporter.saveAll(chosen) },
                onDelete = {
                    val removable = deletable(chosen, hub::isActive)
                    if (removable.isEmpty()) {
                        Toast.makeText(context, "选中的对话都在进行中，不能删除", Toast.LENGTH_SHORT).show()
                    } else {
                        batchSkipped = chosen.size - removable.size
                        batchDeleting = removable
                    }
                },
                modifier = Modifier.padding(horizontal = PaneHorizontalPadding, vertical = 10.dp),
            )
        } else Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PaneHorizontalPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchCapsule(query = query, onQueryChange = { query = it }, modifier = Modifier.weight(1f))
            // 归档的入口固定在底部，不用滚到列表尽头去找；搜索时结果里本来就包括归档的，先收起来
            if (!searching) {
                when {
                    showArchived -> {
                        Spacer(Modifier.width(8.dp))
                        PaneCircleButton(icon = COUIIcons.Back, description = "返回全部对话", onClick = { showArchived = false })
                    }
                    archived.isNotEmpty() -> {
                        Spacer(Modifier.width(8.dp))
                        PaneCircleButton(icon = ZetaIcons.Archive, description = "已归档 · ${archived.size}", onClick = { showArchived = true })
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            PaneCircleButton(icon = COUIIcons.Settings, description = "设置", onClick = onOpenSettings)
        }
    }

    // 单条删除和批量删除同一个规矩：正在进行的、别处的 Claude Code 开着的，都不删。
    // 只提示还是确认删除在打开时定下（DeletePrompt），框开着时对话结束了也不会把“知道了”换成“删除”
    // 对话框是 COUI 的 Overlay 组件，由外层推移容器（ConversationSidePaneScaffold）里的 Scaffold 盖满整个屏幕显示
    val deletingName = lastDeleting?.session?.let { it.title.ifBlank { it.preview }.take(60) }
    NoticeDialog(
        show = deleting is DeletePrompt.Blocked,
        title = "这个对话正在进行",
        summary = deletingName?.let { "$it\n先停止它，或等它结束，再删除。" },
        closeText = "知道了",
        onDismissRequest = { deleting = null },
    )
    ConfirmDialog(
        show = deleting is DeletePrompt.Confirm,
        title = "删除这个对话？",
        summary = deletingName?.let { "$it\n会话记录文件会被一并删除，无法恢复。" },
        confirmText = "删除",
        destructive = true,
        onConfirm = {
            val prompt = deleting
            val target = confirmedDeletion(prompt, hub::isActive)
            if (target != null) onDelete(target)
            else if (prompt != null) Toast.makeText(context, "这个对话正在进行，没有删除", Toast.LENGTH_SHORT).show()
            deleting = null
        },
        onDismissRequest = { deleting = null },
    )
    ConfirmDialog(
        show = batchDeleting != null,
        title = "删除 ${lastBatch?.size ?: 0} 个对话？",
        summary = "会话记录会被删除，Claude Code 为它们存的工具结果、子代理记录等也一并删除，无法恢复。" +
            if (batchSkipped > 0) "\n另有 $batchSkipped 个正在进行的对话不会删除。" else "",
        confirmText = "删除",
        destructive = true,
        onConfirm = {
            // 确认框开着时才开始进行的也不删：删除会结束它的对话进程
            batchDeleting?.let { hub.deleteSessions(deletable(it, hub::isActive)) }
            batchDeleting = null
            exitSelection()
        },
        onDismissRequest = { batchDeleting = null },
    )
    TextInputDialog(
        show = renaming != null,
        title = "重命名",
        value = renameDraft,
        onValueChange = { renameDraft = it },
        label = "对话标题",
        confirmText = "确定",
        confirmEnabled = renameDraft.isNotBlank(),
        onConfirm = {
            renaming?.let { onRename(it, renameDraft) }
            renaming = null
        },
        onDismissRequest = { renaming = null },
    )
}

/** 侧栏里的圆形图标按钮：COUI 17 顶栏按钮的样子（材质底、阴影、按下缩放）。 */
@Composable
internal fun PaneCircleButton(icon: ImageVector, description: String, onClick: () -> Unit, size: Dp = 40.dp) {
    IconButton(onClick = onClick, cornerRadius = size / 2, minWidth = size, minHeight = size) {
        Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(22.dp), tint = COUITheme.colorScheme.labelPrimary)
    }
}

/** 底部的搜索框：COUI 的搜索胶囊，自带搜索图标和清空键。键盘上的“搜索”只收起键盘，结果随打字出来。 */
@Composable
private fun SearchCapsule(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var expanded by remember { mutableStateOf(false) }
    InputField(
        query = query,
        onQueryChange = onQueryChange,
        onSearch = {
            keyboard?.hide()
            focusManager.clearFocus()
        },
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
        label = "搜索对话",
    )
}

@Composable
private fun SectionHeader(label: String, first: Boolean) {
    SmallTitle(
        text = label,
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(
            start = PaneHorizontalPadding + RowHorizontalPadding,
            end = PaneHorizontalPadding + RowHorizontalPadding,
            top = if (first) 8.dp else 20.dp,
            bottom = 6.dp,
        ),
    )
}

@Composable
private fun PaneMessage(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
        Text(text = text, style = COUITheme.textStyles.bodyM, color = COUITheme.colorScheme.labelSecondary)
    }
}

@Composable
private fun SearchFooter(text: String) {
    Text(
        text = text,
        style = COUITheme.textStyles.bodyXS,
        color = COUITheme.colorScheme.labelSecondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = PaneHorizontalPadding + RowHorizontalPadding, vertical = 12.dp),
    )
}

/** 把 [text] 里所有的 [needle]（不分大小写）标成 [color]。 */
private fun highlighted(text: String, needle: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    if (needle.isEmpty()) return@buildAnnotatedString
    var from = 0
    while (true) {
        val at = text.indexOf(needle, from, ignoreCase = true)
        if (at < 0) break
        addStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium), at, at + needle.length)
        from = at + needle.length
    }
}

/** 长按菜单里一项的图标。 */
private fun menuIcon(icon: ImageVector, tint: Color): @Composable (Modifier) -> Unit = { modifier ->
    Icon(imageVector = icon, contentDescription = null, modifier = modifier, tint = tint)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    running: Boolean,
    waiting: Boolean,
    pinned: Boolean,
    snippet: String?,
    highlight: String,
    selecting: Boolean,
    checked: Boolean,
    actions: RowActions,
) {
    val colors = COUITheme.colorScheme
    val accent = colors.primary
    val shape = remember { SquircleShape(16.dp) }
    val haptic = LocalHapticFeedback.current
    // 菜单项的点击读最新的那组操作（RowActions 每次重组都是新的，不拿它当记忆的键）
    val currentActions by rememberUpdatedState(actions)
    val iconTint = colors.labelPrimary
    val deleteTint = colors.error
    val menu = remember(session, pinned, iconTint, deleteTint) {
        listOf(
            DropdownEntry(
                items = listOf(
                    DropdownItem(text = "选择", onClick = { currentActions.select(session) }, icon = menuIcon(COUIIcons.SelectAll, iconTint)),
                    DropdownItem(
                        text = if (pinned) "取消置顶" else "置顶对话",
                        onClick = { currentActions.togglePin(session) },
                        icon = menuIcon(if (pinned) COUIIcons.Unpin else COUIIcons.Pin, iconTint),
                    ),
                    DropdownItem(text = "重命名", onClick = { currentActions.rename(session) }, icon = menuIcon(COUIIcons.Rename, iconTint)),
                    DropdownItem(text = "导出", onClick = { currentActions.export(session) }, icon = menuIcon(COUIIcons.Download, iconTint)),
                    DropdownItem(text = "分享", onClick = { currentActions.share(session) }, icon = menuIcon(COUIIcons.Share, iconTint)),
                    DropdownItem(
                        text = if (session.archived) "取消归档" else "归档",
                        onClick = { currentActions.archive(session) },
                        icon = menuIcon(if (session.archived) ZetaIcons.Unarchive else ZetaIcons.Archive, iconTint),
                    ),
                ),
            ),
            DropdownEntry(
                items = listOf(DropdownItem(text = "删除", onClick = { currentActions.delete(session) }, icon = menuIcon(COUIIcons.Delete, deleteTint), alert = true)),
            ),
        )
    }
    val title = session.title.ifBlank { session.preview }.ifBlank { "未命名对话" }
    val subtitleStyle = COUITheme.textStyles.bodyXS
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = PaneHorizontalPadding, vertical = 1.5.dp)) {
        // 长按弹出 COUI 的长按菜单（在按下的位置）；多选时长按改成勾选这一行，不弹菜单
        OverlayContextMenu(entries = menu, enabled = !selecting) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .background(if (selected) colors.cardBackground else Color.Transparent)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = { if (selecting) actions.toggle(session) else actions.open(session) },
                        onLongClick = if (selecting) {
                            {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                actions.toggle(session)
                            }
                        } else {
                            null
                        },
                    )
                    .padding(horizontal = RowHorizontalPadding, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (highlight.isEmpty()) AnnotatedString(title) else highlighted(title, highlight, accent),
                        style = COUITheme.textStyles.bodyL.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
                        color = colors.labelPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when {
                        // 后台的对话在等你批准一次工具调用：比“在运行”的小圆点醒目得多，免得一直卡着没人管
                        waiting -> Text(
                            text = "等你确认",
                            style = subtitleStyle.copy(fontWeight = FontWeight.Medium),
                            color = StatusWarning,
                            maxLines = 1,
                        )
                        snippet != null -> Text(
                            text = highlighted(snippet, highlight, accent),
                            style = subtitleStyle,
                            color = colors.labelSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        else -> Text(
                            text = session.cwd.trimEnd('/').substringAfterLast('/').ifBlank { "/" } +
                                (if (session.external) " · 别处运行中" else "") +
                                (if (highlight.isNotEmpty() && session.archived) " · 已归档" else ""),
                            style = subtitleStyle,
                            color = colors.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                when {
                    waiting -> {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .size(14.dp)
                                .background(StatusWarning.copy(alpha = 0.22f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(8.dp).background(StatusWarning, CircleShape))
                        }
                    }
                    running -> {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(6.dp).background(accent, CircleShape))
                    }
                    pinned && !selecting -> {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = COUIIcons.Pin,
                            contentDescription = "已置顶",
                            modifier = Modifier.size(16.dp),
                            tint = colors.labelSecondaryVariant,
                        )
                    }
                }
                if (selecting) {
                    Spacer(Modifier.width(10.dp))
                    // 只显示勾选状态，点哪里都由整行处理
                    Checkbox(state = ToggleableState(checked), onClick = null)
                }
            }
        }
    }
}
