package com.dzshzx.zeta.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.EffortLevels
import com.dzshzx.zeta.core.modelLabel
import com.dzshzx.zeta.core.modelMenu
import com.dzshzx.zeta.core.readableModelId
import com.dzshzx.zeta.ui.chat.PermissionModes
import com.dzshzx.zeta.ui.chat.permissionModeLabel
import com.dzshzx.zeta.ui.components.coui2.DropdownValuePreference
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference

/** 可选的模型（值和显示名）：[latest] 直接列出，[older] 收进「更多模型」（[modelMenu]）。 */
internal class ModelChoices(val latest: List<Pair<String, String>>, val older: List<Pair<String, String>>)

/** 可选的模型：Claude Code 给的清单，拿不到时用几个别名（[ConversationHub.modelOptions]），按 [modelMenu] 分两层。 */
internal fun modelChoices(hub: ConversationHub): ModelChoices = hub.modelOptions.let { models ->
    val menu = modelMenu(models)
    ModelChoices(menu.latest.map { it.value to modelLabel(it.value, models) }, menu.older.map { it.value to modelLabel(it.value, models) })
}

/**
 * 「跟随」（[followLabel]）加 [choices]（值和显示名）的下拉；null 表示跟随。[followLabel] 为 null 时没有跟随这一项，只能选 [choices] 里的。
 * [more] 不空时最后多一项「更多模型」，点开是 [more] 的二级菜单（旧版本的模型）。
 * [value] 不在 [choices]、[more] 里时补一项，显示 [unknown] 给的名字。设置首页的新对话默认值，以及定时任务、通知触发、数字助理给对话单独定
 * 模型、思考强度、后台运行的权限模式都用它；[startAction] 是设置首页的行首图标。[enabled] 为 false 时灰着，[summary] 写为什么。
 */
@Composable
internal fun FollowDropdownPreference(
    title: String,
    choices: List<Pair<String, String>>,
    value: String?,
    unknown: (String) -> String = { it },
    followLabel: String? = "跟随 Claude Code",
    startAction: (@Composable () -> Unit)? = null,
    summary: String? = null,
    enabled: Boolean = true,
    more: List<Pair<String, String>> = emptyList(),
    onChange: (String?) -> Unit,
) {
    val options = remember(choices, more, value, followLabel) {
        listOfNotNull<Pair<String?, String>>(followLabel?.let { null to it }) + choices +
            listOfNotNull(value?.takeIf { v -> choices.none { it.first == v } && more.none { it.first == v } }?.let { it to unknown(it) })
    }
    if (more.isNotEmpty()) {
        val currentOnChange by rememberUpdatedState(onChange)
        val entries = remember(options, more, value) {
            fun item(option: Pair<String?, String>) = DropdownItem(
                text = option.second,
                selected = option.first == value,
                onClick = { currentOnChange(option.first) },
            )
            val older = DropdownItem(
                text = "更多模型",
                summary = more.firstOrNull { it.first == value }?.second,
                children = more.map(::item),
            )
            listOf(DropdownEntry(items = options.map(::item) + older))
        }
        DropdownValuePreference(
            title = title,
            entries = entries,
            summary = summary,
            value = (options + more).firstOrNull { it.first == value }?.second,
            enabled = enabled,
            startAction = startAction,
        )
        return
    }
    OverlayDropdownPreference(
        title = title,
        summary = summary,
        enabled = enabled,
        startAction = startAction,
        items = remember(options) { options.map { it.second } },
        selectedIndex = options.indexOfFirst { it.first == value },
        onSelectedIndexChange = { index -> options.getOrNull(index)?.let { onChange(it.first) } },
    )
}

/** 「模型」：跟随 Claude Code 的默认，或 [modelChoices] 里的一个（旧版本在「更多模型」里）。 */
@Composable
internal fun ModelPreference(hub: ConversationHub, model: String?, onChange: (String?) -> Unit) {
    val choices = modelChoices(hub)
    FollowDropdownPreference("模型", choices.latest, model, unknown = ::readableModelId, more = choices.older, onChange = onChange)
}

/** 「思考强度」：跟随 Claude Code 的默认（按模型存的），或 Claude Code 的几档之一。 */
@Composable
internal fun EffortPreference(effort: String?, onChange: (String?) -> Unit) =
    FollowDropdownPreference("思考强度", EffortLevels, effort, onChange = onChange)

/**
 * 后台运行（定时任务、通知触发）的「权限模式」：没有“跟随”，在任务上明确选一个，没人看着的运行能做什么不随别处的默认值变。
 * [mode] 不在清单里时补一项。
 */
@Composable
internal fun PermissionModePreference(mode: String, onChange: (String) -> Unit) =
    FollowDropdownPreference("权限模式", PermissionModes, mode, unknown = ::permissionModeLabel, followLabel = null) { it?.let(onChange) }
