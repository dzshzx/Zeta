package com.dzshzx.zeta.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.diag.DiagEntries
import com.dzshzx.zeta.core.diag.DiagEntry
import com.dzshzx.zeta.core.diag.DiagLevel
import com.dzshzx.zeta.core.diag.DiagLog
import com.dzshzx.zeta.core.diag.DiagScripts
import com.dzshzx.zeta.core.diag.DiagTopic
import com.dzshzx.zeta.core.diag.LinuxProbe
import com.dzshzx.zeta.core.diag.LogFile
import com.dzshzx.zeta.core.diag.LogKind
import com.dzshzx.zeta.core.diag.Redact
import com.dzshzx.zeta.core.environment
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.MonoFont
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.coui2.CardListSegment
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.IconActionButton
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.setup.ConfigEmptyRow
import com.dzshzx.zeta.ui.setup.ConfigLoadingRow
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.Refresh
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference
import io.github.suqi8.coui.kmp.preference.PagerFooterPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 诊断页里再往下的一层。 */
private sealed interface DiagPage {
    data object Log : DiagPage

    data class File(val file: LogFile) : DiagPage
}

/** 设置里的“诊断”：Zeta 自己的运行日志（能筛）、环境信息、权限状态、Claude Code 那边的错误输出和调试日志， 以及把这些打码后打成一个包导出。 */
@Composable
internal fun DiagnosticsScreen(
    hub: ConversationHub,
    onBack: () -> Unit,
    // 截图测试换成假数据；平时进 Linux 环境查
    probeLoader: suspend () -> LinuxProbe? = {
        withContext(Dispatchers.IO) { Diagnostics.probe(hub) }
    },
    tailLoader: suspend (LogFile) -> String? = { file ->
        withContext(Dispatchers.IO) { Diagnostics.tail(hub, file) }
    },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reloads by remember { mutableIntStateOf(0) }
    var probe by remember { mutableStateOf<LinuxProbe?>(null) }
    var probing by remember { mutableStateOf(true) }
    LaunchedEffect(reloads, hub.environment.setup) {
        probing = true
        probe = probeLoader()
        probing = false
    }
    val revision by DiagLog.revision.collectAsState()
    val entries = remember(revision) { DiagLog.entries() }
    val snapshot = rememberPermissionSnapshot()
    var page by remember { mutableStateOf<DiagPage?>(null) }
    var exporting by remember { mutableStateOf(false) }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            exporting = true
            val known = probe
            scope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            Diagnostics.export(context.applicationContext, hub, uri, known)
                        }
                    }
                exporting = false
                reportExport(context, result)
            }
        }

    val current = probe
    CouiPage(
        title = Strings.diagTitle(),
        onBack = onBack,
        actions = {
            IconActionButton(COUIIcons.Refresh, Strings.diagReload(), enabled = !probing) {
                reloads++
            }
        },
    ) {
        // 运行日志是 Zeta 自己记下的关键事件：进 Linux 环境、起停 Claude 进程和退出码、helper 出错、手机工具失败、
        // 无障碍服务被关或加回、定时任务触发。存在应用数据里，Zeta 被关掉后还在
        item(key = "log") { RunLogGroup(entries) { page = DiagPage.Log } }
        item(key = "environment") { EnvironmentGroup(hub, probe, probing) }
        item(key = "permissions") { PermissionsGroup(snapshot, hub) }
        item(key = "assistant") { AssistantDiagnosticsGroup(reloads) }
        // Claude Code 进程写到标准错误的内容；结束的对话列的是结束时留下的那份。点开只看最后 64 KB（页上写着）
        item(key = "stderr") {
            LogFilesGroup(
                title = Strings.diagStderrTitle(),
                footer = Strings.diagStderrFooter(DiagScripts.FINISHED_LIMIT),
                files = current?.files?.filter { it.kind != LogKind.Debug },
                probing = probing,
                unavailable = Strings.diagStderrUnavailable(),
                empty = Strings.diagStderrEmpty() to Strings.diagStderrEmptyHint(),
            ) {
                page = DiagPage.File(it)
            }
        }
        // ~/.claude/debug 下的文件
        item(key = "debug") {
            LogFilesGroup(
                title = Strings.diagDebugTitle(),
                footer = Strings.diagDebugFooter(DiagScripts.DEBUG_LIMIT),
                files = current?.files(LogKind.Debug),
                probing = probing,
                unavailable = null,
                empty = Strings.diagDebugEmpty() to null,
            ) {
                page = DiagPage.File(it)
            }
        }
        // 用系统的“保存到”存下；两种日志文件各取最后 64 KB。打码的是 token、key、password、cookie、Authorization 这类值（见 Redact）
        item(key = "export") {
            PreferenceGroup(footer = Strings.diagExportFooter()) {
                item {
                    BasicComponent(
                        title = Strings.diagExport(),
                        summary = if (exporting) Strings.diagExporting() else null,
                        enabled = !exporting,
                        onClick = {
                            val stamp =
                                LocalDateTime.now()
                                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                            runCatching { exportLauncher.launch("zeta-diag-$stamp.zip") }
                        },
                    )
                }
            }
        }
    }

    SlideInPage(page, onBack = { page = null }) { shown ->
        when (shown) {
            DiagPage.Log -> DiagLogPage(entries = entries, onBack = { page = null })
            is DiagPage.File -> LogTailPage(shown.file, tailLoader, onBack = { page = null })
        }
    }
}

