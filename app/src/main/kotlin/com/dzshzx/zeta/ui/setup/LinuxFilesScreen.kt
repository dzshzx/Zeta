package com.dzshzx.zeta.ui.setup

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.FileImage
import com.dzshzx.zeta.core.LinuxEntry
import com.dzshzx.zeta.core.LinuxFiles
import com.dzshzx.zeta.core.LinuxListing
import com.dzshzx.zeta.core.LinuxPreview
import com.dzshzx.zeta.core.LinuxTransfers
import com.dzshzx.zeta.core.TransferState
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.chat.ChatImage
import com.dzshzx.zeta.ui.components.MonoFont
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.miuix.CardListSegment
import com.dzshzx.zeta.ui.components.miuix.FooterHint
import com.dzshzx.zeta.ui.components.miuix.LongPressMenu
import com.dzshzx.zeta.ui.components.miuix.MenuEntry
import com.dzshzx.zeta.ui.components.miuix.MenuItem
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.PreferenceValue
import com.dzshzx.zeta.ui.components.miuix.TopBarMoreMenu
import com.dzshzx.zeta.ui.components.miuix.ZetaIcons
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Linux 环境页下面的两个子页。 */
internal enum class LinuxFilesPage {
    Browser,
    Shared,
}

/** 文件浏览、共享文件夹：盖在 Linux 环境页上面（进出动画与其它页相同），自己处理返回键。 放在 Linux 环境页里而不是主界面的页面栈里，免得为两个子页去改主界面的导航。 */
@Composable
internal fun LinuxFilesPages(hub: ConversationHub, page: LinuxFilesPage?, onClose: () -> Unit) {
    SlideInPage(page) { shown ->
        when (shown) {
            LinuxFilesPage.Browser -> LinuxFilesScreen(hub, onBack = onClose)
            LinuxFilesPage.Shared -> SharedFoldersScreen(hub, onBack = onClose)
        }
    }
}

/** 系统的“新建文档”，类型按扩展名猜（猜不出用 octet-stream，文件名原样保留）。 */
private class CreateDocument : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent {
        val type =
            MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(input.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
        return Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(type)
            .putExtra(Intent.EXTRA_TITLE, input)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.takeIf { resultCode == Activity.RESULT_OK }?.data
}

/** 把路径放进剪贴板（Android 13 起系统自己会提示已复制）。 */
internal fun copyLinuxPath(context: Context, path: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
        ClipData.newPlainText("path", path),
    )
}

private val SameYear = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val OtherYear = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun timeText(seconds: Long): String {
    if (seconds <= 0) return ""
    val time = Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())
    return time.format(if (time.year == java.time.Year.now().value) SameYear else OtherYear)
}

private fun entrySummary(context: Context, entry: LinuxEntry): String =
    listOfNotNull(
            entry.link?.let {
                if (entry.broken) Strings.setupLinuxFilesBrokenLink(it) else "→ $it"
            },
            if (entry.regular) Formatter.formatShortFileSize(context, entry.size) else null,
            timeText(entry.mtime).ifEmpty { null },
        )
        .joinToString(" · ")

/** 预览的文本按段切开放进列表：一整段几百 KB 的文字一次排版很卡。尽量在换行处切。 */
private fun chunks(text: String): List<String> {
    val out = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(text.length, start + 4000)
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end - 1)
            if (newline >= start) end = newline + 1
        }
        out += text.substring(start, end).removeSuffix("\n")
        start = end
    }
    return out
}

private class Previewing(val path: String, val entry: LinuxEntry)

