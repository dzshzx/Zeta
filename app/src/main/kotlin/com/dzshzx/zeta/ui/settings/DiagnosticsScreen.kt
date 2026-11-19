package com.dzshzx.zeta.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.graphics.Color
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
import com.dzshzx.zeta.ui.components.EtaMonospace
import com.dzshzx.zeta.ui.components.StatusError
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.coui.CouiConfirmDialog
import com.dzshzx.zeta.ui.components.coui.CouiDropdownPreference
import com.dzshzx.zeta.ui.components.coui.CouiGroupItem
import com.dzshzx.zeta.ui.components.coui.CouiLazyScaffoldPage
import com.dzshzx.zeta.ui.components.coui.CouiPageBottomSpacer
import com.dzshzx.zeta.ui.components.coui.CouiPreference
import com.dzshzx.zeta.ui.components.coui.CouiScaffoldPage
import com.dzshzx.zeta.ui.components.coui.CouiTopAppBarIconButton
import com.dzshzx.zeta.ui.setup.ConfigEmptyRow
import com.dzshzx.zeta.ui.setup.ConfigLoadingRow
import com.dzshzx.zeta.ui.setup.ConfigSubPage
import com.dzshzx.zeta.ui.setup.brief
import com.dzshzx.zeta.ui.setup.rememberRetained
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Group
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 诊断页里再往下的一层。 */
private sealed interface DiagPage {
    data object Log : DiagPage
    data class File(val file: LogFile) : DiagPage
}

/**
 * 设置里的“诊断”：Zeta 自己的运行日志（能筛）、环境信息、权限状态、Claude Code 那边的错误输出和调试日志，
 * 以及把这些打码后打成一个包导出。
 */
