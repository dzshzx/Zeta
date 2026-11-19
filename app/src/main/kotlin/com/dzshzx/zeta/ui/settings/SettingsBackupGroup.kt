package com.dzshzx.zeta.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.BuildConfig
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.SharedFolders
import com.dzshzx.zeta.ui.app.UiPrefs
import com.dzshzx.zeta.ui.components.coui.CouiConfirmDialog
import com.dzshzx.zeta.ui.components.coui.CouiNoticeDialog
import com.dzshzx.zeta.ui.components.coui.CouiPreference
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Group
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 读完备份文件、比对过当前设置后要给人看的结果。 */
private sealed interface ImportReview {
    data class Failed(val reason: String) : ImportReview
    data class Ready(val backup: BackupFile.Valid, val plan: ImportPlan) : ImportReview
}

/**
 * 设置页的“备份与恢复”：把 Zeta 自己的设置存成一个 JSON 文件（系统的“保存到”），或从文件导入（系统的“打开”）。
 * 导入前列出会改哪几项、跳过哪几项，确认后才写。转换和校验在 [SettingsBackup]。
 */
@Composable
internal fun SettingsBackupGroup(hub: ConversationHub) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<ImportReview?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { writeBackup(context, hub, uri) }
            busy = false
            Toast.makeText(context, if (ok) "设置已导出" else "导出失败", if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            review = withContext(Dispatchers.IO) { readBackup(context, hub, uri) }
            busy = false
        }
    }

    Group(
        title = "备份与恢复",
        footer = "备份里是 Zeta 自己的设置：外观、聊天、手机操作、新对话的默认值、置顶和共享文件夹。" +
            "不含 Claude Code 的配置、对话和登录状态；Linux 环境的位置和用户、调试用的屏幕编号和各种缓存只属于这台手机，也不导出。",
    ) {
        CouiPreference(
            title = "导出设置",
            summary = "保存成 JSON 文件",
            enabled = !busy,
            onClick = { runCatching { exportLauncher.launch(backupFileName()) } },
        )
        CouiPreference(
            title = "导入设置",
            summary = if (busy) "处理中…" else "从备份文件恢复，导入前会列出要改的项",
            enabled = !busy,
            onClick = { runCatching { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) } },
        )
    }

    // 关掉的对话框还要播完退场动画，内容取最后一次的
    val shown = review
    val last = remember { arrayOfNulls<ImportReview>(1) }
    shown?.let { last[0] = it }
    val failed = last[0] as? ImportReview.Failed
    val ready = last[0] as? ImportReview.Ready
    CouiNoticeDialog(
        visible = shown is ImportReview.Failed,
        title = "没法导入",
        message = failed?.reason,
        onDismissRequest = { review = null },
    )
    CouiNoticeDialog(
        visible = shown is ImportReview.Ready && shown.plan.changes.isEmpty(),
        title = "没有要改的设置",
        message = null,
        onDismissRequest = { review = null },
    ) { ready?.let { PlanDetails(it.plan) } }
    CouiConfirmDialog(
        visible = shown is ImportReview.Ready && shown.plan.changes.isNotEmpty(),
        title = "导入设置？",
        message = null,
        confirmText = "导入",
        onConfirm = {
            val confirmed = review as? ImportReview.Ready
            review = null
            if (confirmed != null) {
                scope.launch {
                    val count = applyBackup(context, hub, confirmed)
                    Toast.makeText(
                        context,
                        if (count != null) "已导入 $count 项设置" else "导入失败，设置没有改动",
                        if (count != null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                    ).show()
                }
            }
        },
        onDismissRequest = { review = null },
    ) { ready?.let { PlanDetails(it.plan) } }
}

