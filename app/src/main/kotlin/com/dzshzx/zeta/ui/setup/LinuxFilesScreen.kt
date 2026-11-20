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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.FileImage
import com.dzshzx.zeta.core.LinuxEntry
import com.dzshzx.zeta.core.LinuxFiles
import com.dzshzx.zeta.core.LinuxListing
import com.dzshzx.zeta.core.LinuxPreview
import com.dzshzx.zeta.core.LinuxTransfers
import com.dzshzx.zeta.core.TransferState
import com.dzshzx.zeta.ui.chat.ChatImage
import com.dzshzx.zeta.ui.components.MonoFont
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.coui2.CardListSegment
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.TopBarMoreMenu
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.components.coui2.menuItem
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Copy
import io.github.suqi8.coui.kmp.icon.extended.Download
import io.github.suqi8.coui.kmp.icon.extended.File
import io.github.suqi8.coui.kmp.icon.extended.Folder
import io.github.suqi8.coui.kmp.icon.extended.GridView
import io.github.suqi8.coui.kmp.icon.extended.Image
import io.github.suqi8.coui.kmp.icon.extended.Import
import io.github.suqi8.coui.kmp.icon.extended.Link
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.menu.OverlayContextMenu
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.PagerFooterPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Linux 环境页下面的两个子页。 */
internal enum class LinuxFilesPage { Browser, Shared }

/**
 * 文件浏览、共享文件夹：盖在 Linux 环境页上面（进出动画与其它页相同），自己处理返回键。
 * 放在 Linux 环境页里而不是主界面的页面栈里，免得为两个子页去改主界面的导航。
 */
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
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(input.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        return Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type).putExtra(Intent.EXTRA_TITLE, input)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.takeIf { resultCode == Activity.RESULT_OK }?.data
}

/** 把路径放进剪贴板（Android 13 起系统自己会提示已复制）。 */
internal fun copyLinuxPath(context: Context, path: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("path", path))
}

private val SameYear = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val OtherYear = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun timeText(seconds: Long): String {
    if (seconds <= 0) return ""
    val time = Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())
    return time.format(if (time.year == java.time.Year.now().value) SameYear else OtherYear)
}

