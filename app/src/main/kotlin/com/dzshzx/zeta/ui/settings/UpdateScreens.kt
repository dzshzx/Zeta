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
import com.dzshzx.zeta.core.of
import com.dzshzx.zeta.core.update.AppUpdates
import com.dzshzx.zeta.core.update.UpdateInstaller
import com.dzshzx.zeta.core.update.UpdateState
import com.dzshzx.zeta.core.update.UpdateStep
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
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
    val icon = @Composable { SettingsIcon(COUIIcons.Info) }
    if (!updates.enabled) {
        BasicComponent(title = Strings.commonVersion(), summary = version, startAction = icon)
        return
    }
    val state = updates.state
    BasicComponent(
        title = Strings.commonVersion(),
        summary = if (state is UpdateState.CheckFailed) "$version\n${state.reason}" else version,
        startAction = icon,
        endActions = {
            when (state) {
                UpdateState.Idle -> PreferenceValue(Strings.updateCheck())
                UpdateState.Checking -> InfiniteProgressIndicator(size = 20.dp)
                UpdateState.UpToDate -> PreferenceValue(Strings.updateUpToDate())
                is UpdateState.CheckFailed ->
                    PreferenceValue(
                        Strings.updateCheckFailed(),
                        color = COUITheme.colorScheme.error,
                    )
                is UpdateState.Available ->
                    PreferenceValue(
                        Strings.updateAvailable(state.manifest.versionName),
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
        title = Strings.settingsAutoCheckUpdates(),
        startAction = { SettingsIcon(COUIIcons.Update) },
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
    CouiPage(title = Strings.updateTitle(), onBack = onBack) {
        if (available == null) {
            item(key = "current") {
                PreferenceGroup { item { VersionRow(updates, onOpenUpdate = {}) } }
            }
        } else {
            item(key = "release") {
                PreferenceGroup(footer = Strings.updateInstallNote()) {
                    item { ReleaseRow(updates, available) }
                }
            }
            item(key = "notes") {
                PreferenceGroup(title = Strings.updateNotes()) {
                    item {
                        StaticMarkdown(
                            content = available.manifest.notes.ifBlank { Strings.updateNoNotes() },
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
                            title = Strings.updateCurrentVersion(),
                            endActions = {
                                PreferenceValue(BuildConfig.VERSION_NAME, clickable = false)
                            },
                        )
                    }
                }
            }
        }
    }
}

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
                        manifest.publishedAt
                            .take(10)
                            .takeIf { it.length == 10 }
                            ?.let { Strings.updatePublished(it) },
                    )
                    .joinToString(" · ")
            is UpdateStep.Downloading -> Strings.updateDownloading(formatBytes(step.done), size)
            UpdateStep.Verifying -> Strings.updateVerifying()
            UpdateStep.WaitingIdle -> Strings.updateWaitingIdle()
            UpdateStep.NeedsPermission -> Strings.updateNeedsPermission()
            UpdateStep.Downloaded -> Strings.updateDownloaded()
            is UpdateStep.Installing ->
                if (step.root) Strings.updateInstallingRoot() else Strings.updateInstallingSystem()
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
        UpdateStep.Ready -> Triple(Strings.updateActionUpdate(), InstallLoadState.Idle, 0f)
        is UpdateStep.Downloading -> {
            val fraction = (step.done.toFloat() / size).coerceIn(0f, 1f)
            Triple("${(fraction * 100).toInt()}%", InstallLoadState.Loading, fraction)
        }
        UpdateStep.Verifying -> Triple(Strings.updateActionVerify(), InstallLoadState.Loading, 1f)
        UpdateStep.WaitingIdle -> Triple(Strings.updateActionWait(), InstallLoadState.Paused, 1f)
        UpdateStep.NeedsPermission -> Triple(Strings.updateActionAllow(), InstallLoadState.Idle, 0f)
        UpdateStep.Downloaded -> Triple(Strings.updateActionInstall(), InstallLoadState.Idle, 0f)
        is UpdateStep.Installing ->
            Triple(Strings.updateActionInstall(), InstallLoadState.Loading, 1f)
        is UpdateStep.Failed -> Triple(Strings.commonRetry(), InstallLoadState.Failed, 0f)
    }
