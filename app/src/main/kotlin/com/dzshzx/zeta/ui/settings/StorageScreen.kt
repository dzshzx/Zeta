package com.dzshzx.zeta.ui.settings

import android.content.Context
import android.text.format.DateFormat
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
import com.dzshzx.zeta.core.Backends
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
import com.dzshzx.zeta.core.environment
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.i18n.UiLanguage
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.miuix.ConfirmDialog
import com.dzshzx.zeta.ui.components.miuix.IconActionButton
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroupScope
import com.dzshzx.zeta.ui.components.miuix.PreferenceValue
import com.dzshzx.zeta.ui.components.miuix.SettingsIcon
import com.dzshzx.zeta.ui.components.miuix.ZetaIcons
import com.dzshzx.zeta.ui.components.rememberRetained
import com.dzshzx.zeta.ui.setup.ConfigNotice
import com.dzshzx.zeta.ui.setup.ConfigNoticeDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference

/** 设置首页的入口：顺带显示手机剩余空间。 */
@Composable
internal fun StorageEntry(onClick: () -> Unit) {
    val free = remember { Storage.phoneSpace().first }
    ArrowPreference(
        title = Strings.settingsStorageTitle(),
        startAction = { SettingsIcon(ZetaIcons.Storage) },
        endActions = {
            if (free > 0) PreferenceValue(Strings.settingsStorageFree(Storage.size(free)))
        },
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
    fun usageOf(kind: StorageKind): StorageUsage? =
        when (kind) {
            StorageKind.AppCache -> appCache
            StorageKind.ImageMemory ->
                StorageUsage(imageMemory, imageMemory, if (imageMemory > 0) 1 else 0)
            else -> report?.linux?.get(kind.key)
        }
}

/**
 * 存储页：Zeta 自己的、Claude Code 的、Debian 的三组，各项占多少、能清掉多少。每一类清理前都要确认，
 * 写明删什么、能腾出多少、什么不删；会话记录本身不在任何一类里，删会话到侧栏里照常删（点项目里的会话会定位过去）。 统计和 Linux 一侧的删除由 zeta-helper
 * 做（storage、clean），apt 缓存以 root 执行 apt-get clean， Android 一侧的应用缓存和内存里的图片由应用自己清。
 */
@Composable
internal fun StorageScreen(
    hub: ConversationHub,
    onBack: () -> Unit,
    onRevealSession: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ready = hub.environment.setup == SetupState.Ready && hub.settings.env != null
    var view by remember {
        mutableStateOf(
            StorageView(
                ready = ready,
                imageMemory = ImageStore.memoryBytes(),
                phone = Storage.phoneSpace(),
            ),
        )
    }
    var notice by remember { mutableStateOf<ConfigNotice?>(null) }
    var project by remember { mutableStateOf<ProjectUsage?>(null) }
    var reloads by remember { mutableStateOf(0) }

    LaunchedEffect(reloads) {
        view =
            view.copy(
                ready = ready,
                scanning = true,
                phone = Storage.phoneSpace(),
                imageMemory = ImageStore.memoryBytes(),
            )
        val keep = Storage.keepIds(hub)
        view = view.copy(appCache = scanAppCache(context))
        if (ready) {
            val result = withContext(Dispatchers.IO) { Storage.scan(hub, keep) }
            view = view.copy(report = result ?: view.report, scanFailed = result == null)
        }
        view = view.copy(scanning = false)
    }
    // Debian 整个的大小要把 rootfs 走一遍（几秒到几十秒），单独算，不挡别的
    LaunchedEffect(ready) { view = view.copy(rootfs = rootfsSize(hub, ready), rootfsDone = true) }

    fun clean(kind: StorageKind) {
        view = view.copy(cleaning = kind)
        val keep = Storage.keepIds(hub)
        // 有对话在进行时 Claude 可能正用着内置浏览器，网页缓存先不清
        val quiet = hub.environment.canSwitchEnvironment
        scope.launch {
            val outcome = cleanStorage(context, hub, kind, keep, quiet)
            view = view.copy(cleaning = null)
            reportClean(context, kind, outcome)?.let { notice = it }
            // Android 一侧的两类只重算这两行；Linux 一侧的清完整个重扫
            if (kind.inLinux) reloads++ else view = withAndroidRescanned(view, context)
        }
    }

    StoragePage(
        view = view,
        onBack = onBack,
        onRefresh = { reloads++ },
        onAsk = { kind ->
            if (readyToClean(context, kind, view.usageOf(kind))) view = view.copy(confirming = kind)
        },
        onConfirm = { kind ->
            view = view.copy(confirming = null)
            clean(kind)
        },
        onDismiss = { view = view.copy(confirming = null) },
        onOpenProject = { project = it },
        dialogs = { ConfigNoticeDialog(notice) { notice = null } },
    )

    SlideInPage(project, onBack = { project = null }) { item ->
        ProjectSessionsScreen(
            hub = hub,
            project = item,
            onBack = { project = null },
            onRevealSession = onRevealSession,
        )
    }
}

/** Debian 整个 rootfs 占多少；环境没就绪时不算（null）。 */
private suspend fun rootfsSize(hub: ConversationHub, ready: Boolean): Long? {
    val config = hub.settings.env
    if (!ready || config == null) return null
    return withContext(Dispatchers.IO) { Storage.rootfsBytes(config) }
}

/** 清掉一类：Android 一侧的应用缓存（[quiet] 时连网页缓存）和内存里的图片由应用自己清，apt 缓存以 root 清，别的交给 zeta-helper。 */
private suspend fun cleanStorage(
    context: Context,
    hub: ConversationHub,
    kind: StorageKind,
    keep: List<String>,
    quiet: Boolean,
): CleanOutcome =
    when (kind) {
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

/** 清理的结果：清成了提示一声（返回 null），没清成返回要弹的说明。 */
private fun reportClean(context: Context, kind: StorageKind, outcome: CleanOutcome): ConfigNotice? {
    val text = Storage.outcomeText(kind, outcome)
    if (outcome.error != null) return ConfigNotice(Strings.settingsStorageCleanFailed(), text)
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    return null
}

/** 重算 Android 一侧的两行（应用缓存、内存里的图片）和手机剩余空间。 */
private suspend fun withAndroidRescanned(view: StorageView, context: Context): StorageView =
    view.copy(
        appCache = scanAppCache(context),
        imageMemory = ImageStore.memoryBytes(),
        phone = Storage.phoneSpace(),
    )

/** 点了一类：能清的返回 true（去确认）；正用着或没有可清的只提示一声。 */
private fun readyToClean(context: Context, kind: StorageKind, usage: StorageUsage?): Boolean =
    when {
        usage == null -> false
        usage.busy != null -> {
            Toast.makeText(context, Storage.summary(kind, usage), Toast.LENGTH_SHORT).show()
            false
        }
        usage.freeable <= 0 -> {
            Toast.makeText(
                    context,
                    Strings.settingsStorageNothingToClean(kind.title),
                    Toast.LENGTH_SHORT,
                )
                .show()
            false
        }
        else -> true
    }

/** 应用缓存目录（含内置浏览器的网页缓存）占多少、能清掉多少。 */
private suspend fun scanAppCache(context: Context): StorageUsage =
    withContext(Dispatchers.IO) {
        val (total, freeable) = AndroidCache.scan(context.cacheDir)
        val web = AndroidCache.measure(File(context.cacheDir, AndroidCache.WEBVIEW).toPath()).bytes
        StorageUsage(total, freeable + web, if (freeable + web > 0) 1 else 0)
    }

/** 存储页本身（不取数据，截图测试直接给它数据）。[dialogs] 是页面上另外的对话框。 */
@Composable
internal fun StoragePage(
    view: StorageView,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAsk: (StorageKind) -> Unit,
    onConfirm: (StorageKind) -> Unit,
    onDismiss: () -> Unit,
    onOpenProject: (ProjectUsage) -> Unit,
    dialogs: @Composable () -> Unit = {},
) {
    val report = view.report
    MiuixPage(
        title = Strings.settingsStorageTitle(),
        onBack = onBack,
        actions = {
            IconActionButton(
                MiuixIcons.Refresh,
                Strings.settingsStorageRescan(),
                enabled = !view.scanning && view.cleaning == null,
                onClick = onRefresh,
            )
        },
        dialogs = {
            CleanConfirmDialog(view, onConfirm, onDismiss)
            dialogs()
        },
    ) {
        item(key = "space") { SpaceGroup(view) }
        // 附件和后台对话的输出在 Linux 环境的 ~/.cache/<AppVariant.linuxName> 下，只管这一个包自己的目录
        item(key = "zeta") {
            PreferenceGroup(title = "Zeta") { kindItems(StorageGroup.Zeta, view, onAsk) }
        }
        item(key = "projects") { ProjectsGroup(view, onOpenProject) }
        item(key = "claude") {
            PreferenceGroup(
                title = "Claude Code",
                // 自动清理期限是 ~/.claude/settings.json 的 cleanupPeriodDays
                footer = Strings.settingsStorageClaudeFooter(),
            ) {
                kindItems(StorageGroup.Claude, view, onAsk)
                item(key = "cleanup") {
                    val value =
                        report
                            ?.takeIf { view.ready }
                            ?.let { Storage.cleanupText(it.cleanupPeriodDays) }
                    BasicComponent(
                        title = Strings.settingsStorageCleanupPeriod(),
                        endActions = {
                            if (value != null) PreferenceValue(value, clickable = false)
                        },
                    )
                }
            }
        }
        item(key = "debian") {
            PreferenceGroup(title = "Debian") { kindItems(StorageGroup.Debian, view, onAsk) }
        }
    }
}

/** 一组里各类的行。 */
private fun PreferenceGroupScope.kindItems(
    group: StorageGroup,
    view: StorageView,
    onAsk: (StorageKind) -> Unit,
) {
    group.kinds().forEach { kind -> item(key = kind) { KindRow(kind, view, onAsk) } }
}

/** 一类的一行：占多少、能清掉多少，正在清时转圈；Linux 一侧的在环境没就绪时看不了。 */
@Composable
private fun KindRow(kind: StorageKind, view: StorageView, onAsk: (StorageKind) -> Unit) {
    val usage = view.usageOf(kind)
    val unavailable = kind.inLinux && !view.ready
    val value = usage?.takeUnless { it.failed }?.let { Storage.size(it.bytes) }
    val usable = !unavailable && usage != null && view.cleaning == null
    BasicComponent(
        title = kind.title,
        summary =
            if (unavailable) Strings.settingsStorageNeedsLinux() else Storage.summary(kind, usage),
        endActions = {
            if (view.cleaning == kind) InfiniteProgressIndicator(size = 20.dp)
            if (value != null) PreferenceValue(value, clickable = usable)
        },
        enabled = usable,
        onClick = { onAsk(kind) },
    )
}

/** 清理前的确认：删什么、能腾出多少、什么不删；关闭动画期间留着上一类的内容。 */
@Composable
private fun CleanConfirmDialog(
    view: StorageView,
    onConfirm: (StorageKind) -> Unit,
    onDismiss: () -> Unit,
) {
    val shown = rememberRetained(view.confirming)
    ConfirmDialog(
        show = view.confirming != null,
        title = shown?.let { Strings.settingsStorageCleanConfirm(it.title) },
        summary =
            shown?.let { kind -> view.usageOf(kind)?.let { Storage.confirmMessage(kind, it) } },
        confirmText = Strings.settingsStorageClean(),
        destructive = true,
        onConfirm = { view.confirming?.let(onConfirm) },
        onDismissRequest = onDismiss,
    )
}

/** 手机剩余空间（内部存储，Debian 环境也在这里）和整个 rootfs 占的空间。 */
@Composable
private fun SpaceGroup(view: StorageView) {
    PreferenceGroup {
        item(key = "phone") {
            ValueRow(Strings.settingsStoragePhoneFree(), phoneSpaceText(view.phone))
        }
        item(key = "rootfs") { ValueRow(Strings.settingsStorageDebian(), rootfsText(view)) }
    }
}

private fun phoneSpaceText(phone: Pair<Long, Long>): String =
    if (phone.second > 0) Storage.size(phone.first) + " / " + Storage.size(phone.second) else "—"

private fun rootfsText(view: StorageView): String =
    when {
        view.rootfs != null -> Storage.size(view.rootfs)
        !view.ready || view.rootfsDone -> "—"
        else -> Strings.settingsStorageCounting()
    }

/** 会话记录（~/.claude/projects），按项目分。点进项目看各会话的占用，点一个会话会回到侧栏并定位到它。 */
@Composable
private fun ProjectsGroup(view: StorageView, onOpenProject: (ProjectUsage) -> Unit) {
    val report = view.report
    PreferenceGroup(
        title =
            Strings.settingsStorageSessions() +
                (report?.let { " · " + Storage.size(it.projectBytes) } ?: ""),
        footer = Strings.settingsStorageSessionsFooter(),
    ) {
        when {
            !view.ready ->
                item {
                    BasicComponent(title = Strings.settingsStorageNeedsLinux(), enabled = false)
                }
            report == null -> item { ScanStateRow(view.scanFailed) }
            report.projects.isEmpty() ->
                item {
                    BasicComponent(title = Strings.settingsStorageNoSessions(), enabled = false)
                }
            else ->
                report.projects.forEach { entry ->
                    item(key = entry.dir) {
                        ArrowPreference(
                            title = entry.label,
                            summary = Strings.settingsStorageSessionCount(entry.sessions),
                            endActions = { PreferenceValue(Storage.size(entry.bytes)) },
                            onClick = { onOpenProject(entry) },
                        )
                    }
                }
        }
    }
}

/** 还没有统计结果时的一行：正在统计（转圈），或没统计成。 */
@Composable
private fun ScanStateRow(failed: Boolean) {
    BasicComponent(
        title =
            if (failed) Strings.settingsStorageCountFailed()
            else Strings.settingsStorageCountingNow(),
        endActions = if (failed) null else ({ InfiniteProgressIndicator(size = 20.dp) }),
    )
}

private fun StorageGroup.kinds(): List<StorageKind> =
    StorageKind.entries.filter { it.group == this }

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
private fun ProjectSessionsScreen(
    hub: ConversationHub,
    project: ProjectUsage,
    onBack: () -> Unit,
    onRevealSession: (String) -> Unit,
) {
    val context = LocalContext.current
    var sessions by remember(project.dir) { mutableStateOf<List<SessionUsage>?>(null) }
    var rest by remember(project.dir) { mutableStateOf(0L) }
    var failed by remember(project.dir) { mutableStateOf(false) }
    var summaries by
        remember(project.dir) { mutableStateOf(hub.sessionList.sessions.associateBy { it.id }) }
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
            val found =
                withContext(Dispatchers.IO) { hub.sessionList.find(missing, Backends.LOCAL) }
                    .orEmpty()
            summaries = summaries + found.associateBy { it.id }
        }
    }
    ProjectSessionsPage(project, sessions, rest, failed, summaries, onBack) { usage ->
        if (summaries[usage.id] == null) {
            Toast.makeText(context, Strings.settingsStorageNotInSidebar(), Toast.LENGTH_SHORT)
                .show()
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
    val dateFormat = remember { sessionDateFormat() }
    // 大标题放不下整条路径：标题用最后一段，整条路径写在分组标题里
    val path = project.label
    MiuixPage(title = path.trimEnd('/').substringAfterLast('/').ifBlank { path }, onBack = onBack) {
        item(key = "sessions") {
            PreferenceGroup(
                title =
                    "$path · ${Strings.settingsStorageSessionCount(project.sessions)} · ${Storage.size(project.bytes)}",
                // 同名目录里是工具结果、子代理记录等。点一个会话回到侧栏并定位到它
                footer = Strings.settingsStorageProjectFooter(),
            ) {
                when {
                    failed ->
                        item {
                            BasicComponent(
                                title = Strings.settingsStorageCountFailed(),
                                enabled = false,
                            )
                        }
                    sessions == null ->
                        item {
                            BasicComponent(
                                title = Strings.settingsStorageCountingNow(),
                                endActions = { InfiniteProgressIndicator(size = 20.dp) },
                            )
                        }
                    sessions.isEmpty() ->
                        item {
                            BasicComponent(
                                title = Strings.settingsStorageProjectEmpty(),
                                enabled = false,
                            )
                        }
                    else ->
                        sessions.forEach { usage ->
                            val summary = summaries[usage.id]
                            item(key = usage.id) {
                                SessionUsageRow(usage, summary, dateFormat) { onOpen(usage) }
                            }
                        }
                }
            }
        }
        if (rest > 0) {
            item(key = "rest") {
                PreferenceGroup(footer = Strings.settingsStorageRestFooter()) {
                    item { ValueRow(Strings.settingsStorageOther(), Storage.size(rest)) }
                }
            }
        }
    }
}

/** 一个会话占的空间；侧栏里有的会话点了定位过去（带箭头），没有的点了只提示一句。 */
@Composable
private fun SessionUsageRow(
    usage: SessionUsage,
    summary: SessionSummary?,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit,
) {
    val title =
        summary?.let { it.title.ifBlank { it.shownPreview.take(40) } }
            ?: Strings.settingsStorageNoContent()
    val detail = sessionUsageDetail(usage, summary, dateFormat)
    if (summary != null) {
        ArrowPreference(
            title = title,
            summary = detail,
            endActions = { PreferenceValue(Storage.size(usage.bytes)) },
            onClick = onClick,
        )
    } else {
        BasicComponent(
            title = title,
            summary = detail,
            endActions = { PreferenceValue(Storage.size(usage.bytes)) },
            onClick = onClick,
        )
    }
}

/** 会话一行的说明：最后更新的时间，进行中、已归档的注明；侧栏里没有的带上 id 的开头。 */
private fun sessionUsageDetail(
    usage: SessionUsage,
    summary: SessionSummary?,
    dateFormat: SimpleDateFormat,
): String =
    listOfNotNull(
            dateFormat.format(Date(usage.updated)),
            if (usage.live || summary?.external == true) Strings.settingsStorageLive() else null,
            if (summary?.archived == true) Strings.settingsStorageArchived() else null,
            if (summary == null) usage.id.take(8) else null,
        )
        .joinToString(" · ")

/** 会话一行里最后更新时间的写法：按界面语言取月、日、时、分的本地写法（简体中文是「M月d日 HH:mm」）。 */
private fun sessionDateFormat(): SimpleDateFormat {
    val locale = Locale.forLanguageTag(UiLanguage.current)
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMdHHmm"), locale)
}