@Composable
internal fun DiagnosticsScreen(
    hub: ConversationHub,
    onBack: () -> Unit,
    // 截图测试换成假数据；平时进 Linux 环境查
    probeLoader: suspend () -> LinuxProbe? = { withContext(Dispatchers.IO) { Diagnostics.probe(hub) } },
    tailLoader: suspend (LogFile) -> String? = { file -> withContext(Dispatchers.IO) { Diagnostics.tail(hub, file) } },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reloads by remember { mutableIntStateOf(0) }
    var probe by remember { mutableStateOf<LinuxProbe?>(null) }
    var probing by remember { mutableStateOf(true) }
    LaunchedEffect(reloads, hub.setup) {
        probing = true
        probe = probeLoader()
        probing = false
    }
    val revision by DiagLog.revision.collectAsState()
    val entries = remember(revision) { DiagLog.entries() }
    val snapshot = rememberPermissionSnapshot()
    var page by remember { mutableStateOf<DiagPage?>(null) }
    var exporting by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exporting = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Diagnostics.export(context.applicationContext, hub, uri) } }
            exporting = false
            val message = result.fold({ "诊断包已导出（$it 个文件，已打码）" }, { "导出失败：${it.message ?: it.javaClass.simpleName}" })
            Toast.makeText(context, message, if (result.isSuccess) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
    }

    CouiScaffoldPage(
        title = "诊断",
        onBack = onBack,
        actions = {
            CouiTopAppBarIconButton(icon = Icons.Rounded.Refresh, contentDescription = "重新查询", enabled = !probing, onClick = { reloads++ })
        },
    ) {
        val errors = entries.count { it.level == DiagLevel.Error }
        val warnings = entries.count { it.level == DiagLevel.Warn }
        Group(
            title = "运行日志",
            footer = "Zeta 自己记下的关键事件：进 Linux 环境、起停 Claude 进程和退出码、helper 出错、手机工具失败、" +
                "无障碍服务被关或加回、定时任务触发。只留最近 ${DiagLog.CAPACITY} 条，存在应用数据里，Zeta 被关掉后还在。",
        ) {
            CouiPreference(
                title = "查看运行日志",
                summary = if (entries.isEmpty()) "还没有记录" else {
                    "共 ${entries.size} 条" + (if (warnings > 0) "，$warnings 条警告" else "") +
                        "\n最近：" + brief(entries.last().message.lineSequence().first(), 40)
                },
                value = if (errors > 0) "$errors 条出错" else null,
                valueColor = if (errors > 0) StatusError else Color.Unspecified,
                onClick = { page = DiagPage.Log },
            )
        }
        Group(title = "环境") {
            // 短的值放右边，长的（路径、系统版本）放标题下面
            Diagnostics.environment(context, hub, probe, probing).forEach {
                if (it.value.length <= 14) CouiPreference(title = it.label, value = it.value) else CouiPreference(title = it.label, summary = it.value)
            }
        }
        Group(title = "权限", footer = "和权限健康页是同一份检查；要修的到那里点一下，或一键授予。") {
            PermissionHealth.items(snapshot, hub).forEach { item ->
                CouiPreference(title = item.title, value = item.value, valueColor = statusColor(item.status))
            }
        }
        val current = probe
        Group(
            title = "对话进程的错误输出",
            footer = "Claude Code 进程写到标准错误的内容：正在跑的对话，和最近结束的 ${DiagScripts.FINISHED_LIMIT} 个（结束时留下的那份）。点开看最后 64 KB。",
        ) {
            val files = current?.files?.filter { it.kind != LogKind.Debug }.orEmpty()
            when {
                current == null && probing -> ConfigLoadingRow()
                current == null -> ConfigEmptyRow("Linux 环境不可用", "进不了环境时看不到 Claude Code 那边的日志")
                files.isEmpty() -> ConfigEmptyRow("没有", "对话进程没有往标准错误写过东西")
                else -> files.forEach { LogFileRow(it) { page = DiagPage.File(it) } }
            }
        }
        Group(
            title = "Claude Code 调试日志",
            footer = "~/.claude/debug 下最近的 ${DiagScripts.DEBUG_LIMIT} 个文件，Claude Code 开着调试时才写。点开看最后 64 KB。",
        ) {
            val files = current?.files(LogKind.Debug).orEmpty()
            when {
                current == null && probing -> ConfigLoadingRow()
                current == null -> ConfigEmptyRow("Linux 环境不可用")
                files.isEmpty() -> ConfigEmptyRow("没有调试日志")
                else -> files.forEach { LogFileRow(it) { page = DiagPage.File(it) } }
            }
        }
        Group(
            footer = "打成一个 zip，用系统的“保存到”存下：运行日志、环境与权限、本应用的 logcat、对话进程的错误输出和调试日志（各取最后 64 KB）。" +
                "导出前给 token、key、password、cookie、Authorization 这类值打码。",
        ) {
            CouiPreference(
                title = "导出诊断包",
                summary = if (exporting) "正在打包…" else null,
                enabled = !exporting,
                showChevron = false,
                onClick = {
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    runCatching { exportLauncher.launch("zeta-diag-$stamp.zip") }
                },
            )
        }
        CouiPageBottomSpacer()
    }

    val shown = rememberRetained(page)
    ConfigSubPage(visible = page == DiagPage.Log, onBack = { page = null }) {
        DiagLogPage(entries = entries, onBack = { page = null })
    }
    ConfigSubPage(visible = page is DiagPage.File, onBack = { page = null }) {
        (shown as? DiagPage.File)?.let { LogTailPage(it.file, tailLoader, onBack = { page = null }) }
    }
}

