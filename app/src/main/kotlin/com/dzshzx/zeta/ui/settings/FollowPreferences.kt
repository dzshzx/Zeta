package com.dzshzx.zeta.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.EffortLevels
import com.dzshzx.zeta.core.ModelMenu
import com.dzshzx.zeta.core.ModelOption
import com.dzshzx.zeta.core.modelLabel
import com.dzshzx.zeta.core.modelMenu
import com.dzshzx.zeta.core.readableModelId
import com.dzshzx.zeta.ui.chat.PermissionModes
import com.dzshzx.zeta.ui.chat.permissionModeLabel
import com.dzshzx.zeta.ui.components.coui2.DropdownValuePreference
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference

/** [models]（一般是 [ConversationHub.modelOptions]）里可选的模型，值和显示名，按 [modelMenu] 分两层。 */
internal fun modelChoices(models: List<ModelOption>): ModelMenu<Pair<String, String>> =
    modelMenu(models).map { it.value to modelLabel(it.value, models) }

/**
 * 两层的选择菜单：[top] 直接列出；[more] 不空时最后一项「更多模型」，点开是 [more] 的二级菜单，选中的在那一项下面写着。
 * 顶栏的模型菜单和设置里的模型下拉都用它。
 */
internal fun <V> choiceEntries(top: List<Pair<V, String>>, more: List<Pair<V, String>>, selected: V?, onPick: (V) -> Unit): List<DropdownEntry> {
    fun item(choice: Pair<V, String>) = DropdownItem(text = choice.second, selected = choice.first == selected, onClick = { onPick(choice.first) })
    val older = more.takeIf { it.isNotEmpty() }?.let { choices ->
        DropdownItem(text = "更多模型", summary = choices.firstOrNull { it.first == selected }?.second, children = choices.map(::item))
    }
    return listOf(DropdownEntry(items = top.map(::item) + listOfNotNull(older)))
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
        // 普通的下拉不认二级菜单，换成能弹二级菜单的那种设置行
        val currentOnChange by rememberUpdatedState(onChange)
        DropdownValuePreference(
            title = title,
            entries = remember(options, more, value) { choiceEntries(options, more, value) { currentOnChange(it) } },
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

/**
 * 「模型」：跟随 Claude Code 的默认，或 [modelChoices] 里的一个（旧版本在「更多模型」里）。用它的定时任务、通知触发、数字助理
 * 都只在手机本机上跑，列的是手机本机的 Claude Code 报的模型。
 */
@Composable
internal fun ModelPreference(hub: ConversationHub, model: String?, onChange: (String?) -> Unit) {
    val choices = modelChoices(hub.modelOptions(Backends.LOCAL))
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
