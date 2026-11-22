package com.dzshzx.zeta.ui.pane

import android.app.Application
import android.content.Context
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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.R
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.conversationPaneContainerColor
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.IconActionButton
import com.dzshzx.zeta.ui.components.coui2.NoticeDialog
import com.dzshzx.zeta.ui.components.coui2.TextInputDialog
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.components.coui2.menuItem
import com.dzshzx.zeta.ui.components.rememberRetained
import com.dzshzx.zeta.ui.tasks.BackgroundTasksEntry
import io.github.suqi8.coui.kmp.basic.Checkbox
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.Icon
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
private enum class SearchProgress {
    Idle,
    Running,
    Done,
    Partial,
    Failed,
}

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
 * 侧栏：品牌头 + 新对话；“置顶 / 今天 / 更早”分组的历史会话；底部搜索框、归档入口和设置。 搜索时列表换成结果：标题当场比对，对话内容交给助手在 Debian 里边找边出。
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
    onOpenBackgroundTasks: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 置顶、内容搜索、导出、“等你确认”都要用到应用级的单例，直接取，不再从外面一样样传进来
    val context = LocalContext.current
    val hub = remember(context) { ConversationHub.get(context.applicationContext as Application) }
    val focusManager = LocalFocusManager.current
    var deleting by remember { mutableStateOf<DeletePrompt?>(null) }
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
    LaunchedEffect(revealed) { if (!revealed) exitSelection() }
    BackHandler(enabled = selecting && revealed, onBack = exitSelection)

    val exporter = rememberSessionExporter(hub, onSavedMany = exitSelection)

    // 置顶存在设置里，各台主机的都在一起（[SessionSummary.pinKey]）；从备份导入设置时也会变，所以用 hub 上那份可观察的
    val pins = hub.settings.pinnedSessions
    val savePins: (Set<String>) -> Unit = { hub.settings.pinnedSessions = it }
    // 列着的这台主机上置顶的会话不在 list 给的最近 300 个里时单独取来；确实已经不在了（别处删掉的）就把置顶去掉。
    // 别的主机上的置顶不管：在这台上查不到不等于没了
    var farPinned by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    LaunchedEffect(sessions, pins) {
        syncFarPinned(hub, sessions, pins, onFound = { farPinned = it }, savePins = savePins)
    }
    // 存储页要定位的会话不在最近 300 个里时，也单独取来放进列表
    var farRevealed by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    val all =
        remember(sessions, farPinned, farRevealed) {
            withFarSessions(sessions, farPinned + farRevealed)
        }

    // 搜索：标题、首句当场比对；对话内容交给助手，换了关键词就把上一次的停掉
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim()
    val searching = needle.isNotEmpty()
    val search = rememberContentSearch(hub, needle)
    val hits = search.hits
    val results = remember(all, needle, hits) { searchResults(all, needle, hits) }

    val actions =
        RowActions(
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
            toggle = { session -> selected = selected.toggled(session.id) },
            togglePin = { session -> savePins(pins.toggled(session.pinKey)) },
            rename = { session ->
                renameDraft = session.title.ifBlank { session.preview.take(40) }
                renaming = session
            },
            export = exporter.save,
            share = exporter.share,
            archive = { session -> onArchive(session, !session.archived) },
            delete = { session -> deleting = deletePrompt(session, hub::isActive) },
        )
    val todayStart = remember(sessions) { startOfToday() }
    // 归档的会话收在底部的入口里，点进去单独看
    var showArchived by remember { mutableStateOf(false) }
    val groups =
        remember(all, pins, showArchived, todayStart) {
            SessionGroups(all, pins, showArchived, todayStart)
        }
    if (groups.archived.isEmpty()) showArchived = false
    // 多选：全选管的是眼前这份列表（搜索结果、归档或主列表）；选中的按列表里的顺序取，已经不在的不算
    val selectable = if (searching) results else groups.visible
    val chosen = remember(all, selected) { all.filter { it.id in selected } }

    // 存储页请侧栏定位一个会话（见 SessionReveal）：侧栏露出来以后切到它所在的列表，滚过去，高亮一会儿
    val listState = rememberLazyListState()
    var flash by remember { mutableStateOf<String?>(null) }
    val allNow by rememberUpdatedState(all)
    val groupsNow by rememberUpdatedState(groups.listed)
    RevealRequests(revealed) { id ->
        val target =
            findRevealTarget(id, context, hub, allNow) { farRevealed = farRevealed + it }
                ?: return@RevealRequests
        query = ""
        exitSelection()
        showArchived = target.archived
        flash = id
        // 等列表按新的分组排好再滚
        withFrameNanos {}
        withFrameNanos {}
        revealIndex(groupsNow, id)?.let { listState.animateScrollToItem(it) }
        delay(1800)
        flash = null
    }

    val rows =
        RowContext(
            currentSessionId = currentSessionId,
            flash = flash,
            isRunning = isRunning,
            hub = hub,
            pins = pins,
            highlight = needle,
            selecting = selecting,
            selected = selected,
            actions = actions,
        )

    Column(
        modifier =
            modifier
                .width(width)
                .fillMaxSize()
                .background(conversationPaneContainerColor())
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Vertical + WindowInsetsSides.Start,
                    ),
                ),
    ) {
        if (selecting) {
            SelectionHeader(
                count = chosen.size,
                allSelected = allSelected(selected, selectable),
                canSelectAll = selectable.isNotEmpty(),
                onToggleAll = { selected = toggleAll(selected, selectable) },
                onExit = exitSelection,
            )
        } else {
            PaneTop(hub, searching, onNewChat, onOpenBackgroundTasks)
        }
        CompositionLocalProvider(LocalHostName provides rowHostName(hub)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                paneItems(searching, results, search, groups, hub.sessionList.problem, rows)
            }
        }

        if (selecting) {
            val archive = archiveAction(chosen)
            SelectionActions(
                count = chosen.size,
                archive = archive,
                onArchive = {
                    hub.sessionList.archive(chosen.filter { it.archived != archive }, archive)
                    exitSelection()
                },
                onExport = { exporter.saveAll(chosen) },
                onDelete = {
                    askBatchDelete(chosen, hub, context) { removable ->
                        batchSkipped = chosen.size - removable.size
                        batchDeleting = removable
                    }
                },
                modifier = Modifier.padding(horizontal = PaneHorizontalPadding, vertical = 10.dp),
            )
        } else
            PaneBottomBar(
                query = query,
                onQueryChange = { query = it },
                searching = searching,
                showArchived = showArchived,
                archivedCount = groups.archived.size,
                onShowArchived = { showArchived = it },
                onOpenSettings = onOpenSettings,
            )
    }

    DeleteDialogs(
        deleting = deleting,
        onDelete = { prompt -> confirmDelete(prompt, hub, context, onDelete) },
        onDismiss = { deleting = null },
    )
    BatchDeleteDialog(
        batch = batchDeleting,
        skipped = batchSkipped,
        onConfirm = {
            // 确认框开着时才开始进行的也不删：删除会结束它的对话进程
            batchDeleting?.let { hub.sessionList.delete(deletable(it, hub::isActive)) }
            batchDeleting = null
            exitSelection()
        },
        onDismiss = { batchDeleting = null },
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

/** 在就去掉，不在就加上。 */
private fun <T> Set<T>.toggled(value: T): Set<T> = if (value in this) this - value else this + value

/** 今天零点的时刻（毫秒）。 */
private fun startOfToday(): Long =
    Calendar.getInstance()
        .apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        .timeInMillis

/** 列表加上单独取来的 [far]（已经在列表里的不重复），按更新时间排。 */
private fun withFarSessions(
    sessions: List<SessionSummary>,
    far: List<SessionSummary>,
): List<SessionSummary> {
    val extra = far.distinctBy { it.id }.filter { extra -> sessions.none { it.id == extra.id } }
    return if (extra.isEmpty()) sessions else (sessions + extra).sortedByDescending { it.updated }
}

/** 列着的这台主机上置顶的会话里，不在 [sessions] 里的那些的 id。 */
private fun missingPinned(
    sessions: List<SessionSummary>,
    pins: Set<String>,
    backend: String?,
): List<String> {
    val listed = sessions.mapTo(HashSet()) { it.id }
    return pins.mapNotNull { key ->
        SessionSummary.pinnedId(key, backend ?: return@mapNotNull null)?.takeIf { it !in listed }
    }
}

/**
 * 把不在 [sessions] 里的置顶会话单独取来交给 [onFound]（取不到时不动）；确实已经不在了（别处删掉的）就把置顶去掉。 只管列着的各台主机上的置顶：没列的主机查不到不等于没了。
 */
private suspend fun syncFarPinned(
    hub: ConversationHub,
    sessions: List<SessionSummary>,
    pins: Set<String>,
    onFound: (List<SessionSummary>) -> Unit,
    savePins: (Set<String>) -> Unit,
) {
    val far = ArrayList<SessionSummary>()
    val gone = HashSet<String>()
    for (backend in sessions.map { it.backend }.distinct()) {
        val missing = missingPinned(sessions, pins, backend)
        if (missing.isEmpty()) continue
        val found =
            withContext(Dispatchers.IO) { hub.sessionList.find(missing, backend) } ?: continue
        far += found
        (missing - found.mapTo(HashSet()) { it.id }).mapTo(gone) {
            SessionSummary.pinKey(backend, it)
        }
    }
    onFound(far)
    if (gone.isNotEmpty()) savePins(pins - gone)
}

/** 对话内容的搜索：内容里找到的会话（id → 片段）和搜到哪了。 */
@Stable
private class ContentSearchState {
    var hits by mutableStateOf<Map<String, String>>(emptyMap())
    var progress by mutableStateOf(SearchProgress.Idle)
}

/** 关键词 [needle] 一变就把上一次的停掉、重新在对话内容里搜；空的不搜。 */
@Composable
private fun rememberContentSearch(hub: ConversationHub, needle: String): ContentSearchState {
    val search = remember { ContentSearchState() }
    LaunchedEffect(needle) {
        search.hits = emptyMap()
        if (needle.isEmpty()) {
            search.progress = SearchProgress.Idle
            return@LaunchedEffect
        }
        search.progress = SearchProgress.Running
        delay(300) // 打字时不必每个字都起一次进程
        searchContent(
            hub,
            needle,
            onHit = { id, snippet -> search.hits = search.hits + (id to snippet) },
            onProgress = { search.progress = it },
        )
    }
    return search
}

/** 侧栏列表的内容：搜索时是结果，平时是分组。 */
private fun LazyListScope.paneItems(
    searching: Boolean,
    results: List<SessionSummary>,
    search: ContentSearchState,
    groups: SessionGroups,
    problem: String?,
    rows: RowContext,
) {
    if (searching) searchItems(results, search.hits, search.progress, rows)
    else groupItems(groups, problem, rows)
}

/** 让助手在对话内容里找 [needle]，边找边交给 [onHit]；找完报 [onProgress]。所在的协程被取消时停掉。 */
private suspend fun searchContent(
    hub: ConversationHub,
    needle: String,
    onHit: (String, String) -> Unit,
    onProgress: (SearchProgress) -> Unit,
) {
    // 列着几台主机就各搜各的；都搜完才算完，有一台只列了一部分或没搜成就照它报
    val backends = hub.listedBackends
    val outcomes = ArrayList<Boolean?>()
    val searches = backends.map { backend ->
        ContentSearch(
            hub,
            backend,
            needle,
            onHit = onHit,
            onDone = { complete ->
                outcomes += complete
                if (outcomes.size == backends.size)
                    onProgress(searchOutcome(combinedOutcome(outcomes)))
            },
        )
    }
    try {
        awaitCancellation()
    } finally {
        searches.forEach { it.cancel() }
    }
}

/** 几台主机各自的搜索结局合成一个：都没搜成是 null，有一台没搜全（或没搜成）是 false，都找完了是 true。 */
internal fun combinedOutcome(outcomes: List<Boolean?>): Boolean? =
    if (outcomes.all { it == null }) null else outcomes.all { it == true }

/** 内容搜索的结局：找完了、只列了一部分、没搜成（null）。 */
private fun searchOutcome(complete: Boolean?): SearchProgress =
    when (complete) {
        true -> SearchProgress.Done
        false -> SearchProgress.Partial
        null -> SearchProgress.Failed
    }

/** 搜索结果：内容里找到的，加上标题或首句里有 [needle] 的。 */
private fun searchResults(
    all: List<SessionSummary>,
    needle: String,
    hits: Map<String, String>,
): List<SessionSummary> =
    if (needle.isEmpty()) emptyList()
    else
        all.filter {
            it.id in hits ||
                it.title.contains(needle, ignoreCase = true) ||
                it.preview.contains(needle, ignoreCase = true)
        }

/** 侧栏眼前这份列表的分组：主列表分“置顶 / 今天 / 更早”，看归档时只有一组（照常按时间排，只带个图钉）。 [archived] 是全部归档的会话，底部的入口看它。 */
private class SessionGroups(
    all: List<SessionSummary>,
    pins: Set<String>,
    val showArchived: Boolean,
    todayStart: Long,
) {
    val archived = all.filter { it.archived }
    val visible = all.filter { it.archived == showArchived }
    // 置顶只在主列表里单独成组
    val pinned = if (showArchived) emptyList() else visible.filter { it.pinKey in pins }
    private val unpinned = if (pinned.isEmpty()) visible else visible - pinned.toSet()
    val today = if (showArchived) emptyList() else unpinned.filter { it.updated >= todayStart }
    val earlier = if (showArchived) unpinned else unpinned.filter { it.updated < todayStart }

    /** 列表里自上而下的几组，定位会话时按它算位置。 */
    val listed: List<List<SessionSummary>>
        get() = listOf(pinned, today, earlier)
}

/** 存储页要定位的会话：列表里有就用它，没有就到主机上单独取（取来的交给 [onFetched]）；找不到时提示一声，返回 null。 */
private suspend fun findRevealTarget(
    id: String,
    context: Context,
    hub: ConversationHub,
    known: List<SessionSummary>,
    onFetched: (SessionSummary) -> Unit,
): SessionSummary? {
    known
        .firstOrNull { it.id == id }
        ?.let {
            return it
        }
    val backend = hub.backend
    val found = withContext(Dispatchers.IO) { hub.sessionList.find(listOf(id), backend) }
    if (found == null) {
        val who = if (backend == Backends.LOCAL) "Linux 环境" else "「${hub.backendName(backend)}」"
        Toast.makeText(context, "没查到这个对话（${who}没有响应）", Toast.LENGTH_SHORT).show()
        return null
    }
    val target = found.firstOrNull()
    if (target == null) {
        Toast.makeText(context, "侧栏里没有这个对话（它没有可显示的内容）", Toast.LENGTH_SHORT).show()
        return null
    }
    onFetched(target)
    return target
}

/** 画一行会话要的：正看着哪个、哪个在闪、谁在跑、置顶、搜索词、多选状态和各项操作。 */
private class RowContext(
    val currentSessionId: String?,
    val flash: String?,
    val isRunning: (String) -> Boolean,
    val hub: ConversationHub,
    val pins: Set<String>,
    val highlight: String,
    val selecting: Boolean,
    val selected: Set<String>,
    val actions: RowActions,
)

@Composable
private fun SessionItem(session: SessionSummary, rows: RowContext, snippet: String? = null) {
    val current = session.id == rows.currentSessionId
    SessionRow(
        session = session,
        selected = current || session.id == rows.flash,
        running = rows.isRunning(session.id),
        // 正看着的对话，确认面板就在眼前，不必再标
        waiting = !current && rows.hub.isWaiting(session.id),
        // 正看着的对话，草稿就在输入框里
        draft = if (!current) rows.hub.drafts.preview(session.id) else null,
        pinned = session.pinKey in rows.pins,
        snippet = snippet,
        highlight = rows.highlight,
        selecting = rows.selecting,
        checked = session.id in rows.selected,
        actions = rows.actions,
    )
}

/** 一组会话：标题一行，下面各个会话；空的不列。 */
private fun LazyListScope.sessionGroup(
    key: String,
    label: String,
    first: Boolean,
    list: List<SessionSummary>,
    rows: RowContext,
) {
    if (list.isEmpty()) return
    item(key = "h-$key") { SectionHeader(label, first = first) }
    items(list, key = { it.id }) { SessionItem(it, rows) }
}

/** 搜索时的列表：结果（带内容里找到的片段），内容还在搜或没搜全时末尾说明。 */
private fun LazyListScope.searchItems(
    results: List<SessionSummary>,
    hits: Map<String, String>,
    progress: SearchProgress,
    rows: RowContext,
) {
    if (results.isEmpty()) {
        item(key = "search-empty") {
            PaneMessage(if (progress == SearchProgress.Running) "正在搜索…" else "没有匹配的对话")
        }
        return
    }
    item(key = "h-search") { SectionHeader("搜索结果", first = true) }
    items(results, key = { it.id }) { SessionItem(it, rows, snippet = hits[it.id]) }
    searchFooterText(progress)?.let { text -> item(key = "search-more") { SearchFooter(text) } }
}

private fun searchFooterText(progress: SearchProgress): String? =
    when (progress) {
        SearchProgress.Running -> "正在搜索对话内容…"
        SearchProgress.Partial -> "结果较多，只列出了一部分，换个更具体的词试试"
        SearchProgress.Failed -> "对话内容没搜成，只比对了标题"
        else -> null
    }

/** 平时的列表：列不出来时的说明，或“置顶 / 今天 / 更早”（看归档时是“已归档”）几组。 */
private fun LazyListScope.groupItems(groups: SessionGroups, problem: String?, rows: RowContext) {
    if (problem != null) {
        item(key = "problem") { PaneMessage(problem) }
    } else if (groups.visible.isEmpty() && !groups.showArchived) {
        item(key = "empty") { PaneMessage("还没有对话") }
    }
    val pinned = groups.pinned
    val today = groups.today
    sessionGroup("pinned", "置顶", first = true, list = pinned, rows = rows)
    sessionGroup("today", "今天", first = pinned.isEmpty(), list = today, rows = rows)
    sessionGroup(
        "earlier",
        if (groups.showArchived) "已归档 · ${groups.archived.size}" else "更早",
        first = pinned.isEmpty() && today.isEmpty(),
        list = groups.earlier,
        rows = rows,
    )
}

/** 侧栏顶上：品牌标、应用名和新对话键。 */
@Composable
private fun PaneBrandHeader(onNewChat: () -> Unit) {
    val colors = COUITheme.colorScheme
    val dark = colors.isDark
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .height(56.dp)
                .padding(
                    start = PaneHorizontalPadding + 4.dp,
                    end = PaneHorizontalPadding - 4.dp,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val accent = colors.primary
        // 品牌标：与启动图标同一个 ζ 字形，主题色往黑（深色下往白）稍稍渐变，和启动图标一致，所以这里用了黑白两个定值
        val badge =
            remember(accent, dark) {
                Brush.linearGradient(
                    listOf(
                        accent,
                        lerp(accent, if (dark) Color.White else Color.Black, 0.12f),
                    ),
                )
            }
        val badgeShape = remember { SquircleShape(8.dp) }
        Box(
            Modifier.size(28.dp).clip(badgeShape).background(badge, badgeShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_stat_zeta),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = colors.onPrimary,
            )
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
}

/** 不在多选时的顶部：品牌头；加过远程主机时的主机胶囊（点开换一台）；有后台对话或后台任务时（不在搜索）列表上方的入口。 */
@Composable
private fun PaneTop(
    hub: ConversationHub,
    searching: Boolean,
    onNewChat: () -> Unit,
    onOpenBackgroundTasks: () -> Unit,
) {
    PaneBrandHeader(onNewChat)
    HostSwitcher(
        hub,
        forList = true,
        modifier =
            Modifier.padding(
                start = PaneHorizontalPadding,
                end = PaneHorizontalPadding,
                bottom = 8.dp,
            ),
    )
    if (!searching) {
        BackgroundTasksEntry(
            hub,
            onOpen = onOpenBackgroundTasks,
            modifier =
                Modifier.padding(
                    start = PaneHorizontalPadding,
                    end = PaneHorizontalPadding,
                    bottom = 6.dp,
                ),
        )
    }
}

/** 侧栏露着时（[revealed]）接存储页发来的定位请求（见 SessionReveal），一个个交给 [onReveal]。 */
@Composable
private fun RevealRequests(revealed: Boolean, onReveal: suspend (String) -> Unit) {
    val reveal by rememberUpdatedState(onReveal)
    LaunchedEffect(revealed) {
        if (!revealed) return@LaunchedEffect
        snapshotFlow { SessionReveal.request }
            .collect { id ->
                if (id == null) return@collect
                SessionReveal.request = null
                reveal(id)
            }
    }
}

/** 侧栏底部：搜索框、归档的入口（看归档时是返回）和设置。 */
@Composable
private fun PaneBottomBar(
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    showArchived: Boolean,
    archivedCount: Int,
    onShowArchived: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth().padding(horizontal = PaneHorizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchCapsule(query = query, onQueryChange = onQueryChange, modifier = Modifier.weight(1f))
        // 归档的入口固定在底部，不用滚到列表尽头去找；搜索时结果里本来就包括归档的，先收起来
        if (!searching) ArchiveToggle(showArchived, archivedCount, onShowArchived)
        Spacer(Modifier.width(8.dp))
        PaneCircleButton(
            icon = COUIIcons.Settings,
            description = "设置",
            onClick = onOpenSettings,
        )
    }
}

/** 看归档时是返回全部对话的键，有归档的会话时是进归档的键，都没有时不显示。 */
@Composable
private fun ArchiveToggle(
    showArchived: Boolean,
    archivedCount: Int,
    onShowArchived: (Boolean) -> Unit,
) {
    when {
        showArchived -> {
            Spacer(Modifier.width(8.dp))
            PaneCircleButton(
                icon = COUIIcons.Back,
                description = "返回全部对话",
                onClick = { onShowArchived(false) },
            )
        }
        archivedCount > 0 -> {
            Spacer(Modifier.width(8.dp))
            PaneCircleButton(
                icon = ZetaIcons.Archive,
                description = "已归档 · $archivedCount",
                onClick = { onShowArchived(true) },
            )
        }
    }
}

/** 多选里点“删除”：正在进行的不删，剩下的交给 [onConfirm] 去确认；一个都删不了时只提示。 */
private fun askBatchDelete(
    chosen: List<SessionSummary>,
    hub: ConversationHub,
    context: Context,
    onConfirm: (List<SessionSummary>) -> Unit,
) {
    val removable = deletable(chosen, hub::isActive)
    if (removable.isEmpty()) {
        Toast.makeText(context, "选中的对话都在进行中，不能删除", Toast.LENGTH_SHORT).show()
    } else {
        onConfirm(removable)
    }
}

/** 确认删除单个对话：确认框开着时它开始进行了就不删，提示一声。 */
private fun confirmDelete(
    prompt: DeletePrompt?,
    hub: ConversationHub,
    context: Context,
    onDelete: (SessionSummary) -> Unit,
) {
    val target = confirmedDeletion(prompt, hub::isActive)
    if (target != null) onDelete(target)
    else if (prompt != null) Toast.makeText(context, "这个对话正在进行，没有删除", Toast.LENGTH_SHORT).show()
}

/**
 * 单条删除和批量删除同一个规矩：正在进行的、别处的 Claude Code 开着的，都不删。
 * 只提示还是确认删除在打开时定下（DeletePrompt），框开着时对话结束了也不会把“知道了”换成“删除”。 对话框是 COUI 的 Overlay
 * 组件，由外层推移容器（ConversationSidePaneScaffold）里的 Scaffold 盖满整个屏幕显示。
 */
@Composable
private fun DeleteDialogs(
    deleting: DeletePrompt?,
    onDelete: (DeletePrompt?) -> Unit,
    onDismiss: () -> Unit,
) {
    val lastDeleting = rememberRetained(deleting)
    val deletingName = lastDeleting?.session?.let { it.title.ifBlank { it.preview }.take(60) }
    NoticeDialog(
        show = deleting is DeletePrompt.Blocked,
        title = "这个对话正在进行",
        summary = deletingName?.let { "$it\n先停止它，或等它结束，再删除。" },
        closeText = "知道了",
        onDismissRequest = onDismiss,
    )
    ConfirmDialog(
        show = deleting is DeletePrompt.Confirm,
        title = "删除这个对话？",
        summary = deletingName?.let { "$it\n会话记录文件会被一并删除，无法恢复。" },
        confirmText = "删除",
        destructive = true,
        onConfirm = {
            onDelete(deleting)
            onDismiss()
        },
        onDismissRequest = onDismiss,
    )
}

/** 批量删除的确认框；[skipped] 个正在进行的不删，注明。 */
@Composable
private fun BatchDeleteDialog(
    batch: List<SessionSummary>?,
    skipped: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val lastBatch = rememberRetained(batch)
    ConfirmDialog(
        show = batch != null,
        title = "删除 ${lastBatch?.size ?: 0} 个对话？",
        summary =
            "会话记录会被删除，Claude Code 为它们存的工具结果、子代理记录等也一并删除，无法恢复。" +
                if (skipped > 0) "\n另有 $skipped 个正在进行的对话不会删除。" else "",
        confirmText = "删除",
        destructive = true,
        onConfirm = onConfirm,
        onDismissRequest = onDismiss,
    )
}

/** 侧栏里的圆形图标按钮：COUI 17 顶栏按钮的样子（材质底、阴影、按下缩放）。 */
@Composable
internal fun PaneCircleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    size: Dp = 40.dp,
) =
    IconActionButton(
        icon,
        description,
        minWidth = size,
        minHeight = size,
        cornerRadius = size / 2,
        iconModifier = Modifier.size(22.dp),
        onClick = onClick,
    )

/** 底部的搜索框：COUI 的搜索胶囊，自带搜索图标和清空键。键盘上的“搜索”只收起键盘，结果随打字出来。 */
@Composable
private fun SearchCapsule(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
        insideMargin =
            PaddingValues(
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
        Text(
            text = text,
            style = COUITheme.textStyles.bodyM,
            color = COUITheme.colorScheme.labelSecondary,
        )
    }
}

@Composable
private fun SearchFooter(text: String) {
    Text(
        text = text,
        style = COUITheme.textStyles.bodyXS,
        color = COUITheme.colorScheme.labelSecondary,
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    horizontal = PaneHorizontalPadding + RowHorizontalPadding,
                    vertical = 12.dp,
                ),
    )
}