/** 诊断包导出完的提示：几个文件，或失败的原因。 */
private fun reportExport(context: Context, result: Result<Int>) {
    val message =
        result.fold(
            { Strings.diagExported(it) },
            { Strings.diagExportFailed(it.message ?: it.javaClass.simpleName) },
        )
    Toast.makeText(
            context,
            message,
            if (result.isSuccess) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
        )
        .show()
}

/**
 * 运行日志是 Zeta 自己记下的关键事件：进 Linux 环境、起停 Claude 进程和退出码、helper 出错、手机工具失败、 无障碍服务被关或加回、定时任务触发。存在应用数据里，Zeta
 * 被关掉后还在。
 */
@Composable
private fun RunLogGroup(entries: List<DiagEntry>, onOpen: () -> Unit) {
    val errors = entries.count { it.level == DiagLevel.Error }
    val warnings = entries.count { it.level == DiagLevel.Warn }
    PreferenceGroup(
        title = Strings.diagRunLog(),
        footer = Strings.diagRunLogFooter(DiagLog.CAPACITY),
    ) {
        item {
            ArrowPreference(
                title = Strings.diagViewRunLog(),
                summary = runLogSummary(entries.size, warnings),
                endActions = {
                    if (errors > 0)
                        PreferenceValue(
                            Strings.diagErrorCount(errors),
                            color = COUITheme.colorScheme.error,
                        )
                },
                onClick = onOpen,
            )
        }
    }
}

private fun runLogSummary(count: Int, warnings: Int): String =
    when {
        count == 0 -> Strings.diagNoRecords()
        warnings > 0 -> Strings.diagRunLogCountWarnings(count, warnings)
        else -> Strings.diagRunLogCount(count)
    }

/** 环境信息：短的值放右边，长的（路径、系统版本）放标题下面。 */
@Composable
private fun EnvironmentGroup(hub: ConversationHub, probe: LinuxProbe?, probing: Boolean) {
    val context = LocalContext.current
    PreferenceGroup(title = Strings.diagEnvironment()) {
        Diagnostics.environment(context, hub, probe, probing).forEach { entry ->
            item {
                if (entry.value.length <= 14) {
                    ValueRow(entry.label, entry.value)
                } else {
                    BasicComponent(title = entry.label, summary = entry.value)
                }
            }
        }
    }
}

