package com.dzshzx.zeta.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.PreferenceValue
import com.dzshzx.zeta.ui.components.miuix.SettingsIcon
import com.dzshzx.zeta.ui.markdown.MarkdownTone
import com.dzshzx.zeta.ui.markdown.StaticMarkdown
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 「关于」里的版本一行：正式版点它检查更新，右边写结果；有新版本时点它进「版本更新」页。 不检查更新的包（测试版、别人自己编的）只显示版本。 */
@Composable
internal fun VersionRow(updates: AppUpdates, onOpenUpdate: () -> Unit) {
    val version = BuildConfig.VERSION_NAME
    val icon = @Composable { SettingsIcon(MiuixIcons.Info) }
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
                        color = MiuixTheme.colorScheme.error,
                    )
                is UpdateState.Available ->
                    PreferenceValue(
                        Strings.updateAvailable(state.manifest.versionName),
                        color = MiuixTheme.colorScheme.primary,
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
        startAction = { SettingsIcon(MiuixIcons.Update) },
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
    MiuixPage(title = Strings.updateTitle(), onBack = onBack) {
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
                    item { ValueRow(Strings.updateCurrentVersion(), BuildConfig.VERSION_NAME) }
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
    val onCapsuleClick = {
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
    }
    BasicComponent(
        title = "Zeta ${manifest.versionName}",
        summary = summary,
        summaryColor =
            if (step is UpdateStep.Failed)
                BasicComponentDefaults.summaryColor(color = MiuixTheme.colorScheme.error)
            else BasicComponentDefaults.summaryColor(),
        endActions = { ProgressCapsule(label, look, progress, onCapsuleClick) },
    )
}

/** 胶囊按钮的字（两个字放得下）、样子和进度：点了做什么见 [ReleaseRow]。 */
private fun capsule(step: UpdateStep, size: Long): Triple<String, CapsuleLook, Float> =
    when (step) {
        UpdateStep.Ready -> Triple(Strings.updateActionUpdate(), CapsuleLook.Idle, 0f)
        is UpdateStep.Downloading -> {
            val fraction = (step.done.toFloat() / size).coerceIn(0f, 1f)
            Triple("${(fraction * 100).toInt()}%", CapsuleLook.Loading, fraction)
        }
        UpdateStep.Verifying -> Triple(Strings.updateActionVerify(), CapsuleLook.Loading, 1f)
        UpdateStep.WaitingIdle -> Triple(Strings.updateActionWait(), CapsuleLook.Paused, 1f)
        UpdateStep.NeedsPermission -> Triple(Strings.updateActionAllow(), CapsuleLook.Idle, 0f)
        UpdateStep.Downloaded -> Triple(Strings.updateActionInstall(), CapsuleLook.Idle, 0f)
        is UpdateStep.Installing -> Triple(Strings.updateActionInstall(), CapsuleLook.Loading, 1f)
        is UpdateStep.Failed -> Triple(Strings.commonRetry(), CapsuleLook.Failed, 0f)
    }

/** 进度胶囊的样子：空闲（主题色淡底主题色字）、进行中（按进度填主题色）、暂停（满格但变淡）、失败（红字）。 */
private enum class CapsuleLook {
    Idle,
    Loading,
    Paused,
    Failed,
}

/** 新版本一行右边的胶囊按钮：两个字或百分比，进行中时从左往右按 [progress] 填主题色。 */
@Composable
private fun ProgressCapsule(text: String, look: CapsuleLook, progress: Float, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val fill =
        when (look) {
            CapsuleLook.Loading -> colors.primary
            CapsuleLook.Paused -> colors.primary.copy(alpha = 0.5f)
            else -> Color.Transparent
        }
    val textColor =
        when (look) {
            CapsuleLook.Failed -> colors.error
            CapsuleLook.Idle -> colors.primary
            else -> if (progress >= 0.5f) colors.onPrimary else colors.primary
        }
    Box(
        Modifier.widthIn(min = 72.dp)
            .height(32.dp)
            .clip(CircleShape)
            .background(colors.tertiaryContainer)
            .drawBehind { drawRect(fill, size = size.copy(width = size.width * progress)) }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MiuixTheme.textStyles.button, color = textColor, maxLines = 1)
    }
}