/** 把 [text] 里所有的 [needle]（不分大小写）标成 [color]。 */
private fun highlighted(text: String, needle: String, color: Color): AnnotatedString =
    buildAnnotatedString {
        append(text)
        if (needle.isEmpty()) return@buildAnnotatedString
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            addStyle(
                SpanStyle(color = color, fontWeight = FontWeight.Medium),
                at,
                at + needle.length,
            )
            from = at + needle.length
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    running: Boolean,
    waiting: Boolean,
    /** 没发出的草稿开头的一行；没有草稿时为 null。 */
    draft: String?,
    pinned: Boolean,
    snippet: String?,
    highlight: String,
    selecting: Boolean,
    checked: Boolean,
    actions: RowActions,
) {
    val colors = COUITheme.colorScheme
    val shape = remember { SquircleShape(16.dp) }
    val haptic = LocalHapticFeedback.current
    // 菜单项的点击读最新的那组操作（RowActions 每次重组都是新的，不拿它当记忆的键）
    val currentActions by rememberUpdatedState(actions)
    val menu = remember(session, pinned) { sessionMenu(session, pinned) { currentActions } }
    Box(
        modifier =
            Modifier.fillMaxWidth().padding(horizontal = PaneHorizontalPadding, vertical = 1.5.dp),
    ) {
        // 长按弹出 COUI 的长按菜单（在按下的位置）；多选时长按改成勾选这一行，不弹菜单
        OverlayContextMenu(entries = menu, enabled = !selecting) {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(shape)
                        .background(if (selected) colors.cardBackground else Color.Transparent)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            onClick = { rowClick(session, selecting, actions) },
                            onLongClick =
                                if (selecting) {
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
                SessionRowText(session, selected, waiting, draft, snippet, highlight)
                SessionRowMark(waiting, running, pinned && !selecting)
                if (selecting) {
                    Spacer(Modifier.width(10.dp))
                    // 只显示勾选状态，点哪里都由整行处理
                    Checkbox(state = ToggleableState(checked), onClick = null)
                }
            }
        }
    }
}

