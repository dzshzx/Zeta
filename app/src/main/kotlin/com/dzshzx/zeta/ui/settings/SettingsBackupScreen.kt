package com.dzshzx.zeta.ui.settings

import android.content.Context
import android.net.Uri
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
import androidx.compose.runtime.Stable
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
import com.dzshzx.zeta.core.PathCheck
import com.dzshzx.zeta.core.PathKind
import com.dzshzx.zeta.core.PrefsListStore
import com.dzshzx.zeta.core.PromptStore
import com.dzshzx.zeta.core.Settings
import com.dzshzx.zeta.core.SharedFolders
import com.dzshzx.zeta.core.notify.NotifyRuleStore
import com.dzshzx.zeta.core.schedule.ScheduleStore
import com.dzshzx.zeta.ui.app.UiPrefs
import com.dzshzx.zeta.ui.app.writeText
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.NoticeDialog
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.rememberRetained
import com.dzshzx.zeta.ui.pane.exportFileName
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 读完备份文件、比对过当前设置后要给人看的结果。 */
private sealed interface ImportReview {
    data class Failed(val reason: String) : ImportReview
    data class Ready(val backup: BackupFile.Valid, val plan: ImportPlan) : ImportReview
}

/**
 * “备份与恢复”页的状态：正在读写文件、导入前的核对结果，以及打开系统“保存到”“打开”的两个入口。
 * 页面列表里的两行和 overlays 里的对话框（[SettingsBackupDialogs]）共用这一份。
 */
@Stable
private class SettingsBackupState {
    var busy by mutableStateOf(false)
    var review by mutableStateOf<ImportReview?>(null)
    var export: () -> Unit = {}
    var import: () -> Unit = {}
}

@Composable
private fun rememberSettingsBackup(hub: ConversationHub): SettingsBackupState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { SettingsBackupState() }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            state.busy = true
            val ok = withContext(Dispatchers.IO) { writeBackup(context, hub, uri) }
            state.busy = false
            Toast.makeText(context, if (ok) "设置已导出" else "导出失败", if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            state.busy = true
            state.review = withContext(Dispatchers.IO) { readBackup(context, hub, uri) }
            state.busy = false
        }
    }
    state.export = { runCatching { exportLauncher.launch(exportFileName("设置", extension = "json")) } }
    state.import = { runCatching { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) } }
    return state
}

/**
 * 设置 › 备份与恢复：把 Zeta 自己的设置存成一个 JSON 文件（系统的“保存到”），或从文件导入（系统的“打开”）。
 * 导入前列出会改哪几项、跳过哪几项，确认后才写。转换和校验在 [SettingsBackup]。
 */
@Composable
internal fun SettingsBackupScreen(hub: ConversationHub, onBack: () -> Unit) {
    val state = rememberSettingsBackup(hub)
    CouiPage(title = "备份与恢复", onBack = onBack, overlays = { SettingsBackupDialogs(hub, state) }) {
        item(key = "backup") {
            // 备份里是外观、聊天、手机操作、数字助理的模型、置顶、共享文件夹、常用指令、定时任务和通知触发（[SettingsBackup.items]）；
            // 新对话的默认值是 Claude Code 的配置，不在里面；只属于这台手机的（Linux 环境的位置和用户、调试用的屏幕编号、各种缓存、
            // 闹钟登记）不导出，见 [SettingsBackup.machineKeys]；定时任务和通知触发只带规则本身，不带运行记录
            PreferenceGroup(footer = "不含 Claude Code 的配置、对话和登录状态，也不含定时任务和通知触发的运行记录。") {
                item {
                    BasicComponent(title = "导出设置", summary = "保存成 JSON 文件", enabled = !state.busy, onClick = state.export)
                }
                item {
                    BasicComponent(
                        title = "导入设置",
                        summary = if (state.busy) "处理中…" else "导入前会列出要改的项",
                        enabled = !state.busy,
                        onClick = state.import,
                    )
                }
            }
        }
    }
}

