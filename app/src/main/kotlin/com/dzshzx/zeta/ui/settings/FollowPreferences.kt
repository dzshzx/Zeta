package com.dzshzx.zeta.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.EffortLevels
import com.dzshzx.zeta.core.modelLabel
import com.dzshzx.zeta.core.readableModelId
import com.dzshzx.zeta.ui.chat.PermissionModes
import com.dzshzx.zeta.ui.chat.permissionModeLabel
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference

/** 可选的模型（值和显示名）：Claude Code 给的清单，拿不到时用几个别名（[ConversationHub.modelOptions]）。 */
internal fun modelChoices(hub: ConversationHub): List<Pair<String, String>> =
    hub.modelOptions.let { models -> models.map { it.value to modelLabel(it.value, models) } }

/** 模型 [value] 的显示名（[modelLabel]）。 */
internal fun modelName(hub: ConversationHub, value: String): String = modelLabel(value, hub.modelOptions)

/**
 * 「跟随」（[followLabel]）加 [choices]（值和显示名）的下拉；null 表示跟随。[followLabel] 为 null 时没有跟随这一项，只能选 [choices] 里的。
 * [value] 不在 [choices] 里时补一项，显示 [unknown] 给的名字。设置首页的新对话默认值，以及定时任务、通知触发、数字助理给对话单独定
 * 模型、思考强度、后台运行的权限模式都用它；[startAction] 是设置首页的行首图标。
 */
@Composable
internal fun FollowDropdownPreference(
    title: String,
    choices: List<Pair<String, String>>,
    value: String?,
    unknown: (String) -> String = { it },
    followLabel: String? = "跟随当前设置",
    startAction: (@Composable () -> Unit)? = null,
    onChange: (String?) -> Unit,
) {
    val options = remember(choices, value, followLabel) {
        listOfNotNull<Pair<String?, String>>(followLabel?.let { null to it }) + choices +
            listOfNotNull(value?.takeIf { v -> choices.none { it.first == v } }?.let { it to unknown(it) })
    }
    OverlayDropdownPreference(
        title = title,
        startAction = startAction,
        items = remember(options) { options.map { it.second } },
        selectedIndex = options.indexOfFirst { it.first == value },
        onSelectedIndexChange = { index -> options.getOrNull(index)?.let { onChange(it.first) } },
    )
}

/** 「模型」：跟随当前设置，或 [modelChoices] 里的一个。 */
@Composable
internal fun ModelPreference(hub: ConversationHub, model: String?, onChange: (String?) -> Unit) =
    FollowDropdownPreference("模型", modelChoices(hub), model, unknown = ::readableModelId, onChange = onChange)

/** 「思考强度」：跟随当前设置，或 Claude Code 的几档之一。 */
@Composable
internal fun EffortPreference(effort: String?, onChange: (String?) -> Unit) =
    FollowDropdownPreference("思考强度", EffortLevels, effort, onChange = onChange)

/**
 * 后台运行（定时任务、通知触发）的「权限模式」：没有“跟随当前设置”，必须明确选一个。当前设置是用户在输入栏随手选的，
 * 可能是完全放行，不该带进没人看着的运行。[mode] 不在清单里时补一项。
 */
@Composable
internal fun PermissionModePreference(mode: String, onChange: (String) -> Unit) =
    FollowDropdownPreference("权限模式", PermissionModes, mode, unknown = ::permissionModeLabel, followLabel = null) { it?.let(onChange) }