/** 点一行：多选时勾选，否则打开。 */
private fun rowClick(session: SessionSummary, selecting: Boolean, actions: RowActions) {
    if (selecting) actions.toggle(session) else actions.open(session)
}

/** 一行会话的长按菜单：选择、置顶、重命名、导出、分享、归档一组，删除单独一组、标成红色。点击时才取 [actions]（最新的那组）。 */
private fun sessionMenu(
    session: SessionSummary,
    pinned: Boolean,
    actions: () -> RowActions,
): List<DropdownEntry> =
    listOf(
        DropdownEntry(
            items =
                listOf(
                    menuItem("选择", COUIIcons.SelectAll, { actions().select(session) }),
                    menuItem(
                        if (pinned) "取消置顶" else "置顶对话",
                        if (pinned) COUIIcons.Unpin else COUIIcons.Pin,
                        { actions().togglePin(session) },
                    ),
                    menuItem("重命名", COUIIcons.Rename, { actions().rename(session) }),
                    menuItem("导出", COUIIcons.Download, { actions().export(session) }),
                    menuItem("分享", COUIIcons.Share, { actions().share(session) }),
                    menuItem(
                        if (session.archived) "取消归档" else "归档",
                        if (session.archived) ZetaIcons.Unarchive else ZetaIcons.Archive,
                        { actions().archive(session) },
                    ),
                ),
        ),
        DropdownEntry(
            items =
                listOf(
                    menuItem("删除", COUIIcons.Delete, { actions().delete(session) }, alert = true),
                ),
        ),
    )