/** 导入前的核对：读不了、没有要改的、确认导入。放在页面的 overlays 里。 */
@Composable
private fun SettingsBackupDialogs(hub: ConversationHub, state: SettingsBackupState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 关掉的对话框还要播完退场动画，内容取最后一次的
    val shown = state.review
    val last = rememberRetained(shown)
    val failed = last as? ImportReview.Failed
    val ready = last as? ImportReview.Ready
    NoticeDialog(
        show = shown is ImportReview.Failed,
        title = "没法导入",
        summary = failed?.reason,
        onDismissRequest = { state.review = null },
    )
    NoticeDialog(
        show = shown is ImportReview.Ready && shown.plan.changes.isEmpty(),
        title = "没有要改的设置",
        summary = null,
        onDismissRequest = { state.review = null },
    ) { ready?.let { PlanDetails(it.plan) } }
    ConfirmDialog(
        show = shown is ImportReview.Ready && shown.plan.changes.isNotEmpty(),
        title = "导入设置？",
        summary = null,
        confirmText = "导入",
        onConfirm = {
            val confirmed = state.review as? ImportReview.Ready
            state.review = null
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
        onDismissRequest = { state.review = null },
    ) { ready?.let { PlanDetails(it.plan) } }
}

/** 确认框里的清单：会改的（原来 → 备份里的）、跳过的及原因、和当前一样的。 */
@Composable
private fun PlanDetails(plan: ImportPlan) {
    val colors = COUITheme.colorScheme
    val body = COUITheme.textStyles.bodyM
    val note = COUITheme.textStyles.bodyS
    Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
        if (plan.changes.isNotEmpty()) {
            Text("将覆盖 ${plan.changes.size} 项：", style = body, color = colors.labelPrimary)
            for (change in plan.changes) {
                Text("${change.label}：${change.from} → ${change.to}", style = body, color = colors.labelPrimary, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (plan.skipped.isNotEmpty()) {
            Text("跳过 ${plan.skipped.size} 项：", style = body, color = colors.labelPrimary, modifier = Modifier.padding(top = 12.dp))
            for (item in plan.skipped) {
                Text("${item.label}：${item.reason}", style = note, color = colors.labelSecondary, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (plan.unchanged.isNotEmpty()) {
            Text(
                "和当前一样、不用改的 ${plan.unchanged.size} 项：" + plan.unchanged.joinToString("、"),
                style = note,
                color = colors.labelSecondary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private fun writeBackup(context: Context, hub: ConversationHub, uri: Uri): Boolean {
    val exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
    return context.writeText(uri, SettingsBackup.export(hub.settings.snapshot(), BuildConfig.VERSION_NAME, exportedAt))
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
    return when (PathCheck.check(config, cwd, null)) {
        PathKind.Folder -> null
        PathKind.Missing -> "这台手机的 Linux 环境里没有这个目录"
        PathKind.File -> "这台手机的 Linux 环境里这是个文件，不是目录"
        PathKind.Denied -> "Linux 用户没有权限进这个目录"
        else -> "查不了这个目录在不在"
    }
}

/** 写回存储，再让界面上的各份副本跟着变。返回写了几项；没写成返回 null。在主线程上调用。 */
private suspend fun applyBackup(context: Context, hub: ConversationHub, ready: ImportReview.Ready): Int? {
    val writes = SettingsBackup.writes(ready.backup, ready.plan)
    if (!withContext(Dispatchers.IO) { restoreBackup(context, hub.settings, writes) }) return null
    hub.reloadSettings()
    UiPrefs.get(context).reload()
    // 共享了内部存储的目录时，Linux 用户要在 media_rw 组里才能读写，和在共享文件夹页添加时一样补上
    val config = hub.settings.env
    if ("sharedFolders" in writes && config != null && hub.settings.sharedFolders.any { SharedFolders.isMediaStorage(it.source) }) {
        withContext(Dispatchers.IO) { SharedFolders.ensureMediaGroup(config) }
    }
    return writes.size
}

/**
 * 把导入要写的值（[SettingsBackup.writes]）写回存储，成功返回 true。可以在后台线程调用。
 * 存成清单的几项（常用指令、定时任务、通知触发）经各自的 store 写：内存里的那份跟着换，定时任务的闹钟跟着重排；其余一次写进 [settings]。
 */
internal fun restoreBackup(context: Context, settings: Settings, writes: Map<String, Any?>): Boolean {
    val stores = listOf<PrefsListStore<*>>(PromptStore, ScheduleStore, NotifyRuleStore).associateBy { it.key }
    if (!settings.restore(writes.filterKeys { it !in stores })) return false
    for ((key, value) in writes) stores[key]?.replace(context, value as String?)
    return true
}
