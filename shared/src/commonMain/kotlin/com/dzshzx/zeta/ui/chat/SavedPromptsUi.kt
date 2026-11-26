package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.PromptList
import com.dzshzx.zeta.core.SavedPrompt
import com.dzshzx.zeta.core.SavedPrompts
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.LocalHub
import com.dzshzx.zeta.ui.components.miuix.ConfirmDialog
import com.dzshzx.zeta.ui.components.miuix.MenuEntry
import com.dzshzx.zeta.ui.components.miuix.MenuItem
import com.dzshzx.zeta.ui.components.miuix.RowMoreMenu
import com.dzshzx.zeta.ui.components.miuix.TextField
import com.dzshzx.zeta.ui.components.miuix.ZetaIcons
import com.dzshzx.zeta.ui.components.rememberRetained
import java.util.UUID
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 常用指令：用户存下来的提问，点一下填进输入框，改一改再发。入口在输入框的“+”菜单里；
 * 长按自己发过的消息可以“收藏”。列表里新建、编辑、上移下移、删除。不进 `/` 菜单，那里是 Claude Code 的斜杠命令。
 */

/** 正在编辑的一条；[id] 为 null 是新建。 */
class PromptDraft(val id: String?, val name: String, val text: String)

/** 聊天页上常用指令的界面状态：列表开没开、在编辑哪条、在等哪条的删除确认。三个对话框一次只开一个，编辑和删除完回到列表。 */
@Stable
class PromptsUi(
    private val store: PromptList,
    private val toast: (String) -> Unit,
    private val input: TextFieldState,
) {
    var listOpen by mutableStateOf(false)
        private set

    var editing by mutableStateOf<PromptDraft?>(null)
        private set

    var deleting by mutableStateOf<SavedPrompt?>(null)
        private set

    val prompts: List<SavedPrompt>
        get() = store.observed

    fun open() {
        store.load()
        listOpen = true
    }

    fun close() {
        listOpen = false
    }

    /** 填进输入框：空着就整个换上，有字就插在光标处（选中了字就替换选中的）。 */
    fun pick(prompt: SavedPrompt) {
        listOpen = false
        insertPrompt(input, prompt.text)
    }

    /** 新建：输入框里正好有字时先填上，方便把正在写的存下来。 */
    fun startNew() {
        listOpen = false
        editing = PromptDraft(null, "", input.text.toString().takeIf { it.isNotBlank() }.orEmpty())
    }

    fun startEdit(prompt: SavedPrompt) {
        listOpen = false
        editing = PromptDraft(prompt.id, prompt.name, prompt.text)
    }

    fun problem(name: String, text: String): String? =
        SavedPrompts.problem(prompts, name, text, editing?.id)

    fun save(name: String, text: String) {
        val draft = editing ?: return
        if (problem(name, text) != null) return
        store.update { list ->
            val id = draft.id
            if (id == null) SavedPrompts.add(list, UUID.randomUUID().toString(), name, text)
            else SavedPrompts.update(list, id, name, text)
        }
        editing = null
        listOpen = true
    }

    fun cancelEdit() {
        editing = null
        listOpen = true
    }

    fun move(prompt: SavedPrompt, delta: Int) = store.update {
        SavedPrompts.move(it, prompt.id, delta)
    }

    fun askDelete(prompt: SavedPrompt) {
        listOpen = false
        deleting = prompt
    }

    fun confirmDelete() {
        val prompt = deleting ?: return
        store.update { SavedPrompts.remove(it, prompt.id) }
        deleting = null
        listOpen = true
    }

    fun dismissDelete() {
        deleting = null
        listOpen = true
    }

    /** 长按消息里的“收藏”：直接存，不弹编辑框；同样的内容存过就不再存。 */
    fun saveMessage(text: String) {
        val list = store.load()
        val message =
            when {
                SavedPrompts.findSame(list, text) != null -> Strings.composerPromptAlreadySaved()
                else ->
                    SavedPrompts.problem(list, "", text)
                        ?: run {
                            store.update {
                                SavedPrompts.add(it, UUID.randomUUID().toString(), "", text)
                            }
                            Strings.composerPromptSaved()
                        }
            }
        toast(message)
    }
}

@Composable
fun rememberPromptsUi(input: TextFieldState): PromptsUi {
    val hub = LocalHub.current
    return remember(input) { PromptsUi(hub.platform.prompts, hub.platform::toast, input) }
}

/** 把常用指令放进输入框：空着就整个换上；有字就插在光标处，替换选中的字，光标停在插入内容的后面。 */
fun insertPrompt(input: TextFieldState, text: String) {
    if (input.text.isBlank()) {
        input.setTextAndPlaceCursorAtEnd(text)
        return
    }
    input.edit {
        val start = selection.min.coerceIn(0, length)
        val end = selection.max.coerceIn(start, length)
        replace(start, end, text)
        selection = TextRange(start + text.length)
    }
}

