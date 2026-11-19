package com.dzshzx.zeta.ui.settings

import androidx.compose.runtime.Composable
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.EffortLevels
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference

/** 没从 Claude Code 拿到模型清单时给的几个别名。 */
internal val FallbackModels = listOf("default" to "默认", "fable" to "Fable", "opus" to "Opus", "sonnet" to "Sonnet", "haiku" to "Haiku")

/** 可选的模型（值和显示名）：Claude Code 给的清单，拿不到时用几个别名。 */
internal fun modelChoices(hub: ConversationHub): List<Pair<String, String>> =
    hub.models.map { it.value to (if (it.value == "default") "默认" else it.displayName.ifBlank { it.value }) }.ifEmpty { FallbackModels }

/** 模型 [value] 的显示名；清单里没有就是原值。 */
internal fun modelName(hub: ConversationHub, value: String): String = modelChoices(hub).firstOrNull { it.first == value }?.second ?: value

/**
 * 「跟随当前设置」加 [choices]（值和显示名）的下拉；null 表示跟随。[value] 不在 [choices] 里时补一项，显示 [unknown] 给的名字。
 * 定时任务、通知触发、数字助理给对话单独定模型、思考强度、权限模式都用它。
 */
@Composable
internal fun FollowDropdownPreference(
    title: String,
    choices: List<Pair<String, String>>,
    value: String?,
    unknown: (String) -> String = { it },
    onChange: (String?) -> Unit,
) {
    val options = listOf<Pair<String?, String>>(null to "跟随当前设置") + choices +
        listOfNotNull(value?.takeIf { v -> choices.none { it.first == v } }?.let { it to unknown(it) })
    OverlayDropdownPreference(
        title = title,
        items = options.map { it.second },
        selectedIndex = options.indexOfFirst { it.first == value },
        onSelectedIndexChange = { index -> options.getOrNull(index)?.let { onChange(it.first) } },
    )
}

/** 「模型」：跟随当前设置，或 [modelChoices] 里的一个。 */
@Composable
internal fun ModelPreference(hub: ConversationHub, model: String?, onChange: (String?) -> Unit) =
    FollowDropdownPreference("模型", modelChoices(hub), model, onChange = onChange)

/** 「思考强度」：跟随当前设置，或 Claude Code 的几档之一。 */
@Composable
internal fun EffortPreference(effort: String?, onChange: (String?) -> Unit) =
    FollowDropdownPreference("思考强度", EffortLevels, effort, onChange = onChange)
