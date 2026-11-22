package com.dzshzx.zeta.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.BuildConfig
import com.dzshzx.zeta.core.Settings
import com.dzshzx.zeta.core.formatBytes
import com.dzshzx.zeta.core.update.AppUpdates
import com.dzshzx.zeta.core.update.UpdateInstaller
import com.dzshzx.zeta.core.update.UpdateState
import com.dzshzx.zeta.core.update.UpdateStep
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.SettingsHue
import com.dzshzx.zeta.ui.components.coui2.SettingsIcon
import com.dzshzx.zeta.ui.markdown.MarkdownTone
import com.dzshzx.zeta.ui.markdown.StaticMarkdown
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.InstallLoadState
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Info
import io.github.suqi8.coui.kmp.icon.extended.Update
import io.github.suqi8.coui.kmp.preference.LoadInstallProgressPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 「关于」里的版本一行：正式版点它检查更新，右边写结果；有新版本时点它进「版本更新」页。 不检查更新的包（测试版、别人自己编的）只显示版本。 */
@Composable
internal fun VersionRow(updates: AppUpdates, onOpenUpdate: () -> Unit) {
    val version = BuildConfig.VERSION_NAME
    val icon = @Composable { SettingsIcon(COUIIcons.Info, SettingsHue.System) }
    if (!updates.enabled) {
        BasicComponent(title = "版本", summary = version, startAction = icon)
        return
    }
    val state = updates.state
    BasicComponent(
        title = "版本",
        summary = if (state is UpdateState.CheckFailed) "$version\n${state.reason}" else version,
        startAction = icon,
        endActions = {
            when (state) {
                UpdateState.Idle -> PreferenceValue("检查更新")
                UpdateState.Checking -> InfiniteProgressIndicator(size = 20.dp)
                UpdateState.UpToDate -> PreferenceValue("已是最新")
                is UpdateState.CheckFailed ->
                    PreferenceValue("检查失败", color = COUITheme.colorScheme.error)
                is UpdateState.Available ->
                    PreferenceValue(
                        "新版本 ${state.manifest.versionName}",
                        color = COUITheme.colorScheme.primary,
                    )
            }
        },
        onClick = { if (state is UpdateState.Available) onOpenUpdate() else updates.check() },
    )
}

/** 「关于」里的自动检查更新开关（只有检查更新的包才有这一行）。 */
@Composable
internal fun AutoUpdateSwitch() {
    val settings = Settings.of(LocalContext.current)
    SwitchPreference(
        title = "自动检查更新",
        startAction = { SettingsIcon(COUIIcons.Update, SettingsHue.System) },
        checked = settings.autoUpdateCheck,
        onCheckedChange = { settings.autoUpdateCheck = it },
    )
}