/** 常用指令的列表、编辑和删除确认。Miuix 的对话框，要在它的 Scaffold 里才显示。 */
@Composable
fun SavedPromptsDialogs(ui: PromptsUi) {
    PromptListDialog(ui)
    PromptEditorDialog(ui)
    val deleting = ui.deleting
    val shown = rememberRetained(deleting)
    ConfirmDialog(
        show = deleting != null,
        title = Strings.composerPromptDeleteTitle(),
        summary = shown?.let { Strings.composerPromptDeleteSummary(SavedPrompts.displayName(it)) },
        confirmText = Strings.composerDelete(),
        destructive = true,
        onConfirm = ui::confirmDelete,
        onDismissRequest = ui::dismissDelete,
    )
}

@Composable
private fun PromptListDialog(ui: PromptsUi) {
    val prompts = ui.prompts
    // 没有的时候说明放在标题下面，有的时候列表放在说明的位置
    ConfirmDialog(
        show = ui.listOpen,
        title = Strings.composerMenuPrompts(),
        summary = if (prompts.isEmpty()) Strings.composerPromptsEmpty() else null,
        confirmText = Strings.composerPromptNew(),
        confirmEnabled = prompts.size < SavedPrompts.MAX,
        cancelText = Strings.composerClose(),
        onConfirm = ui::startNew,
        onDismissRequest = ui::close,
        content =
            if (prompts.isEmpty()) {
                null
            } else {
                {
                    Column(
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        prompts.forEachIndexed { index, prompt ->
                            PromptRow(
                                prompt,
                                first = index == 0,
                                last = index == prompts.lastIndex,
                                ui = ui,
                            )
                        }
                    }
                }
            },
    )
}

@Composable
private fun PromptRow(prompt: SavedPrompt, first: Boolean, last: Boolean, ui: PromptsUi) {
    val colors = MiuixTheme.colorScheme
    val current by rememberUpdatedState(prompt)
    val entries =
        remember(first, last, ui) {
            listOf(
                MenuEntry(
                    items =
                        listOf(
                            MenuItem(
                                Strings.composerEdit(),
                                MiuixIcons.Edit,
                                { ui.startEdit(current) },
                            ),
                            MenuItem(
                                Strings.composerMoveUp(),
                                ZetaIcons.ArrowUp,
                                { ui.move(current, -1) },
                                enabled = !first,
                            ),
                            MenuItem(
                                Strings.composerMoveDown(),
                                ZetaIcons.ArrowDown,
                                { ui.move(current, 1) },
                                enabled = !last,
                            ),
                        ),
                ),
                MenuEntry(
                    items =
                        listOf(
                            MenuItem(
                                Strings.composerDelete(),
                                MiuixIcons.Delete,
                                { ui.askDelete(current) },
                                alert = true,
                            ),
                        ),
                ),
            )
        }
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { ui.pick(prompt) }
                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 起了名字的：名字加两行内容；没起名字的直接显示内容，不把开头重复一遍
        val named = prompt.name.isNotBlank()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (named) prompt.name.trim() else prompt.text.trim(),
                style =
                    MiuixTheme.textStyles.body2.copy(
                        fontSize = 14.5.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                color = colors.onBackground,
                maxLines = if (named) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (named) {
                Text(
                    text = prompt.text.trim(),
                    style =
                        MiuixTheme.textStyles.footnote1.copy(
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                        ),
                    color = colors.onBackgroundVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        RowMoreMenu(Strings.composerMoreActions(), entries, iconSize = 20.dp)
    }
}

@Composable
private fun PromptEditorDialog(ui: PromptsUi) {
    val draft = ui.editing
    // 对话框收起的动画里还要显示原来的内容
    val current = rememberRetained(draft)
    var name by remember(current) { mutableStateOf(current?.name.orEmpty()) }
    var text by remember(current) { mutableStateOf(current?.text.orEmpty()) }
    val problem = ui.problem(name, text)
    ConfirmDialog(
        show = draft != null,
        title =
            if (current?.id == null) Strings.composerPromptNewTitle()
            else Strings.composerPromptEditTitle(),
        summary = null,
        confirmText = Strings.composerSave(),
        confirmEnabled = problem == null,
        onConfirm = { ui.save(name, text) },
        onDismissRequest = ui::cancelEdit,
    ) {
        // Miuix 的输入框只有一个提示文字：用作浮动标题（框里有字或在输入时浮到上面）
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = name,
                onValueChange = { name = it.take(SavedPrompts.MAX_NAME) },
                label = Strings.composerPromptNameLabel(),
                useLabelAsPlaceholder = false,
                singleLine = true,
                showClearButton = true,
            )
            TextField(
                value = text,
                onValueChange = { text = it },
                label = Strings.composerPromptTextLabel(),
                useLabelAsPlaceholder = false,
                minLines = 3,
                maxLines = 8,
            )
            // 空着时不报错，“保存”变灰就够了
            if (problem != null && text.isNotEmpty()) {
                Text(
                    text = problem,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
