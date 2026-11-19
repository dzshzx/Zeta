package com.dzshzx.zeta.ui.settings

import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AndroidCache
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.CleanOutcome
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.ImageStore
import com.dzshzx.zeta.core.ProjectUsage
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.core.SessionUsage
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.Storage
import com.dzshzx.zeta.core.StorageGroup
import com.dzshzx.zeta.core.StorageKind
import com.dzshzx.zeta.core.StorageReport
import com.dzshzx.zeta.core.StorageUsage
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.SettingsHue
import com.dzshzx.zeta.ui.components.coui2.SettingsIcon
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.components.coui2.IconActionButton
import com.dzshzx.zeta.ui.pane.lookupSessions
import com.dzshzx.zeta.ui.setup.ConfigNotice
import com.dzshzx.zeta.ui.setup.ConfigNoticeDialog
import com.dzshzx.zeta.ui.setup.ConfigSubPage
import com.dzshzx.zeta.ui.components.rememberRetained
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置首页的入口：顺带显示手机剩余空间。 */
@Composable
internal fun StorageEntry(onClick: () -> Unit) {
    val free = remember { Storage.phoneSpace().first }
    ArrowPreference(
        title = "存储",
        startAction = { SettingsIcon(ZetaIcons.Storage, SettingsHue.Files) },
        endActions = { if (free > 0) PreferenceValue("剩余 " + Storage.size(free)) },
        onClick = onClick,
    )
}

/** 存储页上显示的全部数据（统计结果和进行中的操作）。 */
internal data class StorageView(
    /** Linux 环境就绪；没就绪时 Linux 一侧的项都看不了。 */
    val ready: Boolean,
    val report: StorageReport? = null,
    val scanFailed: Boolean = false,
    val scanning: Boolean = false,
    val appCache: StorageUsage? = null,
    val imageMemory: Long = 0,
    val rootfs: Long? = null,
    val rootfsDone: Boolean = false,
    /** 手机剩余、总量。 */
    val phone: Pair<Long, Long> = 0L to 0L,
    val cleaning: StorageKind? = null,
    val confirming: StorageKind? = null,
) {
    fun usageOf(kind: StorageKind): StorageUsage? = when (kind) {
        StorageKind.AppCache -> appCache
        StorageKind.ImageMemory -> StorageUsage(imageMemory, imageMemory, if (imageMemory > 0) 1 else 0)
        else -> report?.linux?.get(kind.key)
    }
}

/**
 * 存储页：Zeta 自己的、Claude Code 的、Debian 的三组，各项占多少、能清掉多少。每一类清理前都要确认，
 * 写明删什么、能腾出多少、什么不删；会话记录本身不在任何一类里，删会话到侧栏里照常删（点项目里的会话会定位过去）。
 * 统计和 Linux 一侧的删除由 zeta_helper.py 做（storage、clean），apt 缓存以 root 执行 apt-get clean，
 * Android 一侧的应用缓存和内存里的图片由应用自己清。
 */