@Composable
private fun LogFileRow(file: LogFile, onClick: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val title = when (file.kind) {
        LogKind.Running -> file.title.ifBlank { "运行中的对话" }
        else -> file.name
    }
    CouiPreference(
        title = title,
        summary = listOf(file.kind.label, DiagScripts.size(file.size), DiagEntries.shortTime(file.modified, zone)).joinToString(" · "),
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
    val shown = remember(entries, topic, level) {
        entries.asReversed().filter { DiagEntries.matches(it, topic, level) }.map { it.copy(message = Redact.text(it.message)) }
    }
    val zone = ZoneId.systemDefault()
    CouiLazyScaffoldPage(
        title = "运行日志",
        onBack = onBack,
        actions = {
            CouiTopAppBarIconButton(icon = Icons.Rounded.DeleteSweep, contentDescription = "清空", enabled = entries.isNotEmpty(), onClick = { confirmClear = true })
        },
    ) {
        item(key = "filters") {
            Column {
                Group {
                    CouiDropdownPreference(
                        title = "类别",
                        items = topics.map { it?.label ?: "全部" },
                        selectedIndex = topicIndex,
                        onSelectedIndexChange = { topicIndex = it },
                    )
                    CouiDropdownPreference(
                        title = "轻重",
                        items = listOf("全部", "警告和出错", "只看出错"),
                        selectedIndex = levelIndex,
                        onSelectedIndexChange = { levelIndex = it },
                    )
                }
                Spacer(Modifier.height(CouiPageDefaults.groupSpacing))
            }
        }
        if (shown.isEmpty()) {
            item(key = "empty") { Hint(if (entries.isEmpty()) "还没有记录" else "没有符合条件的记录") }
        }
        itemsIndexed(shown, key = { index, entry -> "${entry.at}:$index" }) { index, entry ->
            CouiGroupItem(isFirst = index == 0, isLast = index == shown.lastIndex) { EntryRow(entry, zone) }
        }
    }
    CouiConfirmDialog(
        visible = confirmClear,
        title = "清空运行日志？",
        message = "应用里和文件里的记录都会删掉。",
        confirmText = "清空",
        destructive = true,
        onConfirm = {
            confirmClear = false
            DiagLog.clear()
        },
        onDismissRequest = { confirmClear = false },
    )
}

@Composable
private fun EntryRow(entry: DiagEntry, zone: ZoneId) {
    val colors = CouiPageDefaults.themeColors()
    val levelColor = when (entry.level) {
        DiagLevel.Error -> StatusError
        DiagLevel.Warn -> StatusWarning
        DiagLevel.Info -> colors.summary
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = DiagEntries.shortTime(entry.at, zone) + " · " + entry.topic.label,
                style = MiuixTheme.textStyles.footnote1,
                color = colors.summary,
                modifier = Modifier.weight(1f),
            )
            Text(text = entry.level.label, style = MiuixTheme.textStyles.footnote1, color = levelColor)
        }
        SelectionContainer {
            Text(
                text = entry.message,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = colors.title,
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
    CouiLazyScaffoldPage(title = if (file.kind == LogKind.Running) file.title.ifBlank { file.name } else file.name, onBack = onBack) {
        item(key = "info") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(top = 4.dp, bottom = 10.dp)) {
                Text(text = file.path, style = MiuixTheme.textStyles.footnote1.copy(fontFamily = EtaMonospace), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(
                    text = listOf(file.kind.label, DiagScripts.size(file.size), DiagEntries.shortTime(file.modified, zone)).joinToString(" · ") +
                        if (file.size > DiagScripts.TAIL_BYTES) " · 只显示最后 ${DiagScripts.TAIL_BYTES / 1024} KB" else "",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        val loaded = text
        when {
            failed -> item(key = "error") { Hint("读不出这个文件（可能已经被删掉了）", error = true) }
            loaded == null -> item(key = "loading") { Group { ConfigLoadingRow() } }
            loaded.isBlank() -> item(key = "blank") { Hint("空文件") }
            else -> {
                val parts = chunks(loaded)
                itemsIndexed(parts, key = { index, _ -> "t:$index" }) { index, part ->
                    CouiGroupItem(isFirst = index == 0, isLast = index == parts.lastIndex) {
                        SelectionContainer {
                            Text(
                                text = part,
                                style = MiuixTheme.textStyles.footnote1.copy(fontFamily = EtaMonospace, fontSize = 12.sp, lineHeight = 17.sp),
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
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
        if (end < text.length) text.lastIndexOf('\n', end).takeIf { it > start }?.let { end = it + 1 }
        out += text.substring(start, end).trimEnd('\n')
        start = end
    }
    return out
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
