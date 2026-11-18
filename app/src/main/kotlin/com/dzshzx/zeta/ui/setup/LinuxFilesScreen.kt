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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Workspaces
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.dzshzx.zeta.ui.components.EtaMonospace
import com.dzshzx.zeta.ui.components.coui.CouiGroupItem
import com.dzshzx.zeta.ui.components.coui.CouiLazyScaffoldPage
import com.dzshzx.zeta.ui.components.coui.CouiPreference
import com.dzshzx.zeta.ui.components.coui.CouiSettingsIcon
import com.dzshzx.zeta.ui.components.coui.CouiTopAppBarIconButton
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageColors
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Group
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.theme.CouiTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Linux 环境页下面的两个子页。 */
internal enum class LinuxFilesPage { Browser, Shared }

/**
 * 文件浏览、共享文件夹：盖在 Linux 环境页上面（进出动画与其它页相同），自己处理返回键。
 * 放在 Linux 环境页里而不是主界面的页面栈里，免得为两个子页去改主界面的导航。
 */
@Composable
internal fun LinuxFilesPages(hub: ConversationHub, page: LinuxFilesPage?, onClose: () -> Unit) {
    var last by remember { mutableStateOf(page ?: LinuxFilesPage.Browser) }
    page?.let { last = it }
    AnimatedVisibility(
        visible = page != null,
        enter = slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200)),
        exit = slideOutHorizontally(tween(220)) { it / 3 } + fadeOut(tween(160)),
    ) {
        // 底下的 Linux 环境页还在；空的 pointerInput 让触摸不漏到它身上
        Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) {}) {
            when (last) {
                LinuxFilesPage.Browser -> LinuxFilesScreen(hub, onBack = onClose)
                LinuxFilesPage.Shared -> SharedFoldersScreen(hub, onBack = onClose)
            }
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LinuxFilesScreen(hub: ConversationHub, onBack: () -> Unit) {
    val context = LocalContext.current
    val env = hub.env
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
    CouiLazyScaffoldPage(
        title = shown?.entry?.name ?: "Linux 文件",
        onBack = { if (preview != null) preview = null else onBack() },
        listState = if (shown != null) previewState else listState,
        actions = {
            if (shown == null) {
                TopMenu(
                    items = listOf(
                        CouiMenuItem(value = 0, text = "导入文件到这里", icon = Icons.Rounded.UploadFile, enabled = !LinuxTransfers.running),
                        CouiMenuItem(value = 1, text = "刷新", icon = Icons.Rounded.Refresh),
                        CouiMenuItem(value = 2, text = "工作目录", icon = Icons.Rounded.FolderSpecial, dividerBefore = true),
                        CouiMenuItem(value = 3, text = "主目录", icon = Icons.Rounded.Home),
                        CouiMenuItem(value = 4, text = "/workspace", icon = Icons.Rounded.Workspaces),
                        CouiMenuItem(value = 5, text = "根目录", icon = Icons.Rounded.Storage),
                        CouiMenuItem(value = 6, text = "复制当前路径", icon = Icons.Rounded.ContentCopy, dividerBefore = true),
                    ),
                ) {
                    when (it) {
                        0 -> runCatching { importLauncher.launch(arrayOf("*/*")) }
                        1 -> reload++
                        2 -> path = start
                        3 -> path = LinuxFiles.normalize(env?.home ?: "/root")
                        4 -> path = "/workspace"
                        5 -> path = "/"
                        else -> copyLinuxPath(context, path)
                    }
                }
            } else {
                TopMenu(
                    items = listOfNotNull(
                        CouiMenuItem(value = 0, text = "导出到手机", icon = Icons.Rounded.SaveAlt, enabled = !LinuxTransfers.running)
                            .takeIf { shown.entry.regular },
                        CouiMenuItem(value = 1, text = "复制路径", icon = Icons.Rounded.ContentCopy),
                    ),
                ) {
                    if (it == 0) export(shown.path, shown.entry) else copyLinuxPath(context, shown.path)
                }
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
    if (parent != null) {
        item(key = "..") {
            CouiGroupItem(isFirst = true, isLast = entries.isEmpty(), hasLeadingIcon = true) {
                CouiPreference(
                    title = "上级目录",
                    summary = parent,
                    icon = { CouiSettingsIcon(Icons.Rounded.FolderOpen, CouiPageDefaults.themeColors().accent) },
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
                EntryRow(
                    entry = entry,
                    summary = entrySummary(context, entry),
                    isFirst = index == 0 && parent == null,
                    isLast = index == entries.lastIndex,
                    onOpen = { onOpen(target, entry) },
                    onCopyPath = { copyLinuxPath(context, target) },
                    onExport = if (entry.regular) ({ onExport(target, entry) }) else null,
                )
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
                style = MiuixTheme.textStyles.footnote1.copy(fontFamily = EtaMonospace),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = entrySummary(context, shown.entry),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
                TextSlice(part, isFirst = index == 0, isLast = index == parts.lastIndex)
            }
        }
    }
}

/** 预览文本的一段。几段连成一张卡片，中间不画分隔线。 */
@Composable
private fun TextSlice(text: String, isFirst: Boolean, isLast: Boolean) {
    val shape = when {
        isFirst && isLast -> RoundedCornerShape(24.dp)
        isFirst -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        isLast -> RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
        else -> RoundedCornerShape(0.dp)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CouiPageDefaults.horizontalMargin)
            .padding(bottom = if (isLast) CouiPageDefaults.groupSpacing else 0.dp)
            .clip(shape)
            .background(CouiPageDefaults.themeColors().card)
            .padding(horizontal = 16.dp)
            .padding(top = if (isFirst) 14.dp else 0.dp, bottom = if (isLast) 14.dp else 0.dp),
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = MiuixTheme.textStyles.footnote1.copy(fontFamily = EtaMonospace, fontSize = 12.sp, lineHeight = 17.sp),
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 当前路径，一段一段可以点回去。 */
@Composable
private fun Breadcrumb(path: String, onNavigate: (String) -> Unit) {
    val colors = CouiPageDefaults.themeColors()
    val scroll = rememberScrollState()
    // 路径很长时让最后一段露出来
    LaunchedEffect(path, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val segments = path.split('/').filter { it.isNotEmpty() }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 24.dp).padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb("/", current = segments.isEmpty(), color = colors) { onNavigate("/") }
        segments.forEachIndexed { index, segment ->
            if (index > 0) Text("/", style = MiuixTheme.textStyles.body2.copy(fontFamily = EtaMonospace), color = colors.summary)
            val target = "/" + segments.take(index + 1).joinToString("/")
            Crumb(segment, current = index == segments.lastIndex, color = colors) { onNavigate(target) }
        }
    }
}

@Composable
private fun Crumb(label: String, current: Boolean, color: CouiPageColors, onClick: () -> Unit) {
    Text(
        text = label,
        style = MiuixTheme.textStyles.body2.copy(fontFamily = EtaMonospace),
        color = if (current) color.title else color.accent,
        maxLines = 1,
        modifier = Modifier.clip(CouiCapsuleShape).clickable(enabled = !current, onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/** 一项文件或目录：点一下打开，长按弹出菜单。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: LinuxEntry,
    summary: String,
    isFirst: Boolean,
    isLast: Boolean,
    onOpen: () -> Unit,
    onCopyPath: () -> Unit,
    onExport: (() -> Unit)?,
) {
    val dark = CouiTheme.isDark
    val colors = CouiPageDefaults.themeColors(dark)
    val anchor = rememberCouiMenuAnchor()
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    var menu by remember { mutableStateOf(false) }
    val icon = when {
        entry.directory -> Icons.Rounded.Folder
        entry.broken -> Icons.Rounded.LinkOff
        entry.link != null && !entry.regular -> Icons.Rounded.Link
        LinuxFiles.isImage(entry.name) -> Icons.Rounded.Image
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }
    val items = listOfNotNull(
        CouiMenuItem(value = 0, text = "复制路径", icon = Icons.Rounded.ContentCopy),
        onExport?.let { CouiMenuItem(value = 1, text = "导出到手机", icon = Icons.Rounded.SaveAlt, enabled = !LinuxTransfers.running) },
    )
    CouiGroupItem(isFirst = isFirst, isLast = isLast, hasLeadingIcon = true) {
        Box {
            CouiPreference(
                modifier = Modifier
                    .couiMenuAnchor(anchor)
                    .couiButtonFeedback(dark, source, true, list = true, hoverLocked = menu)
                    .combinedClickable(
                        interactionSource = source,
                        indication = null,
                        onClick = onOpen,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            anchor.open { menu = true }
                        },
                    ),
                title = entry.name,
                summary = summary.ifEmpty { null },
                icon = { CouiSettingsIcon(icon, if (entry.directory) colors.accent else colors.summary) },
                showChevron = entry.directory,
            )
            Menu(
                expanded = menu,
                onDismissRequest = { menu = false },
                items = items,
                onItemClick = {
                    menu = false
                    if (it.value == 0) onCopyPath() else onExport?.invoke()
                },
                anchor = anchor,
            )
        }
    }
}

/** 顶栏右边的“更多”菜单。 */
@Composable
private fun TopMenu(items: List<CouiMenuItem<Int>>, onSelect: (Int) -> Unit) {
    val dark = CouiTheme.isDark
    val anchor = rememberCouiMenuAnchor()
    var expanded by remember { mutableStateOf(false) }
    Box {
        CouiTopAppBarIconButton(
            icon = CouiIcons.Outlined.More,
            contentDescription = "更多",
            modifier = Modifier.couiMenuAnchor(anchor),
            dark = dark,
            colors = CouiPageDefaults.themeColors(dark),
            onClick = { anchor.open { expanded = true } },
        )
        Menu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            items = items,
            onItemClick = {
                expanded = false
                onSelect(it.value)
            },
            anchor = anchor,
        )
    }
}

/** 导入导出的进度；结束后显示结果，点一下收起。 */
@Composable
private fun TransferGroup(state: TransferState) {
    val context = LocalContext.current
    Group(title = if (state.export) "导出到手机" else "从手机导入") {
        if (state.running) {
            val fraction = state.total?.takeIf { it > 0 }?.let { (state.done.toFloat() / it).coerceIn(0f, 1f) }
            CouiPreference(
                title = (if (state.export) "正在导出 " else "正在导入 ") + state.name.ifEmpty { "…" } +
                    if (state.count > 1) "（${state.index}/${state.count}）" else "",
                summary = Formatter.formatShortFileSize(context, state.done) +
                    (state.total?.let { " / " + Formatter.formatShortFileSize(context, it) } ?: ""),
                value = fraction?.let { "${(it * 100).toInt()}%" },
            )
            LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp))
            CouiPreference(title = "取消", showChevron = false, onClick = LinuxTransfers::cancel)
        } else {
            CouiPreference(
                title = state.message.orEmpty(),
                titleColor = if (state.ok) Color.Unspecified else MiuixTheme.colorScheme.error,
                summary = "点按收起",
                showChevron = false,
                onClick = LinuxTransfers::dismiss,
            )
        }
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        InfiniteProgressIndicator(size = 24.dp)
    }
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2.copy(fontSize = 13.5.sp, lineHeight = 19.sp),
        color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
    )
}