@Composable
internal fun StorageScreen(hub: ConversationHub, onBack: () -> Unit, onRevealSession: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ready = hub.setup == SetupState.Ready && hub.env != null
    var view by remember { mutableStateOf(StorageView(ready = ready, imageMemory = ImageStore.memoryBytes(), phone = Storage.phoneSpace())) }
    var notice by remember { mutableStateOf<ConfigNotice?>(null) }
    var project by remember { mutableStateOf<ProjectUsage?>(null) }
    var reloads by remember { mutableStateOf(0) }

    LaunchedEffect(reloads) {
        view = view.copy(ready = ready, scanning = true, phone = Storage.phoneSpace(), imageMemory = ImageStore.memoryBytes())
        val keep = Storage.keepIds(hub)
        view = view.copy(appCache = scanAppCache(context))
        if (ready) {
            val result = withContext(Dispatchers.IO) { Storage.scan(hub, keep) }
            view = view.copy(report = result ?: view.report, scanFailed = result == null)
        }
        view = view.copy(scanning = false)
    }
    // Debian 整个的大小要把 rootfs 走一遍（几秒到几十秒），单独算，不挡别的
    LaunchedEffect(ready) {
        val config = hub.env
        val size = if (ready && config != null) withContext(Dispatchers.IO) { Storage.rootfsBytes(config) } else null
        view = view.copy(rootfs = size, rootfsDone = true)
    }

    fun clean(kind: StorageKind) {
        view = view.copy(cleaning = kind)
        val keep = Storage.keepIds(hub)
        // 有对话在进行时 Claude 可能正用着内置浏览器，网页缓存先不清
        val quiet = hub.canSwitchEnvironment
        scope.launch {
            val outcome: CleanOutcome = when (kind) {
                StorageKind.AppCache -> {
                    val freed = withContext(Dispatchers.IO) { AndroidCache.clean(context.cacheDir) }
                    CleanOutcome(freed + if (quiet) clearWebViewCache(context) else 0L, 1)
                }
                StorageKind.ImageMemory -> {
                    val bytes = ImageStore.memoryBytes()
                    ImageStore.clearMemory()
                    CleanOutcome(bytes, 1)
                }
                StorageKind.Apt -> withContext(Dispatchers.IO) { Storage.cleanApt(hub) }
                else -> withContext(Dispatchers.IO) { Storage.clean(hub, kind, keep) }
            }
            view = view.copy(cleaning = null)
            val text = Storage.outcomeText(kind, outcome)
            if (outcome.error != null) notice = ConfigNotice("没有清理成", text) else Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            // Android 一侧的两类只重算这两行；Linux 一侧的清完整个重扫
            if (kind.inLinux) {
                reloads++
            } else {
                view = view.copy(appCache = scanAppCache(context), imageMemory = ImageStore.memoryBytes(), phone = Storage.phoneSpace())
            }
        }
    }

    StoragePage(
        view = view,
        onBack = onBack,
        onRefresh = { reloads++ },
        onAsk = { kind ->
            val usage = view.usageOf(kind)
            when {
                usage == null -> Unit
                usage.busy != null -> Toast.makeText(context, Storage.summary(kind, usage), Toast.LENGTH_SHORT).show()
                usage.freeable <= 0 -> Toast.makeText(context, "没有可清理的${kind.title}", Toast.LENGTH_SHORT).show()
                else -> view = view.copy(confirming = kind)
            }
        },
        onConfirm = { kind ->
            view = view.copy(confirming = null)
            clean(kind)
        },
        onDismiss = { view = view.copy(confirming = null) },
        onOpenProject = { project = it },
        overlays = { ConfigNoticeDialog(notice) { notice = null } },
    )

    val shownProject = rememberRetained(project)
    ConfigSubPage(visible = project != null, onBack = { project = null }) {
        shownProject?.let { item ->
            ProjectSessionsScreen(hub = hub, project = item, onBack = { project = null }, onRevealSession = onRevealSession)
        }
    }
}

/** 应用缓存目录（含内置浏览器的网页缓存）占多少、能清掉多少。 */
private suspend fun scanAppCache(context: Context): StorageUsage = withContext(Dispatchers.IO) {
    val (total, freeable) = AndroidCache.scan(context.cacheDir)
    val web = AndroidCache.measure(File(context.cacheDir, AndroidCache.WEBVIEW).toPath()).bytes
    StorageUsage(total, freeable + web, if (freeable + web > 0) 1 else 0)
}