/** 权限状态，和权限健康页是同一份检查。 */
@Composable
private fun PermissionsGroup(snapshot: PermissionSnapshot?, hub: ConversationHub) {
    PreferenceGroup(title = Strings.diagPermissions(), footer = Strings.diagPermissionsFooter()) {
        PermissionHealth.items(snapshot, hub).forEach { entry ->
            item(key = entry.id) {
                ValueRow(entry.title, entry.value, color = statusColor(entry.status))
            }
        }
    }
}

/**
 * Claude Code 那边的一类日志文件，点一个看它的末尾。[files] 为 null 是进不了 Linux 环境（还在查时转圈，查完说明 [unavailable]）， 空的时候是
 * [empty]（标题和说明）。
 */
@Composable
private fun LogFilesGroup(
    title: String,
    footer: String,
    files: List<LogFile>?,
    probing: Boolean,
    unavailable: String?,
    empty: Pair<String, String?>,
    onOpen: (LogFile) -> Unit,
) {
    PreferenceGroup(title = title, footer = footer) {
        when {
            files == null && probing -> item { ConfigLoadingRow() }
            files == null -> item { ConfigEmptyRow(Strings.diagLinuxUnavailable(), unavailable) }
            files.isEmpty() -> item { ConfigEmptyRow(empty.first, empty.second) }
            else ->
                files.forEach { file ->
                    item(key = file.path) { LogFileRow(file) { onOpen(file) } }
                }
        }
    }
}

@Composable
private fun LogFileRow(file: LogFile, onClick: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val title =
        when (file.kind) {
            LogKind.Running -> file.title.ifBlank { Strings.diagRunningConversation() }
            else -> file.name
        }
    ArrowPreference(
        title = title,
        summary =
            listOf(
                    file.kind.label,
                    DiagScripts.size(file.size),
                    DiagEntries.shortTime(file.modified, zone),
                )
                .joinToString(" · "),
        onClick = onClick,
    )
}