private fun entrySummary(context: Context, entry: LinuxEntry): String = listOfNotNull(
    entry.link?.let { "→ $it" + if (entry.broken) "（不存在）" else "" },
    if (entry.regular) Formatter.formatShortFileSize(context, entry.size) else null,
    timeText(entry.mtime).ifEmpty { null },
).joinToString(" · ")

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

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val config = hub.settings.env
        if (uris.isNotEmpty() && config != null) LinuxTransfers.import(context, config, uris, path)
    }
    val exportLauncher = rememberLauncherForActivityResult(CreateDocument()) { uri ->
        val source = pendingExport
        pendingExport = null
        val config = hub.settings.env
        if (uri != null && source != null && config != null) LinuxTransfers.export(context, config, source, pendingSize.takeIf { it >= 0 }, uri)
    }
    val export: (String, LinuxEntry) -> Unit = { target, entry ->
        pendingExport = target
        pendingSize = entry.size
        runCatching { exportLauncher.launch(entry.name) }
    }

    BackHandler { if (preview != null) preview = null else onBack() }

    LaunchedEffect(path) {
        listing = null
        listState.scrollToItem(0)
    }
    LaunchedEffect(path, reload, LinuxTransfers.imports) {
        val config = hub.settings.env
        listing = if (config == null) {
            LinuxListing.Failed("Linux 环境还不可用")
        } else {
            withContext(Dispatchers.IO) { LinuxFiles.list(config, hub.helperScript, path) }
        }
    }
    LaunchedEffect(preview) {
        content = null
        val target = preview ?: return@LaunchedEffect
        val config = hub.settings.env ?: return@LaunchedEffect
        if (LinuxFiles.isImage(target.entry.name)) return@LaunchedEffect
        content = withContext(Dispatchers.IO) { LinuxFiles.read(config, hub.helperScript, target.path) }
    }

    val shown = preview
    // 列表和预览共用一个页面骨架（顶栏标题随之变），列表状态分开记，从预览回来还在原来的位置
    CouiPage(
        title = shown?.entry?.name ?: "Linux 文件",
        onBack = { if (preview != null) preview = null else onBack() },
        listState = if (shown != null) previewState else listState,
        actions = {
            if (shown == null) {
                TopBarMoreMenu(
                    listOf(
                        DropdownEntry(
                            items = listOf(
                                menuItem("导入文件到这里", COUIIcons.Import, { runCatching { importLauncher.launch(arrayOf("*/*")) } }, enabled = !LinuxTransfers.running),
                                menuItem("刷新", COUIIcons.Refresh, { reload++ }),
                            ),
                        ),
                        DropdownEntry(
                            items = listOf(
                                menuItem("工作目录", ZetaIcons.FolderStar, { path = start }),
                                menuItem("主目录", ZetaIcons.Home, { path = LinuxFiles.normalize(env?.home ?: "/root") }),
                                menuItem("/workspace", COUIIcons.GridView, { path = "/workspace" }),
                                menuItem("根目录", ZetaIcons.Storage, { path = "/" }),
                            ),
                        ),
                        DropdownEntry(items = listOf(menuItem("复制当前路径", COUIIcons.Copy, { copyLinuxPath(context, path) }))),
                    ),
                )
            } else {
                TopBarMoreMenu(
                    listOf(
                        DropdownEntry(
                            items = listOfNotNull(
                                menuItem("导出到手机", COUIIcons.Download, { export(shown.path, shown.entry) }, enabled = !LinuxTransfers.running)
                                    .takeIf { shown.entry.regular },
                                menuItem("复制路径", COUIIcons.Copy, { copyLinuxPath(context, shown.path) }),
                            ),
                        ),
                    ),
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
                    if (entry.directory) path = target else if (entry.regular) preview = Previewing(target, entry)
                },
                onExport = export,
            )
        }
    }
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
            CardListSegment(index = 0, count = count, dividerStart = RowDividerStartWithIcon) {
                ArrowPreference(
                    title = "上级目录",
                    summary = parent,
                    startAction = { RowIcon(ZetaIcons.FolderOpen, COUITheme.colorScheme.primary) },
                    onClick = { onNavigate(parent) },
                )
            }
        }
    }
    when (listing) {
        null -> item(key = "loading") { Loading() }
        is LinuxListing.Failed -> item(key = "error") { Hint(listing.message, error = true) }
        is LinuxListing.Entries -> {
            if (entries.isEmpty()) item(key = "empty") { Hint("该目录为空") }
            // 名字里有非 UTF-8 字节的会被替换成同样的字符，可能重名，键里带上序号
            itemsIndexed(entries, key = { index, entry -> "e$index:" + entry.name }) { index, entry ->
                val target = LinuxFiles.child(path, entry.name)
                CardListSegment(index = offset + index, count = count, dividerStart = RowDividerStartWithIcon) {
                    EntryRow(
                        entry = entry,
                        summary = entrySummary(context, entry),
                        onOpen = { onOpen(target, entry) },
                        onCopyPath = { copyLinuxPath(context, target) },
                        onExport = if (entry.regular) ({ onExport(target, entry) }) else null,
                    )
                }
            }
            if (listing.truncated) item(key = "truncated") { Hint("只列出了前 ${entries.size} 项（共 ${listing.total} 项）") }
        }
    }
}

