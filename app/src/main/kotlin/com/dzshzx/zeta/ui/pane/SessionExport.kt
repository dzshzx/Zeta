package com.dzshzx.zeta.ui.pane

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.ui.app.discardDocument
import com.dzshzx.zeta.ui.app.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 分享走 Intent 传文字，太长会超出系统一次跨进程传输的上限（约 1 MB，按 UTF-16 算），留足余量。 */
private const val MaxShareChars = 200_000

/** 侧栏菜单里的“导出”和“分享”，两样都先让助手把会话记录转成 Markdown。 [saveAll] 是多选时的导出：几个会话存进同一个 .md 文件。 */
internal class SessionExporter(
    val save: (SessionSummary) -> Unit,
    val share: (SessionSummary) -> Unit,
    val saveAll: (List<SessionSummary>) -> Unit,
)

/** 导出照 Eta 直接打开系统的“存储到”界面存成 .md 文件；分享交给系统分享面板。多选导出选好了保存位置时调 [onSavedMany]。 */
@Composable
internal fun rememberSessionExporter(
    hub: ConversationHub,
    onSavedMany: () -> Unit = {},
): SessionExporter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 选保存位置时界面可能被系统重建：要导出的记录文件（一行一个）和是不是多选导出记在可保存的状态里
    var savingFiles by rememberSaveable { mutableStateOf<String?>(null) }
    var savingBackend by rememberSaveable { mutableStateOf(Backends.LOCAL) }
    var savingBatch by rememberSaveable { mutableStateOf(false) }
    val saveLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/markdown"),
        ) { uri ->
            val files = savingFiles?.split('\n')?.filter { it.isNotEmpty() }
            val batch = savingBatch
            savingFiles = null
            savingBatch = false
            if (uri == null || files.isNullOrEmpty()) return@rememberLauncherForActivityResult
            if (batch) onSavedMany()
            scope.launch {
                val saved =
                    withContext(Dispatchers.IO) {
                        saveExport(context, hub, savingBackend, files, uri)
                    }
                Toast.makeText(
                        context,
                        exportedMessage(saved, files.size),
                        if (saved == files.size) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }
    fun launch(sessions: List<SessionSummary>, batch: Boolean) {
        if (sessions.isEmpty()) return
        // 一次导出的都在侧栏同一台主机上
        savingFiles = sessions.joinToString("\n") { it.file }
        savingBackend = sessions[0].backend
        savingBatch = batch
        runCatching { saveLauncher.launch(exportName(sessions)) }.onFailure { savingFiles = null }
    }
    return SessionExporter(
        save = { session -> launch(listOf(session), batch = false) },
        share = { session -> scope.launch { shareExport(context, hub, session) } },
        saveAll = { sessions -> launch(sessions, batch = true) },
    )
}

/** 导出完的提示：[saved] 个导出成了，一共 [total] 个。 */
private fun exportedMessage(saved: Int, total: Int): String =
    when {
        saved == 0 -> "导出失败"
        total == 1 -> "已导出"
        saved < total -> "已导出 $saved 个对话，${total - saved} 个没导出成"
        else -> "已导出 $saved 个对话"
    }

/** 存成的文件名：一个对话用它的标题（没有就用开头的话），多个是“N个对话”。 */
private fun exportName(sessions: List<SessionSummary>): String =
    if (sessions.size == 1) {
        exportFileName(
            sessions[0].title.ifBlank { sessions[0].preview.take(40) }.ifBlank { "未命名对话" },
        )
    } else {
        exportFileName("${sessions.size}个对话")
    }

/** 导出 [files] 写进 [uri]，返回导出成的会话数；一个都没成（或没写进文件）返回 0。 */
private fun saveExport(
    context: Context,
    hub: ConversationHub,
    backend: String,
    files: List<String>,
    uri: Uri,
): Int {
    val exported = exportMarkdowns(hub, backend, files).orEmpty()
    if (exported.isEmpty()) {
        // 一个都没导出来：系统已经建好的那个空文件也删掉，免得留下一个空的 .md
        context.discardDocument(uri)
        return 0
    }
    val text = if (exported.size == 1) exported[0].markdown else combineExports(exported)
    return if (context.writeText(uri, text)) exported.size else 0
}

private suspend fun shareExport(context: Context, hub: ConversationHub, session: SessionSummary) {
    val exported =
        withContext(Dispatchers.IO) { exportMarkdown(hub, session.backend, session.file) }
    when {
        exported == null -> Toast.makeText(context, "导出失败", Toast.LENGTH_LONG).show()
        exported.markdown.length > MaxShareChars ->
            Toast.makeText(context, "对话太长，没法直接分享，请保存到文件", Toast.LENGTH_LONG).show()
        else -> {
            val send =
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, exported.title)
                    .putExtra(Intent.EXTRA_TEXT, exported.markdown)
            val chooser = Intent.createChooser(send, "分享对话")
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(chooser) }
                .onFailure { Toast.makeText(context, "导出失败", Toast.LENGTH_LONG).show() }
        }
    }
}