/** 一行的文字：标题（搜索词标出来），下面一行说明。 */
@Composable
private fun RowScope.SessionRowText(
    session: SessionSummary,
    selected: Boolean,
    waiting: Boolean,
    draft: String?,
    snippet: String?,
    highlight: String,
) {
    val colors = COUITheme.colorScheme
    val title = session.title.ifBlank { session.preview }.ifBlank { "未命名对话" }
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text =
                if (highlight.isEmpty()) AnnotatedString(title)
                else highlighted(title, highlight, colors.primary),
            style =
                COUITheme.textStyles.bodyL.copy(
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                ),
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        SessionSubtitle(session, waiting, draft, snippet, highlight)
    }
}

/** 标题下面的一行：等你确认、搜到的片段、没发出的草稿，或者工作目录。 */
@Composable
private fun SessionSubtitle(
    session: SessionSummary,
    waiting: Boolean,
    draft: String?,
    snippet: String?,
    highlight: String,
) {
    val colors = COUITheme.colorScheme
    val subtitleStyle = COUITheme.textStyles.bodyXS
    when {
        // 后台的对话在等你批准一次工具调用：比“在运行”的小圆点醒目得多，免得一直卡着没人管
        waiting ->
            Text(
                text = "等你确认",
                style = subtitleStyle.copy(fontWeight = FontWeight.Medium),
                color = StatusWarning,
                maxLines = 1,
            )
        snippet != null ->
            Text(
                text = highlighted(snippet, highlight, colors.primary),
                style = subtitleStyle,
                color = colors.labelSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        // 有没发出的草稿：照常见聊天应用，红字的「[草稿]」后面接草稿的开头
        draft != null ->
            Text(
                text =
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.error)) { append("[草稿] ") }
                        append(draft)
                    },
                style = subtitleStyle,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        else ->
            Text(
                text =
                    sessionPlace(
                        session,
                        highlight,
                        LocalHostName.current?.invoke(session.backend),
                    ),
                style = subtitleStyle,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
    }
}