/** Linux 文件（只读）：从默认工作目录开始浏览，预览文本和图片；长按一项可以复制路径、导出到手机，右上角可以从手机导入。 */
@Composable
internal fun LinuxFilesScreen(hub: ConversationHub, onBack: () -> Unit) {
    val context = LocalContext.current
    val env = hub.settings.env
    val start = LinuxFiles.normalize(env?.defaultCwd ?: "/workspace")
    var path by rememberSaveable { mutableStateOf(start) }
    var listing by remember { mutableStateOf<LinuxListing?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Previewing?>(null) }
    var content by remember { mutableStateOf<LinuxPreview?>(null) }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSize by rememberSaveable { mutableLongStateOf(-1L) }
    val listState = rememberLazyListState()
    // 每打开一个文件从头看起
    val previewState = remember(preview) { LazyListState() }
    val transfer = LinuxTransfers.state

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            importFiles(context, hub, uris, path)
        }
    val exportLauncher =
        rememberLauncherForActivityResult(CreateDocument()) { uri ->
            val source = pendingExport
            pendingExport = null
            exportFile(context, hub, source, pendingSize.takeIf { it >= 0 }, uri)
        }
    val export: (String, LinuxEntry) -> Unit = { target, entry ->
        pendingExport = target
        pendingSize = entry.size
        runCatching { exportLauncher.launch(entry.name) }
    }

    // 预览时返回到列表，列表时离开本页
    val back = { if (preview != null) preview = null else onBack() }
    BackHandler(onBack = back)

    LaunchedEffect(path) {
        listing = null
        listState.scrollToItem(0)
    }
    LaunchedEffect(path, reload, LinuxTransfers.imports) { listing = loadListing(hub, path) }
    LaunchedEffect(preview) {
        content = null
        val target = preview ?: return@LaunchedEffect
        content = loadPreview(hub, target)
    }

    val shown = preview
    // 列表和预览共用一个页面骨架（顶栏标题随之变），列表状态分开记，从预览回来还在原来的位置
    MiuixPage(
        title = shown?.entry?.name ?: Strings.setupLinuxFilesTitle(),
        onBack = back,
        listState = if (shown != null) previewState else listState,
        actions = {
            if (shown == null) {
                BrowserMenu(
                    onImport = { runCatching { importLauncher.launch(arrayOf("*/*")) } },
                    onRefresh = { reload++ },
                    onGo = { path = it },
                    start = start,
                    home = LinuxFiles.normalize(env?.home ?: "/root"),
                    onCopyPath = { copyLinuxPath(context, path) },
                )
            } else {
                PreviewMenu(
                    exportable = shown.entry.regular,
                    onExport = { export(shown.path, shown.entry) },
                    onCopyPath = { copyLinuxPath(context, shown.path) },
                )
            }
        },
    ) {
        if (transfer != null) item(key = "transfer") { TransferGroup(transfer) }
        if (shown != null) {
            previewItems(context, shown, content)
        } else {
            item(key = "crumbs") { Breadcrumb(path) { path = it } }
            browserItems(
                context = context,
                path = path,
                listing = listing,
                onNavigate = { path = it },
                onOpen = { target, entry ->
                    openEntry(
                        target,
                        entry,
                        onNavigate = { path = it },
                        onPreview = { preview = it },
                    )
                },
                onExport = export,
            )
        }
    }
}

/** 点了一项：目录就进去，普通文件就预览。 */
private fun openEntry(
    target: String,
    entry: LinuxEntry,
    onNavigate: (String) -> Unit,
    onPreview: (Previewing) -> Unit,
) {
    if (entry.directory) onNavigate(target)
    else if (entry.regular) onPreview(Previewing(target, entry))
}

/** 从手机导入选中的文件到 [path]；没选或 Linux 环境没配好时什么都不做。 */
private fun importFiles(context: Context, hub: ConversationHub, uris: List<Uri>, path: String) {
    val config = hub.settings.env
    if (uris.isNotEmpty() && config != null) LinuxTransfers.import(context, config, uris, path)
}

/** 把 [source] 导出到选好的 [uri]；取消了选择或 Linux 环境没配好时什么都不做。 */
private fun exportFile(
    context: Context,
    hub: ConversationHub,
    source: String?,
    size: Long?,
    uri: Uri?,
) {
    val config = hub.settings.env
    if (uri != null && source != null && config != null)
        LinuxTransfers.export(context, config, source, size, uri)
}

/** 当前目录的列表；Linux 环境还没配好时是一条错误。 */
private suspend fun loadListing(hub: ConversationHub, path: String): LinuxListing {
    if (hub.settings.env == null) return LinuxListing.Failed(Strings.setupLinuxFilesUnavailable())
    return withContext(Dispatchers.IO) {
        LinuxFiles.list({ s, a -> hub.helper(s, a, backend = Backends.LOCAL) }, path)
    }
}

