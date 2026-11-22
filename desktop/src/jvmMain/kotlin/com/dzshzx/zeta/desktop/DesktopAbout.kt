package com.dzshzx.zeta.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.Settings
import com.dzshzx.zeta.core.formatBytes
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.markdown.MarkdownTone
import com.dzshzx.zeta.ui.markdown.StaticMarkdown
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.InstallLoadState
import io.github.suqi8.coui.kmp.preference.LoadInstallProgressPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 设置里的「关于」：版本、检查更新、自动检查更新；有新版本时下面多出新版本一行（「更新」按钮）和更新说明。开发构建只显示版本。 [state] 是
 * [DesktopUpdates.state]，由调用的地方读好传进来，列表跟着它增减项。
 */
fun LazyListScope.aboutItems(
    updates: DesktopUpdates,
    state: DesktopUpdateState,
    settings: Settings,
) {
    item(key = "about") {
        PreferenceGroup(title = "关于") {
            item { VersionRow(updates.version) }
            if (updates.enabled) {
                item { CheckRow(updates, state) }
                item {
                    SwitchPreference(
                        title = "自动检查更新",
                        checked = settings.autoUpdateCheck,
                        onCheckedChange = { settings.autoUpdateCheck = it },
                    )
                }
            }
        }
    }
    if (state !is DesktopUpdateState.Available) return
    item(key = "release") {
        PreferenceGroup(title = "新版本", footer = "安装时 Zeta 会关闭，完成后自动重新打开。") {
            item { ReleaseRow(updates, state) }
        }
    }
    item(key = "notes") {
        PreferenceGroup(title = "更新说明") {
            item {
                StaticMarkdown(
                    content = state.manifest.notes.ifBlank { "- 没有更新说明" },
                    tone = MarkdownTone.Answer,
                    modifier =
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun VersionRow(version: DesktopVersion) {
    BasicComponent(
        title = "版本",
        summary = if (version.isRelease) null else "开发版",
        endActions = { PreferenceValue(version.name) },
    )
}

/** 检查更新一行：右边是结果，点它再查一次（有新版本时不用再查）。 */
@Composable
private fun CheckRow(updates: DesktopUpdates, state: DesktopUpdateState) {
    BasicComponent(
        title = "检查更新",
        summary = (state as? DesktopUpdateState.CheckFailed)?.reason,
        summaryColor = BasicComponentDefaults.summaryColor(color = COUITheme.colorScheme.error),
        endActions = { CheckResult(state) },
        onClick = { updates.check() },
    )
}

@Composable
private fun CheckResult(state: DesktopUpdateState) {
    when (state) {
        DesktopUpdateState.Idle -> Unit
        DesktopUpdateState.Checking ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InfiniteProgressIndicator(size = 18.dp)
                PreferenceValue("正在检查…")
            }
        DesktopUpdateState.UpToDate -> PreferenceValue("已是最新版本")
        is DesktopUpdateState.CheckFailed ->
            PreferenceValue("检查失败", color = COUITheme.colorScheme.error)
        is DesktopUpdateState.Available ->
            PreferenceValue(
                "有新版本 ${state.manifest.versionName}",
                color = COUITheme.colorScheme.primary,
            )
    }
}

/** 新版本一行：标题是版本号，说明随步骤变（失败时红字写原因），右边的按钮管下载、安装，下载中显示进度、点它取消。 */
@Composable
private fun ReleaseRow(updates: DesktopUpdates, state: DesktopUpdateState.Available) {
    val step = state.step
    val (label, look, progress) = capsule(step, state.msi.size)
    LoadInstallProgressPreference(
        title = "Zeta ${state.manifest.versionName}",
        summary = releaseSummary(state),
        summaryColor =
            if (step is DesktopUpdateStep.Failed)
                BasicComponentDefaults.summaryColor(color = COUITheme.colorScheme.error)
            else BasicComponentDefaults.summaryColor(),
        progressText = label,
        state = look,
        progress = progress,
        onProgressClick = {
            when (step) {
                DesktopUpdateStep.Ready,
                is DesktopUpdateStep.Failed -> updates.start()
                is DesktopUpdateStep.Downloading -> updates.cancel()
                DesktopUpdateStep.Verifying,
                DesktopUpdateStep.Installing -> Unit
            }
        },
    )
}

private fun releaseSummary(state: DesktopUpdateState.Available): String {
    val size = formatBytes(state.msi.size)
    return when (val step = state.step) {
        DesktopUpdateStep.Ready ->
            listOfNotNull(
                    size,
                    state.manifest.publishedAt
                        .take(10)
                        .takeIf { it.length == 10 }
                        ?.let { "$it 发布" },
                )
                .joinToString(" · ")
        is DesktopUpdateStep.Downloading -> "正在下载 ${formatBytes(step.done)} / $size"
        DesktopUpdateStep.Verifying -> "正在校验"
        DesktopUpdateStep.Installing -> "正在安装"
        is DesktopUpdateStep.Failed -> step.reason
    }
}

/** 按钮上的字、样子和进度。 */
private fun capsule(step: DesktopUpdateStep, size: Long): Triple<String, InstallLoadState, Float> =
    when (step) {
        DesktopUpdateStep.Ready -> Triple("更新", InstallLoadState.Idle, 0f)
        is DesktopUpdateStep.Downloading -> {
            val fraction = (step.done.toFloat() / size).coerceIn(0f, 1f)
            Triple("${(fraction * 100).toInt()}%", InstallLoadState.Loading, fraction)
        }
        DesktopUpdateStep.Verifying -> Triple("校验", InstallLoadState.Loading, 1f)
        DesktopUpdateStep.Installing -> Triple("安装", InstallLoadState.Loading, 1f)
        is DesktopUpdateStep.Failed -> Triple("重试", InstallLoadState.Failed, 0f)
    }