/** 「版本更新」：新版本的版本号、大小，右边的胶囊按钮管下载、核对、安装（下载中显示进度），下面是更新说明。 从通知点进来时这个进程可能还没查过，就先查一次。 */
@Composable
internal fun UpdateScreen(onBack: () -> Unit) {
    val updates = AppUpdates.get(LocalContext.current)
    LaunchedEffect(Unit) { if (updates.state == UpdateState.Idle) updates.check() }
    val available = updates.state as? UpdateState.Available
    CouiPage(title = "版本更新", onBack = onBack) {
        if (available == null) {
            item(key = "current") {
                PreferenceGroup { item { VersionRow(updates, onOpenUpdate = {}) } }
            }
        } else {
            item(key = "release") {
                PreferenceGroup(footer = INSTALL_NOTE) { item { ReleaseRow(updates, available) } }
            }
            item(key = "notes") {
                PreferenceGroup(title = "更新说明") {
                    item {
                        StaticMarkdown(
                            content = available.manifest.notes.ifBlank { "这个版本没有写更新说明。" },
                            tone = MarkdownTone.Answer,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    }
                }
            }
            item(key = "current") {
                PreferenceGroup {
                    item {
                        BasicComponent(
                            title = "当前版本",
                            endActions = { PreferenceValue(BuildConfig.VERSION_NAME) },
                        )
                    }
                }
            }
        }
    }
}

private const val INSTALL_NOTE =
    "下载后核对大小、sha256 和签名，不对就删掉。装新版本会关掉 Zeta 的界面，进行中的对话照样跑完；" +
        "有手机操作正在执行时等这一步结束再装。有 root 时直接装，装好自动重新打开；没有 root 时交给系统安装，由你确认。"

/** 新版本一行：标题是版本号，说明随步骤变（失败时红字写原因），右边胶囊按钮的字和进度见 [capsule]。 */
@Composable
private fun ReleaseRow(updates: AppUpdates, available: UpdateState.Available) {
    val context = LocalContext.current
    val manifest = available.manifest
    val step = available.step
    val size = formatBytes(manifest.size)
    val summary =
        when (step) {
            UpdateStep.Ready ->
                listOfNotNull(
                        size,
                        manifest.publishedAt.take(10).takeIf { it.length == 10 }?.let { "发布于 $it" },
                    )
                    .joinToString(" · ")
            is UpdateStep.Downloading -> "下载中 ${formatBytes(step.done)} / $size"
            UpdateStep.Verifying -> "核对大小、sha256 和签名"
            UpdateStep.WaitingIdle -> "有手机操作正在执行，这一步结束后自动安装"
            UpdateStep.NeedsPermission -> "没有 root：先允许 Zeta 安装未知应用，再点这里"
            UpdateStep.Downloaded -> "已下载并核对，点安装"
            is UpdateStep.Installing -> if (step.root) "正在安装，装好后 Zeta 自动重新打开" else "在系统的安装界面上确认"
            is UpdateStep.Failed -> step.reason
        }
    val (label, look, progress) = capsule(step, manifest.size)
    LoadInstallProgressPreference(
        title = "Zeta ${manifest.versionName}",
        summary = summary,
        summaryColor =
            if (step is UpdateStep.Failed)
                BasicComponentDefaults.summaryColor(color = COUITheme.colorScheme.error)
            else BasicComponentDefaults.summaryColor(),
        progressText = label,
        state = look,
        progress = progress,
        onProgressClick = {
            when (step) {
                UpdateStep.Ready,
                is UpdateStep.Failed -> updates.start()
                is UpdateStep.Downloading,
                UpdateStep.WaitingIdle -> updates.cancel()
                UpdateStep.Downloaded -> updates.installNow()
                UpdateStep.NeedsPermission ->
                    if (UpdateInstaller.canRequestInstalls(context)) updates.installNow()
                    else UpdateInstaller.openInstallPermission(context)
                UpdateStep.Verifying,
                is UpdateStep.Installing -> Unit
            }
        },
    )
}

/** 胶囊按钮的字（两个字放得下）、样子和进度：点了做什么见 [ReleaseRow]。 */
private fun capsule(step: UpdateStep, size: Long): Triple<String, InstallLoadState, Float> =
    when (step) {
        UpdateStep.Ready -> Triple("更新", InstallLoadState.Idle, 0f)
        is UpdateStep.Downloading -> {
            val fraction = (step.done.toFloat() / size).coerceIn(0f, 1f)
            Triple("${(fraction * 100).toInt()}%", InstallLoadState.Loading, fraction)
        }
        UpdateStep.Verifying -> Triple("核对", InstallLoadState.Loading, 1f)
        UpdateStep.WaitingIdle -> Triple("等待", InstallLoadState.Paused, 1f)
        UpdateStep.NeedsPermission -> Triple("授权", InstallLoadState.Idle, 0f)
        UpdateStep.Downloaded -> Triple("安装", InstallLoadState.Idle, 0f)
        is UpdateStep.Installing -> Triple("安装", InstallLoadState.Loading, 1f)
        is UpdateStep.Failed -> Triple("重试", InstallLoadState.Failed, 0f)
    }