/** 存储页本身（不取数据，截图测试直接给它数据）。[overlays] 是页面上另外的对话框。 */
@Composable
internal fun StoragePage(
    view: StorageView,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAsk: (StorageKind) -> Unit,
    onConfirm: (StorageKind) -> Unit,
    onDismiss: () -> Unit,
    onOpenProject: (ProjectUsage) -> Unit,
    overlays: @Composable () -> Unit = {},
) {
    @Composable
    fun KindRow(kind: StorageKind) {
        val usage = view.usageOf(kind)
        val unavailable = kind.inLinux && !view.ready
        val value = usage?.takeUnless { it.failed }?.let { Storage.size(it.bytes) }
        BasicComponent(
            title = kind.title,
            summary = if (unavailable) "Linux 环境准备好以后才能看" else Storage.summary(kind, usage),
            endActions = {
                if (view.cleaning == kind) InfiniteProgressIndicator(size = 20.dp)
                if (value != null) PreferenceValue(value)
            },
            enabled = !unavailable && usage != null && view.cleaning == null,
            onClick = { onAsk(kind) },
        )
    }

    val linuxName = AppVariant.current.linuxName
    val report = view.report
    val shown = rememberRetained(view.confirming)
    CouiPage(
        title = "存储",
        onBack = onBack,
        actions = {
            IconActionButton(COUIIcons.Refresh, "重新统计", enabled = !view.scanning && view.cleaning == null, onClick = onRefresh)
        },
        overlays = {
            ConfirmDialog(
                show = view.confirming != null,
                title = shown?.let { "清理${it.title}？" },
                summary = shown?.let { kind -> view.usageOf(kind)?.let { Storage.confirmMessage(kind, it) } },
                confirmText = "清理",
                destructive = true,
                onConfirm = { view.confirming?.let(onConfirm) },
                onDismissRequest = onDismiss,
            )
            overlays()
        },
    ) {
        item(key = "zeta") {
            PreferenceGroup(title = "Zeta", footer = "附件和后台对话的输出在 Linux 环境的 ~/.cache/$linuxName 下，只管这一个包自己的目录。") {
                StorageGroup.Zeta.kinds().forEach { kind -> item(key = kind) { KindRow(kind) } }
            }
        }
        item(key = "projects") {
            PreferenceGroup(
                title = "会话记录" + (report?.let { " · " + Storage.size(it.projectBytes) } ?: ""),
                footer = "~/.claude/projects，按项目分。点进项目看各会话的占用，点一个会话会回到侧栏并定位到它；删会话在侧栏里照常删（长按或多选）。",
            ) {
                when {
                    !view.ready -> item { BasicComponent(title = "Linux 环境准备好以后才能看", enabled = false) }
                    report == null -> item {
                        BasicComponent(
                            title = if (view.scanFailed) "没统计成" else "正在统计…",
                            endActions = if (view.scanFailed) null else ({ InfiniteProgressIndicator(size = 20.dp) }),
                        )
                    }
                    report.projects.isEmpty() -> item { BasicComponent(title = "还没有会话记录", enabled = false) }
                    else -> report.projects.forEach { entry ->
                        item(key = entry.dir) {
                            ArrowPreference(
                                title = entry.label,
                                summary = "${entry.sessions} 个会话",
                                endActions = { PreferenceValue(Storage.size(entry.bytes)) },
                                onClick = { onOpenProject(entry) },
                            )
                        }
                    }
                }
            }
        }
        item(key = "claude") {
            PreferenceGroup(
                title = "Claude Code",
                footer = "Claude Code 自己也会在启动时删掉超过“自动清理期限”没动过的会话记录和上面这些文件（~/.claude/settings.json 的 cleanupPeriodDays）。",
            ) {
                StorageGroup.Claude.kinds().forEach { kind -> item(key = kind) { KindRow(kind) } }
                item(key = "cleanup") {
                    val value = if (view.ready && report != null) Storage.cleanupText(report.cleanupPeriodDays) else null
                    BasicComponent(title = "自动清理期限", summary = "cleanupPeriodDays", endActions = { if (value != null) PreferenceValue(value) })
                }
            }
        }
        item(key = "debian") {
            PreferenceGroup(title = "Debian") {
                StorageGroup.Debian.kinds().forEach { kind -> item(key = kind) { KindRow(kind) } }
                item(key = "rootfs") {
                    BasicComponent(
                        title = "Debian 环境",
                        summary = "整个 rootfs 占的空间",
                        endActions = {
                            PreferenceValue(
                                when {
                                    view.rootfs != null -> Storage.size(view.rootfs)
                                    !view.ready || view.rootfsDone -> "—"
                                    else -> "统计中"
                                },
                            )
                        },
                    )
                }
                item(key = "phone") {
                    BasicComponent(
                        title = "手机剩余空间",
                        summary = "内部存储（Debian 环境也在这里）",
                        endActions = {
                            PreferenceValue(if (view.phone.second > 0) Storage.size(view.phone.first) + " / " + Storage.size(view.phone.second) else "—")
                        },
                    )
                }
            }
        }
    }
}

private fun StorageGroup.kinds(): List<StorageKind> = StorageKind.entries.filter { it.group == this }

/** 借一个 WebView 清掉网页缓存（全应用共用一份），返回腾出的字节数。在主线程上调用。 */
private suspend fun clearWebViewCache(context: Context): Long {
    val directory = File(context.cacheDir, AndroidCache.WEBVIEW).toPath()
    val before = withContext(Dispatchers.IO) { AndroidCache.measure(directory).bytes }
    if (before == 0L) return 0L
    runCatching { WebView(context.applicationContext).apply { clearCache(true) }.destroy() }
    // 缓存由 WebView 在自己的线程上删，稍等一下再量
    delay(800)
    val after = withContext(Dispatchers.IO) { AndroidCache.measure(directory).bytes }
    return (before - after).coerceAtLeast(0L)
}