/** 列着全部设备时各行要注明的设备名（主机 → 名字）；只列一台时是 null，不注明。 */
private val LocalHostName = compositionLocalOf<((String) -> String)?> { null }

private fun rowHostName(hub: ConversationHub): ((String) -> String)? =
    if (hub.allHosts) hub::backendName else null

/** 工作目录的最后一级，列着全部设备时前面是所在设备的名字 [host]；别处在运行、（搜索时）已归档的注明。 */
private fun sessionPlace(session: SessionSummary, highlight: String, host: String?): String =
    (host?.let { "$it · " } ?: "") +
        session.cwd.trimEnd('/').substringAfterLast('/').ifBlank { "/" } +
        (if (session.external) " · 别处运行中" else "") +
        (if (highlight.isNotEmpty() && session.archived) " · 已归档" else "")

/** 行尾的记号：等你确认的醒目圆点、在运行的小圆点，或者（不在多选时）置顶的图钉。 */
@Composable
private fun SessionRowMark(waiting: Boolean, running: Boolean, showPin: Boolean) {
    when {
        waiting -> {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(14.dp).background(StatusWarning.copy(alpha = 0.22f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(8.dp).background(StatusWarning, CircleShape))
            }
        }
        running -> {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(6.dp).background(COUITheme.colorScheme.primary, CircleShape))
        }
        showPin -> {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = COUIIcons.Pin,
                contentDescription = "已置顶",
                modifier = Modifier.size(16.dp),
                tint = COUITheme.colorScheme.labelSecondaryVariant,
            )
        }
    }
}