/** 运行日志：新的在上面，可以按类别和轻重筛。 */
@Composable
private fun DiagLogPage(entries: List<DiagEntry>, onBack: () -> Unit) {
    var topicIndex by rememberSaveable { mutableIntStateOf(0) }
    var levelIndex by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val topics = listOf<DiagTopic?>(null) + DiagTopic.entries
    val levels = listOf(DiagLevel.Info, DiagLevel.Warn, DiagLevel.Error)
    val topic = topics.getOrNull(topicIndex)
    val level = levels.getOrElse(levelIndex) { DiagLevel.Info }
    // 显示前也打码：消息里可能带着进程的错误输出
    val shown =
        remember(entries, topic, level) {
            entries
                .asReversed()
                .filter { DiagEntries.matches(it, topic, level) }
                .map { it.copy(message = Redact.text(it.message)) }
        }
    val zone = ZoneId.systemDefault()
    CouiPage(
        title = Strings.diagRunLog(),
        onBack = onBack,
        actions = {
            IconActionButton(
                COUIIcons.Delete,
                Strings.diagClear(),
                enabled = entries.isNotEmpty(),
            ) {
                confirmClear = true
            }
        },
        dialogs = {
            ConfirmDialog(
                show = confirmClear,
                title = Strings.diagClearTitle(),
                summary = Strings.diagClearSummary(),
                confirmText = Strings.diagClear(),
                destructive = true,
                onConfirm = {
                    confirmClear = false
                    DiagLog.clear()
                },
                onDismissRequest = { confirmClear = false },
            )
        },
    ) {
        item(key = "filters") {
            PreferenceGroup {
                item {
                    OverlayDropdownPreference(
                        title = Strings.diagFilterTopic(),
                        items = topics.map { it?.label ?: Strings.diagFilterAll() },
                        selectedIndex = topicIndex,
                        onSelectedIndexChange = { topicIndex = it },
                    )
                }
                item {
                    OverlayDropdownPreference(
                        title = Strings.diagFilterLevel(),
                        items =
                            listOf(
                                Strings.diagFilterAll(),
                                Strings.diagFilterWarnings(),
                                Strings.diagFilterErrors(),
                            ),
                        selectedIndex = levelIndex,
                        onSelectedIndexChange = { levelIndex = it },
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                PagerFooterPreference(
                    summary =
                        if (entries.isEmpty()) Strings.diagNoRecords() else Strings.diagNoMatches(),
                )
            }
        }
        itemsIndexed(shown, key = { index, entry -> "${entry.at}:$index" }) { index, entry ->
            CardListSegment(index, shown.size) { EntryRow(entry, zone) }
        }
    }
}

@Composable
private fun EntryRow(entry: DiagEntry, zone: ZoneId) {
    val colors = COUITheme.colorScheme
    val levelColor =
        when (entry.level) {
            DiagLevel.Error -> colors.error
            DiagLevel.Warn -> StatusWarning
            DiagLevel.Info -> colors.labelSecondary
        }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = DiagEntries.shortTime(entry.at, zone) + " · " + entry.topic.label,
                style = COUITheme.textStyles.bodyS,
                color = colors.labelSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(text = entry.level.label, style = COUITheme.textStyles.bodyS, color = levelColor)
        }
        SelectionContainer {
            Text(
                text = entry.message,
                style = COUITheme.textStyles.bodyM.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = colors.labelPrimary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 一个日志文件的最后 64 KB。显示前也打码。 */
@Composable
private fun LogTailPage(file: LogFile, load: suspend (LogFile) -> String?, onBack: () -> Unit) {
    var text by remember(file) { mutableStateOf<String?>(null) }
    var failed by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file) {
        val loaded = load(file)?.let { withContext(Dispatchers.Default) { Redact.text(it) } }
        text = loaded
        failed = loaded == null
    }
    val zone = ZoneId.systemDefault()
    CouiPage(
        title = if (file.kind == LogKind.Running) file.title.ifBlank { file.name } else file.name,
        onBack = onBack,
    ) {
        item(key = "info") {
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(top = 4.dp, bottom = 10.dp),
            ) {
                Text(
                    text = file.path,
                    style = COUITheme.textStyles.bodyS.copy(fontFamily = MonoFont),
                    color = COUITheme.colorScheme.labelSecondary,
                )
                Text(
                    text =
                        listOf(
                                file.kind.label,
                                DiagScripts.size(file.size),
                                DiagEntries.shortTime(file.modified, zone),
                            )
                            .joinToString(" · ") +
                            if (file.size > DiagScripts.TAIL_BYTES)
                                " · " + Strings.diagTailOnly(DiagScripts.TAIL_BYTES / 1024)
                            else "",
                    style = COUITheme.textStyles.bodyS,
                    color = COUITheme.colorScheme.labelSecondary,
                )
            }
        }
        val loaded = text
        when {
            failed ->
                item(key = "error") {
                    PagerFooterPreference(
                        summary = Strings.diagFileUnreadable(),
                        summaryColor = COUITheme.colorScheme.error,
                    )
                }
            loaded == null ->
                item(key = "loading") { PreferenceGroup { item { ConfigLoadingRow() } } }
            loaded.isBlank() ->
                item(key = "blank") { PagerFooterPreference(summary = Strings.diagFileEmpty()) }
            else -> {
                val parts = chunks(loaded)
                itemsIndexed(parts, key = { index, _ -> "t:$index" }) { index, part ->
                    CardListSegment(index, parts.size) {
                        SelectionContainer {
                            Text(
                                text = part,
                                style =
                                    COUITheme.textStyles.bodyS.copy(
                                        fontFamily = MonoFont,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                    ),
                                color = COUITheme.colorScheme.labelPrimary,
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 长文本按行切成几段，列表里逐段组合，滚动时不卡。 */
private fun chunks(text: String, size: Int = 4000): List<String> {
    val out = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = (start + size).coerceAtMost(text.length)
        if (end < text.length)
            text.lastIndexOf('\n', end).takeIf { it > start }?.let { end = it + 1 }
        out += text.substring(start, end).trimEnd('\n')
        start = end
    }
    return out
}
