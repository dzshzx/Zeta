package com.dzshzx.zeta.ui.pane

import android.app.Application
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MapsUgc
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import androidx.compose.ui.res.painterResource
import com.dzshzx.zeta.R
import com.itos.coui.shape.CouiShape
import com.itos.coui.theme.CouiTheme
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.ui.components.StatusError
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.conversationPaneContainerColor
import com.dzshzx.zeta.ui.components.coui.CouiConfirmDialog
import com.dzshzx.zeta.ui.components.coui.CouiInputDialog
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val PaneHorizontalPadding = 16.dp
private val RowHorizontalPadding = 14.dp

/** 内容搜索进行到哪了。 */
private enum class SearchProgress { Idle, Running, Done, Partial, Failed }

/** 一行会话长按菜单里的各项操作。 */
private class RowActions(
    val open: (SessionSummary) -> Unit,
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
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionPane(
    width: Dp,
    sessions: List<SessionSummary>,
    currentSessionId: String?,
    isRunning: (String) -> Boolean,
    onNewChat: () -> Unit,
    onOpen: (SessionSummary) -> Unit,
    onDelete: (SessionSummary) -> Unit,
    onRename: (SessionSummary, String) -> Unit,
    onArchive: (SessionSummary, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    // 置顶、内容搜索、导出、“等你确认”都要用到应用级的单例，直接取，不再从外面一样样传进来
    val context = LocalContext.current
    val hub = remember(context) { ConversationHub.get(context.applicationContext as Application) }
    val focusManager = LocalFocusManager.current
    var deleting by remember { mutableStateOf<SessionSummary?>(null) }
    val lastDeleting = remember { arrayOfNulls<SessionSummary>(1) }
    deleting?.let { lastDeleting[0] = it }
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    val exporter = rememberSessionExporter(hub)

    // 置顶存在设置里，按会话 id
    var pins by remember { mutableStateOf(hub.settings.pinnedSessions) }
    val savePins: (Set<String>) -> Unit = {
        pins = it
        hub.settings.pinnedSessions = it
    }
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
    val all = remember(sessions, farPinned) {
        if (farPinned.isEmpty()) sessions else (sessions + farPinned.filter { far -> sessions.none { it.id == far.id } }).sortedByDescending { it.updated }
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
        togglePin = { session -> savePins(if (session.id in pins) pins - session.id else pins + session.id) },
        rename = { session ->
            renameDraft = session.title.ifBlank { session.preview.take(40) }
            renaming = session
        },
        export = exporter.save,
        share = exporter.share,
        archive = { session -> onArchive(session, !session.archived) },
        delete = { session -> deleting = session },
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

    @Composable
    fun SessionItem(session: SessionSummary, snippet: String? = null) = SessionRow(
        session = session,
        selected = session.id == currentSessionId,
        running = isRunning(session.id),
        // 正看着的对话，确认面板就在眼前，不必再标
        waiting = session.id != currentSessionId && hub.isWaiting(session.id),
        pinned = session.id in pins,
        snippet = snippet,
        highlight = needle,
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
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = PaneHorizontalPadding + 4.dp, end = PaneHorizontalPadding - 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val accent = CouiTheme.colorScheme.accent
            val badge = remember(accent, dark) { Brush.linearGradient(listOf(accent, lerp(accent, if (dark) Color.White else Color.Black, 0.12f))) }
            // 与启动图标同一个 ζ 字形
            val badgeShape = remember { CouiShape(8.dp, 2.3f) }
            Box(Modifier.size(28.dp).clip(badgeShape).background(badge, badgeShape), contentAlignment = Alignment.Center) {
                Icon(painter = painterResource(R.drawable.ic_stat_zeta), contentDescription = null, modifier = Modifier.size(22.dp), tint = Color.White)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Zeta",
                style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.SemiBold),
                color = colors.title,
                modifier = Modifier.weight(1f),
            )
            PaneCircleButton(icon = Icons.Rounded.MapsUgc, description = "新对话", onClick = onNewChat)
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PaneHorizontalPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchCapsule(query = query, onQueryChange = { query = it }, modifier = Modifier.weight(1f))
            // 归档的入口固定在底部，不用滚到列表尽头去找；搜索时结果里本来就包括归档的，先收起来
            if (!searching) {
                when {
                    showArchived -> {
                        Spacer(Modifier.width(8.dp))
                        PaneCircleButton(icon = Icons.AutoMirrored.Rounded.ArrowBack, description = "返回全部对话", onClick = { showArchived = false }, size = 44.dp)
                    }
                    archived.isNotEmpty() -> {
                        Spacer(Modifier.width(8.dp))
                        PaneCircleButton(icon = Icons.Rounded.Archive, description = "已归档 · ${archived.size}", onClick = { showArchived = true }, size = 44.dp)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            PaneCircleButton(icon = Icons.Outlined.Settings, description = "设置", onClick = onOpenSettings, size = 44.dp)
        }
    }

    CouiConfirmDialog(
        visible = deleting != null,
        title = "删除这个对话？",
        message = lastDeleting[0]?.let { it.title.ifBlank { it.preview }.take(60) + "\n会话记录文件会被一并删除，无法恢复。" },
        confirmText = "删除",
        destructive = true,
        onConfirm = {
            deleting?.let {
                if (it.id in pins) savePins(pins - it.id)
                onDelete(it)
            }
            deleting = null
        },
        onDismissRequest = { deleting = null },
    )
    CouiInputDialog(
        visible = renaming != null,
        title = "重命名",
        value = renameDraft,
        onValueChange = { renameDraft = it },
        placeholder = "对话标题",
        confirmText = "确定",
        confirmEnabled = renameDraft.isNotBlank(),
        onConfirm = {
            renaming?.let { onRename(it, renameDraft) }
            renaming = null
        },
        onDismissRequest = { renaming = null },
    )
}

@Composable
private fun PaneCircleButton(icon: ImageVector, description: String, onClick: () -> Unit, size: Dp = 40.dp) {
    val dark = CouiTheme.isDark
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (dark) Color(0xFF252830) else Color.White, CircleShape)
            .border(0.5.dp, if (dark) Color(0x1EFFFFFF) else Color(0x12000000), CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(19.dp), tint = CouiPageDefaults.themeColors(dark).title)
    }
}

/** 底部的搜索框：与旁边的圆按钮同一种底色和描边，聚焦时描边换成主题色。照 Eta 侧栏底部的搜索胶囊。 */
@Composable
private fun SearchCapsule(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    val accent = CouiTheme.colorScheme.accent
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val hint = if (dark) Color(0x70FFFFFF) else Color(0x66000000)
    val textStyle = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp, color = colors.title)
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(if (dark) Color(0xFF252830) else Color.White, CircleShape)
            .border(
                if (focused) 1.dp else 0.5.dp,
                if (focused) accent.copy(alpha = 0.6f) else if (dark) Color(0x1EFFFFFF) else Color(0x12000000),
                CircleShape,
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                focusRequester.requestFocus()
                keyboard?.show()
            }
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (focused) accent else hint)
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(text = "搜索对话", style = textStyle, color = hint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { focused = it.isFocused },
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "清空") { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Cancel,
                    contentDescription = "清空",
                    modifier = Modifier.size(18.dp),
                    tint = if (dark) Color(0x4DFFFFFF) else Color(0x42000000),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String, first: Boolean) {
    Text(
        text = label,
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        color = CouiPageDefaults.themeColors(CouiTheme.isDark).summary,
        modifier = Modifier.fillMaxWidth().padding(
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
        Text(text = text, style = MiuixTheme.textStyles.body2, color = CouiPageDefaults.themeColors(CouiTheme.isDark).summary)
    }
}

@Composable
private fun SearchFooter(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 16.sp),
        color = CouiPageDefaults.themeColors(CouiTheme.isDark).summary,
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
    actions: RowActions,
) {
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    val accent = CouiTheme.colorScheme.accent
    val shape = remember { CouiShape(14.dp, 2.1f) }
    val haptic = LocalHapticFeedback.current
    val anchor = rememberCouiMenuAnchor()
    var showMenu by remember { mutableStateOf(false) }
    val deleteTint = StatusError
    val menuItems = remember(deleteTint, session.archived, pinned) {
        listOf(
            CouiMenuItem(value = 3, text = if (pinned) "取消置顶" else "置顶对话", icon = Icons.Rounded.PushPin),
            CouiMenuItem(value = 1, text = "重命名", icon = Icons.Rounded.Edit),
            CouiMenuItem(value = 4, text = "导出", icon = Icons.Rounded.Download),
            CouiMenuItem(value = 5, text = "分享", icon = Icons.Rounded.Share),
            CouiMenuItem(
                value = 2,
                text = if (session.archived) "取消归档" else "归档",
                icon = if (session.archived) Icons.Rounded.Unarchive else Icons.Rounded.Archive,
            ),
            CouiMenuItem(value = 0, text = "删除", icon = Icons.Rounded.DeleteOutline, iconTint = deleteTint, dividerBefore = true),
        )
    }
    val title = session.title.ifBlank { session.preview }.ifBlank { "未命名对话" }
    val subtitleStyle = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 16.sp)
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = PaneHorizontalPadding, vertical = 1.5.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .couiMenuAnchor(anchor)
                .then(if (selected && !dark) Modifier.dropShadow(shape = shape, shadow = Shadow(radius = 8.dp, color = Color.Black, alpha = 0.028f)) else Modifier)
                .clip(shape)
                .background(if (!selected) Color.Transparent else if (dark) Color(0xFF262932) else Color.White, shape)
                .then(if (selected) Modifier.border(0.5.dp, if (dark) Color(0x1AFFFFFF) else Color(0x0D000000), shape) else Modifier)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { actions.open(session) },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        anchor.open { showMenu = true }
                    },
                )
                .padding(horizontal = RowHorizontalPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (highlight.isEmpty()) AnnotatedString(title) else highlighted(title, highlight, accent),
                    style = MiuixTheme.textStyles.body1.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
                    color = colors.title,
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
                        color = colors.summary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    else -> Text(
                        text = session.cwd.trimEnd('/').substringAfterLast('/').ifBlank { "/" } +
                            (if (session.external) " · 别处运行中" else "") +
                            (if (highlight.isNotEmpty() && session.archived) " · 已归档" else ""),
                        style = subtitleStyle,
                        color = colors.summary,
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
                pinned -> {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = "已置顶",
                        modifier = Modifier.size(13.dp),
                        tint = colors.summary.copy(alpha = 0.72f),
                    )
                }
            }
        }
        Menu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            items = menuItems,
            onItemClick = {
                showMenu = false
                when (it.value) {
                    3 -> actions.togglePin(session)
                    1 -> actions.rename(session)
                    4 -> actions.export(session)
                    5 -> actions.share(session)
                    2 -> actions.archive(session)
                    else -> actions.delete(session)
                }
            },
            anchor = anchor,
        )
    }
}