/** 预览的内容；图片由界面自己读，Linux 环境没配好时也不读，都返回 null。 */
private suspend fun loadPreview(hub: ConversationHub, target: Previewing): LinuxPreview? {
    if (hub.settings.env == null) return null
    if (LinuxFiles.isImage(target.entry.name)) return null
    return withContext(Dispatchers.IO) {
        LinuxFiles.read({ s, a -> hub.helper(s, a, backend = Backends.LOCAL) }, target.path)
    }
}

/** 浏览时右上角的菜单：导入、刷新，跳到常用目录，复制当前路径。[home] 已经规整过。 */
@Composable
private fun BrowserMenu(
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    onGo: (String) -> Unit,
    start: String,
    home: String,
    onCopyPath: () -> Unit,
) {
    TopBarMoreMenu(
        listOf(
            MenuEntry(
                items =
                    listOf(
                        MenuItem(
                            Strings.setupLinuxFilesImportHere(),
                            MiuixIcons.Import,
                            onImport,
                            enabled = !LinuxTransfers.running,
                        ),
                        MenuItem(Strings.setupLinuxFilesRefresh(), MiuixIcons.Refresh, onRefresh),
                    ),
            ),
            MenuEntry(
                items =
                    listOf(
                        MenuItem(
                            Strings.setupLinuxFilesWorkDir(),
                            ZetaIcons.FolderStar,
                            { onGo(start) },
                        ),
                        MenuItem(Strings.setupLinuxFilesHome(), ZetaIcons.Home, { onGo(home) }),
                        MenuItem("/workspace", MiuixIcons.GridView, { onGo("/workspace") }),
                        MenuItem(Strings.setupLinuxFilesRoot(), ZetaIcons.Storage, { onGo("/") }),
                    ),
            ),
            MenuEntry(
                items =
                    listOf(
                        MenuItem(
                            Strings.setupLinuxFilesCopyCurrentPath(),
                            MiuixIcons.Copy,
                            onCopyPath,
                        ),
                    ),
            ),
        ),
    )
}

/** 预览时右上角的菜单：导出到手机（普通文件才有）、复制路径。 */
@Composable
private fun PreviewMenu(exportable: Boolean, onExport: () -> Unit, onCopyPath: () -> Unit) {
    TopBarMoreMenu(
        listOf(
            MenuEntry(
                items =
                    listOfNotNull(
                        MenuItem(
                                Strings.setupLinuxFilesExport(),
                                MiuixIcons.Download,
                                onExport,
                                enabled = !LinuxTransfers.running,
                            )
                            .takeIf { exportable },
                        MenuItem(Strings.setupLinuxFilesCopyPath(), MiuixIcons.Copy, onCopyPath),
                    ),
            ),
        ),
    )
}

private fun LazyListScope.browserItems(
    context: Context,
    path: String,
    listing: LinuxListing?,
    onNavigate: (String) -> Unit,
    onOpen: (String, LinuxEntry) -> Unit,
    onExport: (String, LinuxEntry) -> Unit,
) {
    val parent = LinuxFiles.parent(path)
    val entries = (listing as? LinuxListing.Entries)?.entries.orEmpty()
    // 上级目录和各项连成一张卡片；项目可能很多，所以逐项放进列表，不用 CardListGroup
    val offset = if (parent != null) 1 else 0
    val count = offset + entries.size
    if (parent != null) {
        item(key = "..") {
            CardListSegment(index = 0, count = count) {
                ArrowPreference(
                    title = Strings.setupLinuxFilesParent(),
                    summary = parent,
                    startAction = { RowIcon(ZetaIcons.FolderOpen, MiuixTheme.colorScheme.primary) },
                    onClick = { onNavigate(parent) },
                )
            }
        }
    }
    when (listing) {
        null -> item(key = "loading") { Loading() }
        is LinuxListing.Failed -> item(key = "error") { Hint(listing.message, error = true) }
        is LinuxListing.Entries -> {
            if (entries.isEmpty()) item(key = "empty") { Hint(Strings.setupLinuxFilesEmpty()) }
            // 名字里有非 UTF-8 字节的会被替换成同样的字符，可能重名，键里带上序号
            itemsIndexed(entries, key = { index, entry -> "e$index:" + entry.name }) { index, entry
                ->
                val target = LinuxFiles.child(path, entry.name)
                CardListSegment(index = offset + index, count = count) {
                    EntryRow(
                        entry = entry,
                        summary = entrySummary(context, entry),
                        onOpen = { onOpen(target, entry) },
                        onCopyPath = { copyLinuxPath(context, target) },
                        onExport = if (entry.regular) ({ onExport(target, entry) }) else null,
                    )
                }
            }
            if (listing.truncated)
                item(key = "truncated") {
                    Hint(Strings.setupLinuxFilesTruncated(entries.size, listing.total))
                }
        }
    }
}