/** 确认框里的清单：会改的（原来 → 备份里的）、跳过的及原因、和当前一样的。 */
@Composable
private fun PlanDetails(plan: ImportPlan) {
    val colors = CouiPageDefaults.themeColors()
    val body = MiuixTheme.textStyles.body2
    val note = MiuixTheme.textStyles.footnote1
    Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
        if (plan.changes.isNotEmpty()) {
            Text("将覆盖 ${plan.changes.size} 项：", style = body, color = colors.title)
            for (change in plan.changes) {
                Text("${change.label}：${change.from} → ${change.to}", style = body, color = colors.title, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (plan.skipped.isNotEmpty()) {
            Text("跳过 ${plan.skipped.size} 项：", style = body, color = colors.title, modifier = Modifier.padding(top = 12.dp))
            for (item in plan.skipped) {
                Text("${item.label}：${item.reason}", style = note, color = colors.summary, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (plan.unchanged.isNotEmpty()) {
            Text(
                "和当前一样、不用改的 ${plan.unchanged.size} 项：" + plan.unchanged.joinToString("、"),
                style = note,
                color = colors.summary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** 默认文件名：Zeta-设置-年月日-时分.json。 */
private fun backupFileName(now: Long = System.currentTimeMillis()): String =
    "Zeta-设置-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now)) + ".json"

private fun writeBackup(context: Context, hub: ConversationHub, uri: Uri): Boolean {
    val exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
    val text = SettingsBackup.export(hub.settings.snapshot(), BuildConfig.VERSION_NAME, exportedAt)
    val written = runCatching {
        context.contentResolver.openOutputStream(uri, "w")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
    }.getOrDefault(false)
    // 没写成的话，系统已经建好的那个空文件也删掉
    if (!written) runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    return written
}

/** 读文件、校验、和当前设置比对。耗时（读文件，查工作目录在不在），别在主线程调用。 */
private fun readBackup(context: Context, hub: ConversationHub, uri: Uri): ImportReview {
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readNBytes(SettingsBackup.MAX_BYTES + 1) }
    }.getOrNull() ?: return ImportReview.Failed("读不了这个文件")
    if (bytes.size > SettingsBackup.MAX_BYTES) return ImportReview.Failed("文件太大，不像 Zeta 的设置备份")
    val backup = when (val parsed = SettingsBackup.parse(bytes.toString(Charsets.UTF_8))) {
        is BackupFile.Invalid -> return ImportReview.Failed(parsed.reason)
        is BackupFile.Valid -> parsed
    }
    val extraSkips = HashMap<String, String>()
    (backup.values["cwd"] as? JsonPrimitive)?.contentOrNull?.let { cwd ->
        cwdProblem(hub, cwd)?.let { extraSkips["cwd"] = it }
    }
    return ImportReview.Ready(backup, SettingsBackup.plan(backup, hub.settings.snapshot(), extraSkips))
}

/** 备份里的默认工作目录在这台手机的 Linux 环境里用不了的原因；能用返回 null。 */
private fun cwdProblem(hub: ConversationHub, cwd: String): String? {
    val config = hub.settings.env ?: return "还没有 Linux 环境，装好以后再导入这一项"
    if (config.defaultCwd == cwd) return null
    val result = runCatching { LinuxEnv.run(config, "test -d " + LinuxEnv.quote(cwd), timeoutMs = 30_000) }.getOrNull()
    return when {
        result == null || result.exitCode < 0 -> "查不了这个目录在不在"
        result.exitCode != 0 -> "这台手机的 Linux 环境里没有这个目录"
        else -> null
    }
}

/** 写回存储，再让界面上的各份副本跟着变。返回写了几项；没写成返回 null。在主线程上调用。 */
private suspend fun applyBackup(context: Context, hub: ConversationHub, ready: ImportReview.Ready): Int? {
    val writes = SettingsBackup.writes(ready.backup, ready.plan)
    if (!withContext(Dispatchers.IO) { hub.settings.restore(writes) }) return null
    hub.reloadSettings()
    UiPrefs.get(context).reload()
    // 共享了内部存储的目录时，Linux 用户要在 media_rw 组里才能读写，和在共享文件夹页添加时一样补上
    val config = hub.settings.env
    if ("sharedFolders" in writes && config != null && hub.settings.sharedFolders.any { SharedFolders.isMediaStorage(it.source) }) {
        withContext(Dispatchers.IO) { SharedFolders.ensureMediaGroup(config) }
    }
    return writes.size
}
