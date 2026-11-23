package com.dzshzx.zeta.ui.settings

import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.ModelMenu
import com.dzshzx.zeta.core.ModelOption
import com.dzshzx.zeta.core.modelLabel
import com.dzshzx.zeta.core.modelMenu
import com.dzshzx.zeta.i18n.Strings
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem

/** [models]（一般是 [ConversationHub.modelOptions]）里可选的模型，值和显示名，按 [modelMenu] 分两层。 */
fun modelChoices(models: List<ModelOption>): ModelMenu<Pair<String, String>> =
    modelMenu(models).map { it.value to modelLabel(it.value, models) }

/** 两层的选择菜单：[top] 直接列出；[more] 不空时最后一项「更多模型」，点开是 [more] 的二级菜单，选中的在那一项下面写着。 顶栏的模型菜单和设置里的模型下拉都用它。 */
fun <V> choiceEntries(
    top: List<Pair<V, String>>,
    more: List<Pair<V, String>>,
    selected: V?,
    onPick: (V) -> Unit,
): List<DropdownEntry> {
    fun item(choice: Pair<V, String>) =
        DropdownItem(
            text = choice.second,
            selected = choice.first == selected,
            onClick = { onPick(choice.first) },
        )
    val older =
        more
            .takeIf { it.isNotEmpty() }
            ?.let { choices ->
                DropdownItem(
                    text = Strings.settingsModelMore(),
                    summary = choices.firstOrNull { it.first == selected }?.second,
                    children = choices.map(::item),
                )
            }
    return listOf(DropdownEntry(items = top.map(::item) + listOfNotNull(older)))
}
