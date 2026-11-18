package com.dzshzx.zeta.ui.pane

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 分享走 Intent 传文字，太长会超出系统一次跨进程传输的上限（约 1 MB，按 UTF-16 算），留足余量。 */
private const val MaxShareChars = 200_000

/** 侧栏菜单里的“导出”和“分享”，两样都先让助手把会话记录转成 Markdown。 */
internal class SessionExporter(val save: (SessionSummary) -> Unit, val share: (SessionSummary) -> Unit)

/** 导出照 Eta 直接打开系统的“存储到”界面存成 .md 文件；分享交给系统分享面板。 */
@Composable
internal fun rememberSessionExporter(hub: ConversationHub): SessionExporter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 选保存位置时界面可能被系统重建：要导出的记录文件记在可保存的状态里
    var savingFile by rememberSaveable { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val file = savingFile
        savingFile = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { saveExport(context, hub, file, uri) }
            Toast.makeText(context, if (ok) "已导出" else "导出失败", if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
    }
    return SessionExporter(
        save = { session ->
            savingFile = session.file
            val title = session.title.ifBlank { session.preview.take(40) }.ifBlank { "未命名对话" }
            runCatching { saveLauncher.launch(exportFileName(title)) }.onFailure { savingFile = null }
        },
        share = { session -> scope.launch { shareExport(context, hub, session) } },
    )
}

private fun saveExport(context: Context, hub: ConversationHub, file: String, uri: Uri): Boolean {
    val written = exportMarkdown(hub, file)?.let { exported ->
        runCatching {
            context.contentResolver.openOutputStream(uri, "w")?.use { it.write(exported.markdown.toByteArray(Charsets.UTF_8)) } != null
        }.getOrDefault(false)
    } == true
    // 没写成的话，系统已经建好的那个空文件也删掉，免得留下一个空的 .md
    if (!written) runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    return written
}

private suspend fun shareExport(context: Context, hub: ConversationHub, session: SessionSummary) {
    val exported = withContext(Dispatchers.IO) { exportMarkdown(hub, session.file) }
    when {
        exported == null -> Toast.makeText(context, "导出失败", Toast.LENGTH_LONG).show()
        exported.markdown.length > MaxShareChars -> Toast.makeText(context, "对话太长，没法直接分享，请保存到文件", Toast.LENGTH_LONG).show()
        else -> {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, exported.title)
                .putExtra(Intent.EXTRA_TEXT, exported.markdown)
            val chooser = Intent.createChooser(send, "分享对话")
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(chooser) }.onFailure { Toast.makeText(context, "导出失败", Toast.LENGTH_LONG).show() }
        }
    }
}
