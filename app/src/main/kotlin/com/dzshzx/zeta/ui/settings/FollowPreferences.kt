package com.dzshzx.zeta.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.EffortLevels
import com.dzshzx.zeta.core.Harnesses
import com.dzshzx.zeta.core.readableModelId
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.chat.PermissionModes
import com.dzshzx.zeta.ui.chat.permissionModeLabel
import com.dzshzx.zeta.ui.components.miuix.DropdownPreference
import com.dzshzx.zeta.ui.components.miuix.DropdownValuePreference

/**
 * 「跟随」（[followLabel]）加 [choices]（值和显示名）的下拉；null 表示跟随。[followLabel] 为 null 时没有跟随这一项，只能选 [choices]
 * 里的。 [more] 不空时最后多一项「更多模型」，点开是 [more] 的二级菜单（旧版本的模型）。 [value] 不在 [choices]、[more] 里时补一项，显示
 * [unknown] 给的名字。主机页的新对话默认值，以及定时任务、通知触发、数字助理给对话单独定 模型、思考强度、后台运行的权限模式都用它。[enabled] 为 false
 * 时灰着，[summary] 写为什么。
 */
@Composable
internal fun FollowDropdownPreference(
    title: String,
    choices: List<Pair<String, String>>,
    value: String?,
    unknown: (String) -> String = { it },
    followLabel: String? = Strings.commonFollowClaudeCode(),
    summary: String? = null,
    enabled: Boolean = true,
    more: List<Pair<String, String>> = emptyList(),
    onChange: (String?) -> Unit,
) {
    val options =
        remember(choices, more, value, followLabel) {
            listOfNotNull<Pair<String?, String>>(followLabel?.let { null to it }) +
                choices +
                listOfNotNull(
                    value
                        ?.takeIf { v ->
                            choices.none { it.first == v } && more.none { it.first == v }
                        }
                        ?.let { it to unknown(it) },
                )
        }
    if (more.isNotEmpty()) {
        // 普通的下拉不认二级菜单，换成能弹二级菜单的那种设置行
        val currentOnChange by rememberUpdatedState(onChange)
        DropdownValuePreference(
            title = title,
            entries =
                remember(options, more, value) {
                    choiceEntries(options, more, value) { currentOnChange(it) }
                },
            summary = summary,
            value = (options + more).firstOrNull { it.first == value }?.second,
            enabled = enabled,
        )
        return
    }
    DropdownPreference(
        title = title,
        summary = summary,
        enabled = enabled,
        items = remember(options) { options.map { it.second } },
        selectedIndex = options.indexOfFirst { it.first == value },
        onSelectedIndexChange = { index -> options.getOrNull(index)?.let { onChange(it.first) } },
    )
}

internal fun harnessFollowLabel(harness: String): String =
    if (Harnesses.normalize(harness) == Harnesses.AGY) Strings.commonFollowAntigravity()
    else Strings.commonFollowClaudeCode()

/**
 * 「模型」：跟随对应工具的默认，或 [modelChoices] 里的一个（旧版本在「更多模型」里）。列的是主机 [backend] 上 [harness]
 * 报的模型：定时任务、通知触发是它们所在的那台，数字助理是手机本机。
 */
@Composable
internal fun ModelPreference(
    hub: ConversationHub,
    backend: String,
    model: String?,
    harness: String = Harnesses.CLAUDE,
    onChange: (String?) -> Unit,
) {
    val usable = hub.usable(backend)
    LaunchedEffect(hub, backend, harness, usable) {
        if (usable) hub.ensureModelSource(backend, harness)
    }
    val choices = modelChoices(hub.modelOptions(backend, harness))
    FollowDropdownPreference(
        Strings.settingsFollowModel(),
        choices.latest,
        model,
        unknown = ::readableModelId,
        followLabel = harnessFollowLabel(harness),
        more = choices.older,
        enabled = !hub.modelSourceChanging(backend, harness),
    ) { value ->
        if (hub.modelSelectable(backend, value, harness)) onChange(value)
    }
}

/** 「思考强度」：跟随对应工具的默认（按模型存的），或可选思考强度档位之一。 */
@Composable
internal fun EffortPreference(
    effort: String?,
    harness: String = Harnesses.CLAUDE,
    onChange: (String?) -> Unit,
) =
    FollowDropdownPreference(
        Strings.settingsFollowEffort(),
        EffortLevels,
        effort,
        followLabel = harnessFollowLabel(harness),
        onChange = onChange,
    )

/** 后台运行（定时任务、通知触发）的「命令行工具」选择：Claude Code 或 Antigravity。 */
@Composable
internal fun HarnessPreference(harness: String, onChange: (String) -> Unit) =
    FollowDropdownPreference(
        Strings.setupInstallCli(),
        Harnesses.ALL.map { it to Harnesses.shortTitle(it) },
        Harnesses.normalize(harness),
        unknown = Harnesses::shortTitle,
        followLabel = null,
    ) {
        it?.let(onChange)
    }

/** 带「跟随」选项的「命令行工具」选择（如数字助理的跟随设备默认 / Claude Code / Antigravity）。 */
@Composable
internal fun HarnessPreference(
    harness: String?,
    followLabel: String,
    onChange: (String?) -> Unit,
) =
    FollowDropdownPreference(
        Strings.setupInstallCli(),
        Harnesses.ALL.map { it to Harnesses.shortTitle(it) },
        harness,
        unknown = Harnesses::shortTitle,
        followLabel = followLabel,
        onChange = onChange,
    )

/** 后台运行（定时任务、通知触发）的「权限模式」：没有“跟随”，在任务上明确选一个，没人看着的运行能做什么不随别处的默认值变。 [mode] 不在清单里时补一项。 */
@Composable
internal fun PermissionModePreference(mode: String, onChange: (String) -> Unit) =
    FollowDropdownPreference(
        Strings.settingsFollowMode(),
        PermissionModes,
        mode,
        unknown = ::permissionModeLabel,
        followLabel = null,
    ) {
        it?.let(onChange)
    }