/** 一个项目里的会话：取数据，交给 [ProjectSessionsPage] 显示。 */
@Composable
private fun ProjectSessionsScreen(hub: ConversationHub, project: ProjectUsage, onBack: () -> Unit, onRevealSession: (String) -> Unit) {
    val context = LocalContext.current
    var sessions by remember(project.dir) { mutableStateOf<List<SessionUsage>?>(null) }
    var rest by remember(project.dir) { mutableStateOf(0L) }
    var failed by remember(project.dir) { mutableStateOf(false) }
    var summaries by remember(project.dir) { mutableStateOf(hub.sessions.associateBy { it.id }) }
    LaunchedEffect(project.dir) {
        val keep = Storage.keepIds(hub)
        val result = withContext(Dispatchers.IO) { Storage.sessions(hub, project.dir, keep) }
        if (result == null) {
            failed = true
            return@LaunchedEffect
        }
        sessions = result.first
        rest = result.second
        // 不在侧栏最近 300 个里的，按 id 取一次标题（最多取前 100 个）
        val missing = result.first.map { it.id }.filter { it !in summaries }.take(100)
        if (missing.isNotEmpty()) {
            val found = withContext(Dispatchers.IO) { lookupSessions(hub, missing) }.orEmpty()
            summaries = summaries + found.associateBy { it.id }
        }
    }
    ProjectSessionsPage(project, sessions, rest, failed, summaries, onBack) { usage ->
        if (summaries[usage.id] == null) {
            Toast.makeText(context, "侧栏里没有这个会话（它没有可显示的内容）", Toast.LENGTH_SHORT).show()
        } else {
            onRevealSession(usage.id)
        }
    }
}

/** 一个项目里的会话，按占用从大到小；[sessions] 为 null 是还在统计。 */
@Composable
internal fun ProjectSessionsPage(
    project: ProjectUsage,
    sessions: List<SessionUsage>?,
    rest: Long,
    failed: Boolean,
    summaries: Map<String, SessionSummary>,
    onBack: () -> Unit,
    onOpen: (SessionUsage) -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA) }
    // 大标题放不下整条路径：标题用最后一段，整条路径写在分组标题里
    val path = project.label
    CouiPage(title = path.trimEnd('/').substringAfterLast('/').ifBlank { path }, onBack = onBack) {
        item(key = "sessions") {
            PreferenceGroup(
                title = "$path · ${project.sessions} 个会话 · ${Storage.size(project.bytes)}",
                footer = "大小包括会话记录和它的同名目录（工具结果、子代理记录等）。点一个会话回到侧栏并定位到它，要删就在侧栏里长按它，或用多选批量删除；进行中的会话删不了。",
            ) {
                when {
                    failed -> item { BasicComponent(title = "没统计成", enabled = false) }
                    sessions == null -> item { BasicComponent(title = "正在统计…", endActions = { InfiniteProgressIndicator(size = 20.dp) }) }
                    sessions.isEmpty() -> item { BasicComponent(title = "这个项目里没有会话记录", enabled = false) }
                    else -> sessions.forEach { usage ->
                        val summary = summaries[usage.id]
                        val title = summary?.let { it.title.ifBlank { it.preview.take(40) } } ?: "（没有对话内容）"
                        val detail = listOfNotNull(
                            dateFormat.format(Date(usage.updated)),
                            if (usage.live || summary?.external == true) "进行中" else null,
                            if (summary?.archived == true) "已归档" else null,
                            if (summary == null) usage.id.take(8) else null,
                        ).joinToString(" · ")
                        item(key = usage.id) {
                            // 侧栏里有的会话点了定位过去（带箭头）；没有的点了只提示一句
                            if (summary != null) {
                                ArrowPreference(title = title, summary = detail, endActions = { PreferenceValue(Storage.size(usage.bytes)) }, onClick = { onOpen(usage) })
                            } else {
                                BasicComponent(title = title, summary = detail, endActions = { PreferenceValue(Storage.size(usage.bytes)) }, onClick = { onOpen(usage) })
                            }
                        }
                    }
                }
            }
        }
        if (rest > 0) {
            item(key = "rest") {
                PreferenceGroup(footer = "项目目录里不属于某个会话的东西，比如自动记忆、已删会话留下的同名目录。") {
                    item { BasicComponent(title = "其他", endActions = { PreferenceValue(Storage.size(rest)) }) }
                }
            }
        }
    }
}