private fun LazyListScope.previewItems(context: Context, shown: Previewing, content: LinuxPreview?) {
    item(key = "info") {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(top = 4.dp, bottom = 10.dp)) {
            Text(
                text = shown.path,
                style = COUITheme.textStyles.bodyS.copy(fontFamily = MonoFont),
                color = COUITheme.colorScheme.labelSecondary,
            )
            Text(
                text = entrySummary(context, shown.entry),
                style = COUITheme.textStyles.bodyS,
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
    }
    if (LinuxFiles.isImage(shown.entry.name)) {
        item(key = "image") {
            if (shown.entry.size > LinuxFiles.MAX_PREVIEW_IMAGE) {
                Hint("图片太大，无法预览，可以导出到手机查看")
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
        is LinuxPreview.Binary -> item(key = "binary") { Hint("二进制文件，无法预览，可以导出到手机查看") }
        is LinuxPreview.Text -> {
            if (content.truncated) item(key = "truncated") { Hint("内容较大，只显示开头 ${LinuxFiles.PREVIEW_BYTES / 1024} KB") }
            val parts = chunks(content.text)
            if (parts.isEmpty()) item(key = "blank") { Hint("空文件") }
            itemsIndexed(parts, key = { index, _ -> "t:$index" }) { index, part ->
                // 几段连成一张卡片，中间不画分隔线
                CardListSegment(index = index, count = parts.size, dividerStart = null) {
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = if (isFirst) 14.dp else 0.dp, bottom = if (isLast) 14.dp else 0.dp),
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = COUITheme.textStyles.bodyS.copy(fontFamily = MonoFont, fontSize = 12.sp, lineHeight = 17.sp),
                color = COUITheme.colorScheme.labelPrimary,
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
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 24.dp).padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb("/", current = segments.isEmpty()) { onNavigate("/") }
        segments.forEachIndexed { index, segment ->
            if (index > 0) Text("/", style = COUITheme.textStyles.bodyM.copy(fontFamily = MonoFont), color = COUITheme.colorScheme.labelSecondary)
            val target = "/" + segments.take(index + 1).joinToString("/")
            Crumb(segment, current = index == segments.lastIndex) { onNavigate(target) }
        }
    }
}

@Composable
private fun Crumb(label: String, current: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = COUITheme.textStyles.bodyM.copy(fontFamily = MonoFont),
        color = if (current) COUITheme.colorScheme.labelPrimary else COUITheme.colorScheme.primary,
        maxLines = 1,
        modifier = Modifier.clip(CircleShape).clickable(enabled = !current, onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/** 行首图标的大小。 */
private val RowIconSize = 24.dp

/** 行首有图标时分隔线的起点，和 CardListGroup 画的一样从标题开始：行的左内边距、图标、图标和标题的间距。 */
private val RowDividerStartWithIcon: Dp = 16.dp + RowIconSize + BasicComponentDefaults.StartActionSpacing

/** 行首的图标。 */
@Composable
private fun RowIcon(icon: ImageVector, tint: Color) {
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(RowIconSize), tint = tint)
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
    val colors = COUITheme.colorScheme
    val icon = when {
        entry.directory -> COUIIcons.Folder
        entry.broken -> ZetaIcons.LinkOff
        entry.link != null && !entry.regular -> COUIIcons.Link
        LinuxFiles.isImage(entry.name) -> COUIIcons.Image
        else -> COUIIcons.File
    }
    val actions = listOfNotNull(
        menuItem("复制路径", COUIIcons.Copy, onCopyPath),
        onExport?.let { menuItem("导出到手机", COUIIcons.Download, it, enabled = !LinuxTransfers.running) },
    )
    val startAction: @Composable () -> Unit = { RowIcon(icon, if (entry.directory) colors.primary else colors.labelSecondary) }
    OverlayContextMenu(entries = listOf(DropdownEntry(items = actions))) {
        // 目录点进去是下一层，带箭头；文件点开是预览
        if (entry.directory) {
            ArrowPreference(title = entry.name, summary = summary.ifEmpty { null }, startAction = startAction, onClick = onOpen)
        } else {
            BasicComponent(title = entry.name, summary = summary.ifEmpty { null }, startAction = startAction, onClick = onOpen)
        }
    }
}

/** 导入导出的进度；结束后显示结果，点一下收起。 */
@Composable
private fun TransferGroup(state: TransferState) {
    val context = LocalContext.current
    PreferenceGroup(title = if (state.export) "导出到手机" else "从手机导入") {
        if (state.running) {
            item {
                val fraction = state.total?.takeIf { it > 0 }?.let { (state.done.toFloat() / it).coerceIn(0f, 1f) }
                BasicComponent(
                    title = (if (state.export) "正在导出 " else "正在导入 ") + state.name.ifEmpty { "…" } +
                        if (state.count > 1) "（${state.index}/${state.count}）" else "",
                    summary = Formatter.formatShortFileSize(context, state.done) +
                        (state.total?.let { " / " + Formatter.formatShortFileSize(context, it) } ?: ""),
                    endActions = fraction?.let { f -> { PreferenceValue("${(f * 100).toInt()}%") } },
                    bottomAction = { LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth()) },
                )
            }
            item { BasicComponent(title = "取消", onClick = LinuxTransfers::cancel) }
        } else {
            item {
                BasicComponent(
                    title = state.message.orEmpty(),
                    titleColor = if (state.ok) BasicComponentDefaults.titleColor() else BasicComponentDefaults.titleColor(color = COUITheme.colorScheme.error),
                    summary = "点按收起",
                    onClick = LinuxTransfers::dismiss,
                )
            }
        }
    }
}

/** 卡片之间的一句说明或报错。 */
@Composable
private fun Hint(text: String, error: Boolean = false) {
    if (error) PagerFooterPreference(summary = text, summaryColor = COUITheme.colorScheme.error) else PagerFooterPreference(summary = text)
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        InfiniteProgressIndicator(size = 24.dp)
    }
}