private fun LazyListScope.previewItems(
    context: Context,
    shown: Previewing,
    content: LinuxPreview?,
) {
    item(key = "info") {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(top = 4.dp, bottom = 10.dp),
        ) {
            Text(
                text = shown.path,
                style = MiuixTheme.textStyles.footnote1.copy(fontFamily = MonoFont),
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
            Text(
                text = entrySummary(context, shown.entry),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
    }
    if (LinuxFiles.isImage(shown.entry.name)) {
        item(key = "image") {
            if (shown.entry.size > LinuxFiles.MAX_PREVIEW_IMAGE) {
                Hint(Strings.setupLinuxFilesImageTooLarge())
            } else {
                // 与聊天里的图片同一套载入和缓存，点一下全屏查看
                ChatImage(FileImage(shown.path), modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        return
    }
    when (content) {
        null -> item(key = "loading") { Loading() }
        is LinuxPreview.Failed -> item(key = "error") { Hint(content.message, error = true) }
        is LinuxPreview.Binary -> item(key = "binary") { Hint(Strings.setupLinuxFilesBinary()) }
        is LinuxPreview.Text -> {
            if (content.truncated)
                item(key = "truncated") {
                    Hint(Strings.setupLinuxFilesPreviewTruncated(LinuxFiles.PREVIEW_BYTES / 1024))
                }
            val parts = chunks(content.text)
            if (parts.isEmpty()) item(key = "blank") { Hint(Strings.setupLinuxFilesEmptyFile()) }
            itemsIndexed(parts, key = { index, _ -> "t:$index" }) { index, part ->
                // 几段连成一张卡片，中间不画分隔线
                CardListSegment(index = index, count = parts.size) {
                    TextSlice(part, isFirst = index == 0, isLast = index == parts.lastIndex)
                }
            }
        }
    }
}

/** 预览文本的一段。 */
@Composable
private fun TextSlice(text: String, isFirst: Boolean, isLast: Boolean) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = if (isFirst) 14.dp else 0.dp, bottom = if (isLast) 14.dp else 0.dp),
    ) {
        SelectionContainer {
            Text(
                text = text,
                style =
                    MiuixTheme.textStyles.footnote1.copy(
                        fontFamily = MonoFont,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    ),
                color = MiuixTheme.colorScheme.onBackground,
            )
        }
    }
}

/** 当前路径，一段一段可以点回去。 */
@Composable
private fun Breadcrumb(path: String, onNavigate: (String) -> Unit) {
    val scroll = rememberScrollState()
    // 路径很长时让最后一段露出来
    LaunchedEffect(path, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val segments = path.split('/').filter { it.isNotEmpty() }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(scroll)
                .padding(horizontal = 24.dp)
                .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb("/", current = segments.isEmpty()) { onNavigate("/") }
        segments.forEachIndexed { index, segment ->
            if (index > 0)
                Text(
                    "/",
                    style = MiuixTheme.textStyles.body2.copy(fontFamily = MonoFont),
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            val target = "/" + segments.take(index + 1).joinToString("/")
            Crumb(segment, current = index == segments.lastIndex) { onNavigate(target) }
        }
    }
}

@Composable
private fun Crumb(label: String, current: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MiuixTheme.textStyles.body2.copy(fontFamily = MonoFont),
        color =
            if (current) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.primary,
        maxLines = 1,
        modifier =
            Modifier.clip(CircleShape)
                .clickable(enabled = !current, onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/** 行首图标的大小。 */
private val RowIconSize = 24.dp

/** 行首的图标。 */
@Composable
private fun RowIcon(icon: ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(RowIconSize),
        tint = tint,
    )
}

/** 一项文件或目录：点一下打开，长按弹出菜单。 */
@Composable
private fun EntryRow(
    entry: LinuxEntry,
    summary: String,
    onOpen: () -> Unit,
    onCopyPath: () -> Unit,
    onExport: (() -> Unit)?,
) {
    val colors = MiuixTheme.colorScheme
    val icon =
        when {
            entry.directory -> MiuixIcons.Folder
            entry.broken -> ZetaIcons.LinkOff
            entry.link != null && !entry.regular -> MiuixIcons.Link
            LinuxFiles.isImage(entry.name) -> MiuixIcons.Image
            else -> MiuixIcons.File
        }
    val actions =
        listOfNotNull(
            MenuItem(Strings.setupLinuxFilesCopyPath(), MiuixIcons.Copy, onCopyPath),
            onExport?.let {
                MenuItem(
                    Strings.setupLinuxFilesExport(),
                    MiuixIcons.Download,
                    it,
                    enabled = !LinuxTransfers.running,
                )
            },
        )
    val startAction: @Composable () -> Unit = {
        RowIcon(icon, if (entry.directory) colors.primary else colors.onBackgroundVariant)
    }
    LongPressMenu(entries = listOf(MenuEntry(items = actions))) {
        // 目录点进去是下一层，带箭头；文件点开是预览
        if (entry.directory) {
            ArrowPreference(
                title = entry.name,
                summary = summary.ifEmpty { null },
                startAction = startAction,
                onClick = onOpen,
            )
        } else {
            BasicComponent(
                title = entry.name,
                summary = summary.ifEmpty { null },
                startAction = startAction,
                onClick = onOpen,
            )
        }
    }
}

/** 导入导出的进度；结束后显示结果，点一下收起。 */
@Composable
private fun TransferGroup(state: TransferState) {
    val context = LocalContext.current
    PreferenceGroup(
        title =
            if (state.export) Strings.setupLinuxFilesExport() else Strings.setupLinuxFilesImport(),
    ) {
        if (state.running) {
            item { TransferProgressRow(state, context) }
            item { BasicComponent(title = Strings.setupCancel(), onClick = LinuxTransfers::cancel) }
        } else {
            item {
                BasicComponent(
                    title = state.message.orEmpty(),
                    titleColor =
                        if (state.ok) BasicComponentDefaults.titleColor()
                        else
                            BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                    summary = Strings.setupLinuxFilesTapToDismiss(),
                    onClick = LinuxTransfers::dismiss,
                )
            }
        }
    }
}

/** 进行中的那一行：哪个文件（多个时第几个）、传了多少、进度条。 */
@Composable
private fun TransferProgressRow(state: TransferState, context: Context) {
    val fraction =
        state.total?.takeIf { it > 0 }?.let { (state.done.toFloat() / it).coerceIn(0f, 1f) }
    BasicComponent(
        title = transferTitle(state),
        summary =
            Formatter.formatShortFileSize(context, state.done) +
                (state.total?.let { " / " + Formatter.formatShortFileSize(context, it) } ?: ""),
        endActions =
            fraction?.let { f -> { PreferenceValue("${(f * 100).toInt()}%", clickable = false) } },
        bottomAction = {
            LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth())
        },
    )
}

private fun transferTitle(state: TransferState): String {
    val name = state.name.ifEmpty { "…" }
    val shown =
        if (state.count > 1) Strings.setupLinuxFilesNthOf(name, state.index, state.count) else name
    return if (state.export) Strings.setupLinuxFilesExporting(shown)
    else Strings.setupLinuxFilesImporting(shown)
}

/** 卡片之间的一句说明或报错。 */
@Composable
private fun Hint(text: String, error: Boolean = false) {
    if (error) FooterHint(summary = text, summaryColor = MiuixTheme.colorScheme.error)
    else FooterHint(summary = text)
}

@Composable
private fun Loading() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        InfiniteProgressIndicator(size = 24.dp)
    }
}
